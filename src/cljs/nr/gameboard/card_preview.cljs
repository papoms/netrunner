(ns nr.gameboard.card-preview
  (:require [cljs.core.async :refer [chan put!]]
            [nr.appstate :refer [app-state]]))

(defonce zoom-channel (chan))

;; Where the pointer is on the hovered card, as [x y] fractions (0..1 from the
;; top left), or nil when no card is hovered. Drives the optional holo effect on
;; the zoomed card (nr.gameboard.holo). A plain atom on purpose: it changes on
;; every mouse move and must not cause re-renders.
(defonce zoom-pointer (atom nil))

(defn set-zoom-pointer!
  [x y]
  (reset! zoom-pointer [(min 1 (max 0 x)) (min 1 (max 0 y))]))

(defn track-zoom-pointer!
  "on-mouse-move handler for card elements: records the pointer position within the card.
  Uses offsetX/Y, which are in the target's own (untransformed) coordinates, so
  rotated cards (ice, the opponent's side of the board) map correctly."
  [^js e]
  (when (get-in @app-state [:options :card-zoom-holo])
    (let [native (.-nativeEvent e)
          ^js card (.-currentTarget e)
          w (.-offsetWidth card)
          h (.-offsetHeight card)]
      ;; offsetX/Y are relative to the element that was hit (image, counters
      ;; overlay, name label, ...): add up layout offsets to get card coordinates
      (when (and (pos? w) (pos? h))
        (loop [^js el (.-target native)
               x (.-offsetX native)
               y (.-offsetY native)]
          (cond
            (identical? el card) (set-zoom-pointer! (/ x w) (/ y h))
            (nil? el) nil ; offsetParent chain doesn't pass the card: ignore
            :else (recur (.-offsetParent el) (+ x (.-offsetLeft el)) (+ y (.-offsetTop el)))))))))

(defn clear-zoom-pointer! []
  (reset! zoom-pointer nil))

(defn- safe-get-attribute [target attribute]
  (when (.-getAttribute target)
    (.getAttribute target attribute)))

(defn- get-card-data-title [e]
  (let [target (.. e -target)
        title (or (safe-get-attribute target "data-card-title")
                  (when (= "BUTTON" (.-tagName target))
                    (some-> target .-firstChild (safe-get-attribute "data-card-title"))))]
    (not-empty title)))

(defn put-game-card-in-channel
  [card channel]
  (if-let [server-card (get-in @app-state [:all-cards-and-flips (or (:title card) (:printed-title card))])]
    (put! channel (merge server-card card))
    (put! channel card))
  nil)

(defn card-preview-mouse-over
  [e channel]
  (.preventDefault e)
  (when-let [title (get-card-data-title e)]
    (when-let [card (get-in @app-state [:all-cards-and-flips title])]
      (put! channel card)))
  nil)

(defn card-preview-mouse-out [e channel]
  (.preventDefault e)
  (when (get-card-data-title e)
    (put! channel false))
  nil)

(defn card-highlight-mouse-over [e value channel]
  (.preventDefault e)
  (when (:cid value)
    (put! channel (select-keys value [:cid])))
  nil)

(defn card-highlight-mouse-out [e value channel]
  (.preventDefault e)
  (when (:cid value)
    (put! channel false))
  nil)

