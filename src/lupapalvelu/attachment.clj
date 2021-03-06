(ns lupapalvelu.attachment
  (:require [taoensso.timbre :as timbre :refer [trace debug debugf info infof warn warnf error errorf fatal]]
            [clojure.java.io :as io]
            [clojure.set :refer [rename-keys]]
            [monger.operators :refer :all]
            [schema.core :refer [defschema] :as sc]
            [sade.core :refer :all]
            [sade.files :as files]
            [sade.http :as http]
            [sade.schemas :as ssc]
            [sade.strings :as ss]
            [sade.util :refer [=as-kw not=as-kw fn->] :as util]
            [lupapalvelu.action :refer [update-application application->command]]
            [lupapalvelu.archiving-util :as archiving-util]
            [lupapalvelu.assignment :as assignment]
            [lupapalvelu.attachment.conversion :as conversion]
            [lupapalvelu.attachment.tags :as att-tags]
            [lupapalvelu.attachment.tag-groups :as att-tag-groups]
            [lupapalvelu.attachment.type :as att-type]
            [lupapalvelu.attachment.accessibility :as access]
            [lupapalvelu.attachment.metadata :as metadata]
            [lupapalvelu.attachment.preview :as preview]
            [lupapalvelu.authorization :as auth]
            [lupapalvelu.domain :refer [get-application-as get-application-no-access-checking]]
            [lupapalvelu.file-upload :as file-upload]
            [lupapalvelu.states :as states]
            [lupapalvelu.comment :as comment]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.operations :as op]
            [lupapalvelu.organization :as org]
            [lupapalvelu.pdf.pdf-export :as pdf-export]
            [lupapalvelu.tiedonohjaus :as tos]
            [lupapalvelu.user :as usr])
  (:import [java.util.zip ZipOutputStream ZipEntry]
           [java.io File InputStream]))

;;
;; Metadata
;;

(def attachment-meta-types [:size :scale :group :contents :drawingNumber])

(def attachment-scales
  [:1:20 :1:50 :1:100 :1:200 :1:500
   :muu])

(def attachment-sizes
  [:A0 :A1 :A2 :A3 :A4 :A5
   :B0 :B1 :B2 :B3 :B4 :B5
   :muu])

(def attachment-states #{:ok :requires_user_action :requires_authority_action})


(defschema AttachmentId
  (ssc/min-length-string 24))

(defschema AttachmentAuthUser
  "User summary for authorized users in attachment.
   Only name and role is used for users without Lupapiste account."
  (let [SummaryAuthUser (assoc usr/SummaryUser :role (sc/enum "stamper" "uploader"))]
    (sc/if :id
      SummaryAuthUser
      (select-keys SummaryAuthUser [:firstName :lastName :role]))))

(defschema VersionNumber
  {:minor                                sc/Int
   :major                                sc/Int})

(defschema Target
  "Refers to part of the application which attachment is targetted.
   Possible types are verdict, statement etc."
  {:id                                   ssc/ObjectIdStr
   :type                                 sc/Keyword
   (sc/optional-key :poytakirjaId)       sc/Str
   (sc/optional-key :urlHash)            sc/Str})

(defschema Source
  "Source for the automatically generated attachment."
  {:id                                   ssc/ObjectIdStr
   :type                                 sc/Str})

(defschema Operation
  "Operation for operation specific attachments"
  {:id                                   ssc/ObjectIdStr    ;;
   (sc/optional-key :name)               sc/Str})           ;;

(defschema Signature
  "Signature for attachment version"
  {:user                                 usr/SummaryUser   ;;
   :created                              ssc/Timestamp      ;;
   :fileId                               sc/Str             ;; used as 'foreign key' to attachment version
   :version                              VersionNumber})    ;; version number of the signed attachment version

(defschema Approval
  {:state                       (apply sc/enum attachment-states)
   (sc/optional-key :timestamp) ssc/Timestamp
   (sc/optional-key :user)      {:id sc/Str, :firstName sc/Str, :lastName sc/Str}
   (sc/optional-key :note)      sc/Str})

(defschema VersionApprovals
  "An approvals are version-specific. Keys are originalFileIds."
  {sc/Keyword Approval})

(defschema Version
  "Attachment version"
  {:version                              VersionNumber
   :fileId                               sc/Str             ;; fileId in GridFS
   :originalFileId                       sc/Str             ;; fileId of the unrotated file
   :created                              ssc/Timestamp
   :user                                 (sc/if :id         ;; User who created the version
                                           usr/SummaryUser ;; Only name is used for users without Lupapiste account, eg. neighbours
                                           (select-keys usr/User [:firstName :lastName]))
   :filename                             sc/Str             ;; original filename
   :contentType                          sc/Str             ;; MIME type of the file
   :size                                 sc/Int             ;; file size
   (sc/optional-key :stamped)            sc/Bool
   (sc/optional-key :archivable)         (sc/maybe sc/Bool)
   (sc/optional-key :archivabilityError) (sc/maybe (apply sc/enum conversion/archivability-errors))
   (sc/optional-key :missing-fonts)      (sc/maybe [sc/Str])
   (sc/optional-key :autoConversion)     (sc/maybe sc/Bool)
   (sc/optional-key :conversionLog)      (sc/maybe [sc/Str])})

(defschema Type
  "Attachment type"
  {:type-id                              (apply sc/enum att-type/all-attachment-type-ids)
   :type-group                           (apply sc/enum att-type/all-attachment-type-groups)})

(def GroupType (apply sc/enum att-tags/attachment-groups))

(defschema Attachment
  {:id                                   AttachmentId
   :type                                 Type               ;; Attachment type
   :modified                             ssc/Timestamp      ;; last modified
   (sc/optional-key :sent)               ssc/Timestamp      ;; sent to backing system
   :locked                               sc/Bool            ;;
   (sc/optional-key :readOnly)           sc/Bool            ;;
   :applicationState                     (apply sc/enum states/all-states) ;; state of the application when attachment is created if not forced to any other
   (sc/optional-key :originalApplicationState) (apply sc/enum states/pre-verdict-states) ;; original application state if visible application state if forced to any other
   :target                               (sc/maybe Target)  ;;
   (sc/optional-key :source)             Source             ;;
   (sc/optional-key :ramLink)            AttachmentId       ;; reference from ram attachment to base attachment
   :required                             sc/Bool            ;;
   :requestedByAuthority                 sc/Bool            ;;
   :notNeeded                            sc/Bool            ;;
   :forPrinting                          sc/Bool            ;; see kopiolaitos.clj
   :op                                   (sc/maybe [Operation])
   (sc/optional-key :groupType)          (sc/maybe GroupType)
   :signatures                           [Signature]
   :versions                             [Version]
   (sc/optional-key :latestVersion)      (sc/maybe Version) ;; last item of the versions array
   (sc/optional-key :contents)           (sc/maybe sc/Str)  ;; content description
   (sc/optional-key :drawingNumber)      sc/Str             ;;
   (sc/optional-key :scale)              (apply sc/enum attachment-scales)
   (sc/optional-key :size)               (apply sc/enum attachment-sizes)
   :auth                                 [AttachmentAuthUser]
   (sc/optional-key :metadata)           {sc/Keyword sc/Any}
   (sc/optional-key :approvals)          VersionApprovals})

;;
;; Utils
;;

(def attachment-type-coercer (ssc/json-coercer Type))
(def attachment-target-coercer (ssc/json-coercer Target))
(def group-type-coercer (ssc/json-coercer GroupType))

(defn ->attachment-operation [operation]
  (select-keys operation [:id :name]))

(defn if-not-authority-state-must-not-be [state-set {user :user {:keys [state]} :application}]
  (when (and (not (usr/authority? user))
             (state-set (keyword state)))
    (fail :error.non-authority-viewing-application-in-verdictgiven-state)))

(defn attachment-value-is?
  "predicate is invoked for attachment value of key"
  [pred key attachment]
  (pred (get attachment (keyword key))))

(def attachment-is-readOnly? (partial attachment-value-is? true? :readOnly))
(def attachment-is-locked?   (partial attachment-value-is? true? :locked))

(defn get-original-file-id
  "Returns original file id of the attachment version that matches
  file-id. File-id can be either fileId or originalFileId."
  [attachment file-id]
  (some->> (:versions attachment)
           (util/find-first (fn [{:keys [fileId originalFileId]}]
                              (or (= fileId file-id)
                                  (= originalFileId file-id))))
           :originalFileId))

(defn- version-approval-path
  [original-file-id & [subkey]]
  (->>  (if (ss/blank? (str subkey))
          [original-file-id]
          [original-file-id (name subkey)])
        (cons "attachments.$.approvals")
        (ss/join ".")
        keyword))

(defn attachment-version-state [attachment file-id]
  (when-let [file-key (keyword (get-original-file-id attachment file-id))]
    (some-> attachment :approvals file-key :state keyword)))

(defn attachment-state [attachment]
  (attachment-version-state attachment (some-> attachment
                                               :latestVersion
                                               :originalFileId)))

(defn attachment-array-updates
  "Generates mongo updates for application attachment array. Gets all attachments from db to ensure proper indexing."
  [app-id pred & kvs]
  (as-> app-id $
        (get-application-no-access-checking $ {:attachments true})
        (apply mongo/generate-array-updates :attachments (:attachments $) pred kvs)))

(defn sorted-attachments
  "Sorted attachments for command:
    1. Localized type info ascending (using lang in command)
    2. Latest version timestamp descending."
  [{{attachments :attachments} :application lang :lang}]
  (letfn [(type-text [{{:keys [type-id type-group]} :type}]
            (i18n/localize lang (format "attachmentType.%s.%s" type-group type-id)))
          (filestamp [attachment]
            (-> attachment :latestVersion :created))]
    (sort (fn [att1 att2]
            (let [txt1        (type-text att1)
                  txt2        (type-text att2)
                  txt-compare (compare txt1 txt2)]
              (if (zero? txt-compare)
                (compare (filestamp att2) (filestamp att1))
                txt-compare)))
          attachments)))
;;
;; Api
;;

(defn link-files-to-application [app-id fileIds]
  {:pre [(string? app-id)]}
  (mongo/update-by-query :fs.files {:_id {$in fileIds}} {$set {:metadata.application app-id
                                                               :metadata.linked true}}))

(defn- by-file-ids [file-ids {versions :versions :as attachment}]
  (some (comp (set file-ids) :fileId) versions))

(defn get-attachments-infos
  "gets attachments from application"
  [application attachment-ids]
  (let [ids (set attachment-ids)] (filter (comp ids :id) (:attachments application))))

(defn get-attachment-info
  "gets an attachment from application or nil"
  [application attachment-id]
  (first (get-attachments-infos application [attachment-id])))

(defn get-attachment-info-by-file-id
  "gets an attachment from application or nil"
  [{:keys [attachments]} file-id]
  (first (filter (partial by-file-ids #{file-id}) attachments)))

(defn get-operation-ids [{op :op :as attachment}]
  (mapv :id op))

(defn get-attachments-by-operation
  [{:keys [attachments] :as application} op-id]
  (filter (fn-> get-operation-ids set (contains? op-id)) attachments))

(defn get-attachments-by-type
  [{:keys [attachments]} type]
  {:pre [(map? type)]}
  (filter #(= (:type %) type) attachments))

(defn get-attachments-by-target-type-and-id
  [{:keys [attachments]} {:keys [type id] :as target}]
  {:pre [(string? type)
         (string? id)]}
  (filter #(and (= (get-in % [:target :type]) type)
                (= (get-in % [:target :id])   id))  attachments))

(defn create-sent-timestamp-update-statements [attachments file-ids timestamp]
  (mongo/generate-array-updates :attachments attachments (partial by-file-ids file-ids) :sent timestamp))

(defn create-read-only-update-statements [attachments file-ids]
  (mongo/generate-array-updates :attachments attachments (partial by-file-ids file-ids) :readOnly true))

(defn remove-operation-updates [{op :op :as attachment} removed-op-id]
  (when (-> (get-operation-ids attachment) set (contains? removed-op-id))
    (let [updated-op (not-empty (remove (comp #{removed-op-id} :id) op))]
      {:op updated-op :groupType (when updated-op :operation)})))

(defn make-attachment
  [created target required? requested-by-authority? locked? application-state group attachment-type metadata & [attachment-id contents read-only? source]]
  {:pre  [(sc/validate att-type/AttachmentType attachment-type) (keyword? application-state) (or (nil? target) (sc/validate Target target))]
   :post [(sc/validate Attachment %)]}
  (cond-> {:id (if (ss/blank? attachment-id) (mongo/create-id) attachment-id)
           :type (select-keys attachment-type [:type-id :type-group])
           :modified created
           :locked locked?
           :readOnly (boolean read-only?)
           :applicationState (if (and (= :verdict (:type target)) (not (states/post-verdict-states application-state)))
                               :verdictGiven
                               application-state)
           ;;:state :requires_user_action
           :target target
           :required required?       ;; true if the attachment is added from from template along with the operation, or when attachment is requested by authority
           :requestedByAuthority requested-by-authority?  ;; true when authority is adding a new attachment template by hand
           :notNeeded false
           :forPrinting false
           :op (->> (:operations group) (map ->attachment-operation) not-empty)
           :signatures []
           :versions []
           ;:approvals {}
           :auth []
           :contents contents}
          (:groupType group) (assoc :groupType (group-type-coercer (:groupType group)))
          (map? source) (assoc :source source)
          (seq metadata) (assoc :metadata metadata)))

(defn make-attachments
  "creates attachments with nil target"
  [created application-state attachment-types-with-metadata group locked? required? requested-by-authority?]
  (map #(make-attachment created nil required? requested-by-authority? locked? (keyword application-state) group (:type %) (:metadata %)) attachment-types-with-metadata))

(defn- default-tos-metadata-for-attachment-type [type {:keys [organization tosFunction verdicts]}]
  (let [metadata (-> (tos/metadata-for-document organization tosFunction type)
                     (tos/update-end-dates verdicts))]
    (if (seq metadata)
      metadata
      {:nakyvyys :julkinen})))

(defn- required-options-check [options-map]
  (and (map?    (:attachment-type options-map))
       (number? (:created options-map))))

(defn- resolve-operation-names [operations {documents :documents}]
  (let [application-operations (map (comp :op :schema-info) documents)]
    (->> operations
         (map (fn [op] (if (or (ss/blank? (:id op)) (ss/not-blank? (:name op)))
                         op
                         (->> (util/find-by-id (:id op) application-operations)
                              :name
                              (util/assoc-when op :name)))))
         not-empty)))

(defn- attachment-grouping-for-application [group application]
  (when-not (false? (op/get-primary-operation-metadata application :attachment-op-selector))
    (update group :operations resolve-operation-names application)))

(defn create-attachment-data
  "Returns the attachment data model as map. This attachment data can be pushed to mongo (no versions included)."
  [application {:keys [attachment-id attachment-type group created target locked required requested-by-authority contents read-only source]
                :or {required false locked false requested-by-authority false} :as options}]
  {:pre [(required-options-check options)]}
  (let [metadata (default-tos-metadata-for-attachment-type attachment-type application)]
    (make-attachment created
                     (when target (attachment-target-coercer target))
                     required
                     requested-by-authority
                     locked
                     (-> application :state keyword)
                     (attachment-grouping-for-application group application)
                     (attachment-type-coercer attachment-type)
                     metadata
                     attachment-id
                     contents
                     read-only
                     source)))

(defn create-attachment!
  "Creates attachment data and $pushes attachment to application. Updates TOS process metadata retention period, if needed"
  [application {ts :created :as options}]
  {:pre [(map? application)]}
  (let [attachment-data (create-attachment-data application options)]
    (update-application
     (application->command application)
      {$set {:modified ts}
       $push {:attachments attachment-data}})
    (tos/update-process-retention-period (:id application) ts)
    attachment-data))

(defn create-attachments! [application attachment-types group created locked? required? requested-by-authority?]
  {:pre [(map? application)]}
  (let [attachment-types-with-metadata (map (fn [type] {:type     (attachment-type-coercer type)
                                                        :metadata (default-tos-metadata-for-attachment-type type application)})
                                            attachment-types)
        attachments (make-attachments created (:state application) attachment-types-with-metadata group locked? required? requested-by-authority?)]
    (update-application
      (application->command application)
      {$set {:modified created}
       $push {:attachments {$each attachments}}})
    (tos/update-process-retention-period (:id application) created)
    (map :id attachments)))

(defn construction-time-state-updates
  "Returns updates for 'setting' construction time flag. Updates are for elemMatch query. Value is true or false."
  [{:keys [applicationState originalApplicationState]} value]
  (when-not (contains? states/post-verdict-states (keyword (or originalApplicationState applicationState)))
    (if value
      {$set {:attachments.$.originalApplicationState (or originalApplicationState applicationState)
             :attachments.$.applicationState :verdictGiven}}
      {$set   {:attachments.$.applicationState originalApplicationState}
       $unset {:attachments.$.originalApplicationState true}})))

(defn- signature-updates [{:keys [fileId version]} user ts original-signature attachment-signatures]
  (let [signature {:user   (or (:user original-signature) (usr/summary user))
                   :created (or (:created original-signature) ts (now))
                   :version version
                   :fileId fileId}]
    (if-let [orig-index (util/position-by-key :version version attachment-signatures)]
      {$set  {(util/kw-path :attachments.$.signatures orig-index) signature}}
      {$push {:attachments.$.signatures signature}})))

(defn can-delete-version?
  "False if the attachment version is a) rejected or approved and b)
  the user is not authority."
  [user application attachment-id file-id]
  (not (when-not (auth/application-authority? application user)
         (when (some-> (get-attachment-info application attachment-id)
                       (attachment-version-state file-id)
                       keyword
                       #{:ok :requires_user_action})
           :cannot-delete))))

(defn- delete-attachment-file-and-preview! [file-id]
  (mongo/delete-file-by-id file-id)
  (mongo/delete-file-by-id (str file-id "-preview")))

(defn next-attachment-version [{:keys [major minor] :or {major 0 minor 0}} user]
  (if (usr/authority? user)
    {:major major, :minor (inc minor)}
    {:major (inc major), :minor 0}))

(defn- version-number
  [{{:keys [major minor] :or {major 0 minor 0}} :version}]
  (+ (* 1000 major) minor))

(defn- latest-version-after-removing-file [attachment fileId]
  (->> (:versions attachment)
       (remove (comp #{fileId} :fileId))
       (sort-by version-number)
       (last)))

(defn make-version
  [attachment user {:keys [fileId original-file-id replaceable-original-file-id filename contentType size created
                           stamped archivable archivabilityError missing-fonts autoConversion conversionLog]}]
  (let [version-number (or (->> (:versions attachment)
                                (filter (comp (hash-set original-file-id replaceable-original-file-id) :originalFileId))
                                last
                                :version)
                           (next-attachment-version (get-in attachment [:latestVersion :version]) user))]
    (sc/validate Version
      (util/assoc-when
        {:version        version-number
         :fileId         fileId
         :originalFileId (or original-file-id fileId)
         :created        created
         :user           (usr/summary user)
         ;; File name will be presented in ASCII when the file is downloaded.
         ;; Conversion could be done here as well, but we don't want to lose information.
         :filename       filename
         :contentType    contentType
         :size           size
         :stamped        (boolean stamped)
         :archivable     (boolean archivable)}
        :archivabilityError archivabilityError
        :missing-fonts missing-fonts
        :autoConversion autoConversion
        :conversionLog conversionLog))))

(defn- ->approval [state user timestamp]
  (sc/validate Approval
    {:state state
     :user (select-keys user [:id :firstName :lastName])
     :timestamp timestamp}))

(defn- build-version-updates [user attachment version-model
                              {:keys [created target stamped replaceable-original-file-id state contents drawingNumber group]}]
  {:pre [(map? attachment) (map? version-model) (number? created) (map? user)]}

  (let [{:keys [originalFileId]} version-model
        version-index            (util/position-by-key :originalFileId (or replaceable-original-file-id originalFileId)
                                                       (:versions attachment))
        user-role                (if stamped :stamper :uploader)]
    (util/deep-merge
     (when target
       {$set {:attachments.$.target target}})
     (when (->> (:versions attachment) butlast (map :originalFileId) (some #{originalFileId}) not)
       {$set {:attachments.$.latestVersion version-model}})
     (when-not (ss/blank? contents)
       {$set {:attachments.$.contents contents}})
     (when-not (ss/blank? drawingNumber)
       {$set {:attachments.$.drawingNumber drawingNumber}})
     (when (not-empty group)
       {$set {:attachments.$.op (not-empty (:operations group))
              :attachments.$.groupType (:groupType group)}})
     (when-let [approval (cond
                           state                                           (->approval state user created )
                           (not (attachment-version-state attachment
                                                          originalFileId)) {:state :requires_authority_action})]
       {$set {(version-approval-path originalFileId) approval}})

     (merge
       {$set      (merge
                    {:modified                            created
                     :attachments.$.modified              created
                     :attachments.$.notNeeded             false}
                    (when version-index
                      {(ss/join "." ["attachments" "$" "versions" version-index]) version-model}))
        $addToSet {:attachments.$.auth (usr/user-in-role (usr/summary user) user-role)}}
       (when-not version-index
         {$push {:attachments.$.versions version-model}})))))

(defn- remove-old-files! [{old-versions :versions} {file-id :fileId original-file-id :originalFileId :as new-version}]
  (some->> (filter (comp #{original-file-id} :originalFileId) old-versions)
           (first)
           ((juxt :fileId :originalFileId))
           (remove (set [file-id original-file-id]))
           (run! delete-attachment-file-and-preview!)))

(defn- attachment-comment-updates [application user attachment {:keys [comment? comment-text created]
                                                                :or   {comment? true}}]
  (let [comment-target {:type :attachment
                        :id (:id attachment)}]
    (when comment?
      (comment/comment-mongo-update (:state application) comment-text comment-target :system false user nil created))))

(defn set-attachment-version!
  "Creates a version from given attachment and options and saves that version to application.
  Returns version model with attachment-id (not file-id) as id."
  ([application user {attachment-id :id :as attachment} options]
    {:pre [(map? application) (map? attachment) (map? options)]}
   (loop [application application attachment attachment retries-left 5]
     (let [options       (if (contains? options :group)
                           (update options :group attachment-grouping-for-application application)
                           options)
           version-model (make-version attachment user options)
           mongo-query   {:attachments {$elemMatch {:id attachment-id
                                                    :latestVersion.version.fileId (get-in attachment [:latestVersion :version :fileId])}}}
           mongo-updates (util/deep-merge (attachment-comment-updates application user attachment options)
                                          (when (:constructionTime options)
                                            (construction-time-state-updates attachment true))
                                          (when (or (:sign options) (:signature options))
                                            (signature-updates version-model user (:created options) (:signature options) (:signatures attachment)))
                                          (build-version-updates user attachment version-model options))
           update-result (update-application (application->command application) mongo-query mongo-updates :return-count? true)]

       (cond (pos? update-result)
             (do (remove-old-files! attachment version-model)
                 (assoc version-model :id attachment-id))

             (pos? retries-left)
             (do (errorf
                  "Latest version of attachment %s changed before new version could be saved, retry %d time(s)."
                  attachment-id retries-left)
                 (let [application (mongo/by-id :applications (:id application) [:attachments :state])
                       attachment  (get-attachment-info application attachment-id)]
                   (recur application attachment (dec retries-left))))

             :else
             (do (error
                  "Concurrency issue: Could not save attachment version meta data.")
                 nil))))))

(defn meta->attachment-data [meta]
  (merge (dissoc meta :group)
         (when (contains? meta :group)
           {:op (not-empty (->> (get-in meta [:group :operations]) (map ->attachment-operation)))
            :groupType (get-in meta [:group :groupType])})))

(defn update-attachment-data! [command attachmentId data now & {:keys [set-app-modified? set-attachment-modified?] :or {set-app-modified? true set-attachment-modified? true}}]
  (update-application command
                      {:attachments {$elemMatch {:id attachmentId}}}
                      {$set (merge (util/map-keys (partial util/kw-path "attachments" "$") data)
                                   (when set-app-modified? {:modified now})
                                   (when set-attachment-modified? {:attachments.$.modified now}))}))

(defn get-empty-attachment-placeholder-id [attachments type exclude-ids-set]
  (->> (filter (comp empty? :versions) attachments)
       (remove (comp exclude-ids-set :id))
       (remove :notNeeded)
       (util/find-first (comp (partial att-type/equals? type) :type))
       :id))

(defn- attachment-file-ids
  "Gets all file-ids from attachment."
  [attachment]
  (->> (:versions attachment)
       (mapcat (juxt :originalFileId :fileId))
       (distinct)))

(defn attachment-latest-file-id
  "Gets latest file-id from attachment."
  [application attachment-id]
  (last (attachment-file-ids (get-attachment-info application attachment-id))))

(defn file-id-in-application?
  "tests that file-id is referenced from application"
  [application attachment-id file-id]
  (let [file-ids (attachment-file-ids (get-attachment-info application attachment-id))]
    (boolean (some #{file-id} file-ids))))

(defn- get-file-ids-for-attachments-ids [application attachment-ids]
  (reduce (fn [file-ids attachment-id]
            (concat file-ids (attachment-file-ids (get-attachment-info application attachment-id))))
          []
          attachment-ids))

(defn delete-attachments!
  "Delete attachments with all it's versions. does not delete comments.
   Deletes also assignments that are targets of attachments in question.
   Non-atomic operation: first deletes files, then updates document."
  [application attachment-ids]
  (when (seq attachment-ids)
    (let [ids-str (pr-str attachment-ids)]
      (info "1/4 deleting assignments regarding attachments" ids-str)
      (run! (partial assignment/remove-target-from-assignments (:id application)) attachment-ids)
      (info "2/4 deleting files of attachments" ids-str)
      (run! delete-attachment-file-and-preview! (get-file-ids-for-attachments-ids application attachment-ids))
      (info "3/4 deleted files of attachments" ids-str)
      (update-application (application->command application) {$pull {:attachments {:id {$in attachment-ids}}}})
      (info "4/4 deleted meta-data of attachments" ids-str)))
  (when (org/some-organization-has-archive-enabled? #{(:organization application)})
    (archiving-util/mark-application-archived-if-done application (now))))

(defn delete-attachment-version!
  "Delete attachment version. Is not atomic: first deletes file, then removes application reference."
  [application attachment-id file-id original-file-id]
  (let [attachment (get-attachment-info application attachment-id)
        latest-version (latest-version-after-removing-file attachment file-id)]
    (infof "1/3 deleting files [%s] of attachment %s" (ss/join ", " (set [file-id original-file-id])) attachment-id)
    (run! delete-attachment-file-and-preview! (set [file-id original-file-id]))
    (infof "2/3 deleted file %s of attachment %s" file-id attachment-id)
    (update-application
     (application->command application)
     {:attachments {$elemMatch {:id attachment-id}}}
     {$pull {:attachments.$.versions {:fileId file-id}
             :attachments.$.signatures {:fileId file-id}}
      $unset {(version-approval-path original-file-id) 1}
      $set  (merge
             {:attachments.$.latestVersion latest-version}
             (when (nil? latest-version) ; No latest version: reset auth and state
               {:attachments.$.auth []}))})
    (infof "3/3 deleted meta-data of file %s of attachment" file-id attachment-id)))

(defn get-attachment-file-as!
  "Returns the attachment file if user has access to application and the attachment, otherwise nil."
  [user file-id]
  (when-let [attachment-file (mongo/download file-id)]
    (when-let [application (and (:application attachment-file) (get-application-as (:application attachment-file) user :include-canceled-apps? true))]
      (when (and (seq application) (access/can-access-attachment-file? user file-id application)) attachment-file))))

(defn get-attachment-file!
  "Returns the attachment file without access checking, otherwise nil."
  [file-id]
  (when-let [attachment-file (mongo/download file-id)]
    (when-let [application (and (:application attachment-file) (get-application-no-access-checking (:application attachment-file)))]
      (when (seq application) attachment-file))))

(defn get-attachment-latest-version-file
  "Returns the file for the latest attachment version if user has access to application and the attachment, otherwise nil."
  [user attachment-id]
  (let [application (get-application-as {:attachments.id attachment-id} user :include-canceled-apps? true)
        file-id (attachment-latest-file-id application attachment-id)]
    (when (and application file-id (access/can-access-attachment-file? user file-id application))
      (mongo/download file-id))))

(def- not-found {:status 404
                 :headers {"Content-Type" "text/plain"}
                 :body "404"})

(defn- attachment-200-response [attachment filename]
  {:status 200
   :body ((:content attachment))
   :headers {"Content-Type" (:contentType attachment)
             "Content-Length" (str (:size attachment))
             "Content-Disposition" (format "filename=\"%s\"" filename)}})

(defn output-attachment
  ([attachment download?]
  (if attachment
    (let [filename (ss/encode-filename (:filename attachment))
          response (attachment-200-response attachment filename)]
      (if download?
        (assoc-in response [:headers "Content-Disposition"] (format "attachment;filename=\"%s\"" filename))
        (update response :headers merge http/no-cache-headers)))
    not-found))
  ([file-id download? attachment-fn]
   (output-attachment (attachment-fn file-id) download?)))

(defn output-file
  [file-id session-id]
  (if-let [attachment-file (mongo/download-find {:_id file-id :metadata.sessionId session-id})]
    (update (attachment-200-response attachment-file (ss/encode-filename (:filename attachment-file)))
            :headers
            http/no-cache-headers)
    not-found))

(defn output-attachment-preview!
  "Outputs attachment preview creating it if is it does not already exist"
  [file-id attachment-fn]
  (let [preview-id (str file-id "-preview")]
    (if-let [{:keys [filename contentType application]} (attachment-fn file-id)]
      (do
        (when (zero? (mongo/count :fs.files {:_id preview-id}))
          (preview/create-preview! file-id filename contentType application mongo/*db-name*))
        (output-attachment preview-id false attachment-fn))
      not-found)))

(defn cleanup-temp-file [conversion-result]
  (if (and (:content conversion-result) (not (instance? InputStream (:content conversion-result))))
    (io/delete-file (:content conversion-result) :silently)))

(defn conversion
  "Does archivability conversion, if required, for given file.
   If file was converted, uploads converted file to mongo.
   Returns map with conversion result and :file if conversion was made."
  [application filedata]
  (let [conversion-result  (conversion/archivability-conversion application filedata)
        converted-filedata (when (:autoConversion conversion-result)
                             ; upload and return new fileId for converted file
                             (file-upload/save-file (select-keys conversion-result [:content :filename])
                                                    {:application (:id application) :linked false}))]
    {:result conversion-result
     :file converted-filedata}))

(defn- attach!
  [{:keys [application user]} {attachment-id :attachment-id :as attachment-options} original-filedata conversion-data]
  (let [options            (merge attachment-options
                                  original-filedata
                                  (:result conversion-data)
                                  {:original-file-id (or (:original-file-id attachment-options)
                                                         (:fileId original-filedata))}
                                  (:file conversion-data))
        attachment         (or (get-attachment-info application attachment-id)
                               (create-attachment! application
                                                   (assoc attachment-options
                                                     :requested-by-authority
                                                     (auth/application-authority? application user))))
        linked-version     (set-attachment-version! application user attachment options)]
    (preview/preview-image! (:id application) (:fileId options) (:filename options) (:contentType options))
    (link-files-to-application (:id application) ((juxt :fileId :originalFileId) linked-version))
    (cleanup-temp-file (:result conversion-data))
    linked-version))

(defn upload-and-attach!
  "1) Uploads original file to GridFS
   2) Validates and converts for archivability, uploads converted file to GridFS if applicable
   3) Creates and saves attachment model for application, or fetches it if attachment-id is given
   4) Creates preview image in separate thread
   5) Links file as new version to attachment. If conversion was made, converted file is used (originalFileId points to original file)
   Returns attached version."
  [{:keys [application] :as command} attachment-options file-options]
  (let [original-filedata   (file-upload/save-file file-options {:application (:id application) :linked false})
        content            (if (instance? File (:content file-options))
                             (:content file-options)
                             ;; stream is consumed at this point, load from mongo
                             ((-> original-filedata :fileId mongo/download :content)))
        conversion-data    (conversion application (assoc original-filedata :content content))]
    (attach! command attachment-options original-filedata conversion-data)))

(defn- append-stream [zip file-name in]
  (when in
    (.putNextEntry zip (ZipEntry. (ss/encode-filename file-name)))
    (io/copy in zip)
    (.closeEntry zip)))

(defn- append-gridfs-file! [zip {:keys [filename fileId]}]
  (when fileId
    (if-let [content (:content (mongo/download fileId))]
      (with-open [in (content)]
        (append-stream zip (str fileId "_" filename) in))
      (errorf "File '%s' not found in GridFS. Try manually: db.fs.files.find({_id: '%s'})" filename fileId))))

(defn ^java.io.File get-all-attachments!
  "Returns attachments as zip file.
   If application and lang, application and submitted application PDF are included.
   Callers responsibility is to delete the returned file when done with it!"
  [attachments & [application lang]]
  (let [temp-file (files/temp-file "lupapiste.attachments." ".zip.tmp")] ; Must be deleted by caller!
    (debugf "Created temporary zip file for %d attachments: %s" (count attachments) (.getAbsolutePath temp-file))
    (with-open [zip (ZipOutputStream. (io/output-stream temp-file))]
      ; Add all attachments:
      (doseq [attachment attachments]
        (append-gridfs-file! zip (-> attachment :versions last)))

      (when (and application lang)
        ; Add submitted PDF, if exists:
        (when-let [submitted-application (mongo/by-id :submitted-applications (:id application))]
          (append-stream zip (i18n/loc "attachment.zip.pdf.filename.submitted") (pdf-export/generate submitted-application lang)))
        ; Add current PDF:
        (append-stream zip (i18n/loc "attachment.zip.pdf.filename.current") (pdf-export/generate application lang)))
      (.finish zip))
    (debugf "Size of the temporary zip file: %d" (.length temp-file))
    temp-file))

(defn- maybe-append-gridfs-file!
  "Download and add the attachment file if user can access the application"
  [zip user {:keys [filename fileId]}]
  (when fileId
    (when-let [file (get-attachment-file-as! user fileId)]
      (with-open [in ((:content file))]
        (append-stream zip (str (:application file) "_" fileId "_" filename) in)))))

(defn ^java.io.InputStream get-attachments-for-user!
  "Returns the latest corresponding attachment files readable by the user as an input stream of a self-destructing ZIP file"
  [user attachments]
  (let [temp-file (files/temp-file "lupapiste.attachments." ".zip.tmp")] ; deleted via temp-file-input-stream
    (debugf "Created temporary zip file for %d attachments: %s" (count attachments) (.getAbsolutePath temp-file))
    (with-open [zip (ZipOutputStream. (io/output-stream temp-file))]
      (doseq [attachment attachments]
        (maybe-append-gridfs-file! zip user (-> attachment :versions last)))
      (.finish zip))
    (debugf "Size of the temporary zip file: %d" (.length temp-file))
    (files/temp-file-input-stream temp-file)))

(defn- post-process-attachment [attachment]
  (assoc attachment :isPublic (metadata/public-attachment? attachment)))

(defn post-process-attachments [application]
  (update-in application [:attachments] (partial map post-process-attachment)))

(defn set-attachment-state!
  "Updates attachment's approvals."
  [{:keys [created user application] :as command} file-id new-state]
  {:pre [(number? created) (map? user) (map? application) (ss/not-blank? file-id) (#{:ok :requires_user_action} new-state)]}
  (let [attachment       (get-attachment-info-by-file-id application file-id)
        original-file-id (get-original-file-id attachment file-id)
        data             (->> (->approval new-state user created)
                              (util/map-keys (partial util/kw-path "approvals" original-file-id)))]
    (update-attachment-data! command (:id attachment) data created :set-app-modified? true :set-attachment-modified? false)))

(defn set-attachment-reject-note! [{:keys [created user application] :as command} file-id note]
  {:pre [(number? created) (map? user) (map? application) (ss/not-blank? file-id)]}
  (let [attachment (get-attachment-info-by-file-id application file-id)]
    (update-attachment-data! command
                             (:id attachment)
                             {(util/kw-path "approvals" (get-original-file-id attachment file-id) "note") note}
                             created
                             :set-app-modified? true
                             :set-attachment-modified? false)))

(defn convert-existing-to-pdfa! [application user attachment]
  {:pre [(map? application) (:id application) (:attachments application)]}
  (let [{:keys [archivable contentType]} (last (:versions attachment))]
    (if (or archivable (not ((conj conversion/libre-conversion-file-types :image/jpeg :application/pdf) (keyword contentType))))
      (fail :error.attachment.content-type)
      ;; else
      (let [{:keys [fileId filename user stamped]} (last (:versions attachment))
            file-content (mongo/download fileId)]
        (if (nil? file-content)
          (do
            (error "PDF/A conversion: No mongo file for fileId" fileId)
            (fail :error.attachment-not-found))
          (files/with-temp-file temp-pdf
            (with-open [content ((:content file-content))]
              (io/copy content temp-pdf)
              (upload-and-attach! {:application application :user user}
                                  {:attachment-id (:id attachment)
                                   :comment-text nil
                                   :required false
                                   :created (now)
                                   :stamped stamped
                                   :original-file-id fileId}
                                  {:content temp-pdf :filename filename}))))))))

(defn- manually-set-construction-time [{app-state :applicationState orig-app-state :originalApplicationState :as attachment}]
  (boolean (and (states/post-verdict-states (keyword app-state))
                ((conj states/all-application-states :info) (keyword orig-app-state)))))

(defn validate-attachment-manually-set-construction-time [{{:keys [attachmentId]} :data application :application :as command}]
  (when-not (manually-set-construction-time (get-attachment-info application attachmentId))
    (fail :error.attachment-not-manually-set-construction-time)))

(defn set-attachment-construction-time! [{app :application :as command} attachment-id value]
  (->> (construction-time-state-updates (get-attachment-info app attachment-id) value)
       (update-application command {:attachments {$elemMatch {:id attachment-id}}})))

(defn enrich-attachment [attachment]
  (assoc attachment
         :tags (att-tags/attachment-tags attachment)
         :manuallySetConstructionTime (manually-set-construction-time attachment)))

(defn- attachment-assignment-info
  "Return attachment info as assignment target"
  [{{:keys [type-group type-id]} :type contents :contents id :id :as doc}]
  (util/assoc-when-pred {:id id :type-key (ss/join "." ["attachmentType" type-group type-id])} ss/not-blank?
                        :description contents))

(defn- describe-assignment-targets [application]
  (let [attachments (map enrich-attachment (:attachments application))]
    (->> (att-tag-groups/attachment-tag-groups application)
         (att-tags/sort-by-tags attachments)
         (map attachment-assignment-info))))

(assignment/register-assignment-target! :attachments describe-assignment-targets)

;;
;; Enriching attachment
;;

(defn- assignment-trigger-tags [assignments attachment]
  (or (not-empty (->> (assignment/targeting-assignments assignments attachment)
                      (remove assignment/completed?)
                      (map #(assignment/assignment-tag (:trigger %)))
                      distinct))
      [(assignment/assignment-tag "not-targeted")]))

(defn- enrich-attachment-and-add-trigger-tags
  [assignments attachment]
  (update (enrich-attachment attachment) :tags
          #(concat % (assignment-trigger-tags assignments attachment))))

(defn enrich-attachment-with-trigger-tags [assignments attachment]
  (if assignments
    (enrich-attachment-and-add-trigger-tags assignments
                                            attachment)
    (enrich-attachment attachment)))


;;
;; Pre-checks
;;

(defn- get-target-type [{{attachment-id :attachmentId} :data application :application}]
  (-> (get-attachment-info application attachment-id) :target :type keyword))

(defmulti upload-to-target-allowed
  {:arglists '([command])}
  (fn [{{:keys [target]} :data}] (-> target :type keyword)))

(defmethod upload-to-target-allowed :default [_] nil)

(defmulti edit-allowed-by-target
  {:arglists '([command])}
  get-target-type)

(defmethod edit-allowed-by-target :default [_] nil)

(defmulti delete-allowed-by-target
  {:arglists '([command])}
  get-target-type)

(defmethod delete-allowed-by-target :default [{:keys [state] :as application}]
  (when (= :sent (keyword state))
    (fail :error.illegal-state)))

(defn attachment-is-needed [{{:keys [attachmentId]} :data application :application}]
  (when (and attachmentId (:notNeeded (get-attachment-info application attachmentId)))
    (fail :error.attachment.not-needed)))

(defn attachment-not-locked [{{:keys [attachmentId]} :data application :application}]
  (when (-> (get-attachment-info application attachmentId) attachment-is-locked?)
    (fail :error.attachment-is-locked)))

(defn attachment-not-readOnly [{{attachmentId :attachmentId} :data :keys [application user]}]
  (let [attachment (get-attachment-info application attachmentId)
        readonly-after-sent? (op/get-primary-operation-metadata application :attachments-readonly-after-sent)]
    (when (or (attachment-is-readOnly? attachment)
              (and readonly-after-sent?
                   (not (states/pre-sent-application-states (-> application :state keyword)))
                   (not (auth/application-authority? application user))))
      (fail :error.unauthorized
            :desc "Attachment is read only."))))

(defn attachment-matches-application
  ([{{:keys [attachmentId]} :data :as command}]
   (attachment-matches-application command attachmentId))
  ([{{:keys [attachments]} :application} attachmentId]
   (when-not (or (ss/blank? attachmentId) (some (comp #{attachmentId} :id) attachments))
     (fail :error.attachment.id))))

(defn foreman-must-be-uploader [{:keys [user application data] :as command}]
  (when (and (auth/has-auth-role? application (:id user) :foreman)
             (ss/not-blank? (:attachmentId data)))
    (access/has-attachment-auth-role :uploader command)))

(defn allowed-only-for-authority-when-application-sent
  "Pre-check is OK if the user is application authority or the
  application has an environmental permit type."
  [{:keys [application user]}]
  (when (util/=as-kw :sent (:state application))
    (when-not (or (auth/application-authority? application user)
                  (contains? #{:YI :YL :YM :VVVL :MAL} (-> application :permitType keyword)))
      (fail :error.unauthorized))))

(defn attachment-editable-by-application-state
  [{{attachmentId :attachmentId} :data user :user {current-state :state organization :organization :as application} :application}]
  (when-not (ss/blank? attachmentId)
    (let [{create-state :applicationState} (get-attachment-info application attachmentId)]
      (when-not (if (states/terminal-states (keyword current-state))
                  (usr/user-is-archivist? user organization)
                  (or (not (states/post-verdict-states (keyword current-state)))
                      (states/post-verdict-states (keyword create-state))
                      (usr/authority? user)))
        (fail :error.pre-verdict-attachment)))))

(defn validate-group-is-selectable [{application :application}]
  (when (false? (op/get-primary-operation-metadata application :attachment-op-selector))
    (fail :error.illegal-meta-type)))

(defn- validate-group-op [group]
  (when-let [operations (:operations group)]
    (when (sc/check [Operation] (map ->attachment-operation operations))
      (fail :error.illegal-attachment-operation))))

(defn- validate-group-type [group]
  (when-let [group-type (keyword (:groupType group))]
    (when-not ((set att-tags/attachment-groups) group-type)
      (fail :error.illegal-attachment-group-type))))

(defn- ensure-operation-id-exists [application op-id]
  (when-not (op/operation-id-exists? application op-id)
    (fail :error.illegal-attachment-operation)))

(defn validate-group
  "Validates :group from command against schema.
   If :operations are given, their existence in application is validated also."
  ([command]
   (validate-group [:group] command))
  ([group-path command]
   (let [group (get-in command (cons :data group-path))]
     (when (or (:groupType group) (not-empty (:operations group)))
       (or ((some-fn validate-group-op validate-group-type) group)
           (validate-group-is-selectable command)
           (->> (:operations group)
                (map :id)
                (some (partial ensure-operation-id-exists (:application command)))))))))
