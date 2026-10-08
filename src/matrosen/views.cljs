(ns matrosen.views
  (:require [matrosen.photos :as photos]
            [matrosen.model :refer [menu menu-by-id order-menu dish-label gluten-free-id menu-url restaurant-url valid-number? valid-settings? valid-rows? quantity totals target active-ids share-total format-number]]))

;; Presentation reads the same calculations used by the order actions.
(defn icon [kind]
  [:svg.icon {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width 1.7
              :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden true}
   (case kind
     :arrow [:path {:d "M7 17 17 7M7 7h10v10"}]
     :close [:path {:d "m6 6 12 12M6 18 18 6"}]
     :photo [:g [:rect {:x 3 :y 4 :width 18 :height 16 :rx 2}] [:circle {:cx 8 :cy 9 :r 1.5}] [:path {:d "m3 17 5-5 4 4 4-6 5 7"}]]
     :copy [:g [:rect {:x 8 :y 8 :width 12 :height 13 :rx 2}] [:path {:d "M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"}]]
     :link [:g [:path {:d "m10 13 4-4M8 16l-1 1a4 4 0 0 1-6-6l4-4a4 4 0 0 1 6 0M16 8l1-1a4 4 0 0 1 6 6l-4 4a4 4 0 0 1-6 0"}]]
     :check [:path {:d "m5 12 4 4L19 6"}]
     :leaf [:g [:path {:d "M20 3C10 2 3 7 5 14c2 7 15 7 15-11Z"}] [:path {:d "m4 21 11-12"}]]
     :reset [:g [:path {:d "M4 12a8 8 0 1 0 2.2-5.6"}] [:path {:d "M4 4v5h5"}]]
     [:circle {:cx 12 :cy 12 :r 8}])])
(defn dish-preview [{:keys [id position]}]
  (let [{:keys [name description base-id gluten-free?]} (menu-by-id id) photo (get photos/photos (or base-id id))]
    [:section#dish-preview.dish-preview
     {:role "dialog" :aria-modal false :aria-labelledby "preview-title" :style position}
     [:button#preview-close.preview-close {:type "button" :aria-label "Stäng förhandsvisning"
                                          :on {:click [[:preview/ax.close true]]}} (icon :close)]
     (if photo
       [:img.preview-photo {:src (:src photo) :alt (str name " från Matrosen") :width 640 :height 360}]
       [:div.preview-empty (icon :photo) "Bild saknas"])
     [:div.preview-caption [:h3#preview-title name] [:p description]
      (when gluten-free? [:p "Bilden visar originalet."])
      [:a {:href (or (:source photo) "https://matrosensmorrebrod.com/meny") :target "_blank" :rel "noreferrer"}
       (if photo (str "Foto: " (:credit photo)) "Se Matrosens meny") (icon :arrow)]]]))
(defn menu-row [value preview {:keys [id name description vego? gluten-free?] :as dish}]
  (let [row (get-in value [:rows id]) valid? (valid-number? (:qty row) 0 1000000 true)
        label (dish-label dish)
        desired (target value)
        editable? (and (:enabled? row) desired (> (count (active-ids value)) 1))
        step-enabled? (and editable? (valid-rows? value))
        field-id (str "qty-" (cljs.core/name id))]
    [:tr {:replicant/key id :class (when-not (:enabled? row) "excluded")
          :data-has-variant (and (not gluten-free?) (contains? (:rows value) (gluten-free-id id)))}
     [:td [:label.toggle-label
           [:input {:id (str "include-" (cljs.core/name id)) :type "checkbox" :checked (:enabled? row) :on {:change [[:order/ax.toggle id]]}
                    :aria-label (str "Ta med " label)}]]]
     [:th {:scope "row"}
      [:div.dish-title [:span.dish-name name] (when vego? [:span.vego-tag (icon :leaf) "Vego*"])
       (when gluten-free? [:span.gluten-tag "Glutenfri"])
       [:button.dish-trigger
        {:id (str "preview-" (cljs.core/name id)) :type "button" :aria-label (str "Visa " label)
         :aria-haspopup "dialog" :aria-expanded (= id (:id preview))
         :aria-controls (when (= id (:id preview)) "dish-preview")
         :on {:click [[:preview/ax.toggle id :preview/anchor :event/detail]]}}
        (icon :photo)]]
      [:span.dish-description description]
      [:div.row-details
       [:span.row-share {:id (str "share-" (cljs.core/name id))}
        (str "Fördelning: " (format-number (* 100 (:share row))) " %")]
       (when (and (not gluten-free?) (not (contains? (:rows value) (gluten-free-id id))))
         [:button.split-button {:type "button" :aria-label (str "Lägg till glutenfri rad för " name)
                                :disabled (or (not (:enabled? row)) (not (valid-rows? value)))
                                :on {:click [[:order/ax.add-gluten-free id]]}}
          "+ Glutenfri"])]]
     [:td.qty-cell
      [:div.qty-control
       [:button.qty-step {:type "button" :aria-label (str "Minska " label)
                          :disabled (or (not step-enabled?) (zero? (quantity row)))
                          :on {:click [[:order/ax.step id -1]]}} "−"]
       [:input.qty-input {:id field-id :type "number" :inputmode "numeric" :min 0 :max (or desired 1000000) :step 1
                         :aria-label (str "Antal " label) :aria-invalid (not valid?)
                         :aria-describedby (when-not valid? "quantity-error")
                         :value (:qty row) :disabled (not editable?)
                         :on {:focus [[:ui/ax.select-input field-id]]
                              :input [[:order/ax.edit [:rows id :qty] :event/target.value]]}}]
       [:button.qty-step {:type "button" :aria-label (str "Öka " label)
                          :disabled (or (not step-enabled?) (and desired (>= (quantity row) desired)))
                          :on {:click [[:order/ax.step id 1]]}} "+"]]]
     [:td.numeric.veg-number {:class (when-not vego? "no-veg")}
      (if vego? (quantity row) [:span {:aria-label "0 vegetariska"} "–"])]]))
(defn app [{:keys [order ui]}]
  (let [value order {:keys [total vego gluten-free]} (totals value) desired (target value)
        dishes (order-menu value)
        ids (active-ids value)
        settings-valid? (valid-settings? value) rows-valid? (valid-rows? value)
        difference (when desired (- total desired))]
    [:main.shell
     [:header.masthead
      [:div [:h1 "Smörrebrödsplaneraren"] [:p.subtitle "Ordna mat till eventet från Matrosen Smörrebröd"]]
      [:a.event-link {:href restaurant-url :target "_blank" :rel "noreferrer"} "Matrosen Smörrebröd" (icon :arrow)]]
     [:section.planner {:aria-label "Planera antal"}
      [:div.planner-fields
       [:div.field [:label {:for "guests"} "Antal gäster"]
        [:input#guests.plan-input {:type "number" :inputmode "numeric" :min 1 :max 10000 :step 1
                                   :value (:guests value) :aria-invalid (not (valid-number? (:guests value) 1 10000 true))
                                   :aria-describedby (when-not settings-valid? "settings-error")
                                   :on {:focus [[:ui/ax.select-input "guests"]]
                                        :input [[:order/ax.edit [:guests] :event/target.value]]}}]]
       [:div.field [:label {:for "per-person"} "Smörrebröd per person"]
        [:input#per-person.plan-input {:type "number" :inputmode "decimal" :min 0.5 :max 100 :step "any"
                                       :value (:per-person value) :aria-invalid (not (valid-number? (:per-person value) 0.5 100 false))
                                       :aria-describedby (if settings-valid? "target-note" "settings-error")
                                       :on {:focus [[:ui/ax.select-input "per-person"]]
                                            :input [[:order/ax.edit [:per-person] :event/target.value]]}}]]]
      [:p#target-note.target-note
       (if desired (list "Mål: " [:strong (str (format-number desired) " smörrebröd")] ". ") "")
       "Ändrar du gäster eller smörrebröd per person fördelas beställningen enligt den sparade fördelningen. Ändrar du en rad hålls det antalet fast och resten fördelas."]
      (when-not settings-valid? [:p#settings-error.error "Ange 1–10 000 hela gäster och 0,5–100 smörrebröd per person."])
      (cond
        (empty? ids) [:p.error "Aktivera minst en sort för att fördela."]
        (= 1 (count ids)) [:p.target-note "En enda aktiv sort får hela målantalet. Aktivera en sort till för att justera fördelningen."]
        (zero? (share-total value)) [:p.error "Öka antalet på minst en rad för att skapa en fördelning."])]
     [:section {:aria-labelledby "order-title"}
      [:div.order-heading
       [:div.heading-line [:h2#order-title "Din beställning"] [:span.sort-count (str (count ids) " av " (count dishes) " sorter")]]
       [:div.order-actions
        [:button.secondary {:on {:click [[:order/ax.reset :location/hash]]}}
         (icon :reset) "Återställ"]
        [:button.secondary {:on {:click [[:order/ax.share]]} :disabled (not (matrosen.model/shareable? value))
                            :title "Kopiera en länk med alla antal, val och fördelningsprocent"}
         (icon :link) "Kopiera för delning"]
        [:button.secondary {:on {:click [[:order/ax.copy]]} :disabled (or (not settings-valid?) (not rows-valid?) (zero? total))}
         (icon :copy) "Kopiera"]]]
      [:div.notice {:role "status" :aria-live "polite"} (:notice ui)]
      [:div.table-wrap
       [:table
        [:caption.sr-only "Smörrebröd: välj sorter, ändra antal och se hur många som är vegetariska."]
        [:colgroup [:col.include-col] [:col] [:col.qty-col] [:col.veg-col]]
        [:thead [:tr [:th {:scope "col"} "Med"] [:th {:scope "col"} "Smörrebröd"]
                 [:th.numeric {:scope "col"} "Antal"] [:th.numeric {:scope "col"} "Vego*"]]]
        [:tbody (for [dish dishes] (menu-row value (:preview ui) dish))]
        [:tfoot
         [:tr
          [:th {:scope "row" :colspan 2}
           [:div.footer-overview
            [:div.footer-label "Totalt" [:span.total-sub "smörrebröd"]]
            [:div.footer-gluten
             [:span#gluten-free-count.footer-number (if rows-valid? gluten-free "–")]
             [:span.total-sub "glutenfria"]]]]
          [:td.total-quantity
           [:span#total-count.footer-number (if rows-valid? total "–")]]
          [:td#vego-count.numeric (if rows-valid? vego "–")
           [:span.total-sub (if (and rows-valid? (pos? total)) (str (js/Math.round (* 100 (/ vego total))) " % vego") "vego")]]]]]]
      (when-not rows-valid? [:p#quantity-error.error "Ange ett helt antal från 0 i de markerade raderna. Summan visas när alla antal är giltiga."])
      [:div.balance
       [:span.balance-label {:class (if (and rows-valid? (= 0 difference)) "balanced" "unbalanced")}
        (cond
          (not rows-valid?) "Kontrollera de markerade antalen"
          (nil? desired) "Fyll i planeringen för att jämföra med målet"
          (zero? difference) (list (icon :check) "Beställningen matchar målet")
          (pos? difference) (str (format-number difference) " fler än målet på " (format-number desired))
          :else (str (format-number (- difference)) " färre än målet på " (format-number desired)))]
       [:span.saved (:saved ui)]]
      (when-let [text (:copy-text ui)]
        [:div.copy-fallback [:label {:for "copy-text"} (if (= :link (:copy-kind ui)) "Länk till din beställning" "Din beställning som text")]
         [:textarea#copy-text {:value text :readonly true :on {:focus [[:ui/ax.select-input "copy-text"]]}}]])
      [:p.menu-note [:strong "*Vego"] " enligt menybeskrivningarna, med ägg och mjölk. Bekräfta med Matrosen. Gamle Ole räknas inte som vego eftersom den serveras med skygelé."]]
     [:footer.footer
      [:a {:href menu-url :target "_blank" :rel "noreferrer"} "Menyunderlag · 18 sep 2025" (icon :arrow)]
      [:span "För din planering. Ingen beställning skickas."]
      [:span.footer-credit "Gjort med kärlek till Matrosen, av "
       [:a {:href "https://agical.se" :target "_blank" :rel "noreferrer"} "Agical"]]]
     (when-let [preview (:preview ui)] (dish-preview preview))]))
