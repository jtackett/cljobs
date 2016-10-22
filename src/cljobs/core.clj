(ns cljobs.core
  (:require [clj-http.client :as client]
            [cheshire.core :as json]
            [environ.core :refer [env]]
            [clojure.data.xml :as xml]
            [net.cgrand.enlive-html :as html]
            [clojure.string :as string]
            [clj-time.core :as t]))

(def lang "clojure")

; ----------------------------------
; HELPERS --------------------------
; ----------------------------------
(defn get-text
  "use enlive to retrieve text of provided enlive-doc at selector"
  [doc selector]
  (-> (html/select doc selector) first html/text string/trim))

(defn get-attr
  "use enlive to retrieve given attr of provided enlive-doc at selector"
  [doc selector attr]
  (-> (html/select doc selector) first #(get-in % [:attrs (keyword attr)])))

(defn parse-int [s]
  "string->int
   src: http://stackoverflow.com/a/12503724/3481754"
   (Integer. (re-find  #"\d+" s)))

(defn get-num-pgs
  "return total number of webpages based off total records and current page chunk"
  [records chunk]
  (-> records (#(/ % chunk)) Math/ceil int))


; doesnt handle 'yesterday'
(defn format-date
 "converts string in format 'num unit ago' (ex 5 days ago) to org.joda.time.DateTime
  using clj-time lib"
 [time]
 (let [unit (->> time (re-find #"\d+\s([^s]+)s*\s*ago") second string/trim (str "s"))
       unit-fn (->> unit (str "t/") symbol resolve)
       num (parse-int (re-find #"\d+" time))]
   (-> num unit-fn t/ago)))

; ----------------------------------
; SCRAPERS -------------------------
; ----------------------------------
(defn angel-scrape
  []
  (let [id-url "https://angel.co/job_listings/startup_ids"
        id-res (-> (client/get id-url {:query-params
                                        {"filter_data[keywords][]" lang}}))
                   :body
                   json/decode)
        job-url "https://angel.co/job_listings/browse_startups_table"
        listing-id-params (string/join "&" (map-indexed
                                              (fn [idx id]
                                                (str "listing_ids[" idx "][]=" (first id)))
                                              (id-res "listing_ids")))
        job-url (str job-url "?" listing-id-params)
        jobs-doc ((client/get job-url {:query-params {:multi-param-array :array "startup_ids" (id-res "ids")}}) :body)
        job-listings (html/select (html/html-snippet jobs-doc) [:div.browse_startups_table_row])]
    (map
      (fn [listing]
          {:company (get-text listing [:a.startup-link])
           :tagline (get-text listing [:div.tagline])
           :snippet (get-text listing [:.product :.description])
           :title (get-text listing [:div.collapsed-title])
           :location (get-text listing [:div.locations])
           :link (get-attr listing [:div.details :div.title :a] :href)
           :compensation (get-text listing [:div.compensation])
           :date (format-date (get-text listing [:div.active.tag]))
           :source :angel-list})
        job-listings)))

(defn indeed-scrape
  []
  (let [url "http://api.indeed.com/ads/apisearch"
        query-params {:publisher (env :indeed-api-key)
                      :limit 25
                      :q lang
                      :v 2}
        res ((client/get url {:query-params query-params}) :body)
        res (xml/parse (java.io.StringReader. res) :coalescing false)
        num-jobs (-> res
                     .content
                     (filter #(= (.tag %) :totalresults))
                     first
                     .content
                     first
                     parse-int)
        pages (get-num-pgs num-jobs 25)]
    (for [p (range pages)]
      (let [query-params (merge query-params {:start (* p 25)})
            res ((client/get url {:query-params query-params}) :body)
            res (xml/parse (java.io.StringReader. res))
            job-listings (-> res
                             .content
                             (filter #(= (.tag %) :results))
                             first
                             .content)]
            (map
              (fn [listing]
                (let [res-map  (into {} (map #(hash-map (.tag %) (first (.content %))) (.content listing)))]
                {:company (res-map :company)
                 :tagline nil
                 :snippet (res-map :snippet)
                 :title (res-map :jobtitle)
                 :location (res-map :formattedLocation)
                 :link (res-map :url)
                 :compensation nil
                 :date (format-date (res-map :formattedRelativeTime))
                 :source :indeed})) job-listings)))))

(defn github-scrape
  []
  (let [url "https://jobs.github.com/positions"
        query-params {:description lang}
        job-doc ((client/get url {:query-params query-params}) :body)
        job-listings (html/select (html/html-snippet job-doc) [:table.positionlist :tr])]
    (map
      (fn [listing]
          {:company (get-text listing [:.source :a])
           :tagline nil
           :snippet nil
           :title (get-text listing [:.title :a])
           :location (get-text listing [:.location])
           :link (str "https://jobs.github.com" (get-attr listing [:.title :h4 :a] :href))
           :compensation nil
           :date (get-text listing [:.when])
           :source :github})
        job-listings)))

(defn so-scrape
  []
  (let [url "http://stackoverflow.com/jobs"
        query-params {:searchTerm lang}
        job-doc ((client/get url {:query-params query-params}) :body)
        num-jobs (re-find #"\d+" (get-text (html/html-snippet job-doc) [:span.description]))
        pgs (get-num-pgs num-jobs 25)]
    (for [p (range pages)]
      (let [query-params (merge query-params {:pg p})
            job-doc ((client/get url {:query-params query-params}) :body)
            job-listings (html/select (html/html-snippet job-doc) [:.-job])]

            (map
              (fn [listing]
                {:company (get-text listing [:li.employer])
                 :tagline nil
                 :snippet (get-text listing [:p.text._muted])
                 :title (get-text listing [:a.job-link])
                 :location (get-text listing [:li.location])
                 :link (str "http://stackoverflow.com" (first (re-find #".+\?" (get-attr listing [:a.job-link] :href))))
                 :compensation nil
                 :date (format-date (get-text listing [:p.posted]))
                 :source :so}
                ) job-listings)))))

; (defn monster-scrape
;   []
;   (let [url "https://www.monster.com/jobs/search/"
;         query-params {:q lang}
;         res ((client/get url {:query-params query-params}) :body)
;
;
;         ]))


      ; (def query-params {:query-params {:publisher "239314955390961", :q "clojure", :v 2}})


; (defn indeed-scrape
;   []
;   (let [url "http://www.indeed.com/jobs?q=clojure&l="
;         res ((client/get url) :body)
;         num-jobs (re-find #"\d+" (get-in (first (filter #(= (get-in % [:attrs :name]) "description") (html/select (html/html-snippet res) [:meta]))) [:attrs :content]))
;         pgs
;
;         ]
;
;   (loop [p pages]
;     (let [pg-url (str url )
;           job-doc ((client/get pg-url) :body)
;           job-listings (html/select (html/html-snippet job-doc) [:.result])]
;       (map
;         (fn [listing]
;           {:company
;            :tagline
;            :title
;            :location
;            :link
;            :compensation
;            :source :indeed }
;           ) job-listings)
;
;     ))
;
;         ))

; (html/select listing #{[:a.startup-link html/text] [:div.tagline html/text] [:div.collapsed-title html/text] [:div.locations html/text] [:div.details :div.title :a]})

; hacker news uses a small image and ident by setting the width of that image.
; all high-level posts are `<img src="s.gif" height="1" width="0">` with a width 0
(defn hacker-scrape
  []
  (let [id-url "https://hacker-news.firebaseio.com/v0/item/12627852.json"
        id-res (map str ((json/decode ((client/get id-url) :body)) "kids"))
        ; separate comments from high-level posts
        job-url "https://news.ycombinator.com/item?id=12627852"
        job-doc ((client/get job-url) :body)
        listings (html/select (html/html-snippet job-doc) [:tr.athing])
        job-listings (filter #(and (contains? (set id-res) (get-in % [:attrs :id]))
                                   (re-find (re-pattern (str "(?i)" lang)) (get-text % [:.comment]))) listings)]
    (map
      (fn [listing]
        (let [content (get-text listing [:.comment])
              name (let [opts (string/split content #"\||-")
                         name (first opts)
                         name-len (count (string/split name #"\s"))]
                    (if (< name-len 3)
                      name))]
          (if name
            {:company name
             :tagline nil
             :title nil
             :location nil
             :link (str "https://news.ycombinator.com/item?id=" (get-in listing [:attrs :id]))
             :compensation nil
             :date (format-date (get-text listing [:.age :a]))
             :source :hackernews }))) job-listings)))

;
; (map
;   (fn [listing]
;     {:company
;      :tagline
;      :snippet
;      :title
;      :location
;      :link
;      :compensation
;       :date
;      :source }
;     ) job-listings)
