(ns browser-nrepl
  (:require [sci.nrepl.browser-server :as server]))

(def default-nrepl-port 1339)
(def default-websocket-port 1340)

(def cli-spec
  {:coerce {:nrepl-port :long
            :websocket-port :long}
   :alias {:n :nrepl-port
           :w :websocket-port}})

(defn- accepting?
  "True when something on this machine accepts TCP connections on port."
  [port]
  (try
    (with-open [socket (java.net.Socket.)]
      (.connect socket (java.net.InetSocketAddress. "127.0.0.1" (int port)) 300)
      true)
    (catch Exception _
      false)))

(defn start!
  "Relay nREPL to the page WebSocket until the process is stopped."
  [opts]
  (let [nrepl-port (or (:nrepl-port opts) default-nrepl-port)
        websocket-port (or (:websocket-port opts) default-websocket-port)
        refuse! (fn [message]
                  (throw (ex-info message {:babashka/exit 1})))]
    (when (accepting? nrepl-port)
      (refuse! (str "nREPL port " nrepl-port " is already in use.")))
    (when (accepting? websocket-port)
      (refuse! (str "WebSocket port " websocket-port " is already in use.")))
    (try
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn []
                                   (server/halt!)
                                   (server/stop-nrepl-server!))))
      (server/start! {:nrepl-port nrepl-port
                      :websocket-port websocket-port})
      (when-not (accepting? websocket-port)
        (server/halt!)
        (server/stop-nrepl-server!)
        (refuse! (str "WebSocket port " websocket-port " is not accepting connections.")))
      (println (str "Editor nREPL port " nrepl-port ". Page WebSocket port " websocket-port "."))
      @(promise)
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Exception e
        (refuse! (str "Could not start the relay: " (.getMessage e)))))))
