(ns metabase.driver.d1.client
  "HTTP client for the Cloudflare D1 REST API.

  Uses the `/raw` endpoint rather than `/query`: it returns column names and row value-arrays separately (in
  selection order, even for zero-row results), so duplicate column names in joins don't collapse into one JSON key."
  (:require
   [clj-http.client :as http]
   [clojure.string :as str]
   [metabase.driver-api.core :as driver-api]
   [metabase.util.date-2 :as u.date]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.json :as json])
  (:import
   (java.time.temporal Temporal)))

(set! *warn-on-reflection* true)

(def ^:private default-api-base-url "https://api.cloudflare.com/client/v4")

(defn- api-base-url
  [{:keys [api-base-url]}]
  (let [url (some-> api-base-url str/trim)]
    (if (seq url)
      (str/replace url #"/+$" "")
      default-api-base-url)))

(defn- raw-query-url
  [{:keys [account-id database-id] :as details}]
  (format "%s/accounts/%s/d1/database/%s/raw" (api-base-url details) (str/trim account-id) (str/trim database-id)))

(defn- coerce-param
  "Make a query parameter JSON- and SQLite-friendly. D1 bindings accept strings, numbers, and null."
  [param]
  (cond
    (instance? Temporal param) (u.date/format-sql param)
    (boolean? param)           (if param 1 0)
    (keyword? param)           (name param)
    :else                      param))

(defn- error-message
  [status parsed body]
  (or (some->> (:errors parsed) (keep :message) seq (str/join "; "))
      (tru "Cloudflare D1 request failed with status {0}: {1}" status (pr-str body))))

(defn execute!
  "Run `sql` with `params` against the D1 database described by connection `details`.

  Returns {:columns [\"col\" ...], :rows [[val ...] ...]} for the (first) statement's result set. Throws ex-info
  with a user-facing message on HTTP or SQL errors."
  [{:keys [api-token] :as details} sql params]
  (let [{:keys [status body]} (http/post (raw-query-url details)
                                         {:headers          {"Authorization" (str "Bearer " (str/trim api-token))}
                                          :content-type     :json
                                          :accept           :json
                                          :socket-timeout   (* 5 60 1000)
                                          :conn-timeout     10000
                                          :body             (json/encode {:sql    sql
                                                                          :params (mapv coerce-param params)})
                                          :throw-exceptions false})
        parsed (try
                 (json/decode+kw body)
                 (catch Throwable _ nil))]
    (when-not (and (= status 200) (:success parsed))
      (throw (ex-info (error-message status parsed body)
                      {:type   driver-api/qp.error-type.db
                       :status status
                       :errors (:errors parsed)})))
    (let [{:keys [results success error]} (first (:result parsed))]
      (when (false? success)
        (throw (ex-info (str (or error (tru "Cloudflare D1 query failed")))
                        {:type driver-api/qp.error-type.db})))
      {:columns (mapv str (:columns results))
       :rows    (mapv vec (:rows results))})))
