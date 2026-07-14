(ns metabase.driver.d1.query-processor
  "SQL dialect for Cloudflare D1 (SQLite semantics).

  Ported from `metabase.driver.sqlite` (v0.62.4), re-registered for `:d1`, with the JDBC-specific pieces removed.
  See the [SQLite Date and Time Functions Reference](https://www.sqlite.org/lang_datefunc.html)."
  (:require
   [clojure.math :as math]
   [java-time.api :as t]
   [metabase.driver-api.core :as driver-api]
   [metabase.driver.sql :as driver.sql]
   [metabase.driver.sql.parameters.substitution :as sql.params.substitution]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.util.date-2 :as u.date]
   [metabase.util.honey-sql-2 :as h2x]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.malli :as mu])
  (:import
   (java.time LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZonedDateTime)
   (java.time.temporal Temporal)))

(set! *warn-on-reflection* true)

(defn- ->date [& args]
  (-> (into [:date] args)
      (h2x/with-database-type-info "date")))

(defn- ->datetime [& args]
  (-> (into [:datetime] args)
      (h2x/with-database-type-info "datetime")))

(defn- ->time [& args]
  (-> (into [:time] args)
      (h2x/with-database-type-info "time")))

(defn- strftime [format-str expr]
  [:strftime (h2x/literal format-str) expr])

(defmethod sql.qp/date [:d1 :default] [_driver _unit expr] expr)

(defmethod sql.qp/date [:d1 :second]
  [_driver _unit expr]
  (if (h2x/database-or-effective-type-isa? expr "time" :type/Time)
    (->time (strftime "%H:%M:%S" expr))
    (->datetime (strftime "%Y-%m-%d %H:%M:%S" expr))))

(defmethod sql.qp/date [:d1 :second-of-minute]
  [_driver _unit expr]
  (h2x/->integer (strftime "%S" expr)))

(defmethod sql.qp/date [:d1 :minute]
  [_driver _unit expr]
  (if (h2x/database-or-effective-type-isa? expr "time" :type/Time)
    (->time (strftime "%H:%M" expr))
    (->datetime (strftime "%Y-%m-%d %H:%M" expr))))

(defmethod sql.qp/date [:d1 :minute-of-hour]
  [_driver _ expr]
  (h2x/->integer (strftime "%M" expr)))

(defmethod sql.qp/date [:d1 :hour]
  [_driver _unit expr]
  (if (h2x/database-or-effective-type-isa? expr "time" :type/Time)
    (->time (strftime "%H:00" expr))
    (->datetime (strftime "%Y-%m-%d %H:00" expr))))

(defmethod sql.qp/date [:d1 :hour-of-day]
  [_driver _ expr]
  (h2x/->integer (strftime "%H" expr)))

(defmethod sql.qp/date [:d1 :day]
  [_driver _ expr]
  (->date expr))

;; SQLite day of week (%w) is Sunday = 0 <-> Saturday = 6. We want 1 - 7 so add 1
(defmethod sql.qp/date [:d1 :day-of-week]
  [_driver _ expr]
  (sql.qp/adjust-day-of-week :d1 (h2x/->integer (h2x/inc (strftime "%w" expr)))))

(defmethod sql.qp/date [:d1 :day-of-month]
  [_driver _ expr]
  (h2x/->integer (strftime "%d" expr)))

(defmethod sql.qp/date [:d1 :day-of-year]
  [_driver _ expr]
  (h2x/->integer (strftime "%j" expr)))

(defmethod sql.qp/date [:d1 :week]
  [_ _ expr]
  (let [week-extract-fn (fn [expr]
                          ;; Move back 6 days, then forward to the next Sunday
                          (->date expr
                                  (h2x/literal "-6 days")
                                  (h2x/literal "weekday 0")))]
    (sql.qp/adjust-start-of-week :d1 week-extract-fn expr)))

(defmethod sql.qp/date [:d1 :week-of-year-iso]
  [driver _ expr]
  (throw (ex-info (tru "SQLite doesn''t support extract isoweek")
                  {:driver driver
                   :form   expr
                   :type   driver-api/qp.error-type.invalid-query})))

(defmethod sql.qp/date [:d1 :month]
  [_driver _ expr]
  (->date expr (h2x/literal "start of month")))

(defmethod sql.qp/date [:d1 :month-of-year]
  [_driver _ expr]
  (h2x/->integer (strftime "%m" expr)))

(defmethod sql.qp/date [:d1 :quarter]
  [_driver _ expr]
  (->date
   (->date expr (h2x/literal "start of month"))
   [:||
    (h2x/literal "-")
    (h2x/mod (h2x/dec (strftime "%m" expr))
             3)
    (h2x/literal " months")]))

;; q = (m + 2) / 3
(defmethod sql.qp/date [:d1 :quarter-of-year]
  [_driver _ expr]
  (h2x// (h2x/+ (strftime "%m" expr)
                2)
         3))

(defmethod sql.qp/date [:d1 :year]
  [_driver _ expr]
  (->date expr (h2x/literal "start of year")))

(defmethod sql.qp/date [:d1 :year-of-era]
  [_driver _ expr]
  (h2x/->integer (strftime "%Y" expr)))

(defmethod sql.qp/add-interval-honeysql-form :d1
  [_driver hsql-form amount unit]
  (let [[multiplier sqlite-unit] (case unit
                                   :second  [1 "seconds"]
                                   :minute  [1 "minutes"]
                                   :hour    [1 "hours"]
                                   :day     [1 "days"]
                                   :week    [7 "days"]
                                   :month   [1 "months"]
                                   :quarter [3 "months"]
                                   :year    [1 "years"])]
    (->datetime hsql-form (h2x/literal (format "%+d %s" (* amount multiplier) sqlite-unit)))))

(defmethod sql.qp/unix-timestamp->honeysql [:d1 :seconds]
  [_driver _precision expr]
  (->datetime expr (h2x/literal "unixepoch")))

(defn- unix-timestamp->honeysql [expr power]
  (let [divisor       (long (math/pow 10 power))
        format-string (format "%%0%dd" power)]
    [:concat
     (->datetime (h2x// expr divisor) (h2x/literal "unixepoch"))
     (h2x/literal ".")
     [:printf
      (h2x/literal format-string)
      (h2x/mod expr divisor)]]))

(defmethod sql.qp/unix-timestamp->honeysql [:d1 :milliseconds] [_driver _precision expr] (unix-timestamp->honeysql expr 3))
(defmethod sql.qp/unix-timestamp->honeysql [:d1 :microseconds] [_driver _precision expr] (unix-timestamp->honeysql expr 6))
(defmethod sql.qp/unix-timestamp->honeysql [:d1 :nanoseconds]  [_driver _precision expr] (unix-timestamp->honeysql expr 9))

(defmethod sql.qp/cast-temporal-string [:d1 :Coercion/ISO8601->DateTime]
  [_driver _semantic-type expr]
  (->datetime expr))

(defmethod sql.qp/cast-temporal-string [:d1 :Coercion/ISO8601->Date]
  [_driver _semantic-type expr]
  (->date expr))

(defmethod sql.qp/cast-temporal-string [:d1 :Coercion/ISO8601->Time]
  [_driver _semantic-type expr]
  (->time expr))

(defmethod sql.qp/cast-temporal-string [:d1 :Coercion/YYYYMMDDHHMMSSString->Temporal]
  [_driver _coercion-strategy expr]
  (h2x/with-database-type-info [:concat
                                [:substr expr 1 4]
                                "-"
                                [:substr expr 5 2]
                                "-"
                                [:substr expr 7 2]
                                " "
                                [:substr expr 9 2]
                                ":"
                                [:substr expr 11 2]
                                ":"
                                [:substr expr 13 2]]
                               "timestamp"))

(defmethod sql.qp/cast-temporal-byte [:d1 :Coercion/YYYYMMDDHHMMSSBytes->Temporal]
  [driver _coercion-strategy expr]
  (sql.qp/cast-temporal-string driver :Coercion/YYYYMMDDHHMMSSString->Temporal
                               (h2x/cast "TEXT" expr)))

(defmethod sql.qp/cast-temporal-byte [:d1 :Coercion/ISO8601Bytes->Temporal]
  [driver _coercion-strategy expr]
  (sql.qp/cast-temporal-string driver :Coercion/ISO8601->DateTime
                               (h2x/cast "TEXT" expr)))

(defmethod sql.qp/->date :d1
  [_driver value]
  (->date value))

;; SQLite doesn't like Temporal values getting passed in as prepared statement args, so we need to convert them to
;; date literal strings instead to get things to work
(mu/defmethod driver.sql/->prepared-substitution [:d1 Temporal] :- driver.sql/PreparedStatementSubstitution
  [_driver date]
  ;; for anything that's a Temporal value convert it to a yyyy-MM-dd formatted date literal string. For whatever
  ;; reason the SQL generated from parameters ends up looking like `WHERE date(some_field) = ?` sometimes so we need
  ;; to use just the date rather than a full ISO-8601 string
  (sql.params.substitution/make-stmt-subs "?" [(t/format "yyyy-MM-dd" date)]))

;; SQLite doesn't support `TRUE`/`FALSE`; it uses `1`/`0`, respectively; convert these booleans to numbers.
(defmethod sql.qp/->honeysql [:d1 Boolean]
  [_ bool]
  (if bool 1 0))

(defmethod sql.qp/->honeysql [:d1 :substring]
  [driver [_ arg start length]]
  (if length
    [:substr (sql.qp/->honeysql driver arg) (sql.qp/->honeysql driver start) (sql.qp/->honeysql driver length)]
    [:substr (sql.qp/->honeysql driver arg) (sql.qp/->honeysql driver start)]))

(defmethod sql.qp/->honeysql [:d1 :concat]
  [driver [_ & args]]
  (into
   [:||]
   (mapv (partial sql.qp/->honeysql driver) args)))

(defmethod sql.qp/->honeysql [:d1 :floor]
  [_driver [_ arg]]
  [:round (h2x/- arg 0.5)])

(defmethod sql.qp/->honeysql [:d1 :ceil]
  [_driver [_ arg]]
  [:case
   ;; if we're ceiling a whole number, just cast it to an integer; [:ceil 1.0] should return 1
   [:= [:round arg] arg] (h2x/->integer arg)
   :else                 [:round (h2x/+ arg 0.5)]])

;; MEGA HACK (from the SQLite driver)
;;
;; if the time portion is zeroed out generate a date() instead, because SQLite isn't smart enough to compare DATEs
;; and DATETIMEs in a way that could be considered to make any sense whatsoever, e.g.
;;
;; date('2019-12-03') < datetime('2019-12-03 00:00')
(defn- zero-time? [t]
  (= (t/local-time t) (t/local-time 0)))

(defmethod sql.qp/->honeysql [:d1 LocalDate]
  [_ t]
  [:date (h2x/literal (u.date/format-sql t))])

(defmethod sql.qp/->honeysql [:d1 LocalDateTime]
  [driver t]
  (if (zero-time? t)
    (sql.qp/->honeysql driver (t/local-date t))
    [:datetime (h2x/literal (u.date/format-sql t))]))

(defmethod sql.qp/->honeysql [:d1 LocalTime]
  [_ t]
  [:time (h2x/literal (u.date/format-sql t))])

(defmethod sql.qp/->honeysql [:d1 OffsetDateTime]
  [driver t]
  (if (zero-time? t)
    (sql.qp/->honeysql driver (t/local-date t))
    [:datetime (h2x/literal (u.date/format-sql t))]))

(defmethod sql.qp/->honeysql [:d1 OffsetTime]
  [_ t]
  [:time (h2x/literal (u.date/format-sql t))])

(defmethod sql.qp/->honeysql [:d1 ZonedDateTime]
  [driver t]
  (if (zero-time? t)
    (sql.qp/->honeysql driver (t/local-date t))
    [:datetime (h2x/literal (u.date/format-sql t))]))

(defmethod sql.qp/current-datetime-honeysql-form :d1
  [_]
  [:datetime (h2x/literal :now)])

(defmethod sql.qp/datetime-diff [:d1 :year]
  [driver _unit x y]
  (h2x// (sql.qp/datetime-diff driver :month x y) 12))

(defmethod sql.qp/datetime-diff [:d1 :quarter]
  [driver _unit x y]
  (h2x// (sql.qp/datetime-diff driver :month x y) 3))

(defmethod sql.qp/datetime-diff [:d1 :month]
  [driver _unit x y]
  (let [extract            (fn [unit x] (sql.qp/date driver unit x))
        year-diff          (h2x/- (extract :year y) (extract :year x))
        month-of-year-diff (h2x/- (extract :month-of-year y) (extract :month-of-year x))
        total-month-diff   (h2x/+ month-of-year-diff (h2x/* year-diff 12))]
    (h2x/+ total-month-diff
           ;; total-month-diff counts month boundaries not whole months, so we need to adjust
           ;; if x<y but x>y in the month calendar then subtract one month
           ;; if x>y but x<y in the month calendar then add one month
           [:case
            [:and [:< x y] [:> (extract :day-of-month x) (extract :day-of-month y)]]
            -1
            [:and [:> x y] [:< (extract :day-of-month x) (extract :day-of-month y)]]
            1
            :else 0])))

(defmethod sql.qp/datetime-diff [:d1 :week]
  [driver _unit x y]
  (h2x// (sql.qp/datetime-diff driver :day x y) 7))

(defmethod sql.qp/datetime-diff [:d1 :day]
  [_driver _unit x y]
  (h2x/->integer
   (h2x/- [:julianday y (h2x/literal "start of day")]
          [:julianday x (h2x/literal "start of day")])))

(defmethod sql.qp/datetime-diff [:d1 :hour]
  [driver _unit x y]
  (h2x// (sql.qp/datetime-diff driver :second x y) 3600))

(defmethod sql.qp/datetime-diff [:d1 :minute]
  [driver _unit x y]
  (h2x// (sql.qp/datetime-diff driver :second x y) 60))

(defmethod sql.qp/datetime-diff [:d1 :second]
  [_driver _unit x y]
  ;; strftime('%s', <timestring>) returns the unix time as an integer.
  (h2x/- (strftime "%s" y) (strftime "%s" x)))

(defmethod sql.qp/->integer :d1
  [driver value]
  (sql.qp/->integer-with-round driver value))
