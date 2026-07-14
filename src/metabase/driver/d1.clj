(ns metabase.driver.d1
  "Cloudflare D1 driver. D1 is SQLite with an HTTP-only interface, so this driver pairs the SQLite SQL dialect
  (see [[metabase.driver.d1.query-processor]]) with an HTTP execution/sync layer built on the Cloudflare REST API
  (see [[metabase.driver.d1.client]]). Parent is `:sql` (not `:sql-jdbc`), like other HTTP-based drivers."
  (:require
   [clojure.string :as str]
   [metabase.driver :as driver]
   [metabase.driver-api.core :as driver-api]
   [metabase.driver.connection :as driver.conn]
   [metabase.driver.d1.client :as d1.client]
   [metabase.driver.d1.query-processor]
   [metabase.util.date-2 :as u.date]
   [metabase.util.log :as log]))

(set! *warn-on-reflection* true)

(driver/register! :d1, :parent :sql)

;; Match the SQLite driver's feature set, minus JDBC-only features (table-privileges) since D1 sync/execution goes
;; over HTTP.
(doseq [[feature supported?] {:right-join                             false
                              :full-join                              false
                              :regex                                  false
                              :percentile-aggregations                false
                              :advanced-math-expressions              false
                              :standard-deviation-aggregations        false
                              :schemas                                false
                              :datetime-diff                          true
                              :expression-literals                    true
                              :now                                    true
                              :identifiers-with-spaces                true
                              ;; SQLite `LIKE` clauses are case-insensitive by default and cannot be made
                              ;; case-sensitive
                              :case-sensitivity-string-filter-options false
                              :index-info                             false
                              :table-privileges                       false
                              :uploads                                false
                              :persist-models                         false}]
  (defmethod driver/database-supports? [:d1 feature] [_driver _feature _db] supported?))

(defmethod driver/db-start-of-week :d1
  [_]
  :sunday)

;; D1/SQLite stores everything without timezone info; treat as UTC like the SQLite driver does
(defmethod driver/db-default-timezone :d1
  [_driver _database]
  "UTC")

;;;; ------------------------------------------------ Connection -------------------------------------------------

(defmethod driver/can-connect? :d1
  [_driver details]
  {:pre [(map? details)]}
  (let [{:keys [columns rows]} (d1.client/execute! details "SELECT 1 AS ok" [])]
    (boolean (and (= columns ["ok"])
                  (= rows [[1]])))))

(defmethod driver/dbms-version :d1
  [_driver database]
  (try
    (let [{:keys [rows]} (d1.client/execute! (driver.conn/effective-details database)
                                             "SELECT sqlite_version()" [])]
      {:version (str "SQLite " (ffirst rows) " (Cloudflare D1)")})
    (catch Throwable e
      (log/warnf e "Error fetching D1 database version")
      nil)))

;;;; --------------------------------------------------- Sync ----------------------------------------------------

(defn- query-maps
  "Run `sql` and return the results as a seq of maps of column-keyword -> value."
  [details sql params]
  (let [{:keys [columns rows]} (d1.client/execute! details sql params)
        ks                     (mapv keyword columns)]
    (mapv #(zipmap ks %) rows)))

(defn- quote-identifier [s]
  (str "\"" (str/replace s "\"" "\"\"") "\""))

(defmethod driver/describe-database* :d1
  [_driver database]
  (let [details (driver.conn/effective-details database)
        tables  (query-maps details
                            (str "SELECT name FROM sqlite_master "
                                 "WHERE type IN ('table', 'view') "
                                 ;; exclude SQLite internals and D1's own bookkeeping tables (_cf_KV etc.)
                                 "AND name NOT LIKE 'sqlite!_%' ESCAPE '!' "
                                 "AND name NOT LIKE '!_cf!_%' ESCAPE '!'")
                            [])]
    {:tables (set (for [{:keys [name]} tables]
                    {:name name, :schema nil}))}))

;; SQLite types can have optional lengths, e.g. NVARCHAR(100) or NUMERIC(10,5), and columns may have no declared
;; type at all. Same pattern list as the SQLite driver.
(def ^:private pattern->base-type
  [[#"BIGINT"    :type/BigInteger]
   [#"BIG INT"   :type/BigInteger]
   [#"INT"       :type/Integer]
   [#"CHAR"      :type/Text]
   [#"TEXT"      :type/Text]
   [#"CLOB"      :type/Text]
   [#"BLOB"      :type/*]
   [#"REAL"      :type/Float]
   [#"DOUB"      :type/Float] ; codespell:ignore
   [#"FLOA"      :type/Float]
   [#"NUMERIC"   :type/Float]
   [#"DECIMAL"   :type/Decimal]
   [#"BOOLEAN"   :type/Boolean]
   [#"TIMESTAMP" :type/DateTime]
   [#"DATETIME"  :type/DateTime]
   [#"DATE"      :type/Date]
   [#"TIME"      :type/Time]])

(defn- database-type->base-type [database-type]
  (let [upcased (str/upper-case (str database-type))]
    (or (some (fn [[pattern base-type]]
                (when (re-find pattern upcased)
                  base-type))
              pattern->base-type)
        :type/*)))

(defmethod driver/describe-table :d1
  [_driver database table]
  (let [details (driver.conn/effective-details database)
        columns (query-maps details
                            (format "PRAGMA table_info(%s)" (quote-identifier (:name table)))
                            [])]
    {:schema nil
     :name   (:name table)
     :fields (set (for [{:keys [cid name type notnull pk]} columns]
                    {:name              name
                     :database-type     (str type)
                     :base-type         (database-type->base-type type)
                     :database-position cid
                     :pk?               (pos? (long (or pk 0)))
                     :database-required (and (pos? (long (or notnull 0)))
                                             (zero? (long (or pk 0))))}))}))

#_{:clj-kondo/ignore [:deprecated-var]}
(defmethod driver/describe-table-fks :d1
  [_driver database table]
  (let [details (driver.conn/effective-details database)
        fks     (query-maps details
                            (format "PRAGMA foreign_key_list(%s)" (quote-identifier (:name table)))
                            [])]
    (set (for [{dest-table :table, from :from, to :to} fks
               ;; `to` is nil when the FK references the dest table's implicit rowid primary key; skip those
               :when to]
           {:fk-column-name   from
            :dest-table       {:name dest-table, :schema nil}
            :dest-column-name to}))))

;;;; ------------------------------------------------- Execution -------------------------------------------------

;; D1/SQLite has no real temporal types: dates and datetimes come back over JSON as ISO-ish strings (the output of
;; date()/datetime()/strftime()). Metabase middleware expects java.time values for temporal columns, so parse
;; string values in columns whose (merged) metadata says they're temporal.
(defn- temporal-column-indexes
  [outer-query metadata]
  (try
    (let [cols (driver-api/merged-column-info outer-query metadata)]
      (into #{}
            (keep-indexed (fn [i {:keys [base_type effective_type]}]
                            (when (some #(and % (isa? % :type/Temporal)) [effective_type base_type])
                              i)))
            cols))
    (catch Throwable e
      (log/warnf e "Error determining temporal columns for D1 query results")
      #{})))

(defn- parse-temporal-string [v]
  (if (string? v)
    (try
      (u.date/parse v)
      (catch Throwable _
        v))
    v))

(defn- parse-temporal-values [rows temporal-idxs]
  (if (empty? temporal-idxs)
    rows
    (mapv (fn [row]
            (reduce (fn [row i]
                      (update row i parse-temporal-string))
                    row
                    temporal-idxs))
          rows)))

(defmethod driver/execute-reducible-query :d1
  [_driver {{sql :query, params :params} :native, :as outer-query} _context respond]
  {:pre [(string? sql)]}
  (let [database  (driver-api/database (driver-api/metadata-provider))
        details   (driver.conn/effective-details database)
        _         (driver.conn/track-connection-acquisition! details)
        {:keys [columns rows]} (d1.client/execute! details sql params)
        metadata  {:cols (mapv (fn [col] {:name col}) columns)}
        rows      (parse-temporal-values rows (temporal-column-indexes outer-query metadata))
        base-types (transduce identity (driver-api/base-type-inferer metadata) rows)
        metadata  (update metadata :cols
                          (fn [cols]
                            (mapv (fn [col base-type]
                                    (assoc col :base_type base-type))
                                  cols
                                  base-types)))]
    (respond metadata rows)))

;; D1 speaks the SQLite dialect, so reuse the SQLite prompt for Metabot/AI features
(defmethod driver/llm-sql-dialect-resource :d1 [_]
  "metabot/prompts/dialects/sqlite.md")
