(ns lupapalvelu.tiedonohjaus
  (:require [sade.http :as http]
            [sade.env :as env]
            [clojure.core.memoize :as memo]
            [taoensso.timbre :refer [trace debug debugf info infof warn warnf error fatal]]
            [lupapalvelu.organization :as o]
            [lupapalvelu.action :as action]
            [monger.operators :refer :all]
            [clj-time.coerce :as c]
            [clj-time.core :as t]
            [sade.util :as util]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.i18n :as i18n]))

(defn- build-url [& path-parts]
  (apply str (env/value :toj :host) path-parts))

(defn- get-tos-functions-from-toj [organization-id]
  (if (:permanent-archive-enabled (o/get-organization organization-id))
    (try
      (let [url (build-url "/tiedonohjaus/api/org/" organization-id "/asiat")
            response (http/get url {:as               :json
                                    :throw-exceptions false})]
        (if (= 200 (:status response))
          (:body response)
          []))
      (catch Exception _
        []))
    []))

(def available-tos-functions
  (memo/ttl get-tos-functions-from-toj
            :ttl/threshold 10000))

(defn tos-function-with-name [tos-function-code organization]
  (when (and tos-function-code organization)
    (->> (available-tos-functions (name organization))
         (filter #(= tos-function-code (:code %)))
         (first))))

(defn- get-metadata-for-document-from-toj [organization tos-function document-type]
  (if (and organization tos-function document-type)
    (try
      (let [doc-id (if (map? document-type) (str (name (:type-group document-type)) "." (name (:type-id document-type))) document-type)
            url (build-url "/tiedonohjaus/api/org/" organization "/asiat/" tos-function "/document/" doc-id)
            response (http/get url {:as               :json
                                    :throw-exceptions false})]
        (if (= 200 (:status response))
          (:body response)
          {}))
      (catch Exception _
        {}))
    {}))

(def metadata-for-document
  (memo/ttl get-metadata-for-document-from-toj
            :ttl/threshold 10000))

(defn- get-metadata-for-process-from-toj [organization tos-function]
  (if (and organization tos-function)
    (try
      (let [url (build-url "/tiedonohjaus/api/org/" organization "/asiat/" tos-function)
            response (http/get url {:as               :json
                                    :throw-exceptions false})]
        (if (= 200 (:status response))
          (:body response)
          {}))
      (catch Exception _
        {}))
    {}))

(def metadata-for-process
  (memo/ttl get-metadata-for-process-from-toj
            :ttl/threshold 10000))

(defn- paatospvm-plus-years [verdicts years]
  (when-let [paatos-ts (->> verdicts
                            (map (fn [{:keys [paatokset]}]
                                   (map (fn [pt] (map :paatospvm (:poytakirjat pt))) paatokset)))
                            (flatten)
                            (remove nil?)
                            (sort)
                            (last))]
    (-> (c/from-long (long paatos-ts))
        (t/plus (t/years years))
        (.toDate))))

(defn- retention-end-date [{{:keys [arkistointi pituus]} :sailytysaika} verdicts]
  (when (and (= (keyword "m\u00E4\u00E4r\u00E4ajan") (keyword arkistointi)) (seq verdicts))
    (paatospvm-plus-years verdicts pituus)))

(defn- security-end-date [{:keys [salassapitoaika julkisuusluokka]} verdicts]
  (when (and (#{:osittain-salassapidettava :salainen} (keyword julkisuusluokka)) salassapitoaika (seq verdicts))
    (paatospvm-plus-years verdicts salassapitoaika)))

(defn update-end-dates [metadata verdicts]
  (let [retention-end (retention-end-date metadata verdicts)
        security-end (security-end-date metadata verdicts)]
    (cond-> (-> (util/dissoc-in metadata [:sailytysaika :retention-period-end])
                (dissoc :security-period-end))
            retention-end (assoc-in [:sailytysaika :retention-period-end] retention-end)
            security-end (assoc :security-period-end security-end))))

(defn document-with-updated-metadata [{:keys [metadata] :as document} organization tos-function application & [type]]
  (if (#{:arkistoidaan :arkistoitu} (keyword (:tila metadata)))
    ; Do not update metadata for documents that are already archived
    document
    (let [document-type (or type (:type document))
          existing-tila (:tila metadata)
          existing-nakyvyys (:nakyvyys metadata)
          new-metadata (metadata-for-document organization tos-function document-type)
          processed-metadata (cond-> new-metadata
                                     existing-tila (assoc :tila (keyword existing-tila))
                                     true (update-end-dates (:verdicts application))
                                     (and (not (:nakyvyys new-metadata)) existing-nakyvyys) (assoc :nakyvyys existing-nakyvyys))]
      (assoc document :metadata processed-metadata))))

(defn- get-tos-toimenpide-for-application-state-from-toj [organization tos-function state]
  (if (and organization tos-function state)
    (try
      (let [url (build-url "/tiedonohjaus/api/org/" organization "/asiat/" tos-function "/toimenpide-for-state/" state)
            response (http/get url {:as               :json
                                    :throw-exceptions false})]
        (if (= 200 (:status response))
          (:body response)
          {}))
      (catch Exception _
        {}))
    {}))

(def toimenpide-for-state
  (memo/ttl get-tos-toimenpide-for-application-state-from-toj
            :ttl/threshold 10000))

(defn- full-name [{:keys [lastName firstName]}]
  (str lastName " " firstName))

(defn- get-documents-from-application [application]
  [{:type     :hakemus
    :category :document
    :ts       (:created application)
    :user     (:applicant application)}])

(defn- get-attachments-from-application [application]
  (reduce (fn [acc attachment]
            (if-let [versions (seq (:versions attachment))]
              (->> versions
                   (map (fn [ver]
                          {:type     (:type attachment)
                           :category :document
                           :version  (:version ver)
                           :ts       (:created ver)
                           :contents (:contents attachment)
                           :user     (full-name (:user ver))}))
                   (concat acc))
              acc))
          []
          (:attachments application)))

(defn- get-statement-requests-from-application [application]
  (map (fn [stm]
         {:text     (get-in stm [:person :text])
          :category :request-statement
          :ts       (:requested stm)
          :user     (str "" (:name stm))}) (:statements application)))

(defn- get-neighbour-requests-from-application [application]
  (map (fn [req] (let [status (first (filterv #(= "open" (name (:state %))) (:status req)))]
           {:text     (get-in req [:owner :name])
            :category :request-neighbor
            :ts       (:created status)
            :user     (full-name (:user status))})) (:neighbors application)))

(defn- get-review-requests-from-application [application]
  (reduce (fn [acc task]
            (if (= "task-katselmus" (name (get-in task [:schema-info :name])))
              (conj acc {:text     (:taskname task)
                         :category :request-review
                         :ts       (:created task)
                         :user     (full-name (:assignee task))})
              acc)) [] (:tasks application)))


(defn- get-held-reviews-from-application [application]
  (reduce (fn [acc task]
              (if-let [held (get-in task [:data :katselmus :pitoPvm :modified])]
              (conj acc {:text     (:taskname task)
                         :category :review
                         :ts       held
                         :user     (full-name (:assignee task))})
              acc)) [] (:tasks application)))

(defn- tos-function-changes-from-history [history lang]
  (->> (filter :tosFunction history)
       (map (fn [{:keys [tosFunction correction user] :as item}]
              (merge item {:text (str (:code tosFunction) " " (:name tosFunction)
                                      (when correction (str ", " (i18n/localize lang "tos.function.fix.reason") ": " correction)))
                           :category (if correction :tos-function-correction :tos-function-change)
                           :user (full-name user)})))))

(defn generate-case-file-data [{:keys [history organization] :as application} lang]
  (let [documents (get-documents-from-application application)
        attachments (get-attachments-from-application application)
        statement-reqs (get-statement-requests-from-application application)
        neighbors-reqs (get-neighbour-requests-from-application application)
        review-reqs (get-review-requests-from-application application)
        reviews-held (get-held-reviews-from-application application)
        tos-fn-changes (tos-function-changes-from-history history lang)
        all-docs (sort-by :ts (concat tos-fn-changes documents attachments statement-reqs neighbors-reqs review-reqs reviews-held))
        state-changes (filter :state history)]
    (map (fn [[{:keys [state ts user]} next]]
           (let [api-response (toimenpide-for-state organization (:tosFunction application) state)
                 action-name (cond
                               (:name api-response) (:name api-response)
                               (= state "complementNeeded") (i18n/localize lang "caseFile.complementNeeded")
                               :else (i18n/localize lang "caseFile.stateNotSet"))]
             {:action    action-name
              :start     ts
              :user      (full-name user)
              :documents (filter (fn [{doc-ts :ts}]
                                   (and (>= doc-ts ts) (or (nil? next) (< doc-ts (:ts next)))))
                                 all-docs)}))
         (partition 2 1 nil state-changes))))

(defn- document-metadata-final-state [metadata verdicts]
  (-> (assoc metadata :tila :valmis)
      (update-end-dates verdicts)))

(defn mark-attachment-final! [{:keys [attachments verdicts] :as application} now attachment-or-id]
  (let [{:keys [id metadata]} (if (map? attachment-or-id)
                                attachment-or-id
                                (first (filter #(= (:id %) attachment-or-id) attachments)))]
    (when (seq metadata)
      (let [new-metadata (document-metadata-final-state metadata verdicts)]
        (when-not (= metadata new-metadata)
          (action/update-application
            (action/application->command application)
            {:attachments.id id}
            {$set {:modified               now
                   :attachments.$.metadata new-metadata}}))))))

(defn mark-app-and-attachments-final! [app-id modified-ts]
  (let [{:keys [metadata attachments verdicts processMetadata] :as application} (domain/get-application-no-access-checking app-id)]
    (when (seq metadata)
      (let [new-metadata (document-metadata-final-state metadata verdicts)
            new-process-metadata (update-end-dates processMetadata verdicts)]
        (when-not (and (= metadata new-metadata) (= processMetadata new-process-metadata))
          (action/update-application
            (action/application->command application)
            {$set {:modified modified-ts
                   :metadata new-metadata
                   :processMetadata new-process-metadata}}))
        (doseq [attachment attachments]
          (mark-attachment-final! application modified-ts attachment))))))

(defn- retention-key [{{:keys [pituus arkistointi]} :sailytysaika}]
  (let [kw-a (keyword arkistointi)]
    (cond
      (= :ikuisesti kw-a) Integer/MAX_VALUE
      (= :toistaiseksi kw-a) (- Integer/MAX_VALUE 1)
      (= (keyword "m\u00E4\u00E4r\u00E4ajan") kw-a) pituus)))

(defn- comp-sa [sailytysaika]
  (dissoc sailytysaika :perustelu))

(defn calculate-process-metadata [original-process-metadata application-metadata attachments]
  (let [metadatas (conj (map :metadata attachments) application-metadata)
        {max-retention :sailytysaika} (last (sort-by retention-key metadatas))]
    (if (and max-retention (not= (comp-sa (:sailytysaika original-process-metadata)) (comp-sa max-retention)))
      (assoc original-process-metadata :sailytysaika max-retention)
      original-process-metadata)))

(defn update-process-retention-period
  "Update retention period of the process report to match the longest retention time of any document
   as per SAHKE2 operative system sertification requirement 6.3"
  [app-id modified-ts]
  (let [{:keys [metadata attachments processMetadata] :as application} (domain/get-application-no-access-checking app-id)
        new-process-md (calculate-process-metadata processMetadata metadata attachments)]
    (when-not (= processMetadata new-process-md)
      (action/update-application
        (action/application->command application)
        {$set {:modified modified-ts
               :processMetadata new-process-md}}))))
