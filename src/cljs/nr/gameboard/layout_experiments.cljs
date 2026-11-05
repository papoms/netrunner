(ns nr.gameboard.layout-experiments
  "Experimental interface tweaks for the game board, switched on with the
  :ui-experiments setting. Everything lives here and in
  src/css/layout-experiments.styl; the regular board only adds the classes from
  `gameboard-classes` and renders `right-pane-pin`.

  Current experiments:
  - the right side panel (card zoom, log, chat) hides at the screen edge and
    slides in while the pointer is over it; a pin keeps it open."
  (:require
   [jinteki.i18n :refer [tr]]
   [nr.appstate :refer [app-state]]
   [reagent.core :as r]))

(defn- stored [k default]
  (try (if-let [v (.getItem js/localStorage k)] (= v "true") default)
       (catch :default _ default)))

(defn- store! [k v]
  (try (.setItem js/localStorage k (str v)) (catch :default _ nil)))

;; per device, like the panel layout itself
(defonce right-pane-pinned (r/atom (stored "ui-x-right-pane-pinned" false)))

(defn enabled? []
  (boolean (get-in @app-state [:options :ui-experiments])))

(defn gameboard-classes
  "Classes for the .gameboard element, as one string (nil when the experiments are off)."
  []
  (when (enabled?)
    (str "ui-x" (when @right-pane-pinned " ui-x-right-pinned"))))

(defn right-pane-pin
  "Pin button at the top of the right panel: keeps it open instead of sliding away."
  []
  (when (enabled?)
    (let [pinned @right-pane-pinned]
      [:button.ui-x-pin
       {:class (when pinned :pinned)
        :title (if pinned
                 (tr [:game_ui-x-unpin-panel "Let the panel slide away"])
                 (tr [:game_ui-x-pin-panel "Keep the panel open"]))
        :on-click #(let [v (not pinned)]
                     (reset! right-pane-pinned v)
                     (store! "ui-x-right-pane-pinned" v))}
       "📌"])))
