(ns lupapalvelu.actions-api
  (:require [clojure.set :refer [difference]]
            [sade.env :as env]
            [sade.core :refer :all]
            [sade.util :refer [fn-> fn->>]]
            [lupapalvelu.action :refer [defquery] :as action]
            [lupapalvelu.roles :as roles]))

;;
;; Default actions
;;

(defquery actions
  {:user-roles #{:admin}
   :description "List of all actions and their meta-data."} [_]
  (ok :actions (action/serializable-actions)))

(defquery allowed-actions
  {:user-roles       #{:anonymous}
   :user-authz-roles roles/all-authz-roles
   :org-authz-roles  roles/reader-org-authz-roles}
  [command]
  (ok :actions (->> (action/foreach-action command)
                    (action/validate-actions))))

(defquery allowed-actions-for-category
  {:description      "Returns map of allowed actions for a category (attachments, tasks, etc.)"
   :user-roles       #{:anonymous}
   :user-authz-roles roles/all-authz-roles
   :org-authz-roles  roles/reader-org-authz-roles}
  [command]
  (if-let [actions-by-id (action/allowed-actions-for-category command)]
    (ok :actionsById actions-by-id)
    (fail :error.invalid-category)))
