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

(defn- database-url
  [{:keys [account-id database-id] :as details}]
  (format "%s/accounts/%s/d1/database/%s" (api-base-url details) (str/trim account-id) (str/trim database-id)))

(defn- raw-query-url
  [details]
  (str (database-url details) "/raw"))

(defn- request-options
  [{:keys [api-token]}]
  {:headers          {"Authorization" (str "Bearer " (str/trim api-token))}
   :accept           :json
   :socket-timeout   (* 5 60 1000)
   :conn-timeout     10000
   :throw-exceptions false})

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

(defn- parse-response
  "Decode a Cloudflare API response envelope, throwing ex-info with a user-facing message unless it is a successful
  (HTTP 200, `success: true`) response."
  [{:keys [status body]}]
  (let [parsed (try
                 (json/decode+kw body)
                 (catch Throwable _ nil))]
    (when-not (and (= status 200) (:success parsed))
      (throw (ex-info (error-message status parsed body)
                      {:type   driver-api/qp.error-type.db
                       :status status
                       :errors (:errors parsed)})))
    parsed))

(defn database-info
  "Fetch Cloudflare's metadata for the D1 database described by connection `details`
  (`GET /accounts/:account/d1/database/:database`).

  Returns the `:result` map, e.g. {:name \"my-db\", :uuid \"...\", :version \"production\", :num_tables 3,
  :file_size 12288, :created_at \"...\"}. Throws ex-info with a user-facing message on HTTP errors."
  [details]
  (:result (parse-response (http/get (database-url details) (request-options details)))))

(defn execute!
  "Run `sql` with `params` against the D1 database described by connection `details`.

  Returns {:columns [\"col\" ...], :rows [[val ...] ...]} for the (first) statement's result set. Throws ex-info
  with a user-facing message on HTTP or SQL errors."
  [details sql params]
  (let [body                             (json/encode {:sql    sql
                                                       :params (mapv coerce-param params)})
        parsed                           (parse-response (http/post (raw-query-url details)
                                                                    (assoc (request-options details)
                                                                           :content-type :json
                                                                           :body         body)))
        {:keys [results success error]} (first (:result parsed))]
    (when (false? success)
      (throw (ex-info (str (or error (tru "Cloudflare D1 query failed")))
                      {:type driver-api/qp.error-type.db})))
    {:columns (mapv str (:columns results))
     :rows    (mapv vec (:rows results))}))
