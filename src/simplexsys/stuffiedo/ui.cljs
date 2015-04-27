(ns ^:figwheel-always simplexsys.stuffiedo.ui
  (:require [cljs.core.async :as async]
            [simplexsys.stuffiedo.data :as data]
            [datascript :as ds]
            [rum :as rum]
            [dommy.core :as dom]
            [cljs-time.format :as dt-format]))

(enable-console-print!)

; define your app data so that it doesn't get over-written on reload

(def messageq (async/chan))
(def history-list (atom ()))
(def dragstate (atom {}))

(def conn data/conn)


(def authLock (js/Auth0Lock. "Bfl77AwNnI6R1Sy9YL17k0B6mKrvrrYy" "qsys.auth0.com"))
(def auth (atom nil))
(def profile (atom nil))
(def show-add-stuffie (atom false))

(def auth-mixin {:will-mount (fn [] (async/put! messageq [:login]))})
                                    
(rum/defc logged-out-menu []
  [:a {:on-click (fn [] (.show authLock) )}
   [:p "Sign In"]])

(rum/defc active-user < rum/reactive []
  [:span.profile 
   [:img {:src "https://pbs.twimg.com/profile_images/2747119097/5ff2beaf014933ad861a154c500727d6_normal.jpeg"
          :alt (:nickname (rum/react profile))
          :title (:nickname (rum/react profile))} ] ])

(rum/defc logged-in-menu < rum/reactive []
  [:ul
   (map (fn [i] [:li (:provider i)]) (:identities (rum/react profile)))
   [:li {:on-click (fn [] 
                     (.show authLock (clj->js {:callbackUrl "http://localhost:3449/" 
                                               :dict {:signin {:title "link new"}} 
                                               :authParams {:access_token (:access_token @auth)}})))} "link other" ]
   [:li {:on-click (fn [] (async/put! messageq [:logout]))} "log out"]])

(rum/defc auth-menu []
  [:div.user-actions
   {:style {:float "right"}}
   [:ul.logged-in
    [:li (active-user) (logged-in-menu)]]])

(defn extract-stuffie []
  (when-let [title (dom/value (dom/sel1 "#addstuffie form .title"))]
    {:title    title
     :content (dom/value (dom/sel1 "#addstuffie form .content"))}))

(defn clean-add-stuffie []
  (dom/set-value! (dom/sel1 "#addstuffie form .title") nil)
  (dom/set-value! (dom/sel1 "#addstuffie form .content") nil))

(rum/defc add-stuffie < rum/reactive []
  (rum/react show-add-stuffie)
  (if @show-add-stuffie

    [:div#addstuffie
     [:form  {:on-submit (fn [_] 
                           (async/put! messageq [:add-stuff (extract-stuffie)]) 
                           (clean-add-stuffie)
                           false)}
      [:input.title   
       {:style {:vertical-align "top"}
        :type "text" 
        :placeholder "Stuffie title"}]
      [:textarea.content 
       {:type "text" 
        :placeholder "This is what needs to be done."}] 
      [:input.submit-add  
       {:style {:vertical-align "top"}
        :type "submit" :value "Add stuffie"}]]]
    [:span ""]))

(defn- try-undo [e i]
  (let [stage-to (:stuff/stage (:to i))
        stage-from (:stuff/stage (:from i))
        stage-current (:stuff/stage (data/get-entity (:db/id (:to i))))]
    (if (= stage-to stage-current)
      (async/put! messageq [:undo-history {:stage (:db/id stage-from)
                                           :item i}])
      (.log js/console "undo not possible - wrong current state"))))

(rum/defc undo-item [i]
  (let [title (:stuff/title (data/get-entity (:db/id (:from i))))
        to-stage (:stage/name (:stuff/stage (:to i)))
        action (:action i)]
    (println i)
    [:li 
     {:on-click (fn [e] (try-undo e i))}
     [:span.action (name action) ": "]
     [:span.title title]
     [:span 
      (case action
        :move-stuff " \u2192 "
        "/") ]
     [:span.stage (name to-stage)] 
     [:span " (" (dt-format/unparse (dt-format/formatter "HH:mm:ss dd-MM") (:time i)) ")"]]))

(rum/defc history < rum/reactive []
  [:ul#history
   (map #(undo-item %) (rum/react history-list))
   ])

(rum/defc stuff-count-inner < rum/static [c]
  [:span 
   {:class "counter"}
   c])

(rum/defc left-nav []
  [:ul.left-nav 
   (println @history-list)
   (when (not (empty? @history-list)) [:li "history" (history)])] )

(rum/defc right-nav < auth-mixin []
  [:.right-nav
   (if @auth
     (auth-menu)
     (logged-out-menu)) ])



(rum/defc nav < rum/reactive [] 
  [:nav
   [:h1 
    {:on-click (fn [] (reset! show-add-stuffie (false? @show-add-stuffie)))  }
    [:img {:src "./css/logo.png"
           :height "48"
           :title "Stuffiedo: organize stuff and do"
           :alt "Stuffiedo: organize stuff and do"}]
    [:span.app-name "stuffiedo"]]
   (left-nav)
   (add-stuffie)
   (right-nav) ]) 

(rum/defc stuff-count [ent]
  (let [q (->> (ds/q `[:find [?e ...] :where [?e :stuff/stage ~(:db/id ent)]] @conn) count)]
    (stuff-count-inner q)))


(defn- allowdrop? [stage-to stage-from] 
  (and
    (not= stage-to stage-from)
    (not= (:stage/name stage-to) "request")
    (or (nil? (:stage/max-stuff stage-to))
        (< (->> (ds/q `[:find [?e ...]
                        :where [?e :stuff/stage ~(:db/id stage-to)]] @conn) 
                count)
           (:stage/max-stuff stage-to)))
    (let [valids (ds/q `[:find [?i ...]
                         :where [?e :stage/name ~(:stage/name stage-from)]
                         [?e :stage/valid-next ?i]] @conn)]
      (some #{(:db/id stage-to)} valids))))

(rum/defc stuffie < rum/static [ent stage]
  (let [id (:db/id ent)]
    [:li {:id (str "stuffie:" id)
          :class "block"
          :style {:transform (str "rotate(" (- (rand-int 15) 7) "deg) translateY(" (- (rand-int 15) 7) "px)")}
          :draggable true
          :on-drag-start (fn [e] 
                           (doseq [s (:stage/valid-next stage)] 
                             (when (allowdrop? s stage)
                               (dom/add-class! (dom/sel1 (str "#\\" (:stage/name s))) :allowdrop)))
                           (swap! dragstate assoc 
                                  :current stage  
                                  :initial stage)
                           (.setData (.-dataTransfer e) "text" id)) } 
     [:span.title (:stuff/title ent)] ": " [:span.content (:stuff/content ent)]]))

(rum/defc stage-inner < rum/static [ent r]
  [:article 
   {:id (:stage/name ent) 
    :class "block"
    :on-drag-enter (fn [e] (when (every? #(not= ent %) (list nil (:current @dragstate)))
                             (let [allowed (allowdrop? ent (:initial @dragstate))]
                               (when allowed
                                 (swap! dragstate assoc :current ent)))))
    :on-drag-leave (fn [e] (when (every? #(not= ent %) (list nil (:current @dragstate))) 
                             (swap! dragstate assoc :current ent)))

    :on-drag-over (fn [e] (.preventDefault e))
    :on-drop (fn [e] 
               (.preventDefault e) 
               (let [c (dom/closest (.. e -target) "article" )]
                 (when (and (not= c nil) (dom/has-class? c :allowdrop))
                   (async/put! messageq [:move-stuff {:id (js/parseInt (.getData (.-dataTransfer e) "text"))
                                                      :stage (:db/id ent)} ])))
               (doseq [s (:stage/valid-next (:initial @dragstate))] (dom/remove-class! (dom/sel1 (str "#\\" (:stage/name s))) :allowdrop)))}

   [:h1 (name (:stage/name ent)) (stuff-count ent)]
   [:ul.stuff
    (map #(stuffie (data/get-entity %) ent) r) ]]) 

(rum/defc stage [ent]
  (let [q (ds/q `[:find [?e ...] :where [?e :stuff/stage ~(:db/id ent)]] @conn)]
    (stage-inner ent q)))

(rum/defc kanbanboard []
  [:section#kanbanboard 
   {:class "block-group"} 
   (let [q (->> (ds/q '[:find ?e ?o 
                        :where [?e :stage/order ?o]
                        [?e :stage/name ?n]] @conn) (sort-by second))]
     (map #(stage (data/get-entity (first %))) q))])

(rum/defc footer []
  [:footer 
    [:.name 
    {:style {:float "left"}}
    [:img {:src "./css/simplexsys.png"
           :width "50px"
           :vertical-align "middle"}]]
   [:a {:href "http://www.wtfpl.net/"}
    [:img 
     {:src "http://www.wtfpl.net/wp-content/uploads/2012/12/wtfpl-badge-4.png"
      :style {:float "right"
              :margin-top "30px"}
      :width "80" :height "15" :alt "WTFPL"}]] ])

(rum/defc body []
  [:div#body 
   (nav)
   (kanbanboard)
   (footer)])

(defn mount []
  (let [root (rum/mount (body conn) (.-body js/document))]
    (data/listen! (fn [tx-report] 
                    (rum/request-render root)))))

