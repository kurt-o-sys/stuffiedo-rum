(ns simplexsys.stuffiedo.dev
    (:require
     [simplexsys.stuffiedo]
     [figwheel.client :as fw]))

(fw/start {
  :websocket-url "ws://localhost:3449/figwheel-ws"
  :on-jsload (fn []
               ;; (stop-and-start-my app)
               )})
