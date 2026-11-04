(ns nr.gameboard.board3d
  "Experimental three.js renderer for the central game board.

  Only the board itself (servers, ice, rig, identities, deck and discard piles)
  is drawn in 3D. Hands, prompts, buttons, the log and the card zoom stay the
  regular 2D components, and card clicks go through the same handler as the 2D
  board (`nr.gameboard.board/handle-card-click`), so game logic is unchanged.

  This namespace is compiled into its own shadow-cljs module (:board3d) and is
  only loaded when the user enables the :board-3d setting, so three.js is never
  downloaded otherwise."
  (:require
   ["three" :as THREE]
   ["three/examples/jsm/controls/OrbitControls.js" :refer [OrbitControls]]
   [cljs.core.async :refer [put!]]
   [clojure.string :as str]
   [game.core.card :refer [active? faceup? get-counters has-subtype? ice?]]
   [jinteki.i18n :refer [tr tr-span]]
   [nr.gameboard.board :as board]
   [nr.gameboard.card-preview :refer [clear-zoom-pointer! put-game-card-in-channel
                                      set-zoom-pointer! zoom-channel]]
   [nr.gameboard.state :refer [game-state]]
   [reagent.core :as r]))

;;; Layout constants (world units; a card is 0.63 x 0.88 like 63 x 88 mm)

(def card-w 0.63)
(def card-h 0.88)
(def col-w 1.2)        ; spacing between corp server columns
(def ice-step 0.72)    ; spacing between pieces of ice in a column
(def rig-step 0.75)    ; spacing between runner cards in a row
(def corp-dist 3.4)    ; distance of the corp server roots from the table center
(def lerp-factor 0.18) ; how fast cards glide to their new position

;; the event that started the current run floats above the table, leans
;; towards the attacked server and keeps pushing in
(def run-card-height 0.38)
(def run-card-lean 0.22)  ; radians
(def run-card-push 0.14)  ; how far each push moves towards the server

(def highlight-colors
  {:selected "#ffcc00"
   :selectable "#3eff8a"
   :encountered "#ff4d4d"
   :playable "#4aa3ff"})

(def counter-colors
  {:advancement "#f39c12"
   :virus "#2ecc71"
   :power "#9b59b6"
   :credit "#f1c40f"
   :agenda "#3498db"})

;;; Textures

(defonce texture-cache (js/Map.))

;; set when something changed that the render loop can't see itself
;; (a texture finished loading, the pane was resized)
(defonce redraw-requested (atom true))
(defonce texture-loader (THREE/TextureLoader.))

(defn- texture [url]
  (or (.get texture-cache url)
      (let [^js t (.load texture-loader url #(reset! redraw-requested true))]
        (set! (.-colorSpace t) THREE/SRGBColorSpace)
        (.set texture-cache url t)
        t)))

(defn- canvas-texture
  "Draws onto a fresh canvas with `draw-fn` and caches the texture by `k`."
  [k w h draw-fn]
  (or (.get texture-cache k)
      (let [^js canvas (js/document.createElement "canvas")
            _ (set! (.-width canvas) w)
            _ (set! (.-height canvas) h)
            ^js ctx (.getContext canvas "2d")
            _ (draw-fn ctx)
            ^js t (THREE/CanvasTexture. canvas)]
        (set! (.-colorSpace t) THREE/SRGBColorSpace)
        (.set texture-cache k t)
        t)))

(defn- label-texture [text]
  (canvas-texture
    (str "label:" text) 512 96
    (fn [^js ctx]
      (set! (.-font ctx) "bold 52px sans-serif")
      (set! (.-textAlign ctx) "center")
      (set! (.-textBaseline ctx) "middle")
      (set! (.-fillStyle ctx) "rgba(0,0,0,0.55)")
      (.fillRect ctx 0 8 512 80)
      (set! (.-fillStyle ctx) "#e8e8e8")
      (.fillText ctx text 256 48))))

(defn- shadow-texture []
  (canvas-texture
    "shadow" 128 160
    (fn [^js ctx]
      (let [^js g (.createRadialGradient ctx 64 80 4 64 80 72)]
        (.addColorStop g 0 "rgba(0,0,0,0.75)")
        (.addColorStop g 0.55 "rgba(0,0,0,0.45)")
        (.addColorStop g 1 "rgba(0,0,0,0)")
        (set! (.-fillStyle ctx) g)
        (.fillRect ctx 0 0 128 160)))))

(defn- badge-texture
  "Counters (top left) and strength (bottom right) drawn on a transparent card-sized overlay."
  [badges strength]
  (canvas-texture
    (str "badges:" (pr-str badges) ":" strength) 256 358
    (fn [^js ctx]
      (set! (.-font ctx) "bold 44px sans-serif")
      (set! (.-textAlign ctx) "center")
      (set! (.-textBaseline ctx) "middle")
      (doseq [[i [color n]] (map-indexed vector badges)]
        (let [x 40 y (+ 40 (* i 70))]
          (set! (.-fillStyle ctx) color)
          (.beginPath ctx)
          (.arc ctx x y 30 0 (* 2 js/Math.PI))
          (.fill ctx)
          (set! (.-fillStyle ctx) "#111")
          (.fillText ctx (str n) x (+ y 2))))
      (when strength
        (set! (.-fillStyle ctx) "rgba(0,0,0,0.75)")
        (.fillRect ctx 166 290 82 60)
        (set! (.-fillStyle ctx) "#fff")
        (.fillText ctx (str strength) 207 322)))))

;;; Which face of a card to show, mirroring the rules of the 2D board

(defn- my-side [] (:side @game-state))

(defn- card-side-kw [card]
  (some-> (:side card) str/lower-case keyword))

(defn- show-back?
  "Whether the card is drawn face down on the table."
  [card where]
  (or (not (:code card))
      (case where
        :ice (not (:rezzed card))
        :content (not (faceup? card))
        (:pile :run) false
        (board/draw-facedown? card))))

(defn- may-zoom?
  "Same rule as the 2D board: face-up cards, or own facedown cards, or open-information spectating."
  [card back?]
  (or (not back?)
      (board/spectator-view-hidden?)
      (= (my-side) (card-side-kw card))))

(defn- front-url [card back?]
  (if back?
    (board/facedown-card-url (or (:side card) "Corp"))
    (or (board/image-url card) (board/facedown-card-url (or (:side card) "Corp")))))

(defn- highlight [card]
  (let [side (my-side)
        prompt-state (get-in @game-state [side :prompt-state])]
    (cond
      (:selected card) :selected
      (contains? (set (:selectable prompt-state)) (:cid card)) :selectable
      (= (:cid card) (get-in @game-state [:encounters :ice :cid])) :encountered
      (and (:playable card) (not (seq (:prompt-type prompt-state)))) :playable)))

(defn- badges [card]
  (let [counters (cond-> (into [] (filter (comp pos? val)) (:counter card))
                   (pos? (get-counters card :advancement))
                   (conj [:advancement (get-counters card :advancement)]))]
    (vec (for [[k n] (sort-by key counters)]
           [(get counter-colors (keyword k) "#ecf0f1") n]))))

(defn- strength [card]
  (when (and (or (:current-strength card) (:strength card))
             (or (ice? card) (has-subtype? card "Icebreaker"))
             (active? card))
    (or (:current-strength card) (:strength card))))

;;; Layout: game state -> list of things to draw

(defn- card-entry [card where [x y z] rot]
  (let [back? (show-back? card where)]
    {:id (:cid card)
     :card card
     :back? back?
     :url (front-url card back?)
     :pos [x y z]
     :rot rot
     :highlight (highlight card)
     :badges (badges card)
     :strength (strength card)}))

(defn- with-hosted
  "Entry for `card` plus entries for the cards hosted on it, fanned out to the right."
  [card where [x y z] rot]
  (into [(card-entry card where [x y z] rot)]
        (mapcat (fn [i hosted]
                  (with-hosted hosted :hosted
                    [(+ x (* 0.22 (inc i))) (+ y 0.004) (+ z 0.08)] 0))
                (range) (:hosted card))))

(defn- server-order [servers]
  (concat (filter servers [:archives :rd :hq])
          (sort-by #(js/parseInt (subs (name %) 6))
                   (filter #(str/starts-with? (name %) "remote") (keys servers)))))

(defn- server-label [k servers]
  (case k
    :hq (tr [:game_hq "HQ"])
    :rd (tr [:game_rnd "R&D"])
    :archives (let [discard (get-in @game-state [:corp :discard])]
                (tr [:game_archives "Archives"]
                    {:faceup (count (filter #(or (:seen %) (:code %)) discard))
                     :facedown (count (remove #(or (:seen %) (:code %)) discard))}))
    (tr [:game_server "Server"] {:num (subs (name k) 6)})))

(defn- pile-entry [id side cnt top-card [x z] label]
  (cond-> [{:id id :pile true :count cnt :side side
            :url (board/facedown-card-url side)
            :pos [x 0 z] :rot 0 :label label}]
    top-card (conj (card-entry top-card :pile [x (+ 0.002 (* 0.003 (min cnt 40))) z] 0))))

(defn- layout
  "Returns {:cards [...] :labels [...] :run-marker ...} for the current game state."
  [me]
  (let [gs @game-state
        corp-z (if (= me :corp) corp-dist (- corp-dist))
        to-center (if (= me :corp) -1 1)
        ;; runner rows sit on the other side of the table center: programs, hardware, resources
        runner-row-z (fn [row] (* to-center (+ 0.9 (* row 1.0))))
        servers (get-in gs [:corp :servers])
        order (vec (server-order servers))
        n (count order)
        col-x (fn [i] (* col-w (- i (/ (dec n) 2))))
        run (:run gs)
        run-server (some-> run :server first keyword)
        corp-cards
        (apply concat
               (for [[i k] (map-indexed vector order)
                     :let [x (col-x i)
                           {:keys [content ices]} (get servers k)
                           central (case k
                                     :hq (card-entry (get-in gs [:corp :identity]) :pile [x 0 corp-z] 0)
                                     :rd nil
                                     :archives nil
                                     nil)]]
                 (cond->>
                   (concat
                   (when central [central])
                   (when (= k :rd)
                     (pile-entry "corp-deck" "Corp" (get-in gs [:corp :deck-count] 0) nil
                                 [x corp-z] (tr [:game_rnd-count "R&D ({$cnt})"] {:cnt (get-in gs [:corp :deck-count] 0)})))
                   (when (= k :archives)
                     (let [discard (get-in gs [:corp :discard])]
                       (pile-entry "corp-discard" "Corp" (count discard) (last discard) [x corp-z] nil)))
                   (mapcat (fn [j card]
                             (with-hosted card :content
                               [(+ x (* 0.22 (if central (inc j) j))) (* 0.002 (inc j)) corp-z] 0))
                           (range) content)
                   (mapcat (fn [j ice]
                             (with-hosted ice :ice
                               [x 0 (+ corp-z (* to-center (+ (/ card-h 2) 0.18 (/ card-w 2) (* j ice-step))))]
                               (/ js/Math.PI 2)))
                           (range) ices))
                   ;; the attacked server's cards shimmer while the run is on
                   (= k run-server) (map #(cond-> % (:card %) (assoc :under-attack true))))))
        labels (for [[i k] (map-indexed vector order)]
                 {:id (str "label-" (name k))
                  :text (server-label k servers)
                  :pos [(col-x i) 0 (- corp-z (* to-center (+ (/ card-h 2) 0.25)))]})
        rig (get-in gs [:runner :rig])
        row-cards (fn [row cards]
                    (let [cnt (count cards)
                          x0 (* -0.5 rig-step (dec cnt))]
                      (mapcat (fn [j card]
                                (with-hosted card :rig [(+ x0 (* j rig-step)) 0 (runner-row-z row)] 0))
                              (range) cards)))
        runner-cards (concat (row-cards 0 (:program rig))
                             (row-cards 1 (:hardware rig))
                             (row-cards 2 (concat (:resource rig) (:facedown rig))))
        ;; identity, stack and heap form a back row behind the rig, which keeps
        ;; the board narrow (the central pane is often tall and thin)
        ;; the event that started the current run floats in front of the
        ;; attacked server (see run-card-entry)
        run-source (let [c (:source-card run)]
                     (when (and run-server (= "Event" (:type c)) (:code c)) c))
        runner-discard (get-in gs [:runner :discard])
        ;; never show the run's event twice: it may already be in the heap
        heap-top (last (remove #(and run-source (= (:cid %) (:cid run-source))) runner-discard))
        back-z (runner-row-z 3)
        runner-piles (concat
                       [(card-entry (get-in gs [:runner :identity]) :pile [(- rig-step) 0 back-z] 0)]
                       (pile-entry "runner-deck" "Runner" (get-in gs [:runner :deck-count] 0) nil
                                   [0 back-z]
                                   (tr [:game_stack-count "Stack ({$cnt})"] {:cnt (get-in gs [:runner :deck-count] 0)}))
                       (pile-entry "runner-discard" "Runner" (count runner-discard) heap-top
                                   [rig-step back-z]
                                   (tr [:game_heap "Heap ({$cnt})"] {:cnt (count runner-discard)})))
        run-card (when-let [col (and run-source
                                     (some (fn [[i k]] (when (= k run-server) i)) (map-indexed vector order)))]
                   (let [n-ice (count (get-in servers [run-server :ices]))
                         ;; just outside the outermost ice, on the runner's side
                         z (+ corp-z (* to-center (+ (/ card-h 2) 0.18 (/ card-w 2) (* n-ice ice-step) 0.45)))]
                     (assoc (card-entry run-source :run [(col-x col) run-card-height z] 0)
                            :float true
                            ;; towards the server along z
                            :push-dir (- to-center))))
        run-marker (when-let [col (and run-server (some (fn [[i k]] (when (= k run-server) i))
                                                        (map-indexed vector order)))]
                     (let [ices (get-in servers [run-server :ices])
                           pos (or (:position run) 0)
                           z (if (and (pos? pos) (<= pos (count ices)))
                               (+ corp-z (* to-center (+ (/ card-h 2) 0.18 (/ card-w 2) (* (dec pos) ice-step))))
                               corp-z)]
                       {:x (col-x col) :z z :phase (:phase run)}))
        ;; animated lane under the attacked column: from where the runner comes
        ;; in (the floating event, or just outside the outermost ice) to behind
        ;; the server's root
        run-lane (when run-marker
                   (let [n-ice (count (get-in servers [run-server :ices]))
                         outer (+ corp-z (* to-center (+ (/ card-h 2) 0.18 (/ card-w 2) (* n-ice ice-step) 0.45)))
                         from (if run-card (get-in run-card [:pos 2]) outer)
                         to (- corp-z (* to-center (+ (/ card-h 2) 0.1)))]
                     {:x (:x run-marker) :from (+ from (* to-center 0.5)) :to to :phase (:phase run)}))]
    {:cards (vec (concat corp-cards runner-cards runner-piles (when run-card [run-card])))
     :labels (vec labels)
     :run-marker run-marker
     :run-lane run-lane}))

;;; three.js scene management

(defn- plane [w h material]
  (let [^js m (THREE/Mesh. (THREE/PlaneGeometry. w h) material)]
    (set! (.. m -rotation -x) (- (/ js/Math.PI 2)))
    m))

;;; Card front shader: the card texture, plus a holo effect while hovered
;;; (same recipe as the CSS effect on the zoomed card in nr.gameboard.holo:
;;; an overlay glare and a color-dodge rainbow band around the pointer).

(def ^:private card-vertex-shader
  "varying vec2 vUv;
   void main() {
     vUv = uv;
     gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
   }")

(def ^:private card-fragment-shader
  "uniform sampler2D map;
   uniform vec2 uPointer;  // pointer on the card, uv space (0..1, v up)
   uniform float uHolo;    // effect strength 0..1
   varying vec2 vUv;

   vec3 rainbowColor(int i) {
     if (i == 0) return vec3(1.0, 0.46, 0.46);
     if (i == 1) return vec3(1.0, 0.90, 0.38);
     if (i == 2) return vec3(0.58, 1.0, 0.38);
     if (i == 3) return vec3(0.52, 1.0, 0.92);
     if (i == 4) return vec3(0.48, 0.53, 1.0);
     return vec3(0.74, 0.46, 1.0);
   }

   vec3 rainbow(float t) {
     float f = fract(t) * 6.0;
     int i = int(floor(f));
     return mix(rainbowColor(i), rainbowColor(int(mod(float(i + 1), 6.0))), fract(f));
   }

   vec3 overlay(vec3 base, vec3 blend) {
     return mix(2.0 * base * blend, 1.0 - 2.0 * (1.0 - base) * (1.0 - blend), step(0.5, base));
   }

   void main() {
     vec4 tex = texture2D(map, vUv);
     vec3 col = tex.rgb;
     if (uHolo > 0.001) {
       float d = distance(vUv, uPointer);
       // foil: diagonal rainbow bands that shift as the pointer moves
       float t = dot(vUv - 0.5, vec2(-0.34, 0.94)) * 2.5 + (uPointer.x + uPointer.y) * 0.6;
       vec3 foil = rainbow(t);
       vec3 dodge = min(col / max(1.0 - foil * 0.55, 0.001), vec3(1.0));
       float spot = 1.0 - smoothstep(0.0, 0.85, d);
       col = mix(col, dodge, uHolo * 0.4 * spot);
       // glare: brightens around the pointer, slightly darkens far away
       float glare = 1.0 - smoothstep(0.0, 0.6, d);
       col = mix(col, overlay(col, vec3(mix(0.4, 0.95, glare))), uHolo * 0.6);
     }
     gl_FragColor = vec4(col, tex.a);
     #include <colorspace_fragment>
   }")

(defn- card-front-material []
  (THREE/ShaderMaterial.
    #js {:uniforms #js {:map #js {:value nil}
                        :uPointer #js {:value (THREE/Vector2. 0.5 0.5)}
                        :uHolo #js {:value 0}}
         :vertexShader card-vertex-shader
         :fragmentShader card-fragment-shader
         :transparent true}))

;;; Attack lane: a calm animated strip under the attacked column during a run:
;;; soft light streaks gliding towards the server and a few drifting particles. u runs across the lane, v along it (0 = runner side, 1 = server).

(def ^:private lane-fragment-shader
  "uniform float uTime;
   uniform vec3 uColor;    // run phase colour
   uniform float uFlip;    // 1.0 when the lane's v axis must be reversed
   varying vec2 vUv;

   float hash(float n) { return fract(sin(n) * 43758.5453); }

   // soft gaussian falloff: 1 at x = 0, ~0 beyond a few widths
   float soft(float x, float w) { return exp(-(x * x) / (w * w)); }

   void main() {
     float u = vUv.x;
     float v = mix(vUv.y, 1.0 - vUv.y, uFlip);
     float endFade = smoothstep(0.0, 0.15, v) * (1.0 - smoothstep(0.85, 1.0, v));

     // no background: only the streaks and particles below are visible
     vec3 col = vec3(0.0);

     // soft light streaks gliding towards the server at different speeds
     vec3 cyan = vec3(0.35, 0.75, 1.0);
     vec3 magenta = vec3(0.85, 0.35, 1.0);
     for (int k = 0; k < 6; k++) {
       float fk = float(k);
       float row = 0.18 + 0.64 * hash(fk * 3.7 + 1.0);
       float len = 0.18 + 0.22 * hash(fk * 5.3 + 2.0);
       float speed = 0.12 + 0.18 * hash(fk * 7.1 + 3.0);
       float head = fract(hash(fk * 9.7 + 4.0) + uTime * speed) * (1.0 + len);
       float behind = head - v;                       // > 0 behind the head
       // soft rounded head, long fading tail
       float tail = smoothstep(-0.02, 0.0, behind) * (1.0 - smoothstep(0.0, len, behind));
       float body = soft(u - row, 0.03) * tail * (0.35 + 0.65 * clamp(1.0 - behind / len, 0.0, 1.0));
       float halo = soft(u - row, 0.1) * tail * 0.3;
       vec3 c = mix(cyan, magenta, hash(fk * 11.3 + 5.0));
       col += c * (body + halo) * 0.8;
     }

     // sparse soft particles drifting slowly towards the server, gently twinkling
     vec2 cell = vec2(u * 7.0, v * 14.0 - uTime * 0.35);
     vec2 id = floor(cell);
     vec2 f = fract(cell) - 0.5;
     float h = hash(id.x * 17.0 + id.y * 131.0);
     if (h > 0.7) {
       vec2 c = vec2(hash(h * 7.0) - 0.5, hash(h * 11.0) - 0.5) * 0.6;
       float twinkle = 0.6 + 0.4 * sin(uTime * 1.5 + h * 40.0);
       col += cyan * soft(length(f - c), 0.08) * twinkle * 0.35;
     }

     gl_FragColor = vec4(col * endFade, 1.0);
     #include <colorspace_fragment>
   }")

(defn- lane-material []
  (THREE/ShaderMaterial.
    #js {:uniforms #js {:uTime #js {:value 0}
                        :uColor #js {:value (THREE/Color. "#4aa3ff")}
                        :uFlip #js {:value 0}}
         :vertexShader card-vertex-shader
         :fragmentShader lane-fragment-shader
         :transparent true
         :depthWrite false
         :blending THREE/AdditiveBlending}))

(def run-phase-colors
  {"encounter-ice" "#ff4d4d"
   "approach-ice" "#ffcc00"})

(def hover-tilt 0.2)   ; radians at the card's edge
(def hover-lift 0.12)  ; how far a hovered card rises off the table
(def attacked-lift 0.05) ; keeps shimmering (slightly tilted) cards above the lane

(defn- make-card-object [{:keys [pile]}]
  (let [^js group (THREE/Group.)
        ;; the hovered card tilts inside its group, so the group's own
        ;; rotation (e.g. sideways ice) stays untouched
        ^js tilt (THREE/Group.)
        ^js front (plane card-w card-h (card-front-material))
        ^js glow (plane (+ card-w 0.08) (+ card-h 0.08)
                    (THREE/MeshBasicMaterial. #js {:color "#ffffff" :transparent true :opacity 0.85}))
        ^js overlay (plane card-w card-h (THREE/MeshBasicMaterial. #js {:transparent true :depthWrite false}))]
    (set! (.. glow -position -y) -0.001)
    (set! (.. overlay -position -y) 0.001)
    (set! (.-visible glow) false)
    (set! (.-visible overlay) false)
    (.add tilt glow)
    (.add tilt front)
    (.add tilt overlay)
    (.add group tilt)
    (when pile
      (let [^js box (THREE/Mesh. (THREE/BoxGeometry. card-w 1 card-h)
                             (THREE/MeshBasicMaterial. #js {:color "#1b1b1f"}))]
        (.add tilt box)
        (set! (.. group -userData -box) box)))
    ;; shadow on the table, only shown for floating cards (the run's event)
    (let [^js shadow (plane (* 1.25 card-w) (* 1.25 card-h)
                            (THREE/MeshBasicMaterial. #js {:map (shadow-texture) :transparent true
                                                           :depthWrite false}))]
      (set! (.-visible shadow) false)
      (.add group shadow)
      (set! (.. group -userData -shadow) shadow))
    (set! (.. group -userData -tilt) tilt)
    (set! (.. group -userData -front) front)
    (set! (.. front -userData -card-group) group) ; for raycast hits
    (set! (.. group -userData -glow) glow)
    (set! (.. group -userData -overlay) overlay)
    group))

(defn- update-card-object! [^js group {:keys [url highlight badges strength pile count pos rot float push-dir under-attack] :as entry}]
  (let [^js ud (.-userData group)
        ^js front (.-front ud)
        ^js glow (.-glow ud)
        ^js overlay (.-overlay ud)
        ^js shadow (.-shadow ud)
        [x y z] pos]
    (set! (.-entry ud) entry)
    (set! (.-float ud) (boolean float))
    (set! (.-underAttack ud) (boolean under-attack))
    ;; per-card phase, so shimmering cards don't move in sync
    (when (nil? (.-seed ud))
      (set! (.-seed ud) (* 100 (js/Math.random))))
    (set! (.-pushDir ud) (or push-dir 0))
    (set! (.-visible shadow) (boolean float))
    ;; the shadow lies on the table below the floating card
    (set! (.. shadow -position -y) (+ (- y) 0.004))
    (when (not= url (.-url ud))
      (set! (.-url ud) url)
      (set! (.. front -material -uniforms -map -value) (texture url)))
    (if-let [color (get highlight-colors highlight)]
      (do (.set (.. glow -material -color) color)
          (set! (.-visible glow) true))
      (set! (.-visible glow) false))
    (if (or (seq badges) strength)
      (do (set! (.. overlay -material -map) (badge-texture badges strength))
          (set! (.. overlay -material -needsUpdate) true)
          (set! (.-visible overlay) true))
      (set! (.-visible overlay) false))
    (if pile
      (let [h (max 0.001 (* 0.003 (min count 40)))
            ^js box (.-box ud)]
        (set! (.-visible group) (pos? count))
        (.set (.-scale box) 1 h 1)
        (set! (.. box -position -y) (/ h 2))
        (set! (.. front -position -y) (+ h 0.0005))
        (set! (.. glow -position -y) (+ h 0.0001))
        (set! (.. overlay -position -y) (+ h 0.001)))
      (set! (.-visible group) true))
    (set! (.-target ud) #js [x y z rot])))

(defn- spawn-position
  "Where cards appear from when they enter the board (from the owner's hand)."
  [{:keys [card]} me]
  (let [owner (card-side-kw card)]
    (if (= owner me) #js [0 0.6 5.5] #js [0 0.6 -5.5])))

(defn- sync-cards! [{:keys [^js scene ^js objects]} entries me]
  (let [seen (js/Set.)]
    (doseq [{:keys [id] :as entry} entries
            :when id]
      (.add seen id)
      (let [^js obj (or (.get objects id)
                    (let [^js o (make-card-object entry)
                          [sx sy sz] (if (:pile entry) (:pos entry) (spawn-position entry me))]
                      (.set (.-position o) sx sy sz)
                      (set! (.. o -rotation -y) (:rot entry))
                      (.add scene o)
                      (.set objects id o)
                      o))]
        (update-card-object! obj entry)))
    (doseq [[id ^js obj] (es6-iterator-seq (.entries objects))]
      (when-not (.has seen id)
        (.remove scene obj)
        (.delete objects id)))))

(defn- sync-labels! [{:keys [^js scene ^js labels]} label-entries pile-entries]
  (let [all (concat label-entries
                    (for [{:keys [id label pos]} pile-entries :when label]
                      {:id (str id "-label") :text label
                       :pos (update pos 2 #(+ % (* (js/Math.sign %) (+ (/ card-h 2) 0.18))))}))
        seen (js/Set.)]
    (doseq [{:keys [id text pos]} all]
      (.add seen id)
      (let [^js m (or (.get labels id)
                  (let [^js m (plane 1.1 0.21 (THREE/MeshBasicMaterial. #js {:transparent true :depthWrite false}))]
                    (.add scene m)
                    (.set labels id m)
                    m))
            [x y z] pos]
        (set! (.. m -material -map) (label-texture text))
        (.set (.-position m) x (+ y 0.002) z)))
    (doseq [[id ^js m] (es6-iterator-seq (.entries labels))]
      (when-not (.has seen id)
        (.remove scene m)
        (.delete labels id)))))

(defn- sync-run-marker! [{:keys [^js run-marker]} marker]
  (if marker
    (let [{:keys [x z phase]} marker]
      (set! (.-visible run-marker) true)
      (.set (.. run-marker -material -color)
            (case phase "encounter-ice" "#ff4d4d" "approach-ice" "#ffcc00" "#4aa3ff"))
      (set! (.. run-marker -userData -target) #js [x 0.35 z]))
    (set! (.-visible run-marker) false)))

(defn- sync-run-lane! [{:keys [^js run-lane]} lane]
  (if lane
    (let [{:keys [x from to phase]} lane
          ^js uniforms (.. run-lane -material -uniforms)]
      (set! (.-visible run-lane) true)
      (.set (.-position run-lane) x -0.003 (/ (+ from to) 2))
      ;; the plane lies flat: its local y is the lane's length
      (.set (.-scale run-lane) (* 0.85 col-w) (js/Math.abs (- from to)) 1)
      ;; the plane's v runs from +z to -z; reverse it when the runner is on the -z side
      (set! (.. uniforms -uFlip -value) (if (> from to) 0 1))
      (.set (.. uniforms -uColor -value) (get run-phase-colors phase "#4aa3ff")))
    (set! (.-visible run-lane) false)))

(declare fit-camera! card-bounds)

(defn- sync-scene! [{:keys [view] :as ctx} me]
  (let [{:keys [cards labels run-marker run-lane]} (layout me)
        bounds (card-bounds cards)]
    (sync-cards! ctx cards me)
    (sync-labels! ctx labels (filter :pile cards))
    (sync-run-marker! ctx run-marker)
    (sync-run-lane! ctx run-lane)
    (when (not= bounds (:bounds @view))
      (swap! view assoc :bounds bounds)
      (fit-camera! ctx))))

(defn- approach
  "Moves `v` a step towards `target`. Returns the new value."
  [v target]
  (if (< (js/Math.abs (- target v)) 0.0005)
    target
    (+ v (* lerp-factor (- target v)))))

(defn- lerp-objects!
  "Glides every card towards its target position, and the hovered card towards
  its tilt and holo state. Returns true while anything is still moving."
  [{:keys [objects ^js run-marker ^js run-lane hover]}]
  (let [moving (volatile! false)
        now (/ (js/performance.now) 1000) ; seconds, for the run card's animation
        {hover-id :id [hu hv] :uv} @hover]
    (doseq [[id ^js obj] (es6-iterator-seq (.entries objects))]
      (when-let [t (.. obj -userData -target)]
        (let [^js p (.-position obj)
              ^js r (.-rotation obj)
              x (approach (.-x p) (aget t 0))
              y (approach (.-y p) (aget t 1))
              z (approach (.-z p) (aget t 2))
              ry (approach (.-y r) (aget t 3))]
          (when (or (not= x (.-x p)) (not= y (.-y p)) (not= z (.-z p)) (not= ry (.-y r)))
            (vreset! moving true)
            (.set p x y z)
            (set! (.-y r) ry))))
      ;; hover: the side under the pointer comes up towards the viewer, and the
      ;; holo shine follows the pointer (uv: u right, v up; the card's top is -z)
      (let [^js ud (.-userData obj)
            ^js tilt (.-tilt ud)
            ^js uniforms (.. ud -front -material -uniforms)
            hovered (and hover-id (= id hover-id) (not (.-box ud)))
            [tx tz ty th] (if hovered
                            [(* (- hv 0.5) 2 hover-tilt) (* (- hu 0.5) 2 hover-tilt) hover-lift 1]
                            [0 0 0 0])
            ^js tr (.-rotation tilt)]
        (if (.-float ud)
          ;; the run's event: leans towards the server, bobs, and every couple
          ;; of seconds thrusts forward as if pushing into the server
          (let [dir (.-pushDir ud)
                pulse (js/Math.pow (max 0 (js/Math.sin (* now 3.2))) 6)
                push (* dir run-card-push pulse)
                ^js shadow (.-shadow ud)
                ;; a wandering "virtual pointer" (overlapping sines, so it looks
                ;; random but moves smoothly) tilts the card and sweeps the holo
                ;; shine across it; a real hover takes over
                au (+ 0.5 (* 0.25 (js/Math.sin (* now 0.83))) (* 0.15 (js/Math.sin (+ (* now 1.97) 1.3))))
                av (+ 0.5 (* 0.25 (js/Math.sin (+ (* now 0.67) 2.1))) (* 0.15 (js/Math.sin (+ (* now 1.61) 0.4))))
                [tx tz th] (if hovered
                             [tx tz th]
                             [(* (- av 0.5) 2 hover-tilt 0.6) (* (- au 0.5) 2 hover-tilt 0.6) 0.8])]
            (vreset! moving true)
            (when-not hovered
              (.set (.. uniforms -uPointer -value) au av))
            (set! (.-x tr) (+ tx (* dir (+ run-card-lean (* 0.12 pulse)))))
            (set! (.-z tr) (+ tz (* 0.04 (js/Math.sin (* now 1.1)))))
            (.set (.-position tilt) 0 (+ ty (* 0.025 (js/Math.sin (* now 1.7)))) push)
            ;; offset as if lit from slightly above and behind the viewer, so it peeks out
            (set! (.. shadow -position -x) 0.06)
            (set! (.. shadow -position -z) (+ push 0.12))
            (.set (.-scale shadow) (- 1 (* 0.08 pulse)) 1 (- 1 (* 0.08 pulse)))
            (set! (.. uniforms -uHolo -value) (approach (.. uniforms -uHolo -value) th)))
          (let [;; the attacked server's cards stay on the table but shimmer: a
                ;; wandering virtual pointer, each card on its own rhythm
                attacked (and (.-underAttack ud) (not hovered))
                [tx tz th ty] (if attacked
                             (let [s (+ now (.-seed ud))
                                   au (+ 0.5 (* 0.3 (js/Math.sin (* s 0.91))) (* 0.12 (js/Math.sin (+ (* s 2.3) 0.7))))
                                   av (+ 0.5 (* 0.3 (js/Math.sin (+ (* s 0.73) 1.9))) (* 0.12 (js/Math.sin (+ (* s 1.7) 2.6))))]
                               (vreset! moving true)
                               (.set (.. uniforms -uPointer -value) au av)
                               ;; lifted a little so the tilted card never dips below the lane
                               [(* (- av 0.5) 2 hover-tilt 0.25) (* (- au 0.5) 2 hover-tilt 0.25) 0.6 attacked-lift])
                             [tx tz th ty])
                rx (approach (.-x tr) tx)
                rz (approach (.-z tr) tz)
                ly (approach (.. tilt -position -y) ty)
                lz (approach (.. tilt -position -z) 0)
                holo (approach (.. uniforms -uHolo -value) th)]
            (when (or (not= rx (.-x tr)) (not= rz (.-z tr)) (not= ly (.. tilt -position -y))
                      (not= lz (.. tilt -position -z)) (not= holo (.. uniforms -uHolo -value)))
              (vreset! moving true)
              (set! (.-x tr) rx)
              (set! (.-z tr) rz)
              (set! (.. tilt -position -y) ly)
              (set! (.. tilt -position -z) lz)
              (set! (.. uniforms -uHolo -value) holo))))
        (when hovered
          (let [^js ptr (.. uniforms -uPointer -value)
                px (approach (.-x ptr) hu)
                py (approach (.-y ptr) hv)]
            (when (or (not= px (.-x ptr)) (not= py (.-y ptr)))
              (vreset! moving true)
              (.set ptr px py))))))
    (when (.-visible run-marker)
      (when-let [t (.. run-marker -userData -target)]
        (let [^js p (.-position run-marker)]
          (.set p (approach (.-x p) (aget t 0)) (aget t 1) (approach (.-z p) (aget t 2))))
        ;; the marker spins while a run is active
        (set! (.. run-marker -rotation -y) (+ (.. run-marker -rotation -y) 0.03))
        (vreset! moving true)))
    (when (.-visible run-lane)
      (set! (.. run-lane -material -uniforms -uTime -value) now)
      (vreset! moving true))
    @moving))

(defn- card-at
  "The card entry under the pointer (with :uv, where on the card it was hit), or nil."
  [{:keys [^js renderer ^js camera objects ^js raycaster]} ^js e]
  (let [rect (.getBoundingClientRect (.-domElement renderer))
        ndc (THREE/Vector2. (- (* 2 (/ (- (.-clientX e) (.-left rect)) (.-width rect))) 1)
                            (+ (* -2 (/ (- (.-clientY e) (.-top rect)) (.-height rect))) 1))
        fronts (into-array (for [^js obj (es6-iterator-seq (.values objects))
                                 :when (.-visible obj)]
                             (.. obj -userData -front)))]
    (.setFromCamera raycaster ndc camera)
    (when-let [^js hit (first (.intersectObjects raycaster fronts false))]
      (let [entry (.. hit -object -userData -card-group -userData -entry)
            ^js uv (.-uv hit)]
        (when (:card entry)
          (assoc entry :uv (when uv [(.-x uv) (.-y uv)])))))))

(defn- create-context
  "Creates renderer, scene, camera and controls in `container`. Returns nil if WebGL is unavailable."
  [container me]
  (try
    ;; preserveDrawingBuffer: we only draw when something changed, so the canvas
    ;; must keep its last frame. Without it, browsers may show a cleared canvas
    ;; whenever the page recomposites (e.g. every second when the match timer ticks).
    (let [renderer (THREE/WebGLRenderer. #js {:antialias true :alpha true :preserveDrawingBuffer true})
          scene (THREE/Scene.)
          camera (THREE/PerspectiveCamera. 45 1 0.1 100)
          controls (OrbitControls. camera (.-domElement renderer))
          table (plane 13 9.5 (THREE/MeshBasicMaterial. #js {:color "#0b0f14" :transparent true :opacity 0.55}))
          grid (THREE/GridHelper. 13 26 "#24303c" "#18202a")
          run-marker (THREE/Mesh. (THREE/ConeGeometry. 0.22 0.45 4)
                                  (THREE/MeshBasicMaterial. #js {:color "#ff4d4d"}))
          run-lane (plane 1 1 (lane-material))]
      (.setPixelRatio renderer (min 2 (.-devicePixelRatio js/window)))
      (.appendChild container (.-domElement renderer))
      (set! (.. table -position -y) -0.01)
      (set! (.. grid -position -y) -0.005)
      (.add scene table)
      (.add scene grid)
      (set! (.. run-marker -rotation -x) js/Math.PI)
      (set! (.-visible run-marker) false)
      (.add scene run-marker)
      (set! (.-visible run-lane) false)
      (.add scene run-lane)
      (set! (.-enableDamping controls) true)
      (set! (.-maxPolarAngle controls) 1.35)
      (set! (.-minDistance controls) 2)
      (let [view (atom {:user-moved false :aspect 1 :bounds nil})]
        ;; stop auto-framing as soon as the player rotates, pans or zooms
        (.addEventListener controls "start" #(swap! view assoc :user-moved true))
        {:renderer renderer :scene scene :camera camera :controls controls
         :objects (js/Map.) :labels (js/Map.) :run-marker run-marker :run-lane run-lane
         :raycaster (THREE/Raycaster.) :view view
         ;; {:id card-id :uv [u v]} of the card under the pointer
         :hover (atom nil)}))
    (catch :default e
      (js/console.warn "3D board: could not create WebGL renderer" e)
      nil)))

(def camera-elevation (* 58 (/ js/Math.PI 180)))

(defn- fit-camera!
  "Places the camera (seen from the player's side, +z) so all cards on the table fit the pane.
  Does nothing once the player has moved the camera themselves."
  [{:keys [^js camera ^js controls view]}]
  (let [{:keys [user-moved aspect bounds]} @view]
    (when (and bounds (not user-moved))
      (let [[min-x max-x min-z max-z] bounds
            cx (/ (+ min-x max-x) 2)
            ;; nudged towards the player: perspective makes the near side look bigger
            cz (+ 0.35 (/ (+ min-z max-z) 2))
            hw (/ (- max-x min-x) 2)
            hd (/ (- max-z min-z) 2)
            vfov (/ (* (.-fov camera) (/ js/Math.PI 180)) 2)
            hfov (js/Math.atan (* (js/Math.tan vfov) aspect))
            d (* 1.15 (max (/ hw (js/Math.tan hfov))
                           (/ (* hd (js/Math.sin camera-elevation)) (js/Math.tan vfov))))]
        (.set (.-target controls) cx 0 cz)
        (.set (.-position camera) cx (* d (js/Math.sin camera-elevation)) (+ cz (* d (js/Math.cos camera-elevation))))
        (set! (.-maxDistance controls) (* 2 d))
        (.update controls)))))

(defn- card-bounds
  "[min-x max-x min-z max-z] of all card positions, padded for card size and labels."
  [cards]
  (let [xs (map #(get-in % [:pos 0]) cards)
        zs (map #(get-in % [:pos 2]) cards)
        pad-x (+ (/ card-h 2) 0.1)   ; ice lies sideways, so half a card height
        pad-z (+ (/ card-h 2) 0.4)]  ; room for the labels in front of/behind piles
    (when (seq xs)
      [(- (apply min xs) pad-x) (+ (apply max xs) pad-x)
       (- (apply min zs) pad-z) (+ (apply max zs) pad-z)])))

(defn- resize! [{:keys [^js renderer ^js camera ^js scene view] :as ctx} ^js container]
  (let [w (max 1 (.-clientWidth container))
        h (max 1 (.-clientHeight container))
        ^js size (.getSize renderer (THREE/Vector2.))]
    (when (or (not= w (.-x size)) (not= h (.-y size)))
      ;; only the drawing buffer: the canvas' CSS size is 100% of its holder.
      ;; Setting a pixel width here (clientWidth is rounded) can overflow the
      ;; scrollable central pane by a fraction of a pixel, which toggles its
      ;; scrollbars and resizes the canvas again in a loop.
      (.setSize renderer w h false)
      (set! (.-aspect camera) (/ w h))
      (.updateProjectionMatrix camera)
      (swap! view assoc :aspect (/ w h))
      (fit-camera! ctx)
      ;; setSize clears the canvas: draw right away (ResizeObserver callbacks run
      ;; before the browser paints) instead of waiting for the next animation frame
      (.render renderer scene camera))))

(defn- dispose! [{:keys [^js renderer ^js controls]}]
  (.dispose controls)
  (.dispose renderer)
  (some-> renderer .-domElement .remove))

;;; Ability menu overlay (reuses the 2D board's menu components)

(defn- find-card [cid]
  (let [gs @game-state
        cards (concat (mapcat (fn [[_ {:keys [content ices]}]] (concat content ices))
                              (get-in gs [:corp :servers]))
                      (mapcat val (get-in gs [:runner :rig]))
                      [(get-in gs [:corp :identity]) (get-in gs [:runner :identity])])
        all (tree-seq map? :hosted {:hosted cards})]
    (some #(when (= cid (:cid %)) %) all)))

(defn- menu-overlay [menu-pos]
  (when-let [cid (:source @board/card-menu)]
    (when-let [{:keys [side abilities subroutines runner-abilities corp-abilities] :as card} (find-card cid)]
      (let [[x y w] @menu-pos
            gs-side (my-side)]
        ;; the 2D menus are 200px wide and open to the right of their anchor;
        ;; keep them inside the board so they aren't hidden under the side panes
        [:div.board3d-menu {:style {:left (max 0 (min (- x 100) (- w 204))) :top y}}
         (cond
           (and (= :runner gs-side) (pos? (+ (count runner-abilities) (count subroutines))))
           [board/runner-abs card runner-abilities subroutines (:title card)]

           (and (= :corp gs-side) (pos? (count corp-abilities)))
           [board/corp-abs card corp-abilities]

           (= (card-side-kw {:side side}) gs-side)
           [board/card-abilities card abilities subroutines])]))))

;;; Public component

(defn board-3d
  "The 3D board for the player sitting at `me` (:corp or :runner)."
  [me]
  (let [ctx (atom nil)
        container (atom nil)
        canvas-holder (atom nil)
        frame (atom nil)
        dirty (atom true)
        failed (r/atom false)
        menu-pos (r/atom [0 0 0]) ; click x, y and board width
        down-pos (atom nil)
        hovered (atom nil)
        resize-observer (atom nil)
        watch-key (keyword (gensym "board3d"))
        on-pointer-down (fn [^js e] (reset! down-pos [(.-clientX e) (.-clientY e)]))
        on-pointer-up
        (fn [^js e]
          (when-let [[x0 y0] @down-pos]
            (reset! down-pos nil)
            ;; ignore drags (camera rotation)
            (when (< (+ (js/Math.abs (- (.-clientX e) x0)) (js/Math.abs (- (.-clientY e) y0))) 6)
              (if-let [{:keys [card]} (card-at @ctx e)]
                (let [rect (.getBoundingClientRect @container)]
                  (reset! menu-pos [(- (.-clientX e) (.-left rect)) (- (.-clientY e) (.-top rect)) (.-width rect)])
                  (board/handle-card-click card (.-shiftKey e)))
                (board/close-card-menu)))))
        on-pointer-move
        (fn [^js e]
          (let [{:keys [card back? uv] :as entry} (card-at @ctx e)
                id (:id entry)
                zoomable (and card (may-zoom? card back?))]
            (when (not= id @hovered)
              (reset! hovered id)
              (if zoomable
                (put-game-card-in-channel card zoom-channel)
                (put! zoom-channel false)))
            ;; tilt and shine of the hovered card on the board
            (reset! (:hover @ctx) (when (and card uv) {:id id :uv uv}))
            ;; drives the holo effect on the zoomed card (UV v points up, screen y down)
            (if (and zoomable uv)
              (set-zoom-pointer! (first uv) (- 1 (second uv)))
              (clear-zoom-pointer!))))
        on-pointer-leave (fn [_]
                           (reset! hovered nil)
                           (some-> @ctx :hover (reset! nil))
                           (put! zoom-channel false)
                           (clear-zoom-pointer!))
        animate (fn animate []
                  (when-let [c @ctx]
                    (let [synced (when @dirty
                                   (reset! dirty false)
                                   (sync-scene! c me)
                                   true)
                          moving (lerp-objects! c)
                          camera-moved (.update ^js (:controls c))]
                      ;; only draw when something changed, so an idle board costs nothing
                      (when (or synced moving camera-moved @redraw-requested)
                        (reset! redraw-requested false)
                        (.render ^js (:renderer c) (:scene c) (:camera c))))
                    (reset! frame (js/requestAnimationFrame animate))))]
    (r/create-class
      {:display-name "board-3d"
       :component-did-mount
       (fn [_]
         (if-let [c (create-context @canvas-holder me)]
           (let [canvas (.-domElement (:renderer c))
                 el @container]
             (reset! ctx c)
             (resize! c el)
             (reset! resize-observer (js/ResizeObserver. #(resize! c el)))
             (.observe @resize-observer el)
             (.addEventListener canvas "pointerdown" on-pointer-down)
             (.addEventListener canvas "pointerup" on-pointer-up)
             (.addEventListener canvas "pointermove" on-pointer-move)
             (.addEventListener canvas "pointerleave" on-pointer-leave)
             (add-watch game-state watch-key (fn [_ _ _ _] (reset! dirty true)))
             (animate))
           (reset! failed true)))
       :component-will-unmount
       (fn [_]
         (remove-watch game-state watch-key)
         (some-> @frame js/cancelAnimationFrame)
         (some-> @resize-observer .disconnect)
         (when-let [c @ctx]
           (dispose! c)
           (reset! ctx nil)))
       :reagent-render
       (fn [_me]
         ;; .menu-container keeps the global click handler from closing card menus
         [:div.board3d.menu-container {:ref #(reset! container %)}
          [:div.board3d-canvas {:ref #(reset! canvas-holder %)}]
          (when @failed
            [:div.board3d-message [tr-span [:game_board-3d-unavailable "The 3D board could not be started (WebGL is not available). Disable it in the settings to use the regular board."]]])
          [menu-overlay menu-pos]])})))
