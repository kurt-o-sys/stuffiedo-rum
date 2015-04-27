(ns ^:figwheel-always simplexsys.stuffiedo
  (:require [cljs.core.async :as async]
            [simplexsys.stuffiedo.server :as server]
            [simplexsys.stuffiedo.ui :as ui]
            [simplexsys.stuffiedo.data :as data]
            [cljs.reader :as reader]
            [cljs-time.core :as dt-core])
  (:require-macros [simplexsys.stuffiedo :refer [go-loop-sub]]))

(enable-console-print!)

; define your app data so that it doesn't get over-written on reload


(def ^:dynamic *online* false)
(set! *online* true)

(def messagepub (async/pub ui/messageq first))
(def conn data/conn)


(defn- save-data [c]
  (.setItem js/localStorage "simplexsys.stuffiedo" (.compressToUTF16 js/LZString (pr-str @c))))

(defn- save-auth [a]
  (.setItem js/localStorage "simplexsys.stuffiedo.auth" (.compressToUTF16 js/LZString (pr-str a))))


(go-loop-sub messagepub :move-stuff [_ msg & isUndo?]
             (let [id (:id msg)
                   m {:db/id id :stuff/stage (:stage msg)}
                   prev-stage (:stuff/stage (data/get-entity id))
                   next-stage (data/get-entity (:stage msg))] 
               (data/transact! [m])
               (save-data conn)
               (when (not isUndo?)
                 (async/put! ui/messageq [:add-history {:action :move-stuff 
                                                        :from {:db/id id :stuff/stage prev-stage} 
                                                        :to {:db/id id :stuff/stage next-stage }}]))))

(go-loop-sub messagepub :add-stuff [_ msg]
             (let [m {:stuff/stage [:stage/name :logback]
                      :stuff/title (:title msg)
                      :stuff/content (:content msg)}] 
               (data/transact! [m])
               (save-data conn)))

(go-loop-sub messagepub :save-auth [_ msg]
             (save-auth msg))


(go-loop-sub messagepub :add-history [_ msg]
             (swap! ui/history-list conj (conj msg {:time (dt-core/now)})))

(go-loop-sub messagepub :undo-history [_ msg]
             (let [i (:item msg)
                   s (:stage msg)]
               (async/put! ui/messageq [:move-stuff 
                                        {:id (:db/id (:to i))
                                         :stage s}
                                        true ])
               (reset! ui/history-list (remove #{i} @ui/history-list))))

(go-loop-sub messagepub :login [_]
             (let [h (.parseHash ui/authLock (.-hash (.-location js/window)))]
               (when h
                 (reset! ui/auth (js->clj h :keywordize-keys true))
                 (let [storedAuth (.decompressFromUTF16 js/LZString (.getItem js/localStorage "simplexsys.stuffiedo.auth"))] 
                   (when (not= storedAuth @ui/auth)
                     (async/put! ui/messageq [:save-auth @ui/auth]))))
               (when @ui/auth 
                 (async/put! ui/messageq [:get-profile]))))

(go-loop-sub messagepub :logout [_]
             (.removeItem js/localStorage "simplexsys.stuffiedo.auth")
             (reset! ui/auth nil))

(go-loop-sub messagepub :get-profile [_]
             (.getProfile ui/authLock 
                          (:id_token @ui/auth) 
                          (fn [err p] (when p 
                                        (reset! ui/profile (js->clj p :keywordize-keys true))))))
(defn- read-local-storage []
  (doseq [v [[conn "simplexsys.stuffiedo"] [ui/auth "simplexsys.stuffiedo.auth"]]]
    (when-let [stored (.getItem js/localStorage (second v))]
      (reset! (first v) (reader/read-string (.decompressFromUTF16 js/LZString stored))))))

(defn- check-auth []
  (when (< (:exp (:profile @ui/auth)) (/ (.getTime (dt-core/now)) 1000))
    (reset! ui/auth nil)))

(defonce init (fn []
                (println "initializing application")
                (if *online* 
                  (server/connect "http://localhost:3449/stuffiedo"
                                  :timeout 500
                                  :connect-handler (fn []
                                                     (server/get-data conn)
                                                     (save-data conn))
                                  :timeout-handler (fn [] 
                                                     (read-local-storage) 
                                                     (check-auth)
                                                     (ui/mount) ))
                  (let [] 
                    (read-local-storage)
                    (check-auth)
                    (ui/mount)))))

(init)
