(ns matrosen.effects
  (:require [replicant.dom :as dom]
            [matrosen.model :as model]
            [matrosen.views :as views]))

(defn perform-effect! [[effect & args]]
  (case effect
    :dom/fx.focus
    (when-let [element (.getElementById js/document (first args))]
      (.focus element #js {:preventScroll true}))

    :dom/fx.render
    (dom/render (.getElementById js/document "app") (views/app (first args)))

    :dom/fx.select-input
    (when-let [element (.getElementById js/document (first args))] (.select element))

    :dom/fx.check-input
    (when-let [element (.getElementById js/document (first args))]
      (set! (.-checked element) (second args)))

    :dom/fx.set-input
    (when-let [element (.getElementById js/document (first args))]
      (set! (.-value element) (second args)))

    :storage/fx.load
    (assoc
      (try
        (let [raw (.getItem js/localStorage model/storage-key)]
          {:success true :value (when raw
                                 (try (js->clj (js/JSON.parse raw) :keywordize-keys true)
                                      (catch :default _ :invalid)))})
        (catch :default _ {:success false}))
      :fragment (.-hash js/location))

    :url/fx.clear-plan
    ;; Consume the snapshot so refreshing after an edit keeps the newer local plan.
    (when (= (first args) (.-hash js/location))
      (.replaceState js/history nil "" (str (.-pathname js/location) (.-search js/location))))

    :storage/fx.save
    (try (.setItem js/localStorage model/storage-key
                   (js/JSON.stringify (clj->js {:version 3 :order (first args)})))
         {:success true}
         (catch :default _ {:success false}))

    :clipboard/fx.write
    (if (and (.-clipboard js/navigator) (.-isSecureContext js/window))
      (try (-> (.writeText (.-clipboard js/navigator) (first args))
               (.then (fn [] {:success true}))
               (.catch (fn [_] {:success false})))
           (catch :default _ {:success false}))
      {:success false})

    :uf/unhandled-fx))
