(ns lupapalvelu.attachment-accessibility
  (:require [lupapalvelu.attachment-metadata :as metadata]))



(defn owns-latest-version? [user {latest :latestVersion}]
  (= (-> latest :user :id) (:id user)))


(defn can-access-attachment?
  [user {meta :metadata latest :latestVersion :as attachment}]
  (or
    (nil? latest)
    (metadata/public-attachment? attachment)))

(defn can-access-attachment-file? [user file-id {attachments :attachments}]
  (boolean
    (when-let [attachment (some
                            (fn [{versions :versions :as attachment}]
                              (when (some #{file-id} (map :fileId versions)) attachment))
                            attachments)]
      (can-access-attachment? user attachment))))

(defn filter-attachments-for [user attachments]
  {:pre [(map? user) (sequential? attachments)]}
  (letfn [(filter-fn [a]
            (metadata/public-attachment? a))]
    (filter filter-fn attachments)))
