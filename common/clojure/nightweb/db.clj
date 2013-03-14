(ns nightweb.db
  (:use [clojure.java.jdbc :only [with-connection
                                  transaction
                                  create-table
                                  drop-table
                                  update-or-insert-values
                                  with-query-results
                                  delete-rows
                                  do-commands]]
        [nightweb.formats :only [base32-decode
                                 b-encode
                                 b-decode
                                 b-decode-bytes
                                 b-decode-string
                                 b-decode-long
                                 b-decode-list
                                 b-decode-byte-list]]
        [nightweb.constants :only [db-file my-hash-bytes]]))

(def spec nil)
(def limit 25)

(defmacro paginate
  [page & body]
  `(format (str ~@body " LIMIT %d OFFSET %d")
           (+ ~limit 1)
           (* ~limit (if ~page (- ~page 1) 0))))

(defn prepare-results
  [rs table]
  (->> (for [row rs]
         (into {} (assoc row :type table)))
       (doall)
       (vec)))

; initialization

(defn check-table
  ([table-name] (check-table table-name "*"))
  ([table-name column-name]
   (try
     (with-connection
       spec
       (with-query-results
         rs
         [(str "SELECT COUNT(" (name column-name) ") FROM " (name table-name))]
         rs))
     (catch java.lang.Exception e nil))))

(defn create-index
  [table-name columns]
  (do-commands
    "CREATE ALIAS IF NOT EXISTS FT_INIT FOR \"org.h2.fulltext.FullText.init\";"
    "CALL FT_INIT();"
    (format "CALL FT_CREATE_INDEX('PUBLIC', '%s', '%s');"
            table-name (clojure.string/join "," columns))))

(defn create-tables
  []
  (when-not (check-table :user)
    (with-connection
      spec
      (create-table
        :user
        [:id "BIGINT" "PRIMARY KEY AUTO_INCREMENT"]
        [:origuserhash "BINARY"]
        [:userhash "BINARY"]
        [:title "VARCHAR"]
        [:body "VARCHAR"]
        [:time "BIGINT"]
        [:mtime "BIGINT"]
        [:pichash "BINARY"])
      (create-index "USER" ["ID" "TITLE" "BODY"])))
  (when-not (check-table :post)
    (with-connection
      spec
      (create-table
        :post
        [:id "BIGINT" "PRIMARY KEY AUTO_INCREMENT"]
        [:posthash "BINARY"]
        [:userhash "BINARY"]
        [:body "VARCHAR"]
        [:time "BIGINT"]
        [:mtime "BIGINT"]
        [:pichash "BINARY"]
        [:count "BIGINT"]
        [:userptrhash "BINARY"]
        [:postptrhash "BINARY"])
      (create-index "POST" ["ID" "BODY"])))
  (when-not (check-table :pic)
    (with-connection
      spec
      (create-table
        :pic
        [:id "BIGINT" "PRIMARY KEY AUTO_INCREMENT"]
        [:pichash "BINARY"]
        [:userhash "BINARY"]
        [:ptrhash "BINARY"])))
  (when-not (check-table :fav)
    (with-connection
      spec
      (create-table
        :fav
        [:id "BIGINT" "PRIMARY KEY AUTO_INCREMENT"]
        [:favhash "BINARY"]
        [:userhash "BINARY"]
        [:mtime "BIGINT"]))))

(defn drop-tables
  []
  (try
    (with-connection
      spec
      (drop-table :user)
      (drop-table :post)
      (drop-table :pic)
      (drop-table :fav))
    (catch java.lang.Exception e (println "Tables don't exist"))))

(defn init-db
  [base-dir]
  (when (nil? spec)
    (def spec
      {:classname "org.h2.Driver"
       :subprotocol "h2"
       :subname (str base-dir db-file)})
    ;(drop-tables)
    (create-tables)))

; insertion

(defn insert-pic-list
  [user-hash ptr-hash args]
  (let [pics (b-decode-list (get args "pics"))]
    (with-connection
      spec
      (doseq [pic pics]
        (if-let [pic-hash (b-decode-bytes pic)]
          (update-or-insert-values
            :pic
            ["pichash = ? AND userhash = ? AND ptrhash = ?"
             pic-hash user-hash ptr-hash]
            {:pichash pic-hash
             :userhash user-hash
             :ptrhash ptr-hash}))))
    pics))

(defn insert-profile
  [user-hash args]
  (let [edit-time (b-decode-long (get args "mtime"))
        pics (insert-pic-list user-hash user-hash args)]
    (if (and edit-time (<= edit-time (.getTime (java.util.Date.))))
      (with-connection
        spec
        (update-or-insert-values
          :user
          ["userhash = ?" user-hash]
          {:origuserhash user-hash
           :userhash user-hash 
           :title (b-decode-string (get args "title"))
           :body (b-decode-string (get args "body"))
           :time (.getTime (java.util.Date.))
           :mtime edit-time
           :pichash (b-decode-bytes (get pics 0))})))))

(defn insert-post
  [user-hash post-hash args]
  (let [create-time (b-decode-long (get args "time"))
        edit-time (b-decode-long (get args "mtime"))
        pics (insert-pic-list user-hash post-hash args)]
    (if (and create-time
             edit-time
             (<= create-time (.getTime (java.util.Date.)))
             (<= edit-time (.getTime (java.util.Date.))))
      (with-connection
        spec
        (update-or-insert-values
          :post
          ["userhash = ? AND time = ?" user-hash create-time]
          {:posthash post-hash
           :userhash user-hash
           :body (b-decode-string (get args "body"))
           :time create-time
           :mtime edit-time
           :pichash (b-decode-bytes (get pics 0))
           :count (count pics)})))))

(defn insert-meta-data
  [user-hash data-map]
  (case (get data-map :dir-name)
    "post" (insert-post user-hash
                        (base32-decode (get data-map :file-name))
                        (get data-map :contents))
    nil (case (get data-map :file-name)
          "user.profile" (insert-profile user-hash (get data-map :contents)))
    nil))

; retrieval

(defn get-user-data
  [params]
  (let [user-hash (get params :userhash)]
    (with-connection
      spec
      (with-query-results
        rs
        [(str "SELECT * FROM user WHERE userhash = ?") user-hash]
        (if-let [user (first (prepare-results rs :user))]
          user
          {:userhash user-hash :type :user})))))

(defn get-single-post-data
  [params]
  (let [user-hash (get params :userhash)
        create-time (get params :time)]
    (with-connection
      spec
      (with-query-results
        rs
        ["SELECT * FROM post WHERE userhash = ? AND time = ?"
         user-hash create-time]
        (if-let [post (first (prepare-results rs :post))]
          post
          {:userhash user-hash :time create-time :type :post})))))

(defn get-post-data
  [params]
  (let [user-hash (get params :userhash)
        page (get params :page)]
    (with-connection
      spec
      (with-query-results
        rs
        [(paginate page
                   "SELECT * FROM post "
                   "WHERE userhash = ? ORDER BY time DESC")
         user-hash]
        (prepare-results rs :post)))))

(defn get-category-data
  [params]
  (let [data-type (get params :type)
        sub-type (get params :subtype)
        statement (case data-type
                    :user ["SELECT * FROM user ORDER BY time DESC"]
                    :post ["SELECT * FROM post ORDER BY time DESC"]
                    :fav (case sub-type
                           :user [(str "SELECT * FROM fav "
                                       "LEFT JOIN user "
                                       "ON fav.favhash = user.userhash "
                                       "WHERE fav.userhash = ? "
                                       "ORDER BY fav.mtime DESC")
                                  (get params :userhash)]
                           :post [(str "SELECT * FROM fav "
                                       "LEFT JOIN post "
                                       "ON fav.favhash = post.userhash "
                                       "WHERE fav.userhash = ? "
                                       "ORDER BY fav.mtime DESC")
                                  (get params :userhash)])
                    :search (case sub-type
                              :user [(str "SELECT user.* FROM "
                                          "FT_SEARCH_DATA(?, 0, 0) ft, user "
                                          "WHERE ft.TABLE='USER' "
                                          "AND user.ID=ft.KEYS[0] "
                                          "ORDER BY user.time DESC")
                                     (get params :query)]
                              :post [(str "SELECT post.* FROM "
                                          "FT_SEARCH_DATA(?, 0, 0) ft, post "
                                          "WHERE ft.TABLE='POST' "
                                          "AND post.ID=ft.KEYS[0] "
                                          "ORDER BY post.time DESC")
                                     (get params :query)]))]
    (with-connection
      spec
      (with-query-results
        rs
        (vec (concat [(paginate (get params :page) (first statement))]
                     (rest statement)))
        (prepare-results rs (or sub-type data-type))))))

(defn get-pic-data
  [params ptr-key]
  (let [user-hash (get params :userhash)
        ptr-hash (get params ptr-key)
        page (get params :page)]
    (with-connection
      spec
      (with-query-results
        rs
        [(paginate page "SELECT * FROM pic WHERE userhash = ? AND ptrhash = ?")
         user-hash ptr-hash]
        (prepare-results rs :pic)))))
