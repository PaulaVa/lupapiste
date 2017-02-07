(ns lupapalvelu.application
  (:require [taoensso.timbre :as timbre :refer [trace debug debugf info infof warnf error fatal]]
            [clj-time.core :refer [year]]
            [clj-time.local :refer [local-now]]
            [clojure.set :refer [difference]]
            [clojure.walk :refer [keywordize-keys]]
            [monger.operators :refer :all]
            [lupapalvelu.action :as action]
            [lupapalvelu.application-meta-fields :as meta-fields]
            [lupapalvelu.application-utils :refer [location->object]]
            [lupapalvelu.assignment :as assignment]
            [lupapalvelu.attachment :as att]
            [lupapalvelu.attachment.type :as att-type]
            [lupapalvelu.authorization :as auth]
            [lupapalvelu.company :as com]
            [lupapalvelu.comment :as comment]
            [lupapalvelu.document.model :as model]
            [lupapalvelu.document.schemas :as schemas]
            [lupapalvelu.document.tools :as tools]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.link-permit :as link-permit]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.organization :as org]
            [lupapalvelu.operations :as op]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.tiedonohjaus :as tos]
            [lupapalvelu.user :as usr]
            [lupapalvelu.states :as states]
            [lupapalvelu.state-machine :as sm]
            [sade.core :refer :all]
            [sade.env :as env]
            [sade.property :as prop]
            [sade.util :as util]
            [sade.coordinate :as coord]
            [sade.schemas :as ssc]))

(defn get-operations [application]
  (remove nil? (conj (seq (:secondaryOperations application)) (:primaryOperation application))))

(defn resolve-valid-subtypes
  "Returns a set of valid permit and operation subtypes for the application."
  [{permit-type :permitType op :primaryOperation}]
  (let [op-subtypes (op/get-primary-operation-metadata {:primaryOperation op} :subtypes)
        permit-subtypes (permit/permit-subtypes permit-type)]
    (concat op-subtypes permit-subtypes)))

(defn history-entry [to-state timestamp user]
  {:state to-state, :ts timestamp, :user (usr/summary user)})

(defn tos-history-entry [tos-function timestamp user & [correction-reason]]
  {:pre [(map? tos-function)]}
  {:tosFunction tos-function
   :ts timestamp
   :user (usr/summary user)
   :correction correction-reason})

(defn authority-history-entry [authority timestamp user]
  {:authority authority
   :ts timestamp
   :user (usr/summary user)})

(defn handler-history-entry [handler timestamp user]
  {:handler handler
   :ts timestamp
   :user (usr/summary user)})

;;
;; Validators
;;

(defn- count-link-permits [application]
  (count (or (:linkPermitData application)
             (:linkPermitData (meta-fields/enrich-with-link-permit-data application)))))

(defn- required-link-permits [application]
  (let [muutoslupa? (= :muutoslupa (keyword (:permitSubtype application)))]
    (->> (get-operations application)
         (map :name)
         (map op/required-link-permits)
         (reduce + (if muutoslupa? 1 0)))))

(defn validate-link-permits [application]
  (when (> (required-link-permits application) (count-link-permits application))
    (fail :error.permit-must-have-link-permit)))

(defn authorized-to-remove-link-permit [{user :user application :application}]
  (when (and (not (usr/authority? user))
             (>= (required-link-permits application) (count-link-permits application)))
    unauthorized))

(defn validate-only-authority-before-verdict-given
  "Validator: Restrict applicant access before the application verdict
  is given. To be used in commands' :pre-checks vector"
  [{user :user app :application}]
  (when-not (or (states/post-verdict-states (keyword (:state app)))
                (usr/authority? user))
    unauthorized))

(defn validate-authority-in-drafts
  "Validator: Restrict authority access in draft application.
   To be used in commands' :pre-checks vector."
  [{user :user application :application}]
  (when (and (= :draft (keyword (:state application)))
             (usr/authority? user)
             (not (domain/owner-or-write-access? application (:id user))))
    unauthorized))

(defn validate-has-subtypes [{application :application}]
  (when (empty? (resolve-valid-subtypes application))
    (fail :error.permit-has-no-subtypes)))

(defn pre-check-permit-subtype [{data :data application :application}]
  (when-let [subtype (:permitSubtype data)]
    (when-not (util/contains-value? (resolve-valid-subtypes application) (keyword subtype))
      (fail :error.permit-has-no-such-subtype))))

(defn submitted? [{:keys [state]}]
  (boolean (states/post-submitted-states (keyword state))))

(defn- contains-primary-operation? [application op-set]
  {:pre [(set? op-set)]}
  (contains? op-set (-> application :primaryOperation :name keyword)))

(defn allow-primary-operations
  "Prechecker (factory, no partial needed) that fails if the current
  primary operation is not contained in the operation set (keywords)"
  [operation-set]
  (fn [{application :application}]
    (when-not (contains-primary-operation? application operation-set)
      (fail :error.unsupported-primary-operation))))

(defn reject-primary-operations
  "Prechecker (factory, no partial needed) that fails if the current
  primary operation is contained in the operation set (keywords)"
  [operation-set]
  (fn [{application :application}]
    (when (contains-primary-operation? application operation-set)
      (fail :error.unsupported-primary-operation))))

;;
;; Helpers
;;

(defn party-document? [doc]
  (= :party (tools/doc-type doc)))

(defn user-role
  "User role within the application."
  [user {:keys [organization]}]
  (if (usr/user-is-authority-in-organization? user organization)
    :authority
    :applicant))

(defn insert-application [application]
  {:pre [(every? (partial contains? application)  (keys domain/application-skeleton))]}
  (mongo/insert :applications (merge application (meta-fields/applicant-index application))))

(defn filter-party-docs [schema-version schema-names repeating-only?]
  (filter (fn [schema-name]
            (let [schema-info (:info (schemas/get-schema schema-version schema-name))]
              (and (= (:type schema-info) :party) (or (:repeating schema-info) (not repeating-only?)) )))
          schema-names))

(defn state-history-entries [history]
  (filter :state history)) ; only history elements that regard state change

(defn last-history-item
  [{history :history}]
  (last (sort-by :ts history)))

(defn get-previous-app-state
  "Returns second last history item's state as keyword. Recognizes only items with not nil :state."
  [{history :history}]
  (->> (state-history-entries history)
       (sort-by :ts)
       butlast
       last
       :state
       keyword))

(defn enrich-application-handlers [application {roles :handler-roles :as organization}]
  (update application :handlers (partial map #(merge (util/find-by-id (:roleId %) roles) %))))

; Seen updates
(def collections-to-be-seen #{"comments" "statements" "verdicts" "authority-notices" "info-links"})

(defn mark-collection-seen-update [{id :id} timestamp collection]
  {:pre [(collections-to-be-seen collection) id timestamp]}
  {(str "_" collection "-seen-by." id) timestamp})

(defn mark-indicators-seen-updates [application user timestamp]
  (merge
    (apply merge (map (partial mark-collection-seen-update user timestamp) collections-to-be-seen))
    (when (usr/authority? user) (model/mark-approval-indicators-seen-update application timestamp))
    (when (usr/authority? user) {:_attachment_indicator_reset timestamp})))

; Masking
(defn- person-id-masker-for-user [user {handlers :handlers :as application}]
  (cond
    (util/find-by-key :userId (:id user) handlers) identity
    (auth/application-authority? application user) model/mask-person-id-ending
    :else (comp model/mask-person-id-birthday model/mask-person-id-ending)))

(defn with-masked-person-ids [application user]
  (let [mask-person-ids (person-id-masker-for-user user application)]
    (update-in application [:documents] (partial map mask-person-ids))))


; whitelist-action
(defn- prefix-with [prefix coll]
  (conj (seq coll) prefix))

(defn- enrich-single-doc-disabled-flag [{user-role :role} doc]
  (let [doc-schema (model/get-document-schema doc)
        zip-root (tools/schema-zipper doc-schema)
        whitelisted-paths (tools/whitelistify-schema zip-root)]
    (reduce (fn [new-doc [path whitelist]]
              (if-not ((set (:roles whitelist)) (keyword user-role))
                (tools/update-in-repeating new-doc (prefix-with :data path) merge {:whitelist-action (:otherwise whitelist)})
                new-doc))
            doc
            whitelisted-paths)))

; Process

(defn pertinent-validation-errors [{:keys [documents] :as  application}]
  (map (partial model/validate-pertinent application) documents))

(defn validate-fully-formed
 "If the application's organization requires fully-formed
  applications, the following checks are enforced:
  1. All the required fields are valid
  2. Every required attachment is either filled or marked not needed
  This function is called from submit-validation-errors on the api side.
  Returns nil on success and fail map if the application is not fully formed."
  [application]
  (when (and (some-> application
                     :organization
                     org/get-organization
                     :app-required-fields-filling-obligatory)
             (or (->> (pertinent-validation-errors application)
                      flatten
                      (some #(-> % :element :required)))
                 (some (fn [{:keys [required notNeeded versions]}]
                         (and required (not notNeeded) (empty? versions)))
                       (:attachments application))))
    (fail :application.requiredDataDesc)))

(defn- validate [application document]
  (let [all-results   (model/validate-pertinent application document)
        create-result (fn [document result]
                        (assoc-in document (flatten [:data (:path result) :validationResult]) (:result result)))]
    (assoc (reduce create-result document all-results) :validationErrors all-results)))

(defn- populate-operation-info [operations {info :schema-info :as doc}]
  (if (:op info)
    (if-let [operation (util/find-first #(= (:id %) (get-in info [:op :id])) operations)]
      (assoc-in doc [:schema-info :op] operation)
      (do
        (warnf "Couldn't find operation %s for doc %s " (get-in info [:op :id]) (:id doc))
        doc))
    doc))

(defn process-document-or-task [user application doc]
  (->> (validate application doc)
       (populate-operation-info (get-operations application))
       ((person-id-masker-for-user user application))
       (enrich-single-doc-disabled-flag user)))

(defn- process-documents-and-tasks [user application]
  (let [mapper (partial process-document-or-task user application)]
    (-> application
      (update :documents (partial map mapper))
      (update :tasks (partial map mapper)))))

(defn ->location [x y]
  [(util/->double x) (util/->double y)])

(defn get-link-permit-apps
  "Return associated link-permit application."
  [{:keys [linkPermitData]}]
  (when-let [links (not-empty (filter (comp #{"lupapistetunnus"} :type) linkPermitData))]
    (->> (map :id links)
         (domain/get-multiple-applications-no-access-checking))))

;;
;; Application query post process
;;

(defn- with-auth-models [{:keys [application] :as command}]
  (let [document-authz (action/allowed-actions-for-category (assoc-in command [:data :category] "documents"))]
    (update application :documents #(map (fn [doc] (assoc doc :allowedActions (get document-authz (:id doc)))) %))))

(def merge-operation-skeleton (partial merge domain/operation-skeleton))

(defn- with-allowed-attachment-types [application]
  (assoc application :allowedAttachmentTypes (->> (att-type/get-attachment-types-for-application application)
                                                  (att-type/->grouped-array))))
(defn ensure-operations
  "Ensure operations have all properties set."
  [app]
  (-> app
      (update :primaryOperation merge-operation-skeleton)
      (update :secondaryOperations (fn [operations] (map merge-operation-skeleton operations)))))

;; Meta fields with default values.
(def- operation-meta-fields-to-enrich {:attachment-op-selector true, :optional []})
(defn- enrich-primary-operation-with-metadata [app]
  (let [enrichable-fields (-> (op/get-primary-operation-metadata app)
                              (select-keys (keys operation-meta-fields-to-enrich)))
        fields-with-defaults (merge operation-meta-fields-to-enrich enrichable-fields)]
    (update app :primaryOperation merge fields-with-defaults)))


(defn post-process-app [{:keys [user] :as command}]
  (->> (with-auth-models command)
       with-allowed-attachment-types
       ensure-operations
       enrich-primary-operation-with-metadata
       att/post-process-attachments
       meta-fields/enrich-with-link-permit-data
       (meta-fields/with-meta-fields user)
       action/without-system-keys
       (process-documents-and-tasks user)
       location->object))

(defn post-process-app-for-krysp [application organization]
  (-> application
      (domain/enrich-application-handlers organization)
      meta-fields/enrich-with-link-permit-data
      link-permit/update-backend-ids-in-link-permit-data))

;;
;; Application creation
;;

(defn- new-attachment-types-for-operation [organization operation existing-types]
  (->> (org/get-organization-attachments-for-operation organization operation)
       (map (partial apply att-type/attachment-type))
       (filter #(or (att-type/operation-specific? %) (not (att-type/contains? existing-types %))))))

(defn- attachment-grouping-for-type [operation {{group-type :grouping} :metadata}]
  (when-not (false? (op/get-operation-metadata (:name operation) :attachment-op-selector))
    (util/assoc-when nil :groupType group-type :operations (when (and operation (= group-type :operation)) [operation]))))

(defn make-attachments
  [created operation organization applicationState tos-function & {:keys [target existing-attachments-types]}]
  (let [types     (new-attachment-types-for-operation organization operation existing-attachments-types)
        groups    (map (partial attachment-grouping-for-type operation) types)
        metadatas (map (partial tos/metadata-for-document (:id organization) tos-function) types)]
    (map (partial att/make-attachment created target true false false (keyword applicationState)) groups types metadatas)))

(defn multioperation-attachment-updates [operation organization attachments]
  (when-let [added-op (not-empty (select-keys operation [:id :name]))]
    (let [required-types (->> (org/get-organization-attachments-for-operation organization operation)
                              (map (partial apply att-type/attachment-type)))
          ops-to-update (keep-indexed (fn [ind att]
                                        (when (and (att-type/multioperation? (:type att))
                                                   (att-type/contains? required-types (:type att))
                                                   (= (keyword (:groupType att)) :operation))
                                          [(util/kw-path "attachments" ind "op") (:op att)]))
                                      attachments)]
      (util/assoc-when-pred nil not-empty
                            $set  (->> (remove (comp vector? second) ops-to-update) ; Update legacy op
                                       (util/map-values #(->> [% added-op] (remove nil?))))
                            $push (->> (filter (comp vector? second) ops-to-update) ; Update op array
                                       (util/map-values (constantly added-op)))))))

(defn- schema-data-to-body [schema-data application]
  (keywordize-keys
    (reduce
      (fn [body [data-path data-value]]
        (let [path (if (= :value (last data-path)) data-path (conj (vec data-path) :value))
              val (if (fn? data-value) (data-value application) data-value)]
          (assoc-in body path val)))
      {} schema-data)))

(defn make-documents [user created org op application & [manual-schema-datas]]
  {:pre [(or (nil? manual-schema-datas) (map? manual-schema-datas))]}
  (let [op-info (op/operations (keyword (:name op)))
        op-schema-name (:schema op-info)
        schema-version (:schema-version application)
        default-schema-datas (util/assoc-when-pred {} util/not-empty-or-nil?
                                                   op-schema-name (:schema-data op-info))
        merged-schema-datas (merge-with conj default-schema-datas manual-schema-datas)
        make (fn [schema]
               {:pre [(:info schema)]}
               (let [schema-name (get-in schema [:info :name])]
                 {:id          (mongo/create-id)
                  :schema-info (:info schema)
                  :created     created
                  :data        (util/deep-merge
                                 (tools/create-document-data schema tools/default-values)
                                 (tools/timestamped
                                   (if-let [schema-data (get-in merged-schema-datas [schema-name])]
                                     (schema-data-to-body schema-data application)
                                     {})
                                   created))}))
        ;;The merge below: If :removable is set manually in schema's info, do not override it to true.
        op-doc (update-in (make (schemas/get-schema schema-version op-schema-name)) [:schema-info] #(merge {:op op :removable true} %))

        existing-schemas-infos (map :schema-info (:documents application))
        existing-schema-names (set (map :name existing-schemas-infos))

        location-schema (util/find-first #(= (keyword (:type %)) :location) existing-schemas-infos)

        schemas (->> (when (not-empty (:org-required op-info)) ((apply juxt (:org-required op-info)) org))
                     (concat (:required op-info))
                     (map #(schemas/get-schema schema-version %)))
        new-docs (->> schemas
                      (remove (comp existing-schema-names :name :info))
                      (remove
                        (fn [{{:keys [type repeating]} :info}]
                          (and location-schema (= type :location) (not repeating))))
                      (map make)                           ;; required docs
                      (cons op-doc))]                      ;; new docs
    (if-not user
      new-docs
      (conj new-docs (make (schemas/get-schema schema-version (op/get-applicant-doc-schema-name application)))))))


(defn make-op [op-name created]
  {:id          (mongo/create-id)
   :name        op-name
   :description nil
   :created     created})

(defn make-application-id [municipality]
  (let [year (str (year (local-now)))
        sequence-name (str "applications-" municipality "-" year)
        counter (if (env/feature? :prefixed-id)
                  (format "9%04d" (mongo/get-next-sequence-value sequence-name))
                  (format "%05d"  (mongo/get-next-sequence-value sequence-name)))]
    (str "LP-" municipality "-" year "-" counter)))

(defn make-application [id operation-name x y address property-id municipality organization info-request? open-inforequest? messages user created manual-schema-datas]
  {:pre [id operation-name address property-id (not (nil? info-request?)) (not (nil? open-inforequest?)) user created]}
  (let [permit-type (op/permit-type-of-operation operation-name)
        owner (merge (usr/user-in-role user :owner :type :owner)
                     {:unsubscribed (= (keyword operation-name) :aiemmalla-luvalla-hakeminen)})
        op (make-op operation-name created)
        state (cond
                info-request? :info
                (or (usr/user-is-authority-in-organization? user (:id organization)) (usr/rest-user? user)) :open
                :else :draft)
        comment-target (if open-inforequest? [:applicant :authority :oirAuthority] [:applicant :authority])
        tos-function (get-in organization [:operations-tos-functions (keyword operation-name)])
        tos-function-map (tos/tos-function-with-name tos-function (:id organization))
        classification {:permitType permit-type, :primaryOperation op}
        attachments (when-not info-request? (make-attachments created op organization state tos-function))
        metadata (tos/metadata-for-document (:id organization) tos-function "hakemus")
        process-metadata (tos/calculate-process-metadata (tos/metadata-for-process (:id organization) tos-function) metadata attachments)
        application (merge domain/application-skeleton
                      classification
                      {:id                  id
                       :created             created
                       :opened              (when (#{:open :info} state) created)
                       :modified            created
                       :permitSubtype       (first (resolve-valid-subtypes classification))
                       :infoRequest         info-request?
                       :openInfoRequest     open-inforequest?
                       :secondaryOperations []
                       :state               state
                       :history             (cond->> [(history-entry state created user)]
                                                     tos-function-map (concat [(tos-history-entry tos-function-map created user)]))
                       :municipality        municipality
                       :location            (->location x y)
                       :location-wgs84      (coord/convert "EPSG:3067" "WGS84" 5 (->location x y))
                       :organization        (:id organization)
                       :address             address
                       :propertyId          property-id
                       :title               address
                       :auth                (if-let [company (some-> user :company :id com/find-company-by-id com/company->auth)]
                                              [owner company]
                                              [owner])
                       :comments            (map #(domain/->comment % {:type "application"} (:role user) user nil created comment-target) messages)
                       :schema-version      (schemas/get-latest-schema-version)
                       :tosFunction         tos-function
                       :metadata            metadata
                       :processMetadata     process-metadata})]
    (merge application (when-not info-request?
                         {:attachments attachments
                          :documents   (make-documents user created organization op application manual-schema-datas)}))))

(defn do-create-application
  [{{:keys [operation x y address propertyId infoRequest messages]} :data :keys [user created] :as command} & [manual-schema-datas]]
  (let [municipality      (prop/municipality-id-by-property-id propertyId)
        permit-type       (op/permit-type-of-operation operation)
        organization      (org/resolve-organization municipality permit-type)
        scope             (org/resolve-organization-scope municipality permit-type organization)
        organization-id   (:id organization)
        info-request?     (boolean infoRequest)
        open-inforequest? (and info-request? (:open-inforequest scope))]

    (when-not organization-id
      (fail! :error.missing-organization :municipality municipality :permit-type permit-type :operation operation))
    (if info-request?
      (when-not (:inforequest-enabled scope)
        (fail! :error.inforequests-disabled))
      (when-not (:new-application-enabled scope)
        (fail! :error.new-applications-disabled)))

    (let [id (make-application-id municipality)]
      (make-application id operation x y address propertyId municipality organization info-request? open-inforequest? messages user created manual-schema-datas))))

;;
;; Link permit
;;

(defn make-mongo-id-for-link-permit [app-id link-permit-id]
  (if (<= (compare app-id link-permit-id) 0)
    (str app-id "|" link-permit-id)
    (str link-permit-id "|" app-id)))

(defn do-add-link-permit [{:keys [id propertyId primaryOperation] :as application} link-permit-id]
  {:pre [(mongo/valid-key? link-permit-id)
         (not= id link-permit-id)]}
  (let [db-id (make-mongo-id-for-link-permit id link-permit-id)
        link-application (some-> link-permit-id
                     domain/get-application-no-access-checking
                     meta-fields/enrich-with-link-permit-data)
        max-incoming-link-permits (op/get-primary-operation-metadata link-application :max-incoming-link-permits)
        allowed-link-permit-types (op/get-primary-operation-metadata application :allowed-link-permit-types)]

    (when link-application
      (if (and max-incoming-link-permits (>= (count (:appsLinkingToUs link-application)) max-incoming-link-permits))
        (fail! :error.max-incoming-link-permits))

      (if (and allowed-link-permit-types (not (allowed-link-permit-types (permit/permit-type link-application))))
        (fail! :error.link-permit-wrong-type)))

    (mongo/update-by-id :app-links db-id
                        {:_id           db-id
                         :link          [id link-permit-id]
                         id             {:type       "application"
                                         :apptype    (:name primaryOperation)
                                         :propertyId propertyId}
                         link-permit-id {:type           "linkpermit"
                                         :linkpermittype (if link-application
                                                           "lupapistetunnus"
                                                           "kuntalupatunnus")
                                         :apptype (get-in link-application [:primaryOperation :name])}}
                        :upsert true)))

;; Submit

(defn submit [{:keys [application created user] :as command} ]
  (let [history-entries (remove nil?
                                [(when-not (:opened application) (history-entry :open created user))
                                 (history-entry :submitted created user)])]
    (action/update-application command
                               {$set {:state     :submitted
                                      :modified  created
                                      :opened    (or (:opened application) created)
                                      :submitted (or (:submitted application) created)}
                                $push {:history {$each history-entries}}}))
  (try
    (mongo/insert :submitted-applications (-> application
                                              meta-fields/enrich-with-link-permit-data
                                              (dissoc :id)
                                              (assoc :_id (:id application))))
    (catch com.mongodb.DuplicateKeyException e
      ; This is ok. Only the first submit is saved.
      )))

;;
;; Updates
;;

(def timestamp-key
  (merge
    ; Currently used states
    {:draft :created
     :open :opened
     :submitted :submitted
     :sent :sent
     :complementNeeded :complementNeeded
     :verdictGiven nil
     :constructionStarted :started
     :acknowledged :acknowledged
     :foremanVerdictGiven nil
     :closed :closed
     :canceled :canceled}
    ; New states, timestamps to be determined
    (zipmap
      [:appealed
       :extinct
       :hearing
       :final
       :survey
       :sessionHeld
       :proposal
       :registered
       :proposalApproved
       :sessionProposal
       :inUse
       :onHold]
      (repeat nil))))

(assert (= states/all-application-states (set (keys timestamp-key))))

(def two-years-ms 63072000000)

(defn warranty-period [timestamp]
  {:warrantyStart timestamp,
   :warrantyEnd (+ timestamp two-years-ms)})

(defn state-transition-update
  "Returns a MongoDB update map for state transition"
  [to-state timestamp application user]
  (let [ts-key (timestamp-key to-state)]
    {$set (merge {:state to-state, :modified timestamp}
                 (when (and ts-key (not (ts-key application))) {ts-key timestamp}))
     $push {:history (history-entry to-state timestamp user)}}))

(defn change-application-state-targets
  "Namesake query implementation."
  [{:keys [state] :as application}]
  (let [state (keyword state)
        graph (sm/state-graph application)
        [verdict-state] (filter #{:foremanVerdictGiven :verdictGiven} (keys graph))
        target (if (= state :appealed) :appealed verdict-state)]
    (set (cons state (remove #{:canceled} (target graph))))))

(defn valid-new-state
  "Pre-check for change-application-state command."
  [{{new-state :state} :data application :application}]
  (when-not (or (nil? new-state)
                ((change-application-state-targets application) (keyword new-state)))
    (fail :error.illegal-state)))

(defn application-org-authz-users
  [{org-id :organization :as application} org-authz]
  (->> (usr/find-authorized-users-in-org org-id org-authz)
       (map #(select-keys % [:id :firstName :lastName]))))

;; Cancellation

(defn- remove-app-links [id]
  (mongo/remove-many :app-links {:link {$in [id]}}))

(defn cancel-inforequest [{:keys [created user data application] :as command}]
  {:pre [(seq (:application command))]}
  (action/update-application command (state-transition-update :canceled created application user))
  (remove-app-links (:id application))
  (assignment/cancel-assignments (:id application))
  (ok))

(defn cancel-application
  [{created :created {:keys [id state] :as application} :application {:keys [role] :as user} :user {:keys [lang text]} :data :as command}]
  (let [comment-text (str (i18n/localize lang "application.canceled.text") ". "
                          (i18n/localize lang "application.canceled.reason") ": "
                          text)]
    (->> (util/deep-merge
          (state-transition-update :canceled created application user)
          (when (seq text)
            (comment/comment-mongo-update state comment-text {:type "application"} role false user nil created)))
         (action/update-application command)))
  (remove-app-links id)
  (assignment/cancel-assignments id)
  (ok))

(defn undo-cancellation
  [{:keys [application created user] :as command}]
  (action/update-application command
                             {:state :canceled}
                             (merge
                               (state-transition-update (get-previous-app-state application) created application user)
                               {$unset {:canceled 1}}))
  (assignment/activate-assignments (:id application))
  (ok))

(defn handler-upsert-updates [handler handlers created user]
  (let [ind (util/position-by-id (:id handler) handlers)]
    {$set  {:modified created
            (util/kw-path :handlers (or ind (count handlers))) handler}
     $push {:history (handler-history-entry (util/assoc-when handler :new-entry (nil? ind)) created user)}}))
