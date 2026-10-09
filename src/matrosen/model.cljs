(ns matrosen.model
  (:require [clojure.string :as str]))

;; Menu data and identity stay separate from the editable order.
(def menu-url "https://static.thatsup.website/37/71554/Matrosen_Meny_20250918.pdf?v=1762507306")

(def restaurant-url "https://matrosensmorrebrod.com/")

(def menu
  [{:id :avokado :name "Avokado" :description "Ägg · majonnäs" :vego? true}
   {:id :brie :name "Brie" :description "Marmelad · pumpafrön" :vego? true}
   {:id :kornblomst :name "Kornblomst" :description "Äggula · rödlök" :vego? true}
   {:id :sill :name "Sill från Christiansø" :description "Inlagd sill" :vego? false}
   {:id :gubbrora :name "Gubbröra" :description "Ansjovis" :vego? false}
   {:id :gravlax :name "Gravlax" :description "Senapssås" :vego? false}
   {:id :varmrokt-lax :name "Varmrökt lax" :description "Ägg · majonnäs · gräslök" :vego? false}
   {:id :skagen :name "Skagenröra" :description "Handskalade räkor" :vego? false}
   {:id :rodspatta :name "Friterad rödspätta" :description "Remoulad" :vego? false}
   {:id :rostbiff :name "Marinerad rostbiff" :description "Remoulad · pepparrot · gurka · rostad lök" :vego? false}
   {:id :leverpastej :name "Dansk leverpastej" :description "Champinjoner · bacon" :vego? false}
   {:id :currykyckling :name "Currykyckling" :description "Selleri · bacon · rödlök" :vego? false}
   {:id :gamle-ole :name "Gamle Ole" :description "Skygelé · mörk rom" :vego? false}
   {:id :lun-postej :name "Lun postej" :description "Varm pastej" :vego? false :initially-off? true}])

(defn gluten-free-id [id] (keyword (str (name id) "-glutenfri")))

(def all-menu
  (vec (mapcat (fn [dish]
                 [dish (assoc dish :id (gluten-free-id (:id dish))
                                   :base-id (:id dish) :gluten-free? true)]) menu)))

(def menu-by-id (into {} (map (juxt :id identity) all-menu)))

(defn order-menu [order]
  (filterv #(contains? (:rows order) (:id %)) all-menu))

(defn dish-label [{:keys [name gluten-free?]}]
  (str name (when gluten-free? " (glutenfri)")))

(def storage-key "matrosen.order.v1")

;; Guests and breads per person apply the saved shares to the new target.
;; A row edit keeps that row and splits the remainder. Rounding leaves the shares as they are.
(defn number-value [value]
  (when-not (or (nil? value) (= "" value))
    (let [n (js/Number value)] (when (js/Number.isFinite n) n))))

(defn valid-number? [value minimum maximum integer?]
  (let [n (number-value value)]
    (and (some? n) (<= minimum n maximum) (or (not integer?) (js/Number.isInteger n)))))

(defn valid-settings? [order]
  (and (valid-number? (:guests order) 1 10000 true)
       (valid-number? (:per-person order) 0.5 100 false)))

(defn quantity [row] (if (valid-number? (:qty row) 0 1000000 true) (number-value (:qty row)) 0))

(defn valid-rows? [order]
  (every? (fn [[_ row]] (valid-number? (:qty row) 0 1000000 true)) (:rows order)))

(defn target [order]
  (when (valid-settings? order)
    (js/Math.ceil (- (* (number-value (:guests order)) (number-value (:per-person order))) 1e-9))))

(defn active-ids [order]
  (mapv :id (filter #(get-in order [:rows (:id %) :enabled?]) (order-menu order))))

(defn totals [order]
  (reduce (fn [result {:keys [id vego? gluten-free?]}]
            (let [row (get-in order [:rows id]) n (if (:enabled? row) (quantity row) 0)]
              (-> result (update :total + n) (update :vego + (if vego? n 0))
                  (update :gluten-free + (if gluten-free? n 0)))))
          {:total 0 :vego 0 :gluten-free 0} (order-menu order)))

(defn shares [n ids]
  (if (seq ids)
    (let [base (quot n (count ids)) extra (mod n (count ids))]
      (into {} (map-indexed (fn [i id] [id (+ base (if (< i extra) 1 0))]) ids)))
    {}))

(defn share-total [order]
  (reduce + 0 (map #(get-in order [:rows % :share] 0) (active-ids order))))

;; Only used to migrate older saved orders that have no independent shares.
(defn update-shares [order]
  (if (valid-rows? order)
    (let [total (:total (totals order))]
      (update order :rows
              (fn [rows]
                (into {} (map (fn [[id row]]
                                [id (assoc row :share
                                           (if (and (:enabled? row) (pos? total))
                                             (/ (quantity row) total) 0))]) rows)))))
    order))

;; Largest remainders make whole breads add up to the target. Menu order breaks ties.
;; Allocation never rewrites shares: rounding must not change the next distribution.
(defn weighted-shares [order n]
  (let [total (share-total order)
        portions (map-indexed
                   (fn [i id]
                     (let [exact (* n (/ (get-in order [:rows id :share]) total))
                           base (js/Math.floor exact)]
                       {:id id :index i :base base :remainder (- exact base)}))
                   (filterv #(pos? (get-in order [:rows % :share])) (active-ids order)))
        remaining (- n (reduce + 0 (map :base portions)))
        extras (set (map :id (take remaining (sort-by (juxt (comp - :remainder) :index) portions))))]
    (into {} (map (fn [{:keys [id base]}] [id (+ base (if (extras id) 1 0))]) portions))))

(defn distribute [order]
  (let [n (target order)]
    (if (and n (pos? (share-total order)))
      (let [allocation (weighted-shares order n)]
        (update order :rows
                (fn [rows] (into {} (map (fn [[id row]] [id (assoc row :qty (get allocation id 0))]) rows)))))
      order)))

(defn rebalance-row [order id]
  (let [n (target order)
        peers (filterv #(not= id %) (active-ids order))
        chosen (if (seq peers) (min n (quantity (get-in order [:rows id]))) n)
        remainder (- n chosen)
        peer-order (assoc-in order [:rows id :enabled?] false)
        peer-total (share-total peer-order)
        allocation (if (pos? peer-total)
                     (weighted-shares peer-order remainder)
                     (shares remainder peers))]
    ;; Keep fractional shares through every edit. Feeding rounded quantities back
    ;; into shares makes alternating increases steal the same bread back and forth.
    (reduce (fn [result peer]
              (let [relative-share (if (pos? peer-total)
                                     (/ (get-in order [:rows peer :share]) peer-total)
                                     (/ 1 (count peers)))]
                (-> result
                    (assoc-in [:rows peer :qty] (get allocation peer 0))
                    (assoc-in [:rows peer :share] (* (/ remainder n) relative-share)))))
            (-> order
                (assoc-in [:rows id :qty] chosen)
                (assoc-in [:rows id :share] (/ chosen n)))
            peers)))

(defn edit-order [order path value]
  (let [edited (assoc-in order path value)]
    (cond
      (and (= :rows (first path))
           (get-in edited [:rows (second path) :enabled?])
           (valid-settings? edited)
           (valid-rows? edited))
      (rebalance-row edited (second path))

      (and (#{:guests :per-person} (first path))
           (valid-settings? edited)
           (valid-rows? edited))
      (distribute edited)

      :else
      edited)))

(defn add-gluten-free-row [order id]
  (let [dish (menu-by-id id) new-id (gluten-free-id id)]
    (if (and dish (not (:gluten-free? dish))
             (get-in order [:rows id :enabled?]) (not (contains? (:rows order) new-id)))
      (assoc-in order [:rows new-id] {:enabled? true :qty 0 :share 0})
      order)))

(defn toggle-row [order id]
  (let [row (get-in order [:rows id])
        peers (filterv #(and (not= id %) (= (:vego? (menu-by-id id)) (:vego? (menu-by-id %))))
                       (active-ids order))
        n (quantity row) share (:share row)]
    (cond
      (not (:enabled? row)) {:order (assoc-in order [:rows id] {:enabled? true :qty 0 :share 0})}
      (and (or (pos? n) (pos? share)) (empty? peers)) {:error "Det här är sista aktiva sorten i kategorin. Sätt antalet till 0 eller aktivera en annan sort först."}
      :else {:order (reduce (fn [result [peer amount]]
                             (-> result
                                 (assoc-in [:rows peer :qty] (+ (quantity (get-in result [:rows peer])) amount))
                                 (update-in [:rows peer :share] + (/ share (count peers)))))
                           (assoc-in order [:rows id] {:enabled? false :qty 0 :share 0})
                           (shares n peers))})))

(defn default-order []
  (let [active-count (count (remove :initially-off? menu))]
    (distribute {:guests 25 :per-person 3
                 :rows (into {} (map (fn [{:keys [id initially-off?]}]
                                      [id {:enabled? (not initially-off?) :qty 0
                                           :share (if initially-off? 0 (/ 1 active-count))}]) menu))})))

(defn valid-stored-order? [order]
  (and (map? order) (valid-settings? order) (map? (:rows order))
       (every? #(contains? (:rows order) (:id %)) menu)
       (every? #(contains? menu-by-id %) (keys (:rows order)))
       (every? (fn [[_ row]] (and (map? row) (boolean? (:enabled? row))
                                 (valid-number? (:qty row) 0 1000000 true)
                                 (or (:enabled? row) (zero? (quantity row))))) (:rows order))))

(defn valid-stored-shares? [order]
  (and (every? (fn [[_ row]]
                 (and (number? (:share row)) (valid-number? (:share row) 0 (+ 1 1e-9) false)
                      (or (:enabled? row) (zero? (:share row))))) (:rows order))
       (let [total (share-total order)]
         (or (< (js/Math.abs (- total 1)) 1e-9)
             (and (zero? total) (zero? (:total (totals order))))))))

;; Share stable dish IDs and full-precision shares, independently of local storage.
(def site-url "https://agical.github.io/matrosen/")

(defn shareable? [order]
  (and (valid-stored-order? order) (valid-stored-shares? order)))

(defn share-link [order]
  (str site-url "#plan="
       (js/encodeURIComponent
         (js/JSON.stringify
           (clj->js {:v 2 :guests (:guests order) :per-person (:per-person order)
                     :rows (mapv (fn [{:keys [id]}]
                                   (let [{:keys [enabled? qty share]} (get-in order [:rows id])]
                                     [(name id) enabled? qty share])) (order-menu order))})))))

(defn read-shared-plan [fragment]
  (when (str/starts-with? fragment "#plan=")
    (try
      (when (> (count fragment) 12000) (throw (js/Error. "Oversized shared plan")))
      (let [{:keys [v guests per-person rows]}
            (js->clj (js/JSON.parse (js/decodeURIComponent (subs fragment 6))) :keywordize-keys true)
            known-ids (into {} (map (fn [{:keys [id]}] [(name id) id]) (if (= 1 v) menu all-menu)))]
        (if (and (#{1 2} v) (vector? rows) (<= (count menu) (count rows) (count known-ids))
                 (every? #(and (vector? %) (= 4 (count %)) (contains? known-ids (first %))) rows)
                 (= (count rows) (count (set (map first rows)))))
          (let [order {:guests guests :per-person per-person
                       :rows (into {} (map (fn [[id enabled? qty share]]
                                            [(known-ids id) {:enabled? enabled? :qty qty :share share}]) rows))}]
            (if (shareable? order) {:order order} {:error true}))
          {:error true}))
      (catch :default _ {:error true}))))

(defn format-number [n]
  (.toLocaleString n "sv-SE" #js {:maximumFractionDigits 2}))

(defn order-text [value]
  (let [{:keys [total vego gluten-free]} (totals value)]
    (str/join "\n"
      (concat ["Smörrebrödsplaneraren" ""]
              (keep (fn [{:keys [id vego?] :as dish}]
                      (let [row (get-in value [:rows id]) n (quantity row)]
                        (when (and (:enabled? row) (pos? n))
                          (str n " × " (dish-label dish) (when vego? " (vego*)"))))) (order-menu value))
              ["" (str "Totalt: " total " smörrebröd, varav " vego " vego*.")
               (str "Glutenfria: " gluten-free " smörrebröd.")
               (str "Antal gäster: " (format-number (number-value (:guests value))))
               (str "Smörrebröd per person: " (format-number (/ total (number-value (:guests value)))))
               "" "*Vego enligt menybeskrivningarna, inklusive ägg och mjölk. Bekräfta med Matrosen."]))))
