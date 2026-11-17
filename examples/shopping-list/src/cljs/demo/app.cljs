(ns demo.app
  (:require [demo.icons :refer [item-icon]]
            [polku.client.core :as polku]
            [uix.core :refer [$ defui use-effect use-state]]
            [uix.dom]))

(defn- api
  "Calls the backend and resolves to the response body as a ClojureScript value."
  ([method path] (api method path nil))
  ([method path body]
   (-> (js/fetch (str "/api" path)
                 (clj->js (cond-> {:method method}
                            body (assoc :headers {"Content-Type" "application/json"}
                                        :body (js/JSON.stringify (clj->js body))))))
       (.then #(.json %))
       (.then #(js->clj % :keywordize-keys true)))))

(defui add-form [{:keys [placeholder on-add]}]
  (let [[text set-text!] (use-state "")]
    ($ :form {:class "flex gap-2"
              :on-submit (fn [e]
                           (.preventDefault e)
                           (let [name (.trim text)]
                             (when (seq name)
                               (on-add name)
                               (set-text! ""))))}
       ($ :input {:class "min-w-0 flex-1 rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none focus:ring-2 focus:ring-emerald-200"
                  :placeholder placeholder
                  :value text
                  :on-change #(set-text! (.. % -target -value))})
       ($ :button {:type "submit"
                   :class "rounded-lg bg-emerald-600 px-4 py-2 text-sm font-medium text-white hover:bg-emerald-700"}
          "Add"))))

(defui sidebar [{:keys [lists selected-id on-select on-add]}]
  ($ :aside {:class "flex w-72 shrink-0 flex-col gap-4 border-r border-slate-200 bg-slate-50 p-5"}
     ($ :h1 {:class "text-lg font-semibold text-slate-800"} "Shopping lists")
     ($ :nav {:class "flex flex-col gap-1"}
        (for [{:keys [id name open-count]} lists]
          ($ :button {:key id
                      :class (str "flex items-center justify-between rounded-lg px-3 py-2 text-left text-sm "
                                  (if (= id selected-id)
                                    "bg-emerald-600 text-white"
                                    "text-slate-700 hover:bg-slate-200"))
                      :on-click #(on-select id)}
             ($ :span {:class "truncate"} name)
             (when (pos? open-count)
               ($ :span {:class (str "ml-2 rounded-full px-2 text-xs "
                                     (if (= id selected-id)
                                       "bg-emerald-500 text-white"
                                       "bg-slate-200 text-slate-600"))}
                  open-count)))))
     ($ add-form {:placeholder "New list" :on-add on-add})))

(defui item-row [{:keys [item on-toggle on-delete]}]
  (let [{:keys [name done]} item]
    ($ :li {:class "group flex items-center gap-3 rounded-lg px-3 py-2 hover:bg-slate-50"}
       ($ :input {:type "checkbox"
                  :class "h-4 w-4 accent-emerald-600"
                  :checked done
                  :on-change #(on-toggle item)})
       ($ item-icon {:name name
                     :class (str "h-5 w-5 shrink-0 "
                                 (if done "text-slate-300" "text-emerald-600"))})
       ($ :span {:class (str "flex-1 text-sm "
                             (if done "text-slate-400 line-through" "text-slate-800"))}
          name)
       ($ :button {:class "text-sm text-slate-400 opacity-0 hover:text-red-600 group-hover:opacity-100"
                   :title "Remove"
                   :on-click #(on-delete item)}
          "✕"))))

(defui items-panel [{:keys [list items on-add on-toggle on-delete]}]
  ($ :section {:class "flex flex-1 flex-col gap-5 p-8"}
     (if list
       ($ :<>
          ($ :h2 {:class "text-2xl font-semibold text-slate-800"} (:name list))
          ($ :div {:class "max-w-xl"}
             ($ add-form {:placeholder "Add an item" :on-add on-add}))
          (if (seq items)
            ($ :ul {:class "flex max-w-xl flex-col"}
               (for [item items]
                 ($ item-row {:key (:id item) :item item
                              :on-toggle on-toggle :on-delete on-delete})))
            ($ :p {:class "text-sm text-slate-400"} "No items yet.")))
       ($ :p {:class "text-slate-400"} "Choose a list on the left."))))

(defui app []
  (let [[lists set-lists!]             (use-state [])
        [selected-id set-selected-id!] (use-state nil)
        [items set-items!]             (use-state [])
        load-lists  (fn [] (.then (api "GET" "/lists") set-lists!))
        load-items  (fn [id] (.then (api "GET" (str "/lists/" id "/items")) set-items!))
        refresh     (fn [] (load-lists) (when selected-id (load-items selected-id)))]
    (use-effect
     (fn []
       (.then (api "GET" "/lists")
              (fn [ls]
                (set-lists! ls)
                (when-let [first-id (:id (first ls))]
                  (set-selected-id! first-id))))
       js/undefined)
     [])
    (use-effect
     (fn []
       (if selected-id
         (.then (api "GET" (str "/lists/" selected-id "/items")) set-items!)
         (set-items! []))
       js/undefined)
     [selected-id])
    ($ :main {:class "flex min-h-screen bg-white"}
       ($ sidebar {:lists lists
                   :selected-id selected-id
                   :on-select set-selected-id!
                   :on-add (fn [name]
                             (.then (api "POST" "/lists" {:name name})
                                    (fn [l]
                                      (load-lists)
                                      (set-selected-id! (:id l)))))})
       ($ items-panel {:list (some #(when (= (:id %) selected-id) %) lists)
                       :items items
                       :on-add (fn [name]
                                 (.then (api "POST" (str "/lists/" selected-id "/items") {:name name})
                                        refresh))
                       :on-toggle (fn [{:keys [id done]}]
                                    (.then (api "PUT" (str "/items/" id) {:done (not done)})
                                           refresh))
                       :on-delete (fn [{:keys [id]}]
                                    (.then (api "DELETE" (str "/items/" id)) refresh))}))))

(defonce root
  (uix.dom/create-root (js/document.getElementById "app")))

(defn ^:dev/after-load render []
  (uix.dom/render-root ($ app) root))

(defn init []
  (when ^boolean goog.DEBUG
    (polku/init {:endpoint  "http://localhost:4777"
                 :lang      "en-US"
                 :languages ["en-US" "fi-FI"]}))
  (render))
