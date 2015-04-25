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

(defn- read-local-storage []
  (doseq [v [[conn "simplexsys.stuffiedo"]]]
    (when-let [stored (.getItem js/localStorage (second v))]
      (reset! (first v) (reader/read-string (.decompressFromUTF16 js/LZString stored))))))

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
                                                     (ui/mount) ))
                  (let [] 
                    (read-local-storage)
                    (ui/mount)))))

(init)
