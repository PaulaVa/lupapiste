(ns lupapalvelu.kopiolaitos-api
  (:require [sade.core :refer [ok fail]]
            [sade.util :as util]
            [sade.strings :as ss]
            [lupapalvelu.action :refer [defquery defcommand] :as action]
            [lupapalvelu.states :as states]
            [lupapalvelu.kopiolaitos :as kopiolaitos]))

(defn- attachment-amounts-validator [{{attachments :attachmentsWithAmounts} :data}]
  (when (some #(or (nil? (util/->int (:amount %) nil)) (ss/blank? (:id %))) attachments)
    (fail :error.kopiolaitos-print-order-invalid-parameters-content)))

(defn- any-attachment-is-printable [{{attachments :attachments} :application}]
  (when-not (some #(and (:forPrinting %) (not-empty (:versions %))) attachments)
    (fail :error.kopiolaitos-no-printable-attachments)))

(defcommand order-verdict-attachment-prints
  {:description "Orders prints of marked verdict attachments from copy institute.
                 If the command is run more than once, the already ordered attachment copies are ordered again."
   :parameters [:id :lang :attachmentsWithAmounts :orderInfo]
   :states     states/all-but-draft-or-terminal
   :user-roles #{:authority}
   :input-validators [(partial action/non-blank-parameters [:lang])
                      (partial action/map-parameters-with-required-keys [:orderInfo]
                        [:address :ordererPhone :kuntalupatunnus :ordererEmail :ordererAddress :ordererOrganization :applicantName :propertyId :lupapisteId])
                      (partial action/vector-parameters-with-map-items-with-required-keys [:attachmentsWithAmounts] [:id :amount])
                      attachment-amounts-validator]
   :pre-checks [any-attachment-is-printable]}
  [command]
  (kopiolaitos/do-order-verdict-attachment-prints command))
