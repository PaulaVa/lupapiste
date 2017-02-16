(ns lupapalvelu.archiving
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [taoensso.timbre :refer [info error warn]]
            [ring.util.codec :as codec]
            [clj-time.coerce :as c]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [cheshire.core :as json]
            [monger.operators :refer :all]
            [sade.env :as env]
            [sade.files :as files]
            [sade.http :as http]
            [sade.strings :as ss]
            [lupapalvelu.tiedonohjaus :as tiedonohjaus]
            [lupapalvelu.pdf.pdf-export :as pdf-export]
            [lupapalvelu.attachment :as att]
            [lupapalvelu.action :as action]
            [lupapalvelu.application-meta-fields :as amf]
            [lupapalvelu.pdf.libreoffice-conversion-client :as libre]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.states :as states]
            [lupapalvelu.foreman :as foreman]
            [lupapalvelu.domain :as domain])
  (:import [java.util.concurrent ThreadFactory Executors]
           [java.io InputStream]))

(defn thread-factory []
  (let [security-manager (System/getSecurityManager)
        thread-group (if security-manager
                       (.getThreadGroup security-manager)
                       (.getThreadGroup (Thread/currentThread)))]
    (reify
      ThreadFactory
      (newThread [this runnable]
        (doto (Thread. thread-group runnable "archive-upload-worker")
          (.setDaemon true)
          (.setPriority Thread/NORM_PRIORITY))))))

(defonce upload-threadpool (Executors/newFixedThreadPool 1 (thread-factory)))

(defn- upload-file [id is-or-file content-type metadata]
  (let [host (env/value :arkisto :host)
        app-id (env/value :arkisto :app-id)
        app-key (env/value :arkisto :app-key)
        encoded-id (codec/url-encode id)
        url (str host "/documents/" encoded-id)
        result (http/put url {:basic-auth [app-id app-key]
                              :throw-exceptions false
                              :quiet true
                              :multipart  [{:name      "metadata"
                                            :mime-type "application/json"
                                            :encoding  "UTF-8"
                                            :content   (json/generate-string metadata)}
                                           {:name      "file"
                                            :content   is-or-file
                                            :mime-type content-type}]})]
    (when (instance? InputStream is-or-file)
      (try
        (.close is-or-file)
        (catch Exception _)))
    result))

(defn- set-attachment-state [next-state application now id]
  (action/update-application
    (action/application->command application)
    {:attachments.id id}
    {$set {:modified now
           :attachments.$.modified now
           :attachments.$.metadata.tila next-state
           :attachments.$.readOnly (contains? #{:arkistoidaan :arkistoitu} next-state)}}))

(defn- set-application-state [next-state application now _]
  (action/update-application
    (action/application->command application)
    {$set {:modified now
           :metadata.tila next-state}}))

(defn- set-process-state [next-state application now _]
  (action/update-application
    (action/application->command application)
    {$set {:modified now
           :processMetadata.tila next-state}}))

(defn- metadata-query [md-key]
  (let [tila-key (str md-key ".tila")
        arkistointi-key (str md-key ".sailytysaika.arkistointi")]
    {tila-key {$ne :arkistoitu}
     arkistointi-key {$ne :ei
                      $exists true}}))

(defn- mark-application-archived-if-done [{:keys [id] :as application} now]
  ; If these queries return 0 results, we mark the corresponding phase as archived
  (let [attachment-or-app-md (metadata-query "metadata")
        pre-verdict-query {:_id id
                           ; Look for pre-verdict attachments that have versions, are not yet archived, but need to be
                           $or  [{:archived.application {$ne nil}}
                                 {:attachments {$elemMatch (merge {:applicationState {$nin [states/post-verdict-states]}
                                                                   :versions         {$gt []}}
                                                                  attachment-or-app-md)}}
                                 ; Check if the application itself is not yet archived, but needs to be
                                 attachment-or-app-md]}
        post-verdict-query {:_id id
                            ; Look for any attachments that have versions, are not yet arcvhived, but need to be
                            $or  [{:archived.completed {$ne nil}}
                                  {:attachments {$elemMatch (merge {:versions {$gt []}}
                                                                   attachment-or-app-md)}}
                                  ; Check if the application itself is not yet archived, but needs to be
                                  attachment-or-app-md
                                  ; Check if the case file is not yet archived, but needs to be
                                  (metadata-query "processMetadata")
                                  ; Check if the application is not in a final state
                                  {:state {$nin [:closed :extinct :foremanVerdictGiven :acknowledged]}}]}]

    (when (zero? (mongo/count :applications pre-verdict-query))
      (action/update-application
        (action/application->command application)
        {$set {:archived.application now}}))

    (when (zero? (mongo/count :applications post-verdict-query))
      (action/update-application
        (action/application->command application)
        {$set {:archived.completed now}}))))

(defn- upload-and-set-state [id is-or-file content-type metadata {app-id :id :as application} now state-update-fn]
  (info "Trying to archive attachment id" id "from application" app-id)
  (if-not (#{:arkistoidaan :arkistoitu} (:tila metadata))
    (do (state-update-fn :arkistoidaan application now id)
        (.submit
          upload-threadpool
          (fn []
            (let [{:keys [status body]} (upload-file id is-or-file content-type (assoc metadata :tila :arkistoitu))]
              (cond
                (= 200 status)
                (do
                  (state-update-fn :arkistoitu application now id)
                  (info "Archived attachment id" id "from application" app-id)
                  (mark-application-archived-if-done application now))

                (and (= status 409) (string/includes? body "already exists"))
                (do
                  (warn "Onkalo response indicates that" id "is already in archive. Updating state to match.")
                  (state-update-fn :arkistoitu application now id)
                  (mark-application-archived-if-done application now))

                :else
                (do
                  (error "Failed to archive attachment id" id "from application" app-id "status:" status "message:" body)
                  (state-update-fn :valmis application now id)))))))
    (warn "Tried to archive attachment id" id "from application" app-id "again while it is still marked unfinished")))

(defn- find-op [{:keys [primaryOperation secondaryOperations]} op-ids]
  (cond->> (concat [primaryOperation] secondaryOperations)
           (seq op-ids) (filter (comp (set op-ids) :id))
           true (map :name)
           true (distinct)))

(defn- ->iso-8601-date [date]
  (f/unparse (f/with-zone (:date-time-no-ms f/formatters) (t/time-zone-for-id "Europe/Helsinki")) date))

(defn- ts->iso-8601-date [ts]
  (when (number? ts)
    (->iso-8601-date (c/from-long (long ts)))))

(defn- get-verdict-date [{:keys [verdicts]} type]
  (let [ts (->> verdicts
                (map (fn [{:keys [paatokset]}]
                       (->> (map #(get-in % [:paivamaarat type]) paatokset)
                            (remove nil?)
                            (first))))
                (remove nil?)
                (first))]
    (ts->iso-8601-date ts)))

(defn- get-from-verdict-minutes [{:keys [verdicts]} key]
  (->> verdicts
       (map (fn [{:keys [paatokset]}]
              (map (fn [pt] (map key (:poytakirjat pt))) paatokset)))
       (flatten)
       (remove nil?)
       (first)))

(defn- get-paatospvm [{:keys [verdicts]}]
  (let [ts (->> verdicts
                (map (fn [{:keys [paatokset]}]
                       (map (fn [pt] (map :paatospvm (:poytakirjat pt))) paatokset)))
                (flatten)
                (remove nil?)
                (sort)
                (last))]
    (ts->iso-8601-date ts)))

(defn- get-usages [{:keys [documents]} op-ids]
  (let [op-docs (remove #(nil? (get-in % [:schema-info :op :id])) documents)
        id-to-usage (into {} (map (fn [d] {(get-in d [:schema-info :op :id])
                                           (get-in d [:data :kaytto :kayttotarkoitus :value])}) op-docs))]
    (->> (if (seq op-ids)
           (map id-to-usage op-ids)
           (vals id-to-usage))
         (remove nil?)
         (distinct))))

(defn- get-building-ids [bldg-key {:keys [buildings]} op-ids]
  ;; Only some building lists contain operation ids at all
  (->> (if-let [filtered-bldgs (seq (filter (comp (set op-ids) :operationId) buildings))]
         filtered-bldgs
         buildings)
       (map bldg-key)
       (remove nil?)))

(defn- make-version-number [{{{:keys [major minor]} :version} :latestVersion}]
  (str major "." minor))

(defn- make-attachment-type [{{:keys [type-group type-id]} :type}]
  (str type-group "." type-id))

(defn- person-name [person-data]
  (ss/trim (str (get-in person-data [:henkilotiedot :sukunimi :value]) \space (get-in person-data [:henkilotiedot :etunimi :value]))))

(defn- foremen [application]
  (if (empty? (:foreman application))
    (let [foreman-applications (foreman/get-linked-foreman-applications (:id application))
          foreman-documents (mapv foreman/get-foreman-documents foreman-applications)
          foremen (mapv (fn [document] (person-name (:data document))) foreman-documents)]
      (apply str (interpose ", " foremen)))
    (:foreman application)))

(defn- tyomaasta-vastaava [application]
  (when-let [document (domain/get-document-by-name application "tyomaastaVastaava")]
    (if (empty? (get-in document [:data :henkilo :henkilotiedot :sukunimi :value]))
      (get-in document [:data :yritys :yritysnimi :value])
      (person-name (get-in document [:data :henkilo])))))

(defn- generate-archive-metadata
  [{:keys [id propertyId _applicantIndex address organization municipality location location-wgs84] :as application}
   user
   & [attachment]]
  (let [s2-metadata (or (:metadata attachment) (:metadata application))
        base-metadata {:type                  (if attachment (make-attachment-type attachment) :hakemus)
                       :applicationId         id
                       :buildingIds           (get-building-ids :localId application (att/get-operation-ids attachment))
                       :nationalBuildingIds   (get-building-ids :nationalId application (att/get-operation-ids attachment))
                       :propertyId            propertyId
                       :applicants            _applicantIndex
                       :operations            (find-op application (att/get-operation-ids attachment))
                       :tosFunction           (first (filter #(= (:tosFunction application) (:code %)) (tiedonohjaus/available-tos-functions (:organization application))))
                       :address               address
                       :organization          organization
                       :municipality          municipality
                       :location-etrs-tm35fin location
                       :location-wgs84        location-wgs84
                       :kuntalupatunnukset    (remove nil? (map :kuntalupatunnus (:verdicts application)))
                       :lupapvm               (or (get-verdict-date application :lainvoimainen)
                                                  (get-paatospvm application))
                       :paatospvm             (get-paatospvm application)
                       :paatoksentekija       (get-from-verdict-minutes application :paatoksentekija)
                       :tiedostonimi          (get-in attachment [:latestVersion :filename] (str id ".pdf"))
                       :kasittelija           (select-keys (:authority application) [:username :firstName :lastName])
                       :arkistoija            (select-keys user [:username :firstName :lastName])
                       :kayttotarkoitukset    (get-usages application (att/get-operation-ids attachment))
                       :kieli                 "fi"
                       :versio                (if attachment (make-version-number attachment) "1.0")
                       :suunnittelijat        (:_designerIndex (amf/designers-index application))
                       :foremen               (foremen application)}]
    (cond-> base-metadata
            (:contents attachment) (conj {:contents (:contents attachment)})
            (:size attachment) (conj {:size (:size attachment)})
            (:scale attachment) (conj {:scale (:scale attachment)})
            (tyomaasta-vastaava application) (conj {:tyomaasta-vastaava (tyomaasta-vastaava application)})
            (:closed application) (conj {:closed (ts->iso-8601-date (:closed application))})
            (seq (map :geometry-wgs84 (:drawings application))) (conj {:drawing-wgs84 (mapv :geometry-wgs84 (:drawings application))})
            true (merge s2-metadata))))

(defn send-to-archive [{:keys [user created] {:keys [attachments id] :as application} :application} attachment-ids document-ids]
  (if (or (get-paatospvm application) (foreman/foreman-app? application))
    (let [selected-attachments (filter (fn [{:keys [id latestVersion metadata]}]
                                         (and (attachment-ids id) (:archivable latestVersion) (seq metadata)))
                                       attachments)
          application-archive-id (str id "-application")
          case-file-archive-id (str id "-case-file")
          case-file-xml-id     (str case-file-archive-id "-xml")]
      (when (document-ids application-archive-id)
        (let [application-file-stream (pdf-export/generate-application-pdfa application :fi)
              metadata (generate-archive-metadata application user)]
          (upload-and-set-state application-archive-id application-file-stream "application/pdf" metadata application created set-application-state)))
      (when (document-ids case-file-archive-id)
        (files/with-temp-file libre-file
          (let [pdf-is (libre/generate-casefile-pdfa application :fi libre-file)
                case-file-xml (tiedonohjaus/xml-case-file application :fi)
                xml-is (-> (.getBytes case-file-xml "UTF-8") io/input-stream)
                metadata (-> (generate-archive-metadata application user)
                             (assoc :type :case-file :tiedostonimi (str case-file-archive-id ".pdf")))
                xml-metadata (assoc metadata :tiedostonimi (str case-file-archive-id ".xml"))]
            (upload-and-set-state case-file-archive-id pdf-is "application/pdf" metadata application created set-process-state)
            (upload-and-set-state case-file-xml-id xml-is "text/xml" xml-metadata application created set-process-state))))
      (doseq [attachment selected-attachments]
        (let [{:keys [content contentType]} (att/get-attachment-file! (get-in attachment [:latestVersion :fileId]))
              metadata (generate-archive-metadata application user attachment)]
          (upload-and-set-state (:id attachment) (content) contentType metadata application created set-attachment-state))))
    {:error :error.invalid-metadata-for-archive}))

(defn mark-application-archived [application now archived-ts-key]
  (action/update-application
    (action/application->command application)
    {$set {(str "archived." (name archived-ts-key)) now}}))
