(ns dev.nr
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require
    [cljs.core.async :refer [<!]]
    [clojure.string :as str]
    [nr.ajax :refer [POST]]
    [nr.appstate :refer [app-state]]
    [nr.main :as main]
    [devtools.core :as devtools]))

(defn ^:dev/after-load on-reload []
  (main/mount))

(enable-console-print!)

(devtools/install!)

;;; Dev builds only: helpers for web.dev-game / bin/dev-game.
;;;
;;;   /play?dev-login=devrunner        log this browser in as a test user and open
;;;                                    the running dev game (start dialog skipped)
;;;   &dev-opts=board-3d,board-3d-full-width,board-3d-playmat=/img/playmats/x.jpg
;;;                                    client options to switch on for this tab
;;;
;;; The password matches web.dev-game/dev-password.

(def dev-password "devtest")

(defn- session-get [k] (try (.getItem js/sessionStorage k) (catch :default _ nil)))
(defn- session-set! [k v] (try (.setItem js/sessionStorage k v) (catch :default _ nil)))

(defn- dev-login!
  "Logs in as the user named in the dev-login URL parameter, then reloads /play.
  Returns true when a login is in progress."
  []
  (let [params (js/URLSearchParams. (.. js/window -location -search))]
    (when-let [user (.get params "dev-login")]
      (session-set! "dev-skip-start" "1")
      (when-let [opts (.get params "dev-opts")]
        (session-set! "dev-opts" opts))
      (go (<! (POST "/login" (str "username=" (js/encodeURIComponent user)
                                  "&password=" (js/encodeURIComponent dev-password))))
          (.replace (.-location js/window) "/play"))
      true)))

(defn- parse-dev-opts
  "\"board-3d,board-3d-playmat=/img/x.jpg\" -> {:board-3d true :board-3d-playmat \"/img/x.jpg\"}"
  [s]
  (into {} (for [part (str/split s #",") :when (seq part)
                 :let [[k v] (str/split part #"=" 2)]]
             [(keyword k) (if (some? v) v true)])))

(defn- apply-dev-session!
  "Applies what a dev login asked for (for this browser tab only)."
  []
  (when (session-get "dev-skip-start")
    (swap! app-state assoc :start-shown true))
  (when-let [opts (session-get "dev-opts")]
    (swap! app-state update :options merge (parse-dev-opts opts))))

(when-not (dev-login!)
  (main/init!)
  (apply-dev-session!))
