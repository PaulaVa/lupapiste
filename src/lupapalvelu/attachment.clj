(ns lupapalvelu.attachment
  (:require [taoensso.timbre :as timbre :refer [trace debug debugf info infof warn warnf error errorf fatal]]
            [clojure.java.io :as io]
            [monger.operators :refer :all]
            [sade.util :as util]
            [sade.env :as env]
            [sade.strings :as ss]
            [sade.core :refer :all]
            [lupapalvelu.action :refer [update-application application->command]]
            [lupapalvelu.domain :refer [get-application-as get-application-no-access-checking]]
            [lupapalvelu.states :as states]
            [lupapalvelu.comment :as comment]
            [lupapalvelu.mongo :refer [$each] :as mongo]
            [lupapalvelu.user :as user]
            [lupapalvelu.mime :as mime]
            [lupapalvelu.pdf-export :as pdf-export]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.tiedonohjaus :as tos]
            [lupapiste-commons.attachment-types :as attachment-types]
            [lupapalvelu.preview :as preview])
  (:import [java.util.zip ZipOutputStream ZipEntry]
           [java.io File FilterInputStream]
           [org.apache.commons.io FilenameUtils]
           [java.util.concurrent Executors ThreadFactory]))

(defn thread-factory []
  (let [security-manager (System/getSecurityManager)
        thread-group (if security-manager
                       (.getThreadGroup security-manager)
                       (.getThreadGroup (Thread/currentThread)))]
    (reify
      ThreadFactory
      (newThread [this runnable]
        (doto (Thread. thread-group runnable "preview-worker")
          (.setDaemon true)
          (.setPriority Thread/NORM_PRIORITY))))))

(defonce preview-threadpool (Executors/newFixedThreadPool 1 (thread-factory)))

;;
;; Metadata
;;

(def attachment-types-osapuoli attachment-types/osapuolet)

(def attachment-meta-types [:size :scale :op :contents])

(def attachment-scales
  [:1:20
   :1:50
   :1:100
   :1:200
   :1:500
   :muu])

(def attachment-sizes
  [:A0
   :A1
   :A2
   :A3
   :A4
   :A5
   :B0
   :B1
   :B2
   :B3
   :B4
   :B5
   :muu])

(def- attachment-types-R attachment-types/Rakennusluvat)

(def- attachment-types-YA attachment-types/YleistenAlueidenLuvat)

(def- attachment-types-YI attachment-types/Ymparistoilmoitukset)

(def- attachment-types-YL attachment-types/Ymparistolupa)

(def- attachment-types-MAL attachment-types/Maa-ainesluvat)

(def- attachment-types-KT attachment-types/Kiinteistotoimitus)

(defn attachment-ids-from-tree [tree]
  {:pre [(sequential? tree)]}
  (flatten (map second (partition 2 tree))))

(def all-attachment-type-ids
  (attachment-ids-from-tree
    (concat
      attachment-types-R
      attachment-types-YA
      attachment-types-YI
      attachment-types-YL
      attachment-types-MAL
      attachment-types-KT)))

;;
;; Api
;;

(defn by-file-ids [file-ids attachment]
  (let [file-id-set (set file-ids)
        attachment-file-ids (map :fileId (:versions attachment))]
    (some #(file-id-set %) attachment-file-ids)))

(defn get-attachments-infos
  "gets attachments from application"
  [application attachment-ids]
  (let [ids (set attachment-ids)] (filter (comp ids :id) (:attachments application))))

(defn get-attachment-info
  "gets an attachment from application or nil"
  [application attachment-id]
  (first (get-attachments-infos application [attachment-id])))

(defn create-sent-timestamp-update-statements [attachments file-ids timestamp]
  (mongo/generate-array-updates :attachments attachments (partial by-file-ids file-ids) :sent timestamp))

(defn get-attachment-types-by-permit-type
  "Returns partitioned list of allowed attachment types or throws exception"
  [permit-type]
  {:pre [permit-type]}
  (partition 2
    (case (keyword permit-type)
      :R  attachment-types-R
      :YA attachment-types-YA
      :P  attachment-types-R
      :YI attachment-types-YI
      :YL attachment-types-YL
      :VVVL attachment-types-YI ;TODO Put correct attachment list here
      :MM attachment-types-KT ;TODO Put correct attachment list here
      :MAL attachment-types-MAL
      :KT attachment-types-KT
      (fail! (str "unsupported permit-type: " (name permit-type))))))

(defn get-attachment-types-for-application
  [application]
  {:pre [application]}
  (get-attachment-types-by-permit-type (:permitType application)))

(defn make-attachment [now target required? requested-by-authority? locked? application-state op attachment-type metadata & [attachment-id]]
  (cond-> {:id (or attachment-id (mongo/create-id))
           :type attachment-type
           :modified now
           :locked locked?
           :applicationState (if (and (= "verdict" (:type target)) (not (states/post-submitted-states (keyword application-state))))
                               "verdictGiven"
                               application-state)
           :state :requires_user_action
           :target target
           :required required?       ;; true if the attachment is added from from template along with the operation, or when attachment is requested by authority
           :requestedByAuthority requested-by-authority?  ;; true when authority is adding a new attachment template by hand
           :notNeeded false
           :forPrinting false
           :op op
           :signatures []
           :versions []}
          (and (seq metadata) (env/feature? :tiedonohjaus)) (assoc :metadata metadata)))

(defn make-attachments
  "creates attachments with nil target"
  [now application-state attachment-types-with-metadata locked? required? requested-by-authority?]
  (map #(make-attachment now nil required? requested-by-authority? locked? application-state nil (:type %) (:metadata %)) attachment-types-with-metadata))

(defn- default-metadata-for-attachment-type [type {:keys [:organization :tosFunction]}]
  (tos/metadata-for-document organization tosFunction type))

(defn create-attachment [application attachment-type op now target locked? required? requested-by-authority? & [attachment-id]]
  {:pre [(map? application)]}
  (let [metadata (default-metadata-for-attachment-type attachment-type application)
        attachment (make-attachment now target required? requested-by-authority? locked? (:state application) op attachment-type metadata attachment-id)]
    (update-application
      (application->command application)
      {$set {:modified now}
       $push {:attachments attachment}})

    (:id attachment)))

(defn create-attachments [application attachment-types now locked? required? requested-by-authority?]
  {:pre [(map? application)]}
  (let [attachment-types-with-metadata (map (fn [type] {:type type :metadata (default-metadata-for-attachment-type type application)}) attachment-types)
        attachments (make-attachments now (:state application) attachment-types-with-metadata locked? required? requested-by-authority?)]
    (update-application
      (application->command application)
      {$set {:modified now}
       $push {:attachments {$each attachments}}})

    (map :id attachments)))

(defn next-attachment-version [{major :major minor :minor} user]
  (let [major (or major 0)
        minor (or minor 0)]
    (if (user/authority? user)
      {:major major, :minor (inc minor)}
      {:major (inc major), :minor 0})))

(defn attachment-latest-version [attachments attachment-id]
  (:version (:latestVersion (some #(when (= attachment-id (:id %)) %) attachments))))

(defn version-number
  [{{:keys [major minor]} :version}]
  (+ (* 1000 major) minor))

(defn latest-version-after-removing-file [attachments attachment-id fileId]
  (let [attachment (some #(when (= attachment-id (:id %)) %) attachments)
        versions   (:versions attachment)
        stripped   (filter #(not= (:fileId %) fileId) versions)
        sorted     (sort-by version-number stripped)
        latest     (last sorted)]
    latest))

(defn set-attachment-version
  ([options]
    {:pre [(map? options)]}
    (set-attachment-version options 5))
  ([{:keys [application attachment-id file-id filename content-type size comment-text now user stamped make-comment state target valid-pdfa missing-fonts]
     :or {make-comment true state :requires_authority_action} :as options}
    retry-limit]
    {:pre [(map? options) (map? application) (string? attachment-id) (string? file-id) (string? filename) (string? content-type) (number? size) (number? now) (map? user) (not (nil? stamped))]}
    ; TODO refactor to return version-model and mongo updates, so that updates can be merged into single statement
    (if (pos? retry-limit)
      (let [latest-version (attachment-latest-version (application :attachments) attachment-id)
            next-version (next-attachment-version latest-version user)
            version-model {:version  next-version
                           :fileId   file-id
                           :created  now
                           :accepted nil
                           :user    (user/summary user)
                           ; File name will be presented in ASCII when the file is downloaded.
                           ; Conversion could be done here as well, but we don't want to lose information.
                           :filename filename
                           :contentType content-type
                           :size size
                           :stamped stamped
                           :valid-pdfa valid-pdfa
                           :missing-fonts missing-fonts}

            comment-target {:type :attachment
                            :id attachment-id
                            :version next-version
                            :filename filename
                            :fileId file-id}

            result-count (update-application
                           (application->command application)
                           {:attachments {$elemMatch {:id attachment-id
                                                      :latestVersion.version.major (:major latest-version)
                                                      :latestVersion.version.minor (:minor latest-version)}}}
                           (util/deep-merge
                             (when make-comment (comment/comment-mongo-update (:state application) comment-text comment-target :system false user nil now))
                             (when target {$set {:attachments.$.target target}})
                             {$set {:modified now
                                    :attachments.$.modified now
                                    :attachments.$.state  state
                                    :attachments.$.latestVersion version-model}
                              $push {:attachments.$.versions version-model}})
                           true)]
        ; Check return value and try again with new version number
        (if (pos? result-count)
          (assoc version-model :id attachment-id)
          (do
            (errorf
              "Latest version of attachment %s changed before new version could be saved, retry %d time(s)."
              attachment-id retry-limit)
            (set-attachment-version (assoc options :application (mongo/by-id :applications (:id application))) (dec retry-limit)))))
      (do
        (error "Concurrency issue: Could not save attachment version meta data.")
        nil))))

(defn update-attachment-key [command attachmentId k v now & {:keys [set-app-modified? set-attachment-modified?] :or {set-app-modified? true set-attachment-modified? true}}]
  (let [update-key (->> (name k) (str "attachments.$.") keyword)]
    (update-application command
      {:attachments {$elemMatch {:id attachmentId}}}
      {$set (merge
              {update-key v}
              (when set-app-modified? {:modified now})
              (when set-attachment-modified? {:attachments.$.modified now}))})))

(defn update-latest-version-content [application attachment-id file-id size now]
  (let [attachment (get-attachment-info application attachment-id)
        latest-version-index (-> attachment :versions count dec)
        latest-version-path (str "attachments.$.versions." latest-version-index ".")
        old-file-id (get-in attachment [:latestVersion :fileId])]

    (when-not (= old-file-id file-id)
      (mongo/delete-file-by-id old-file-id))

    (update-application
      (application->command application)
      {:attachments {$elemMatch {:id attachment-id}}}
      {$set {:modified now
             :attachments.$.modified now
             (str latest-version-path "fileId") file-id
             (str latest-version-path "size") size
             (str latest-version-path "created") now
             :attachments.$.latestVersion.fileId file-id
             :attachments.$.latestVersion.size size
             :attachments.$.latestVersion.created now}})))

(defn- update-or-create-attachment
  "If the attachment-id matches any old attachment, a new version will be added.
   Otherwise a new attachment is created."
  [{:keys [application attachment-id attachment-type op file-id filename content-type size comment-text created user target locked required] :as options}]
  {:pre [(map? application)]}
  (let [requested-by-authority? (and (ss/blank? attachment-id) (user/authority? (:user options)))
        att-id (cond
                 (ss/blank? attachment-id) (create-attachment application attachment-type op created target locked required requested-by-authority?)
                 (pos? (mongo/count :applications {:_id (:id application) :attachments.id attachment-id})) attachment-id
                 :else (create-attachment application attachment-type op created target locked required requested-by-authority? attachment-id))]
    (set-attachment-version (assoc options :attachment-id att-id :now created :stamped false))))

(defn parse-attachment-type [attachment-type]
  (if-let [match (re-find #"(.+)\.(.+)" (or attachment-type ""))]
    (let [[type-group type-id] (->> match (drop 1) (map keyword))]
      {:type-group type-group :type-id type-id})))

(defn allowed-attachment-types-contain? [allowed-types {:keys [type-group type-id]}]
  (let [type-group (keyword type-group)
        type-id (keyword type-id)]
    (if-let [types (some (fn [[group-name group-types]] (if (= (keyword group-name) type-group) group-types)) allowed-types)]
      (some #(= (keyword %) type-id) types))))

(defn get-attachment-info-by-file-id
  "gets an attachment from application or nil"
  [{:keys [attachments]} file-id]
  (first
    (filter
      (partial by-file-ids #{file-id})
      attachments)))

(defn attachment-file-ids
  "Gets all file-ids from attachment."
  [application attachment-id]
  (->> (get-attachment-info application attachment-id) :versions (map :fileId)))

(defn attachment-latest-file-id
  "Gets latest file-id from attachment."
  [application attachment-id]
  (last (attachment-file-ids application attachment-id)))

(defn file-id-in-application?
  "tests that file-id is referenced from application"
  [application attachment-id file-id]
  (let [file-ids (attachment-file-ids application attachment-id)]
    (boolean (some #{file-id} file-ids))))

(defn delete-attachment
  "Delete attachement with all it's versions. does not delete comments. Non-atomic operation: first deletes files, then updates document."
  [{:keys [attachments] :as application} attachment-id]
  (info "1/3 deleting files of attachment" attachment-id)
  (dorun (map mongo/delete-file-by-id (attachment-file-ids application attachment-id)))
  (info "2/3 deleted files of attachment" attachment-id)
  (update-application (application->command application) {$pull {:attachments {:id attachment-id}}})
  (info "3/3 deleted meta-data of attachment" attachment-id))

(defn delete-attachment-version
  "Delete attachment version. Is not atomic: first deletes file, then removes application reference."
  [{:keys [id attachments] :as application} attachment-id fileId]
  (let [latest-version (latest-version-after-removing-file attachments attachment-id fileId)]
    (infof "1/3 deleting file %s of attachment %s" fileId attachment-id)
    (mongo/delete-file-by-id fileId)
    (infof "2/3 deleted file %s of attachment %s" fileId attachment-id)
    (update-application
      (application->command application)
      {:attachments {$elemMatch {:id attachment-id}}}
      {$pull {:attachments.$.versions {:fileId fileId}}
       $set  {:attachments.$.latestVersion latest-version}})
    (infof "3/3 deleted meta-data of file %s of attachment" fileId attachment-id)))

(defn get-attachment-as
  "Returns the attachment if user has access to application, otherwise nil."
  [user file-id]
  (when-let [attachment (mongo/download file-id)]
    (when-let [application (get-application-as (:application attachment) user :include-canceled-apps? true)]
      (when (seq application) attachment))))

(defn get-attachment
  "Returns the attachment without access checking, otherwise nil."
  [file-id]
  (when-let [attachment (mongo/download file-id)]
    (when-let [application (get-application-no-access-checking (:application attachment))]
      (when (seq application) attachment))))

(defn output-attachment
  [attachment-id download? attachment-fn]
  (debugf "file download: attachment-id=%s" attachment-id)
  (if-let [attachment (attachment-fn attachment-id)]
    (let [response {:status 200
                    :body ((:content attachment))
                    :headers {"Content-Type" (:content-type attachment)
                              "Content-Length" (str (:content-length attachment))}}]
      (if download?
        (assoc-in response
          [:headers "Content-Disposition"]
          (format "attachment;filename=\"%s\"" (ss/encode-filename (:file-name attachment))))
        response))
    {:status 404
     :headers {"Content-Type" "text/plain"}
     :body "404"}))

(defn create-preview
  [file-id filename content-type content application-id]
  (when (and (env/feature? :preview) (preview/converter content-type))
    (mongo/upload (str file-id "-preview") (str (FilenameUtils/getBaseName filename) ".jpg") "image/jpg" (preview/placeholder-image) :application application-id)
    (when-let [preview-content (util/timing (format "Creating preview: id=%s, type=%s file=%s" file-id content-type filename)
                                            (with-open [content ((:content (mongo/download file-id)))]
                                              (preview/create-preview content content-type)))]
      (debugf "Saving preview: id=%s, type=%s file=%s" file-id content-type filename)
      (mongo/upload (str file-id "-preview") (str (FilenameUtils/getBaseName filename) ".jpg") "image/jpg" preview-content :application application-id))))

(defn output-attachment-preview
  "Outputs attachment preview creating it if is it does not already exist"
  [attachment-id attachment-fn]
  (let [preview-id (str attachment-id "-preview")]
    (when (= 0 (mongo/count :fs.files {:_id preview-id}))
      (let [attachment (get-attachment attachment-id)
            file-name (:file-name attachment)
            content-type (:content-type attachment)
            content ((:content attachment))
            application-id (:application attachment)]
        (create-preview attachment-id file-name content-type content application-id)))
    (output-attachment preview-id false attachment-fn)))

(defn attach-file!
  "Uploads a file to MongoDB and creates a corresponding attachment structure to application.
   Content can be a file or input-stream.
   Returns attachment version."
  [options]
  {:pre [(map? (:application options))]}
  (let [file-id (mongo/create-id)
        {:keys [filename content]} options
        application-id (-> options :application :id)
        sanitazed-filename (mime/sanitize-filename filename)
        content-type (mime/mime-type sanitazed-filename)
        options (merge options {:file-id file-id
                                :filename sanitazed-filename
                                :content-type content-type})]
    (mongo/upload file-id sanitazed-filename content-type content :application application-id)
    (.submit preview-threadpool #(create-preview file-id sanitazed-filename content-type content application-id))
    (update-or-create-attachment options)))

(defn get-attachments-by-operation
  [{:keys [attachments] :as application} op-id]
  (filter #(= (:id (:op %)) op-id) attachments))

(defn- append-gridfs-file [zip {:keys [filename fileId]}]
  (when fileId
    (.putNextEntry zip (ZipEntry. (ss/encode-filename (str fileId "_" filename))))
    (with-open [in ((:content (mongo/download fileId)))]
      (io/copy in zip))))

(defn- append-stream [zip file-name in]
  (when in
    (.putNextEntry zip (ZipEntry. (ss/encode-filename file-name)))
    (io/copy in zip)))

(defn get-all-attachments
  "Returns attachments as zip file. If application and lang, application and submitted application PDF are included."
  [attachments & [application lang]]
  (let [temp-file (File/createTempFile "lupapiste.attachments." ".zip.tmp")]
    (debugf "Created temporary zip file for attachments: %s" (.getAbsolutePath temp-file))
    (with-open [out (io/output-stream temp-file)]
      (let [zip (ZipOutputStream. out)]
        ; Add all attachments:
        (doseq [attachment attachments]
          (append-gridfs-file zip (-> attachment :versions last)))

        (when (and application lang)
          ; Add submitted PDF, if exists:
          (when-let [submitted-application (mongo/by-id :submitted-applications (:id application))]
            (append-stream zip (i18n/loc "attachment.zip.pdf.filename.submitted") (pdf-export/generate submitted-application lang)))
          ; Add current PDF:
          (append-stream zip (i18n/loc "attachment.zip.pdf.filename.current") (pdf-export/generate application lang)))
        (.finish zip)))
    (debugf "Size of the temporary zip file: %d" (.length temp-file))
    temp-file))

(defn temp-file-input-stream [^File file]
  (let [i (io/input-stream file)]
    (proxy [FilterInputStream] [i]
      (close []
        (proxy-super close)
        (when (= (io/delete-file file :could-not) :could-not)
          (warnf "Could not delete temporary file: %s" (.getAbsolutePath file)))))))
