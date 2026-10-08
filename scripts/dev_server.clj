(ns dev-server
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as http]))

(def default-port 8080)

(def cli-spec
  {:coerce {:port :long}
   :alias {:p :port}})

(def content-types
  {"html" "text/html; charset=utf-8"
   "css" "text/css; charset=utf-8"
   "js" "text/javascript; charset=utf-8"
   "cljs" "text/plain; charset=utf-8"
   "jpg" "image/jpeg"
   "jpeg" "image/jpeg"
   "png" "image/png"
   "svg" "image/svg+xml"
   "ico" "image/x-icon"
   "ttf" "font/ttf"})

(defn decode-path
  "Percent-decoded request path, or nil when the encoding is broken."
  [uri]
  (try
    (java.net.URLDecoder/decode (or uri "/") "UTF-8")
    (catch IllegalArgumentException _
      nil)))

(defn file-path
  "Absolute path for a request uri inside root, or nil when the uri leaves root."
  [root uri]
  (when-let [decoded (decode-path uri)]
    (let [relative (-> decoded
                       (str/replace #"^/+" "")
                       (str/replace #"/+" "/"))
          relative (if (or (str/blank? relative)
                           (str/ends-with? relative "/"))
                     (str relative "index.html")
                     relative)
          root (fs/normalize (fs/absolutize root))
          file (fs/normalize (fs/path root relative))]
      (when (fs/starts-with? file root)
        (str file)))))

(defn media-type
  "Content type for a file path."
  [path]
  (get content-types
       (str/lower-case (or (fs/extension path) ""))
       "application/octet-stream"))

(defn response
  "Static file response for one request, or a 404."
  [root request]
  (let [path (file-path root (:uri request))]
    (if (and path (fs/regular-file? path))
      {:status 200
       :headers {"Content-Type" (media-type path)
                 "Cache-Control" "no-cache"}
       :body (io/file path)}
      {:status 404
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "Not found"})))

(defn start!
  "Serve the project directory until the process is stopped."
  [opts]
  (let [port (or (:port opts) default-port)
        root (str (fs/normalize (fs/absolutize ".")))]
    (try
      (let [stop (http/run-server #(response root %)
                                  {:port port
                                   :ip "127.0.0.1"})]
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. (fn [] (stop {:timeout 100}))))
        (println (str "http://127.0.0.1:" port "/"))
        @(promise))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "Could not serve on port " port ": " (.getMessage e))))
        (throw (ex-info "Dev server did not start" {:babashka/exit 1} e))))))
