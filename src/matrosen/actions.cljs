(ns matrosen.actions
  (:require [clojure.string :as str]
            [matrosen.model :as model]))

;; Geometry arrives as plain event data; preview actions never read the DOM.
(defn preview-position [{:keys [left top bottom viewport-width viewport-height]}]
  (let [width (min 340 (- viewport-width 32))
        height (min 318 (- viewport-height 32))
        x (max 16 (min left (- viewport-width width 16)))
        below (+ bottom 8)
        y (if (<= (+ below height) (- viewport-height 16)) below
              (max 16 (- top height 8)))]
    {:left (str x "px") :top (str y "px")
     :max-height (str (- viewport-height y 16) "px")}))

(defn open-preview [db id anchor]
  (assoc-in db [:ui :preview] {:id id :position (preview-position anchor)}))

(defn close-preview [db return-focus?]
  (when-let [id (get-in db [:ui :preview :id])]
    (cond-> {:uf/db (assoc-in db [:ui :preview] nil)}
      return-focus? (assoc :uf/fxs [[:dom/fx.focus (str "preview-" (name id))]]))))

(defn save-recipe [order]
  (if (and (model/valid-settings? order) (model/valid-rows? order))
    {:uf/fxs [[:uf/await :storage/fx.save order]]
     :uf/dxs [[:storage/ax.saved :uf/prev-result]]}
    {}))

(defn present-notice
  "Opens a status message, or starts hiding the current one when message is nil."
  [db message]
  (let [token (inc (get-in db [:ui :notice-token] 0))
        open? (get-in db [:ui :notice-open?])
        current (get-in db [:ui :notice])]
    (cond
      (some? message)
      {:db (-> db
               (assoc-in [:ui :notice] message)
               (assoc-in [:ui :notice-open?] true)
               (assoc-in [:ui :notice-token] token))
       :fxs [[:ui/fx.schedule token 3000 :ui/ax.dismiss-notice]]}

      (and open? (some? current))
      {:db (-> db
               (assoc-in [:ui :notice-open?] false)
               (assoc-in [:ui :notice-token] token))
       :fxs [[:ui/fx.schedule token 250 :ui/ax.clear-notice]]}

      :else
      {:db db :fxs []})))

(defn change-order [db value message]
  (let [{:keys [db fxs]} (present-notice db message)
        saved (save-recipe value)]
    (cond-> {:uf/db (-> db
                        (assoc :order value)
                        (assoc-in [:ui :copy-text] nil)
                        (assoc-in [:ui :saved]
                                  (if (and (model/valid-settings? value)
                                           (model/valid-rows? value))
                                    "Sparar…"
                                    "Ogiltiga värden sparas inte")))
             :uf/fxs (into fxs (:uf/fxs saved))}
      (:uf/dxs saved) (assoc :uf/dxs (:uf/dxs saved)))))

(defn reset-order
  "Restores the starting order and drops a shared plan from the address."
  [db fragment]
  (let [order (model/default-order)]
    (cond-> (-> (change-order db order nil)
                (assoc-in [:uf/db :ui :preview] nil))
      (and (string? fragment) (str/starts-with? fragment "#plan="))
      (update :uf/fxs (fnil conj []) [:url/fx.clear-plan fragment]))))

(defn load-order [db {:keys [success value fragment]}]
  (let [shared (model/read-shared-plan (or fragment ""))
        stored (model/read-stored-order value)
        local (if (and (not (:initialized? db)) stored) stored (:order db))
        order (or (:order shared) local)
        message (cond
                  (:error shared) "Delningslänken kunde inte läsas. Din befintliga beställning visas."
                  (and (nil? (:order shared))
                       success
                       (some? value)
                       (nil? stored))
                  "Den sparade beställningen kunde inte läsas. Ett nytt förslag visas.")
        {:keys [db fxs]} (present-notice db message)
        saved (when success (save-recipe order))]
    (cond-> {:uf/db (-> db
                        (assoc :order order :initialized? true)
                        (assoc-in [:ui :copy-text] nil)
                        (assoc-in [:ui :preview] nil)
                        (assoc-in [:ui :saved] (if success "Sparar…" "Lokalt sparande är inte tillgängligt")))
             :uf/fxs (into fxs (:uf/fxs saved))}
      (:uf/dxs saved) (assoc :uf/dxs (:uf/dxs saved))
      (:order shared) (update :uf/fxs (fnil conj []) [:url/fx.clear-plan fragment]))))

(defn change-portion [db size]
  (let [order (:order db)
        updated (model/change-portion-size order size)]
    (when (not= order updated)
      (change-order db updated nil))))

(defn row-count-notice [order path typed]
  (let [shown (model/number-value (get-in order path))
        goal (model/target order)]
    (cond
      (= typed shown) nil
      (and (some? goal) (= shown goal) (> typed goal))
      (str "Antalet begränsades till målet på " goal " " (:unit (model/portion order)) ".")
      :else "Antalet avrundades till ett jämnt antal.")))

(defn handle-action [db [action & args]]
  (case action
    :preview/ax.toggle
    (let [[id anchor detail] args]
      (if (= id (get-in db [:ui :preview :id]))
        (close-preview db false)
        (cond-> {:uf/db (open-preview db id anchor)}
          (= 0 detail) (assoc :uf/fxs [[:dom/fx.focus "preview-close"]]))))

    :preview/ax.close
    (close-preview db (first args))

    :app/ax.initialize
    (if (:initialized? db)
      {:uf/db db}
      {:uf/fxs [[:uf/await :storage/fx.load]]
       :uf/dxs [[:storage/ax.loaded :uf/prev-result]]})

    :share/ax.load
    {:uf/fxs [[:uf/await :storage/fx.load]]
     :uf/dxs [[:storage/ax.loaded :uf/prev-result]]}

    :storage/ax.loaded
    (load-order db (first args))

    :storage/ax.saved
    {:uf/db (assoc-in db [:ui :saved]
                      (if (:success (first args)) "Sparat i den här webbläsaren" "Kunde inte spara i webbläsaren"))}

    :order/ax.portion-size
    (change-portion db (first args))

    :order/ax.edit
    (let [[path value] args
          order (model/edit-order (:order db) path value)
          typed (model/number-value value)
          notice (when (and (= :rows (first path))
                            (model/valid-number? value 0 2000000 true))
                   (row-count-notice order path typed))]
      (change-order db order notice))

    :order/ax.step
    (let [[id delta] args order (:order db) row (get-in order [:rows id])
          n (model/step-count (model/quantity row) delta (model/count-step order))]
      (when (and (:enabled? row) (#{-1 1} delta) (model/valid-settings? order)
                 (model/valid-rows? order) (> (count (model/active-ids order)) 1)
                 (<= 0 n (model/target order)))
        (change-order db (model/edit-order order [:rows id :qty] n) nil)))

    :order/ax.reset
    (reset-order db (first args))

    :order/ax.add-gluten-free
    (let [id (first args) order (:order db)]
      (when (model/valid-rows? order)
        (let [updated (model/add-gluten-free-row order id)]
          (when (not= order updated)
            (-> (change-order db updated "En glutenfri rad är tillagd med 0 och 0 %. Använd + för att ge den en andel.")
                (update :uf/fxs (fnil conj []) [:dom/fx.focus (str "increase-" (name (model/gluten-free-id id)))]))))))

    :order/ax.toggle
    (let [id (first args) order (:order db)]
      (if-not (model/valid-rows? order)
        (let [{:keys [db fxs]} (present-notice db "Rätta antalet i de markerade raderna innan du ändrar vilka sorter som är med.")]
          {:uf/db db
           :uf/fxs (into [[:dom/fx.check-input (str "include-" (name id)) (get-in order [:rows id :enabled?])]] fxs)})
        (let [{next-order :order error :error} (model/toggle-row order id)
              was-active? (get-in order [:rows id :enabled?])
              n (model/quantity (get-in order [:rows id]))]
          (if error
            (let [{:keys [db fxs]} (present-notice db error)]
              {:uf/db db
               :uf/fxs (into [[:dom/fx.check-input (str "include-" (name id)) (get-in order [:rows id :enabled?])]] fxs)})
            (change-order db next-order
                          (if was-active?
                            (if (pos? n) (str n " " (:unit (model/portion order)) " fördelades på övriga "
                                              (if (:vego? (model/menu-by-id id)) "vegosorter." "icke-vegetariska sorter."))
                                "Sorten är avstängd.")
                            "Sorten är aktiverad med 0 och 0 %. Använd + för att ge den en andel."))))))

    :order/ax.share
    (when (model/shareable? (:order db))
      (let [link (model/share-link (:order db))]
        {:uf/fxs [[:uf/await :clipboard/fx.write link]]
         :uf/dxs [[:clipboard/ax.copied :link link :uf/prev-result]]}))

    :order/ax.copy
    (let [order (:order db)]
      (when (and (model/valid-settings? order) (model/valid-rows? order) (pos? (:total (model/totals order))))
        (let [text (model/order-text order)]
          {:uf/fxs [[:uf/await :clipboard/fx.write text]]
           :uf/dxs [[:clipboard/ax.copied :order text :uf/prev-result]]})))

    :clipboard/ax.copied
    (let [[kind text result] args
          shared? (= :link kind)
          {:keys [db fxs]} (present-notice
                            db
                            (if (:success result)
                              (if shared? "Delningslänken är kopierad till klippbordet." "Beställningen är kopierad till klippbordet.")
                              (if shared? "Markera och kopiera länken i textfältet nedan."
                                  "Markera och kopiera beställningen i textfältet nedan.")))]
      {:uf/db (-> db
                  (assoc-in [:ui :copy-kind] kind)
                  (assoc-in [:ui :copy-text] (when-not (:success result) text)))
       :uf/fxs fxs})

    :ui/ax.dismiss-notice
    (when (= (first args) (get-in db [:ui :notice-token]))
      (let [{:keys [db fxs]} (present-notice db nil)]
        {:uf/db db :uf/fxs fxs}))

    :ui/ax.clear-notice
    (when (and (= (first args) (get-in db [:ui :notice-token]))
               (not (get-in db [:ui :notice-open?])))
      {:uf/db (assoc-in db [:ui :notice] nil)})

    :ui/ax.select-input
    {:uf/fxs [[:dom/fx.select-input (first args)]]}

    :ui/ax.effect-failed
    (let [{:keys [db fxs]} (present-notice db "Åtgärden kunde inte slutföras. Dina antal finns kvar på sidan.")]
      {:uf/db db :uf/fxs fxs})

    :uf/unhandled-ax))
