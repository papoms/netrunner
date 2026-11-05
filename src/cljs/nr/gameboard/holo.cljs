(ns nr.gameboard.holo
  "Optional holo effect for the zoomed card (setting :card-zoom-holo).

  While the pointer moves over a card on the board or in a hand, the zoomed copy
  of that card tilts towards the pointer and a glare and a light rainbow shine
  follow it, like tilting a foil card under a lamp.

  The pointer position comes from `nr.gameboard.card-preview/zoom-pointer`. A
  small requestAnimationFrame loop eases towards it with a spring and writes CSS
  variables on the element, so mouse moves never re-render React components.
  The look is defined in src/css/gameboard.styl (.holo-card).

  The spring constants follow selop/pokebox (MIT)."
  (:require
   [nr.gameboard.card-preview :refer [zoom-pointer]]
   [reagent.core :as r]))

(def max-tilt 14)    ; degrees at the card's edges
(def stiffness 0.15) ; per frame at 60 fps
(def damping 0.7)
(def epsilon 0.0005)

(defn- spring
  "One spring step. Returns [position velocity]."
  [pos vel target]
  (let [acc (- (* (- stiffness) (- pos target)) (* damping vel))
        vel (+ vel acc)]
    [(+ pos vel) vel]))

(defn- settled? [pos vel target]
  (and (< (js/Math.abs (- pos target)) epsilon)
       (< (js/Math.abs vel) epsilon)))

(defn- targets
  "Target [x y amount] for the current pointer: centered and off when no card is hovered."
  []
  (if-let [[x y] @zoom-pointer]
    [x y 1]
    [0.5 0.5 0]))

(defn- write-vars! [^js el x y a]
  (let [style (.-style el)]
    (.setProperty style "--holo-x" (str (* 100 x) "%"))
    (.setProperty style "--holo-y" (str (* 100 y) "%"))
    ;; the side under the pointer comes towards the viewer
    (.setProperty style "--holo-rx" (str (* a (- y 0.5) 2 max-tilt) "deg"))
    (.setProperty style "--holo-ry" (str (* a (- 0.5 x) 2 max-tilt) "deg"))
    (.setProperty style "--holo-a" (str a))))

(defn holo-card
  "A card image with the holo effect. Drop-in replacement for the zoomed [:img]."
  [_url _title]
  (let [el (atom nil)
        frame (atom nil)
        ;; positions and velocities of x, y and the effect amount
        state (atom {:x 0.5 :vx 0 :y 0.5 :vy 0 :a 0 :va 0})
        tick (fn tick []
               (let [[tx ty ta] (targets)
                     {:keys [x vx y vy a va]} @state]
                 (when-not (and (settled? x vx tx) (settled? y vy ty) (settled? a va ta))
                   (let [[x vx] (spring x vx tx)
                         [y vy] (spring y vy ty)
                         [a va] (spring a va ta)]
                     (reset! state {:x x :vx vx :y y :vy vy :a a :va va})
                     (when-let [e @el]
                       (write-vars! e x y (max 0 (min 1 a))))))
                 (reset! frame (js/requestAnimationFrame tick))))]
    (r/create-class
      {:display-name "holo-card"
       :component-did-mount (fn [_] (tick))
       :component-will-unmount (fn [_] (some-> @frame js/cancelAnimationFrame))
       :reagent-render
       (fn [url title]
         [:div.holo-card {:ref #(reset! el %)}
          [:img {:src url :alt title}]
          [:div.holo-shine]
          [:div.holo-glare]])})))
