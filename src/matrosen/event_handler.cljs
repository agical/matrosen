(ns matrosen.event-handler
  (:require [clojure.walk :as walk]
            [replicant.dom :as dom]
            [matrosen.db :as db]
            [matrosen.actions :as actions]
            [matrosen.effects :as effects]))

(defn preview-anchor [replicant-data]
  (let [node (:replicant/node replicant-data)
        rect (.getBoundingClientRect node)]
    {:left (.-left rect) :top (.-top rect) :bottom (.-bottom rect)
     :viewport-width (.-innerWidth js/window) :viewport-height (.-innerHeight js/window)}))

(defn enrich [state replicant-data action]
  (walk/postwalk
    (fn [value]
      (cond
        (= :event/target.value value) (some-> replicant-data :replicant/js-event .-target .-value)
        (= :event/detail value) (some-> replicant-data :replicant/js-event .-detail)
        (= :preview/anchor value) (preview-anchor replicant-data)
        (= :location/hash value) (.-hash js/location)
        (and (vector? value) (= :db/get (first value))) (get state (second value))
        :else value))
    action))

(defn handle-actions [state replicant-data action-list]
  (reduce
    (fn [{:uf/keys [db] :as result} action]
      (let [next (actions/handle-action db (enrich db replicant-data action))]
        (when (= :uf/unhandled-ax next)
          (throw (ex-info "Unhandled action" {:action action})))
        (cond-> result
          (contains? next :uf/db) (assoc :uf/db (:uf/db next))
          (:uf/fxs next) (update :uf/fxs into (:uf/fxs next))
          (:uf/dxs next) (update :uf/dxs into (:uf/dxs next)))))
    {:uf/db state :uf/fxs [] :uf/dxs []}
    action-list))

(defn replace-result [form result]
  (walk/postwalk #(if (= :uf/prev-result %) result %) form))

(defn execute-effects! [fxs]
  (reduce
    (fn [promise raw-fx]
      (.then promise
        (fn [previous]
          (let [await? (= :uf/await (first raw-fx))
                fx (replace-result (if await? (vec (rest raw-fx)) raw-fx) previous)
                result (effects/perform-effect! fx)]
            (when (= :uf/unhandled-fx result)
              (throw (ex-info "Unhandled effect" {:effect fx})))
            (if await? result previous)))))
    (js/Promise.resolve nil) fxs))

;; The only runtime state read and write. Rendering receives the committed value.
(defn dispatch!
  ([action-list] (dispatch! action-list nil))
  ([action-list replicant-data]
   (let [before @db/!state
         {:uf/keys [db fxs dxs]} (handle-actions before replicant-data action-list)]
     (when (some? db)
       (reset! db/!state db)
       (effects/perform-effect! [:dom/fx.render db]))
     (if (seq fxs)
       (-> (execute-effects! fxs)
           (.then (fn [result]
                    (when (seq dxs) (dispatch! (replace-result dxs result)))))
           (.catch (fn [error]
                     (js/console.error "Uniflow effect failed" error)
                     (dispatch! [[:ui/ax.effect-failed]]))))
       (when (seq dxs) (dispatch! dxs))))))

(defn outside-preview! [event]
  (when-not (.closest (.-target event) ".dish-trigger, .dish-preview")
    (dispatch! [[:preview/ax.close false]])))

(defn dismiss-preview! [_] (dispatch! [[:preview/ax.close false]]))

(defn preview-key! [event]
  (when (= "Escape" (.-key event))
    (dispatch! [[:preview/ax.close (boolean (.closest (.-activeElement js/document) ".dish-preview"))]])))

;; Install once; the wrappers resolve current functions after a REPL re-evaluation.
(defonce preview-listeners
  (do (.addEventListener js/document "pointerdown" #(outside-preview! %) true)
      (.addEventListener js/document "focusin" #(outside-preview! %) true)
      (.addEventListener js/document "keydown" #(preview-key! %))
      (.addEventListener js/window "scroll" #(dismiss-preview! %) #js {:passive true})
      (.addEventListener js/window "resize" #(dismiss-preview! %))
      (.addEventListener js/window "hashchange" #(dispatch! [[:share/ax.load]]))
      true))

(defn init! []
  (dom/set-dispatch! (fn [data action-list] (dispatch! action-list data)))
  (dispatch! [[:app/ax.initialize]]))

(init!)
