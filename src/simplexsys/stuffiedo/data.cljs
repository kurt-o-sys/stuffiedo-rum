(ns ^:figwheel-always simplexsys.stuffiedo.data
  (:require [datascript :as ds]))

(enable-console-print!)

; define your app data so that it doesn't get over-written on reload

(def ^:private schema {:stuff/owner {:db/valueType :db.type/ref}
                       :stuff/assignee {:db/valueType :db.type/ref}
                       :stuff/title  {}
                       :stuff/children {:db/valueType :db.type/ref
                                        :db/cardinality :db.cardinality/many}
                       :stuff/predecessor {:db/valueType :db.type/ref
                                           :db/cardinality :db.cardinality/many}
                       :stuff/content {}
                       :stuff/stage {:db/valueType :db.type/ref}
                       :stage/order {}
                       :stage/name {:db/unique :db.unique/identity}
                       :stage/style {}
                       :stage/valid-next {:db/valueType :db.type/ref
                                          :db/cardinality :db.cardinality/many}
                       :stage/max-stuff {}
                       :user/id {:db/unique :db.unique/identity}
                       :user/displayname {:db/unique :db.unique/identity}
                       :user/profile {}
                       :user/preferences {}
                       :user/history {:db/cardinality :db.cardinality/many}
                       :comment/content {}
                       :comment/emotion {:db/valueType :db.type/ref
                                         :db/cardinality :db.cardinality/many}
                       :comment/user {:db/valueType :db.type/ref}
                       :comment/stage {:db/valueType :db.type/ref}
                       :comment/timestamp {}
                       :emotion/name {}
                       :emotion/value {} })

(defn- create-conn
  "Like `datascript/create-conn` but uses a reactive atom to store the db."
  [& {:keys [schema] :or {schema {}}}]
  (atom (ds/empty-db schema)
        :meta {:listeners (atom {})}))

(def conn (create-conn :schema schema))

(defn get-entity [reference]
  (ds/entity @conn reference))

(defn transact! [transaction]
  (ds/transact! conn transaction))

(defn listen! [handler]
  (ds/listen! conn handler))
