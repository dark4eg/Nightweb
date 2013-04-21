(ns nightweb-desktop.dialogs
  (:use [nightweb-desktop.utils :only [get-string]]))

(defn get-profile-dialog
  []
  [:div {:id "profile-dialog" :class "reveal-modal dark"}
   [:form
    [:br]
    [:a {:href "#"}
     [:img {:src "img/profile.png"
            :width 250
            :style "float: right;"
            :class "image-view"}]]
    [:div {:style "margin-right: 250px; min-width: 300px;"}
     [:input {:type "text" :placeholder (get-string :name)}]
     [:textarea {:placeholder (get-string :about_me)}]]
    [:div {:style "float: right; clear: both;"}
     [:a {:href "#"
          :class "button"
          :onclick "$('#profile-dialog').foundation('reveal', 'close')"}
      (get-string :cancel)]
     [:a {:href "#" :class "button"} (get-string :export)]
     [:a {:href "#" :class "button"} (get-string :save)]]]
   [:a {:class "close-reveal-modal"} "&#215;"]])

(defn get-search-dialog
  []
  [:div {:id "search-dialog" :class "reveal-modal dark"}
   [:a {:class "close-reveal-modal"} "&#215;"]])

(defn get-new-post-dialog
  []
  [:div {:id "new-post-dialog" :class "reveal-modal dark"}
   [:a {:class "close-reveal-modal"} "&#215;"]])

(defn get-link-dialog
  []
  [:div {:id "link-dialog" :class "reveal-modal dark"}
   [:a {:class "close-reveal-modal"} "&#215;"]])

(defn get-settings-dialog
  []
  [:div {:id "settings-dialog" :class "reveal-modal dark"}
   [:a {:class "close-reveal-modal"} "&#215;"]])