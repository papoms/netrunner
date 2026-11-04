(ns web.dev-game
  "Dev-only: set up and drive a local test game without clicking through the
  lobby, logins and mulligans.

  Two fixed test users play each other: devcorp (Corp) and devrunner (Runner),
  password `dev-password`. They are created on first use.

  From the shell (fast, see bin/dev-game):
    bin/dev-game new                 ; fresh System Gateway game, both hands kept
    bin/dev-game scenario run        ; ... and play up to a live run with an event
    bin/dev-game state               ; short summary of the game
    bin/dev-game act corp credit     ; any game command, as either side
    bin/dev-game play runner Jailbreak
    bin/dev-game choose runner HQ    ; answer the current prompt
    bin/dev-game end                 ; close the dev game

  Watch or play in a browser: open
    http://localhost:1042/play?dev-login=devrunner   (or devcorp)
  which logs that browser in as the user (dev builds only, see dev.nr) and lands
  in the running game. One browser can be one side while the shell plays the other."
  (:require
   [clojure.string :as str]
   [game.core.moving :refer [move]]
   [game.main :as main]
   [integrant.repl.state :as ig-state]
   [monger.collection :as mc]
   [web.app-state :as app-state]
   [web.game :as game]
   [web.lobby :as lobby]
   [web.user :refer [create-user user-keys]]))

(def dev-password "devtest")
(def usernames {:corp "devcorp" :runner "devrunner"})

;;; Users

(defn- db [] (get-in ig-state/system [:mongodb/connection :db]))

(defn- lobby-user
  "The user map as the web layer uses it (see web.auth/wrap-user)."
  [doc]
  (-> doc (select-keys user-keys) (update :_id str)))

(defn ensure-user!
  "Creates the test user, or resets its password to `dev-password`."
  [username]
  (let [db (db)
        new-user (create-user username dev-password (str username "@example.test"))]
    (if (mc/find-one-as-map db "users" {:username username})
      (mc/update db "users" {:username username} {"$set" {:password (:password new-user)}})
      (mc/insert db "users" new-user))
    (lobby-user (mc/find-one-as-map db "users" {:username username}))))

;;; Game lookup

(defn- dev-lobby? [lobby]
  (some (set (vals usernames)) (map :uid (:players lobby))))

(defn current-lobby []
  (some (fn [[_ l]] (when (dev-lobby? l) l)) (:lobbies @app-state/app-state)))

(defn- state []
  (some-> (current-lobby) :state))

(defn end!
  "Closes all dev games."
  []
  (doseq [[_ l] (:lobbies @app-state/app-state) :when (dev-lobby? l)]
    (lobby/close-lobby! (db) l))
  (lobby/broadcast-lobby-list)
  :closed)

;;; Summary

(defn- log-text [{:keys [text]}]
  (cond
    (string? text) text
    (:raw-text text) (:raw-text text)
    (:msg/type text) (str (name (:msg/type text))
                          (when-let [t (:msg/title text)] (str " " (if (map? t) (:title t) t))))
    :else (pr-str text)))

(defn- choice-text [{:keys [value]}]
  (cond
    (string? value) value
    (:title value) (:title value)
    (:msg/type value) (name (:msg/type value))
    :else (pr-str value)))

(defn summary
  "One short text block: turn, players, run, prompts and the last log lines."
  []
  (if-let [st (state)]
    (let [s @st
          side-line (fn [side]
                      (let [{:keys [credit click hand prompt-state agenda-point]} (get s side)]
                        (str (name side) ": " credit "c " click "clk " agenda-point "ap"
                             " hand[" (str/join ", " (map :title hand)) "]"
                             (when-let [msg (:msg prompt-state)]
                               (str "\n  prompt: " (if (string? msg) msg (pr-str msg))
                                    (when-let [cs (seq (:choices prompt-state))]
                                      (when (sequential? cs)
                                        (str " -> " (str/join " | " (map choice-text cs))))))))))
          log (->> (:log s) (keep #(or (:public %) (:corp %) (:runner %))) (take-last 5) (map log-text))]
      (str "game " (:gameid (current-lobby)) " turn " (:turn s) " active " (some-> (:active-player s) name)
           (when-let [run (:run s)]
             (str "\nrun on " (first (:server run)) " phase " (some-> (:phase run) name) " pos " (:position run)
                  (when-let [src (get-in run [:source-card :title])] (str " via " src))))
           "\n" (side-line :corp) "\n" (side-line :runner)
           "\nlog: " (str/join " / " log)))
    "no dev game (bin/dev-game new)"))

;;; Actions

(defn act!
  "Runs a game command as `side` (:corp or :runner), exactly like a click in the
  client (same path as the :game/action websocket handler), then returns the summary."
  ([side command] (act! side command nil))
  ([side command args]
   (let [lobby (or (current-lobby) (throw (ex-info "no dev game" {})))]
     (game/update-and-send-diffs! main/handle-action lobby (keyword side) command args)
     (summary))))

(defn- find-card [side zone title]
  (let [t (str/lower-case title)]
    (some #(when (str/includes? (str/lower-case (:title % "")) t) %)
          (get-in @(state) [(keyword side) zone]))))

(defn- card-ref [card]
  (select-keys card [:cid :zone :side :host :type]))

(defn play!
  "Plays or installs a card from hand by (part of its) title. Corp installs take a server."
  ([side title] (play! side title nil))
  ([side title server]
   (let [card (or (find-card side :hand title)
                  (throw (ex-info (str "not in hand: " title) {})))]
     (act! side "play" (cond-> {:card (card-ref card)} server (assoc :server server))))))

(defn choose!
  "Answers the current prompt of `side` with the choice whose text contains `text`."
  [side text]
  (let [ps (get-in @(state) [(keyword side) :prompt-state])
        t (str/lower-case text)
        choice (or (some #(when (str/includes? (str/lower-case (choice-text %)) t) %) (:choices ps))
                   (throw (ex-info (str "no choice matching " text)
                                   {:choices (map choice-text (:choices ps))})))]
    (act! side "choice" {:eid (:eid ps) :choice {:uuid (:uuid choice)}})))

(defn tutor!
  "Dev cheat: moves a card (by title) from the stack/R&D into the hand."
  [side title]
  (let [card (or (find-card side :deck title)
                 (throw (ex-info (str "not in deck: " title) {})))]
    ;; inside update-and-send-diffs!, so connected clients get the change
    (game/update-and-send-diffs! (fn [st] (move st (keyword side) card :hand)) (current-lobby))
    (summary)))

;;; Setup

(defn new-game!
  "Closes earlier dev games, then creates, starts and keeps both hands in a fresh
  game: devcorp (Corp) vs devrunner (Runner)."
  [& {:keys [format gateway-type] :or {format "system-gateway" gateway-type "beginner"}}]
  (end!)
  (let [db (db)
        corp (ensure-user! (:corp usernames))
        runner (ensure-user! (:runner usernames))
        new-lobby (lobby/create-new-lobby {:uid (:corp usernames)
                                           :user corp
                                           :options {:format format :gateway-type gateway-type
                                                     :side "Corp" :room "casual" :title "dev game"
                                                     :allow-spectator true}})
        gameid (:gameid new-lobby)]
    (swap! app-state/app-state update :lobbies lobby/register-lobby new-lobby (:corp usernames))
    (lobby/join-lobby! db runner (:runner usernames) {:gameid gameid :request-side "Runner"} nil
                       (app-state/get-lobby gameid))
    (game/try-start-game db (:corp usernames) gameid)
    ;; answer the "Keep hand?" prompts like the Keep buttons do (the "keep"
    ;; command alone records the decision but leaves the prompt open)
    (choose! :corp "Keep")
    (choose! :runner "Keep")))

(defn- spend-clicks!
  "Repeats `command` while `side` has clicks left. Stops when a click isn't
  spent (e.g. a prompt blocks it) and after at most 6 tries, so it can never
  spin forever inside the server."
  [side command]
  (loop [tries 6]
    (let [clicks (get-in @(state) [side :click])]
      (when (and (pos? clicks) (pos? tries))
        (act! side command)
        (when (< (get-in @(state) [side :click]) clicks)
          (recur (dec tries)))))))

(defn- hand-card-of-type [side types]
  (some #(when (types (:type %)) %) (get-in @(state) [side :hand])))

(defn scenario-run!
  "A fresh game played up to a live run on HQ started by an event (Jailbreak):
  the Corp installs ice on HQ and a card in a remote, then the Runner runs."
  []
  (new-game!)
  (act! :corp "start-turn")
  (when-let [ice (hand-card-of-type :corp #{"ICE"})]
    (play! :corp (:title ice) "HQ"))
  (when-let [card (hand-card-of-type :corp #{"Asset" "Agenda" "Upgrade"})]
    (play! :corp (:title card) "New remote"))
  (spend-clicks! :corp "credit")
  (act! :corp "end-turn")
  (act! :runner "start-turn")
  (when-not (find-card :runner :hand "Jailbreak")
    (tutor! :runner "Jailbreak"))
  (play! :runner "Jailbreak")
  (choose! :runner "HQ"))

;;; Command line (bin/dev-game)

(defn cli
  "Entry point for bin/dev-game. `args` are strings."
  [args]
  (let [[cmd & more] args
        side (fn [s] (keyword (str/lower-case s)))]
    (case cmd
      "new" (new-game! :gateway-type (or (first more) "beginner"))
      "scenario" (case (first more)
                   "run" (scenario-run!)
                   (str "unknown scenario " (first more) " (available: run)"))
      "state" (summary)
      "act" (let [[s command args-edn] more]
              (act! (side s) command (some-> args-edn read-string)))
      "play" (let [[s title server] more] (play! (side s) title server))
      "choose" (let [[s text] more] (choose! (side s) text))
      "tutor" (let [[s title] more] (tutor! (side s) title))
      "end" (end!)
      (str "usage: bin/dev-game new [beginner|intermediate] | scenario run | state"
           " | act <corp|runner> <command> [args-edn] | play <side> <title> [server]"
           " | choose <side> <text> | tutor <side> <title> | end"))))
