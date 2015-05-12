(ns simplexsys.stuffiedo.server
  (:require [vertx.client.eventbus :as vertx]
            [cljs.core.async :as async]
            [datascript :as ds])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def eb (atom nil)) 

(defn connect [url & {:keys [timeout connect-handler timeout-handler] :or {:timeout 500}}] 
  (reset! eb (vertx/eventbus url))
  (let [ch (async/chan)
        to (async/timeout timeout)]
    (vertx/on-open @eb #((async/put! ch true) (connect-handler %)))
    (go (async/alts! [ch to])
        (timeout-handler)) ))


(defn is-connected? []
  (vertx/open? @eb))

(defn readystate []
  (vertx/ready-state @eb))

(defn get-data [conn]
  (ds/transact! conn [{:stage/order 7
                       :stage/name :done }
                      {:stage/order 8
                       :stage/name :dropped}
                      {:stage/order 5
                       :stage/name :doing 
                       :stage/max-stuff 3}
                      {:stage/order 6
                       :stage/name :on-hold 
                       :stage/max-stuff 5}
                      {:stage/order 4
                       :stage/name :on-deck 
                       :stage/max-stuff 15 
                       :stage/valid-next [[:stage/name :doing] [:stage/name :on-hold]]}
                      {:stage/order 3
                       :stage/name :logback 
                       :stage/valid-next [[:stage/name :on-deck]] } 
                      {:stage/order 2
                       :stage/name :denied }
                      {:stage/order 1
                       :stage/name :request 
                       :stage/valid-next [[:stage/name :logback] [:stage/name :denied]] }])

(defn stage-id [stage-name]
  (ds/q '[:find ?e .
          :in $ ?name
          :where [?e :stage/name ?name]] @conn stage-name))

(let [id (stage-id :doing)]
  (ds/transact! conn [[:db/add id :stage/valid-next [:stage/name :done]]
                      [:db/add id :stage/valid-next [:stage/name :on-hold] ]
                      [:db/add id :stage/valid-next [:stage/name :dropped]]] ))

(let [id (stage-id :on-hold)]
  (ds/transact! conn [[:db/add id :stage/valid-next [:stage/name :doing]]
                      [:db/add id :stage/valid-next [:stage/name :dropped]]] ))


(ds/transact! conn [{:stuff/title "something to do"
                     :stuff/content "request from someone else"
                     :stuff/stage [:stage/name :request]}
                    {:stuff/title "tasky"
                     :stuff/content "one of the many"
                     :stuff/stage [:stage/name :logback]}
                    {:stuff/title "stuff"
                     :stuff/content "very much stuff too do"
                     :stuff/stage [:stage/name :logback]}
                    {:stuff/title "more stuff"
                     :stuff/content "very much stuff too do"
                     :stuff/stage [:stage/name :logback]}
                    {:stuff/title "prepering to do"
                     :stuff/content "some stuff"
                     :stuff/stage [:stage/name :on-deck]}
                    {:stuff/title "doing something"
                     :stuff/content "really silly"
                     :stuff/stage [:stage/name :doing]}
                    {:stuff/title "this one has to wait a little"
                     :stuff/content "'cause it's, well, not very important"
                     :stuff/stage [:stage/name :on-hold]}
                    {:stuff/title "this one is not important"
                     :stuff/content "waiting... "
                     :stuff/stage [:stage/name :on-hold]}
                    {:stuff/title "old tasky"
                     :stuff/content "already long time ago finished!"
                     :stuff/stage [:stage/name :done]}]))
