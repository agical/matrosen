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

(defn change-order [db value message]
  (merge
    {:uf/db (-> db
                (assoc :order value)
                (assoc-in [:ui :notice] message)
                (assoc-in [:ui :copy-text] nil)
                (assoc-in [:ui :saved]
                          (if (and (model/valid-settings? value) (model/valid-rows? value))
                            "Sparar…" "Ogiltiga värden sparas inte")))}
    (save-recipe value)))

(defn reset-order
  "Restores the starting order and drops a shared plan from the address."
  [db fragment]
  (let [order (model/default-order)]
    (cond-> (-> (change-order db order nil)
                (assoc-in [:uf/db :ui :preview] nil))
      (and (string? fragment) (str/starts-with? fragment "#plan="))
      (update :uf/fxs (fnil conj []) [:url/fx.clear-plan fragment]))))

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
    (let [{:keys [success value fragment]} (first args)
          shared (model/read-shared-plan (or fragment ""))
          version (:version value)
          valid? (and (#{1 2 3} version) (model/valid-stored-order? (:order value))
                      (or (= 1 version) (model/valid-stored-shares? (:order value))))
          local (if (and (not (:initialized? db)) valid?)
                  (if (= 1 version) (model/update-shares (:order value)) (:order value)) (:order db))
          order (or (:order shared) local)]
      (cond->
        (merge {:uf/db (-> db (assoc :order order :initialized? true)
                           (assoc-in [:ui :copy-text] nil)
                           (assoc-in [:ui :preview] nil)
                           (assoc-in [:ui :saved] (if success "Sparar…" "Lokalt sparande är inte tillgängligt"))
                           (assoc-in [:ui :notice]
                                     (cond
                                       (:order shared) "Den delade beställningen är öppnad. Alla antal och fördelningsprocent är bevarade."
                                       (:error shared) "Delningslänken kunde inte läsas. Din befintliga beställning visas."
                                       (and success (some? value) (not valid?)) "Den sparade beställningen kunde inte läsas. Ett nytt förslag visas.")))}
               (when success (save-recipe order)))
        (:order shared) (update :uf/fxs (fnil conj []) [:url/fx.clear-plan fragment])))

    :storage/ax.saved
    {:uf/db (assoc-in db [:ui :saved]
                     (if (:success (first args)) "Sparat i den här webbläsaren" "Kunde inte spara i webbläsaren"))}

    :order/ax.edit
    (let [[path value] args
          order (model/edit-order (:order db) path value)
          corrected? (and (= :rows (first path))
                          (model/valid-number? value 0 1000000 true)
                          (not= (model/number-value value) (model/number-value (get-in order path))))]
      (cond-> (change-order db order
                (when corrected? (str "Antalet begränsades till målet på " (model/target order) " smörrebröd.")))
        corrected? (update :uf/fxs #(into [[:dom/fx.set-input (str "qty-" (name (second path))) (get-in order path)]] %))))

    :order/ax.step
    (let [[id delta] args order (:order db) row (get-in order [:rows id])
          n (+ (model/quantity row) delta)]
      (when (and (:enabled? row) (#{-1 1} delta) (model/valid-settings? order)
                 (model/valid-rows? order) (> (count (model/active-ids order)) 1)
                 (<= 0 n (model/target order)))
        (change-order db (model/edit-order order [:rows id :qty] n) nil)))

    :order/ax.distribute
    (let [order (:order db)]
      (when (and (model/valid-settings? order) (model/valid-rows? order) (pos? (model/share-total order)))
        (change-order db (model/distribute order) "Beställningen är fördelad enligt procenten, avrundat till hela smörrebröd.")))

    :order/ax.reset
    (reset-order db (first args))

    :order/ax.add-gluten-free
    (let [id (first args) order (:order db)]
      (when (model/valid-rows? order)
        (let [updated (model/add-gluten-free-row order id)]
          (when (not= order updated)
            (-> (change-order db updated "En glutenfri rad är tillagd med 0 och 0 %. Använd + för att ge den en andel.")
                (update :uf/fxs (fnil conj []) [:dom/fx.focus (str "qty-" (name (model/gluten-free-id id)))]))))))

    :order/ax.toggle
    (let [id (first args) order (:order db)]
      (if-not (model/valid-rows? order)
        {:uf/db (assoc-in db [:ui :notice] "Rätta antalet i de markerade raderna innan du ändrar vilka sorter som är med.")
         :uf/fxs [[:dom/fx.check-input (str "include-" (name id)) (get-in order [:rows id :enabled?])]]}
        (let [{next-order :order error :error} (model/toggle-row order id)
              was-active? (get-in order [:rows id :enabled?])
              n (model/quantity (get-in order [:rows id]))]
          (if error {:uf/db (assoc-in db [:ui :notice] error)
                     :uf/fxs [[:dom/fx.check-input (str "include-" (name id)) (get-in order [:rows id :enabled?])]]}
            (change-order db next-order
              (if was-active?
                (if (pos? n) (str n " smörrebröd fördelades på övriga "
                                 (if (:vego? (model/menu-by-id id)) "vegosorter." "icke-vegetariska sorter."))
                    "Sorten är avstängd.")
                "Sorten är aktiverad med 0 och 0 %. Använd + eller ange ett antal för att ge den en andel."))))))

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
    (let [[kind text result] args shared? (= :link kind)]
      {:uf/db (-> db
                  (assoc-in [:ui :copy-kind] kind)
                  (assoc-in [:ui :copy-text] (when-not (:success result) text))
                  (assoc-in [:ui :notice]
                            (if (:success result)
                              (if shared? "Delningslänken är kopierad till klippbordet." "Beställningen är kopierad till klippbordet.")
                              (if shared? "Markera och kopiera länken i textfältet nedan."
                                  "Markera och kopiera beställningen i textfältet nedan."))))})

    :ui/ax.select-input
    {:uf/fxs [[:dom/fx.select-input (first args)]]}

    :ui/ax.effect-failed
    {:uf/db (assoc-in db [:ui :notice] "Åtgärden kunde inte slutföras. Dina antal finns kvar på sidan.")}

    :uf/unhandled-ax))
