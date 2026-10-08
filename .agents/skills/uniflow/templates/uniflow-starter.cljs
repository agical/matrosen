;; Uniflow + Replicant starter template
;; Copy into your project and adapt. Uses Squint `js-await` - see
;; SKILL.md "await vs js-await Across Runtimes" for other runtimes.

(ns my.app
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [replicant.dom :as r-dom]))

;; -- State --

(defonce !state
  (atom {:ui/draft ""
         :todo/items []}))

;; -- Enrichment --

(defn- js-get-in [obj path]
  (reduce (fn [acc k] (when (some? acc) (unchecked-get acc k)))
          obj path))

(defn- shallow-event-map [^js e]
  {:type (.-type e) :key (.-key e)
   :target.value (some-> e .-target .-value)
   :clientX (.-clientX e) :clientY (.-clientY e)})

(defn- enrich-from-replicant-data [{:replicant/keys [js-event node]} actions]
  (walk/postwalk
   (fn [x]
     (cond
       (keyword? x)
       (cond
         (= :event/event x) (shallow-event-map js-event)
         (= :event/target.value x) (some-> js-event .-target .-value)
         (= :dom/node x) node
         (= "event" (namespace x)) (js-get-in js-event (str/split (name x) #"\."))
         :else x)
       (and (vector? x) (= :dom/element-by-id (first x)))
       (js/document.getElementById (second x))
       :else x))
   actions))

(defn- enrich-from-state [state action]
  (walk/postwalk
   (fn [x]
     (if (and (vector? x) (= :db/get (first x)))
       (get state (second x))
       x))
   action))

(defn- enrich-action [state replicant-data action]
  (->> action
       (enrich-from-state state)
       (enrich-from-replicant-data replicant-data)))

;; -- Actions (pure) --

(defn generic-handle-action [state _uf-data [action & args]]
  (case action
    :db/ax.assoc {:uf/db (apply assoc state args)}
    :uf/unhandled-ax))

(defn handle-action [state uf-data action]
  (let [[op & args] (enrich-action state (:uf/replicant-data uf-data) action)]
    (case op
      :todo/ax.submit
      (let [[text] args
            trimmed (str/trim (or text ""))]
        (when (seq trimmed)
          {:uf/db (-> state
                      (update :todo/items conj
                              {:item/id (:uf/uuid uf-data)
                               :item/text trimmed})
                      (assoc :ui/draft ""))
           :uf/fxs [[:log/fx.log :info "Todo" "Item added:" trimmed]]}))

      :todo/ax.submit-on-enter
      (let [[event text] args]
        (when (= "Enter" (:key event))
          {:uf/dxs [[:todo/ax.submit text]]}))

      :todo/ax.save
      (let [[items] args]
        {:uf/fxs [[:uf/await :http/fx.post "/api/todos" items]
                  [:log/fx.log :info "Saved" :uf/prev-result]]
         :uf/dxs [[:todo/ax.handle-save-result :uf/prev-result]]})

      :todo/ax.handle-save-result
      (let [[result] args]
        (when (:success result)
          {:uf/fxs [[:log/fx.log :info "Save completed, count:" (:count result)]]}))

      ;; Fallback to generic handler
      :uf/unhandled-ax)))

;; Note: `handle-action` processes ONE action and returns a result.
;; `handle-actions` (plural) reduces multiple actions, accumulating results.
(defn handle-actions [state uf-data actions]
  (reduce
   (fn [{:uf/keys [db] :as acc} action]
     (let [result (handle-action db uf-data action)
           result (if (= :uf/unhandled-ax result)
                    (let [generic (generic-handle-action db uf-data action)]
                      (when (= :uf/unhandled-ax generic)
                        (js/console.warn "Unhandled action:" action))
                      generic)
                    result)
           {:uf/keys [db fxs dxs]} (when (map? result) result)]
       (cond-> acc
         db (assoc :uf/db db)
         (seq fxs) (update :uf/fxs into fxs)
         (seq dxs) (update :uf/dxs into dxs))))
   {:uf/db state :uf/fxs [] :uf/dxs []}
   (remove nil? actions)))

;; -- Async Helpers --

(defn await-fx? [fx]
  (and (vector? fx) (= :uf/await (first fx))))

(defn unwrap-fx [fx]
  (if (await-fx? fx) (vec (rest fx)) fx))

(defn replace-prev-result [form prev-result]
  (walk/postwalk
   (fn [x] (if (= :uf/prev-result x) prev-result x))
   form))

(defn replace-prev-result-in-actions [actions prev-result]
  (mapv #(replace-prev-result % prev-result) actions))

;; -- Effects (imperative shell) --

(defn generic-perform-effect! [dispatch [effect & args]]
  (case effect
    :uf/fx.dispatch (dispatch (first args))
    :log/fx.log (apply js/console.log (rest args))
    :uf/unhandled-fx))

(defn perform-effect! [dispatch [effect & args]]
  (case effect
    :http/fx.post
    (let [[url data] args]
      (js/Promise.resolve {:success true :count (count data)}))

    ;; Add app-specific effects here
    :uf/unhandled-fx))

;; -- Dispatch Loop --

(declare dispatch!)

(defn event-handler
  "Replicant dispatch entry point."
  [replicant-data actions]
  (dispatch! actions replicant-data))

(defn execute-effect! [dispatch fx]
  (let [result (perform-effect! dispatch fx)]
    (if (= :uf/unhandled-fx result)
      (let [generic (generic-perform-effect! dispatch fx)]
        (when (= :uf/unhandled-fx generic)
          (js/console.warn "Unhandled effect:" fx))
        generic)
      result)))

(defn ^:async execute-effects! [dispatch fxs]
  (loop [remaining fxs, prev-result nil]
    (if (seq remaining)
      (let [raw-fx (first remaining)
            is-await? (await-fx? raw-fx)
            fx (-> raw-fx unwrap-fx (replace-prev-result prev-result))]
        (if is-await?
          (let [result (js-await (execute-effect! dispatch fx))]
            (recur (rest remaining) result))
          (do (execute-effect! dispatch fx)
              (recur (rest remaining) prev-result))))
      prev-result)))

(defn dispatch!
  ([actions] (dispatch! actions nil))
  ([actions replicant-data]
   (let [old-state @!state
         uf-data {:system/now (.now js/Date)
                  :uf/uuid (str (random-uuid))
                  :uf/replicant-data replicant-data}
         {:uf/keys [db fxs dxs]} (handle-actions old-state uf-data actions)
         dispatch-fn (fn [more] (dispatch! more replicant-data))]
     (when (some? db)
       (reset! !state db))
     (if (seq fxs)
       (-> (execute-effects! dispatch-fn (remove nil? fxs))
           (.then (fn [prev-result]
                    (when (seq dxs)
                      (let [substituted (replace-prev-result-in-actions dxs prev-result)]
                        (dispatch-fn substituted))))))
       (when (seq dxs)
         (dispatch-fn dxs))))))

;; -- View (Replicant) --

(defn app-view [{:ui/keys [draft] :todo/keys [items]}]
  [:main
   [:h1 "Uniflow + Replicant"]
   [:div {:style {:display "flex" :gap "0.5rem"}}
    [:input {:value draft
             :on {:input [[:db/ax.assoc :ui/draft :event/target.value]]
                  :keydown [[:todo/ax.submit-on-enter
                             :event/event [:db/get :ui/draft]]]}}]
    [:button {:on {:click [[:todo/ax.submit [:db/get :ui/draft]]]}}
     "Add"]]
   [:ul (for [{:item/keys [id text]} items]
          [:li {:replicant/key id} text])]])

;; -- Init --

(defn render! []
  (r-dom/render
   (js/document.getElementById "app")
   (app-view @!state)))

(defn init! []
  (r-dom/set-dispatch! event-handler)
  (add-watch !state ::render
             (fn [_ _ old new]
               (when (not= old new) (render!))))
  (render!))
