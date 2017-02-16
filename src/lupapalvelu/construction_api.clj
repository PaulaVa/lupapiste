(ns lupapalvelu.construction-api
  (:require [monger.operators :refer [$set $elemMatch]]
            [lupapalvelu.action :refer [defcommand update-application notify defquery] :as action]
            [lupapalvelu.application :as app]
            [lupapalvelu.application-meta-fields :as meta-fields]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.xml.krysp.application-as-krysp-to-backing-system :as mapping-to-krysp]
            [lupapalvelu.state-machine :as state-machine]
            [sade.core :refer :all]
            [sade.util :as util]))


;;
;; Inform construction started & ready
;;

(defn- save-as-krysp-if-possible
  [application organization lang app-updates]
  (if (organization/krysp-integration? organization (permit/permit-type application))
    (let [krysp-app (app/post-process-app-for-krysp (merge application app-updates) organization)]
      (mapping-to-krysp/save-application-as-krysp krysp-app lang krysp-app organization)
      true)
    false))

(defcommand inform-construction-started
  {:parameters       ["id" startedTimestampStr lang]
   :user-roles       #{:applicant :authority}
   :states           #{:verdictGiven}
   :notified         true
   :on-success       (notify :application-state-change)
   :pre-checks       [(permit/validate-permit-type-is permit/YA)]
   :input-validators [(partial action/non-blank-parameters [:startedTimestampStr])]}
  [{:keys [user created application organization] :as command}]
  (let [timestamp   (util/to-millis-from-local-date-string startedTimestampStr)
        app-updates {:startedBy (select-keys user [:id :firstName :lastName])
                     :started   timestamp}
        krysp?      (save-as-krysp-if-possible application @organization lang app-updates)]
    (update-application command (util/deep-merge
                                 (app/state-transition-update :constructionStarted created application user)
                                 {$set app-updates}))
    (ok :integrationAvailable krysp?)))

(defcommand inform-construction-ready
  {:parameters       ["id" readyTimestampStr lang]
   :user-roles       #{:authority}
   :states           #{:constructionStarted}
   :on-success       (notify :application-state-change)
   :pre-checks       [(permit/validate-permit-type-is permit/YA)
                      (partial state-machine/validate-state-transition :closed)]
   :input-validators [(partial action/non-blank-parameters [:readyTimestampStr])]}
  [{user :user created :created orig-app :application org :organization :as command}]
  (let [timestamp   (util/to-millis-from-local-date-string readyTimestampStr)
        app-updates {:modified created
                     :closed   timestamp
                     :closedBy (select-keys user [:id :firstName :lastName])
                     :state    :closed}
        krysp?      (save-as-krysp-if-possible orig-app @org lang app-updates)]

    (update-application command (util/deep-merge
                                  (app/state-transition-update :closed created orig-app user)
                                   (if krysp?
                                     {$set app-updates}
                                     {$set (merge app-updates (app/warranty-period timestamp))})))
    (ok :integrationAvailable krysp?)))

(defquery info-construction-status
  {:parameters [id]
   :states #{:verdictGiven :constructionStarted :closed}
   :user-roles #{:applicant :authority}
   :pre-checks [(permit/validate-permit-type-is permit/YA)]}
   [_])
