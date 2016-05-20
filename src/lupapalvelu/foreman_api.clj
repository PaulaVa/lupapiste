(ns lupapalvelu.foreman-api
  (:require [clojure.set :as set]
            [taoensso.timbre :as timbre :refer [error]]
            [lupapalvelu.action :refer [defquery defcommand update-application] :as action]
            [lupapalvelu.application :as application]
            [lupapalvelu.authorization :as auth]
            [lupapalvelu.company :as company]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.document.persistence :as doc-persistence]
            [lupapalvelu.foreman :as foreman]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.notifications :as notif]
            [lupapalvelu.states :as states]
            [lupapalvelu.user :as user]
            [sade.core :refer :all]
            [sade.env :as env]
            [sade.strings :as ss]
            [sade.util :as util]
            [sade.validators :as v]
            [monger.operators :refer :all]))

(defcommand create-foreman-application
  {:parameters [id taskId foremanRole foremanEmail]
   :user-roles #{:applicant :authority}
   :input-validators [(partial action/email-validator :foremanEmail)]
   :states states/all-application-states
   :pre-checks [application/validate-authority-in-drafts
                application/validate-only-authority-before-verdict-given]}
  [{:keys [created user application] :as command}]
  (let [foreman-user   (when (v/valid-email? foremanEmail)
                         (user/get-or-create-user-by-email foremanEmail user))
        foreman-app    (-> (foreman/new-foreman-application command)
                           (foreman/update-foreman-docs application foremanRole)
                           (foreman/copy-auths-from-linked-app foreman-user application user created)
                           (foreman/add-foreman-invite-auth foreman-user user created))]
    (application/do-add-link-permit foreman-app (:id application))
    (application/insert-application foreman-app)
    (foreman/update-foreman-task-on-linked-app! application foreman-app taskId command)
    (foreman/send-invite-notifications! foreman-app foreman-user application command)
    (ok :id (:id foreman-app) :auth (:auth foreman-app))))

(defcommand update-foreman-other-applications
  {:user-roles #{:applicant :authority}
   :states     states/all-states
   :parameters [:id foremanHetu]
   :input-validators [(partial action/string-parameters [:foremanHetu])]
   :pre-checks [application/validate-authority-in-drafts]}
  [{application :application user :user :as command}]
  (let [foreman-applications (seq (foreman/get-foreman-project-applications application foremanHetu))
        other-applications (map #(foreman/other-project-document % (:created command)) foreman-applications)
        tyonjohtaja-doc (update-in (domain/get-document-by-name application "tyonjohtaja-v2") [:data :muutHankkeet]
                                   (fn [muut-hankkeet]
                                     (->> (vals muut-hankkeet)
                                          (remove #(get-in % [:autoupdated :value]))
                                          (concat other-applications)
                                          (zipmap (map (comp keyword str) (range))))))
        documents (util/replace-by-id tyonjohtaja-doc (:documents application))]
    (update-application command {$set {:documents documents}}))
  (ok))

(defcommand link-foreman-task
  {:user-roles #{:applicant :authority}
   :states states/all-states
   :parameters [id taskId foremanAppId]
   :input-validators [(partial action/non-blank-parameters [:id :taskId])]
   :pre-checks [application/validate-authority-in-drafts
                foreman/ensure-foreman-not-linked]}
  [{:keys [created application] :as command}]
  (let [task (util/find-by-id taskId (:tasks application))]
    (if task
      (let [updates [[[:asiointitunnus] foremanAppId]]]
        (doc-persistence/persist-model-updates application "tasks" task updates created))
      (fail :error.not-found))))

(defn foreman-app-check [_ application]
  (when-not (foreman/foreman-app? application)
    (fail :error.not-foreman-app)))

(defquery foreman-history
  {:user-roles #{:authority}
   :states           states/all-states
   :user-authz-roles auth/all-authz-roles
   :org-authz-roles  auth/reader-org-authz-roles
   :parameters       [:id]
   :pre-checks       [foreman-app-check]}
  [{application :application user :user :as command}]
  (if application
    (ok :projects (foreman/get-foreman-history-data application))
    (fail :error.not-found)))

(defquery reduced-foreman-history
  {:user-roles #{:authority}
   :states           states/all-states
   :user-authz-roles auth/all-authz-roles
   :org-authz-roles  auth/reader-org-authz-roles
   :parameters       [:id]
   :pre-checks       [foreman-app-check]}
  [{application :application user :user :as command}]
  (if application
    (ok :projects (foreman/get-foreman-reduced-history-data application))
    (fail :error.not-found)))

(defquery foreman-applications
  {:user-roles #{:applicant :authority :oirAuthority}
   :states           states/all-states
   :user-authz-roles auth/all-authz-roles
   :org-authz-roles  auth/reader-org-authz-roles
   :parameters       [id]}
  [{application :application user :user :as command}]
  (let [app-link-resp (mongo/select :app-links {:link {$in [id]}})
        apps-linking-to-us (filter #(= (:type ((keyword id) %)) "linkpermit") app-link-resp)
        foreman-application-links (filter #(= (:apptype (first (:link %)) "tyonjohtajan-nimeaminen")) apps-linking-to-us)
        foreman-application-ids (map (fn [link] (first (:link link))) foreman-application-links)
        applications (mongo/select :applications {:_id {$in foreman-application-ids}} [:id :state :auth :documents])
        mapped-applications (map foreman/foreman-application-info applications)]
    (ok :applications (sort-by :id mapped-applications))))
