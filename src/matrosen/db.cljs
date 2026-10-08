(ns matrosen.db
  (:require [matrosen.model :as model]))

(defonce !state
  (atom {:order (model/default-order) :initialized? false
         :ui {:notice nil :saved "" :copy-text nil}}))
