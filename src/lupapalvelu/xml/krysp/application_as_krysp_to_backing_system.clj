(ns lupapalvelu.xml.krysp.application-as-krysp-to-backing-system
  (:require [lupapalvelu.xml.krysp.rakennuslupa-mapping :as rl-mapping]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.permit :as permit]
            [sade.env :as env]
            [me.raynes.fs :as fs]))

(defn- get-output-directory [permit-type organization]
  (let [sftp-user-key (permit/get-sftp-user-key permit-type)]
    (str (env/value :outgoing-directory) "/" (get organization sftp-user-key) (permit/get-sftp-directory permit-type))))

(defn- get-begin-of-link [permit-type]
  (str (env/value :fileserver-address) (permit/get-sftp-directory permit-type) "/"))

(defn save-application-as-krysp [application lang submitted-application organization]
  (assert (= (:id application) (:id submitted-application)) "Not same application ids.")
  (let [permit-type (permit/permit-type application)
        krysp-fn   (permit/get-application-mapper permit-type)
        output-dir (get-output-directory permit-type organization)
        begin-of-link  (get-begin-of-link permit-type)]
    (krysp-fn application lang submitted-application output-dir begin-of-link)))

(defn save-review-as-krysp [application task-id]
  (let [permit-type (permit/permit-type application)
        organization (organization/get-organization (:organization application))
        krysp-fn   (permit/get-application-mapper permit-type)
        output-dir (get-output-directory permit-type organization)
        begin-of-link  (get-begin-of-link permit-type)]
    ;(krysp-fn application lang submitted-application output-dir begin-of-link)
    )
  )

(defn save-unsent-attachments-as-krysp [application lang organization]
  (let [permit-type (permit/permit-type application)
        output-dir (get-output-directory permit-type organization)
        begin-of-link  (get-begin-of-link permit-type)]
    (assert (= permit/R permit-type)
      (str "Sending unsent attachments to backing system is not supported for " (name permit-type) " type of permits."))
    (rl-mapping/save-unsent-attachments-as-krysp application lang output-dir begin-of-link)))
