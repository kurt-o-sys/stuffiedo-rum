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

(rum/defc login []
  [:a {:on-click (fn [] (.show authLock) )}
   [:p "Sign In"]])

(rum/defc auth-menu < {:will-mount (fn [] 
                                     (let [h (.parseHash authLock (.-hash (.-location js/window)))]
                                       (when h
                                         (reset! auth (js->clj h  :keywordize-keys true))
                                         (let [storedAuth (.decompressFromUTF16 js/LZString (.getItem js/localStorage "simplexsys.stuffiedo.auth"))] 
                                           (when (not= storedAuth @auth)
                                             (async/put! messageq [:save-auth @auth]))))))} []
  [:div.login-box
   {:style {:float "right"}}
   (if @auth
     (let [] 
       (.getProfile authLock 
                    (:id_token @auth) 
                    (fn [err p] (when profile 
                                  (reset! profile (js->clj p :keywordize-keys true))
                                  (println @profile))))
       [:p 
        [:span {:on-click (fn [] (.removeItem js/localStorage "simplexsys.stuffiedo.auth"))} "log out"]
        [:span "logged in as: " (:sub (:profile @auth))]])
     (login))
   ])

(rum/defc header [] 
  [:header
   [:h1 [:img {:src "./css/logo.png"
               :width "65"}] "Stuffiedo" ]
   [:p "organize stuff and do"]
   (auth-menu)]) 

(defn extract-stuffie []
  (when-let [title (dom/value (dom/sel1 "#addstuffie form .title"))]
    {:title    title
     :content (dom/value (dom/sel1 "#addstuffie form .content"))}))

(defn clean-add-stuffie []
  (dom/set-value! (dom/sel1 "#addstuffie form .title") nil)
  (dom/set-value! (dom/sel1 "#addstuffie form .content") nil))

(rum/defc add-stuffie []
  [:div#addstuffie
   [:h2 "add stuffie"]
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
      :type "submit" :value "Add stuffie"}]]])

(defn- try-undo [e i]
  (let [stage-to (:stuff/stage (:to i))
        stage-from (:stuff/stage (:from i))
        stage-current (:stuff/stage (data/get-entity (:db/id (:to i))))]
    (if (= stage-to stage-current)
      (async/put! messageq [:undo-history {:stage (:db/id stage-from)
                                           :item i}])
      (.log js/console "undo not possible - wrong current state"))))

(rum/defc undo-item [i]
  [:li 
   {:on-click (fn [e] (try-undo e i))}
   [:span (dt-format/unparse (dt-format/formatter "HH:mm:ss dd-MM-yyyy") (:time i))]
   [:span (name (:action i))]
   [:span (str (:from i))]
   [:span "->"]
   [:span (str (:to i))]])

(rum/defc history < rum/reactive []
  [:ul#history
   (map #(undo-item %) (rum/react history-list))
   ])

(rum/defc stuff-count-inner < rum/static [c]
  [:span 
   {:class "counter"}
   c])

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
   (history)
   (let [q (->> (ds/q '[:find ?e ?o 
                        :where [?e :stage/order ?o]
                        [?e :stage/name ?n]] @conn) (sort-by second))]
     (map #(stage (data/get-entity (first %))) q))])

(rum/defc footer []
  [:footer 
   {:style {:padding-top "20px"
            :margin "0"
            :font-size "12px"}}
   [:.name 
    {:style {:float "left"}}
    "by simplexsys"]
   [:a {:href "http://www.wtfpl.net/"}
    [:img 
     {:src "http://www.wtfpl.net/wp-content/uploads/2012/12/wtfpl-badge-4.png"
      :style {:float "right"}
      :width "80" :height "15" :alt "WTFPL"}]] ])

(rum/defc body []
  [:div#body 
   (header)
   (add-stuffie)
   (kanbanboard)
   (footer)])

(defn mount []
  (let [root (rum/mount (body conn) (.-body js/document))]
    (data/listen! (fn [tx-report] 
                    (rum/request-render root)))))

