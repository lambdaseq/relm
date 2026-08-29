(ns com.lambdaseq.relm.http
  "HTTP side-effect module for Relm built on the Fetch API.

  Provides `core/fx` side effects for making asynchronous HTTP requests (`::fetch`)
  and aborting active requests (`::abort`). Handles JSON serialization, query parameter
  encoding, response header parsing, response body decoding based on Content-Type,
  request timeouts, and error handling with automated message dispatching back into Relm."
  (:require [com.lambdaseq.relm.core :as core]
            [clojure.string :as string]
            [goog.object :as obj]))

;; -----------------------------------------------------------------------------
;; Default Body Readers
;; -----------------------------------------------------------------------------

(def default-json-reader
  "Default reader configuration for JSON response payloads.
  Converts parsed JavaScript objects into Clojure maps with keywordized keys."
  {:reader-kw :json
   :reader-fn #(js->clj % :keywordize-keys true)})

(def default-text-reader
  "Default fallback reader configuration for text/plain response payloads.
  Returns the raw string response body unmodified."
  {:reader-kw :text
   :reader-fn identity})

(def default-response-content-types
  "Default mapping of Content-Type header patterns to response body reader keywords."
  {"application/json" :json
   #"^application/(.+\+)?json" :json
   "text/plain" :text})

;; -----------------------------------------------------------------------------
;; Utilities
;; -----------------------------------------------------------------------------

(defn ->seq
  "Ensures `x` is wrapped in a sequential collection. Returns `x` if sequential, otherwise `[x]`."
  [x]
  (if (sequential? x) x [x]))

(defn ->str
  "Converts `x` into a String. Returns the name if keyword or symbol, otherwise `(str x)`."
  [x]
  (if (or (symbol? x)
          (keyword? x))
    (name x)
    (str x)))

(defn encode-kv
  "URI-encodes a key-value pair as `key=value` string."
  [k v]
  (str (js/encodeURIComponent (->str k)) "="
       (js/encodeURIComponent (->str v))))

(defn params->str
  "Encodes a map of query parameters into a URL query string prefixed with `?`.
  Supports vector values as repeated query parameters."
  [params]
  (if (zero? (count params))
    ""
    (let [reducer (fn [ret k v]
                    (conj ret (if (vector? v)
                                (string/join "&" (map (fn [x] (encode-kv k x)) v))
                                (encode-kv k v))))
          pairs   (reduce-kv reducer [] params)]
      (str "?" (string/join "&" pairs)))))

(defn headers->js
  "Converts a ClojureScript map of headers into a JavaScript `Headers` instance."
  [headers]
  (reduce-kv
    (fn [js-headers header-name header-value]
      (doto js-headers
        (.append (->str header-name)
                 (->str header-value))))
    (js/Headers.)
    headers))

(defn request->js-init
  "Builds the options configuration object (JavaScript object) passed as the second argument to `js/fetch`."
  [{:keys [method headers request-content-type body mode credentials cache redirect referrer integrity]}
   abort-signal]
  (let [mode        (or mode "cors")
        credentials (or credentials "same-origin")
        redirect    (or redirect "follow")
        body'       (if (= :json request-content-type)
                      (js/JSON.stringify (clj->js body))
                      body)
        headers'    (if (= :json request-content-type)
                      (merge {"Content-Type" "application/json"}
                             headers)
                      headers)]
    (doto
      #js {;; AbortSignal provided either externally or by our internal AbortController
           :signal      abort-signal

           ;; HTTP method (GET, POST, PUT, DELETE, etc.)
           :method      (->str method)

           ;; Request mode: cors, no-cors, same-origin, navigate
           :mode        (->str mode)

           ;; Request credentials: omit, same-origin, include
           :credentials (->str credentials)

           ;; Redirect handling: follow, error, manual
           :redirect    (->str redirect)}

      ;; Headers
      (cond-> headers' (obj/set "headers" (headers->js headers')))

      ;; Request body
      (cond-> body (obj/set "body" body'))

      ;; Cache mode: default, no-store, reload, no-cache, force-cache, only-if-cached
      (cond-> cache (obj/set "cache" (->str cache)))

      ;; Referrer policy
      (cond-> referrer (obj/set "referrer" (->str referrer)))

      ;; Sub-resource integrity string
      (cond-> integrity (obj/set "integrity" (->str integrity))))))

(defn js-headers->clj
  "Converts a JavaScript `Headers` object into a ClojureScript map with keywordized keys."
  [js-headers]
  (reduce
    (fn [headers [header-name header-value]]
      (assoc headers (keyword header-name) header-value))
    {}
    (es6-iterator-seq (.entries js-headers))))

(defn js-response->clj
  "Converts a JavaScript `Response` object into a ClojureScript map containing response metadata."
  [^js js-response]
  {:url         (.-url js-response)
   :ok?         (.-ok js-response)
   :redirected? (.-redirected js-response)
   :status      (.-status js-response)
   :status-text (.-statusText js-response)
   :type        (.-type js-response)
   :final-uri?  (.-useFinalURL js-response)
   :headers     (js-headers->clj (.-headers js-response))})

(defn ->reader
  "Normalizes reader configurations, resolving keyword shortcuts like `:json` to reader maps."
  [reader-or-kw]
  (cond
    ;; default json body reader
    (and (keyword? reader-or-kw)
         (#{:json} reader-or-kw)) default-json-reader

    ;; identity wrap reader
    (keyword? reader-or-kw) {:reader-kw reader-or-kw
                             :reader-fn identity}
    ;; user provided reader
    :else reader-or-kw))

(defn response->reader
  "Selects the appropriate response body reader based on the response's `Content-Type` header.
  Returns a reader map with keys:
  - `:reader-kw` Method to invoke on the JS Response (`:json`, `:text`, `:blob`, `:array-buffer`, `:form-data`)
  - `:reader-fn` Transformation function applied to the decoded body"
  [{:keys [response-content-types response-content-type]} response]
  (if response-content-type
    (->reader response-content-type)
    (let [content-type (get-in response [:headers :content-type] "text/plain")
          reader (reduce-kv
                   (fn [ret pattern reader]
                     (if (or (and (string? pattern)
                                  (or (= content-type pattern)
                                      (string/starts-with? content-type pattern)))
                             (and (regexp? pattern)
                                  (re-find pattern content-type)))
                       (reduced reader)
                       ret))
                   default-text-reader
                   (merge default-response-content-types response-content-types))]
      (->reader reader))))

(defn timeout-race
  "Wraps a JavaScript `Promise` in a timeout race that rejects with `:timeout` after `timeout` milliseconds."
  [js-promise timeout]
  (if timeout
    (.race js/Promise
           #js [js-promise
                (js/Promise.
                  (fn [_ reject]
                    (js/setTimeout #(reject :timeout) timeout)))])
    js-promise))

;; -----------------------------------------------------------------------------
;; Effects and Response Handlers
;; -----------------------------------------------------------------------------

(def request-id->js-abort-controller
  "Registry atom tracking active JavaScript AbortController instances indexed by `request-id`."
  (atom {}))

(defn- dispatch-event!
  "Dispatches an event message to `core/dispatch!`, appending `payload` to the event vector."
  [dom-event event payload]
  (when (and (vector? event)
             (seq event)
             (let [first-kw (first event)]
               (not (or (= first-kw ::fetch-no-on-success)
                        (= first-kw ::fetch-no-on-failure)))))
    (core/dispatch! dom-event (conj event payload))))

(defn body-success-handler
  "Dispatches success event after response body has been read and processed."
  [dom-event
   {:keys [request-id on-success on-failure]
    :or   {on-success [::fetch-no-on-success]
           on-failure [::fetch-no-on-failure]}}
   response
   {:keys [reader-kw reader-fn] :as _reader}
   js-body]
  (swap! request-id->js-abort-controller dissoc request-id)
  (let [body (reader-fn js-body)
        response (cond-> response
                   body
                   (assoc :body body
                          :reader reader-kw)
                   (not (:ok? response))
                   (assoc :problem :server))]
    (if (:ok? response)
      (dispatch-event! dom-event on-success response)
      (dispatch-event! dom-event on-failure response))))

(defn body-problem-handler
  "Dispatches failure event when decoding or parsing the response body fails."
  [dom-event
   {:keys [request-id on-failure]
    :or   {on-failure [::fetch-no-on-failure]}}
   response
   {:keys [reader-kw] :as _reader}
   js-error]
  (swap! request-id->js-abort-controller dissoc request-id)
  (let [problem-message (obj/get js-error "message")
        response        (assoc response
                          :problem         :body
                          :reader          reader-kw
                          :problem-message problem-message)]
    (dispatch-event! dom-event on-failure response)))

(defn response-success-handler
  "Reads the JS `Response` stream according to the resolved reader and forwards to body handlers."
  [dom-event request js-response]
  (let [response                       (js-response->clj js-response)
        {:keys [reader-kw] :as reader} (response->reader request response)]
    (if (or (= "0" (get-in response [:headers :content-length]))
            (= 204 (:status response)))
      (body-success-handler dom-event
                            request
                            response
                            {:reader-fn identity}
                            nil)
      (-> (case reader-kw
            :json (.json js-response)
            :form-data (.formData js-response)
            :blob (.blob js-response)
            :array-buffer (.arrayBuffer js-response)
            :text (.text js-response))
          (.then (partial body-success-handler dom-event request response reader))
          (.catch (partial body-problem-handler dom-event request response reader))))))

(defn js-error->problem
  "Maps JavaScript fetch errors/exceptions to relm problem keywords (`:timeout`, `:aborted`, `:fetch`)."
  [js-error]
  (cond
    (= :timeout js-error)              :timeout
    (= "AbortError" (.-name js-error)) :aborted
    :else                              :fetch))

(defn response-problem-handler
  "Dispatches failure event when network transport, timeout, or abort errors occur."
  [dom-event
   {:keys [request-id on-failure]
    :or   {on-failure [::fetch-no-on-failure]}}
   js-error]
  (swap! request-id->js-abort-controller dissoc request-id)
  (let [problem         (js-error->problem js-error)
        problem-message (if (= :timeout js-error) "Fetch timed out" (obj/get js-error "message"))
        response        {:problem         problem
                         :problem-message problem-message}]
    (dispatch-event! dom-event on-failure response)))

(defn fetch
  "Initializes and executes an HTTP Fetch request.
  Options map:
  - `:url`                  URL string to fetch
  - `:method`               HTTP method keyword/string (`:get`, `:post`, `:put`, etc.)
  - `:params`               Query parameters map
  - `:headers`              HTTP headers map
  - `:body`                 Request body payload
  - `:request-content-type` Content type shortcut (e.g. `:json`)
  - `:timeout`              Request timeout in milliseconds
  - `:request-id`           Unique request ID keyword (auto-generated if omitted)
  - `:on-request-id`        Callback message vector receiving the generated `request-id` (`[::msg ...]`)
  - `:on-success`           Message vector dispatched on successful response `[::msg ...]`
  - `:on-failure`           Message vector dispatched on error `[::msg ...]`"
  [dom-event
   {:keys [url timeout params request-id on-request-id abort-signal] :as request
    :or   {request-id (keyword (gensym "fetch-fx-"))}}]
  (when on-request-id
    (dispatch-event! dom-event on-request-id request-id))
  (let [request'            (assoc request :request-id request-id)
        url'                (str url (params->str params))
        js-abort-controller (when-not abort-signal (js/AbortController.))
        abort-signal'       (or abort-signal (.-signal js-abort-controller))]
    (some->> js-abort-controller
             (swap! request-id->js-abort-controller
                    assoc
                    request-id))
    (-> (timeout-race (js/fetch url' (request->js-init request' abort-signal')) timeout)
        (.then (partial response-success-handler dom-event request'))
        (.catch (partial response-problem-handler dom-event request')))))

(defn fetch-fx
  "Executes one or more fetch effect specifications."
  [event effect]
  (let [seq-of-effects (->seq effect)]
    (doseq [effect seq-of-effects]
      (let [with-defaults effect]
        (fetch event with-defaults)))))

;; Effect handler for executing HTTP fetch requests.
;; Effect format: `[::fetch request-map]` or `[::fetch [req-1 req-2 ...]]`
(defmethod core/fx ::fetch
  [event [_ effect]]
  (fetch-fx event effect))

(defn abort
  "Aborts an in-flight HTTP request identified by `:request-id`."
  [{:keys [request-id]}]
  (let [js-abort-controller (get @request-id->js-abort-controller request-id)]
    (when js-abort-controller
      (swap! request-id->js-abort-controller dissoc request-id)
      (.abort js-abort-controller))))

(defn abort-fx
  "Executes one or more abort effect specifications."
  [effect]
  (let [seq-of-effects (->seq effect)]
    (doseq [effect seq-of-effects]
      (abort effect))))

;; Effect handler for aborting active HTTP fetch requests.
;; Effect format: `[::abort {:request-id req-id}]`
(defmethod core/fx ::abort
  [_ [_ effect]]
  (abort-fx effect))