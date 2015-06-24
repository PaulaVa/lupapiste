(ns lupapalvelu.statement
  (:require [sade.core :refer :all]
            [sade.util :as util]
            [sade.strings :as ss]
            [lupapalvelu.xml.krysp.mapping-common :as mapping-common]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.user :as user]))

;;
;; Common
;;

(defn get-statement [{:keys [statements]} id]
  (first (filter #(= id (:id %)) statements)))

(defn statement-exists [{{:keys [statementId]} :data} application]
  (when-not (get-statement application statementId)
    (fail :error.no-statement :statementId statementId)))

(defn statement-owner [{{:keys [statementId]} :data {user-email :email} :user} application]
  (let [{{statement-email :email} :person} (get-statement application statementId)]
    (when-not (= (user/canonize-email statement-email) (user/canonize-email user-email))
      (fail :error.not-statement-owner))))

(defn statement-given? [application statementId]
  (boolean (->> statementId (get-statement application) :given)))

(defn statement-not-given [{{:keys [statementId]} :data} application]
  (when (statement-given? application statementId)
    (fail :error.statement-already-given)))

;;
;; Statuses
;;

(def- statement-statuses ["puoltaa" "ei-puolla" "ehdoilla"])
;; Krysp Yhteiset 2.1.5+
(def- statement-statuses-more-options
  (into [] (concat statement-statuses ["ei-huomautettavaa" "ehdollinen" "puollettu" "ei-puollettu" "ei-lausuntoa" "lausunto" "kielteinen" "palautettu" "poydalle"])))

(defn- version-is-greater-or-equal [source target]
  (let [[source-major source-minor source-micro] (map #(util/->int % nil) (ss/split source #"\."))
        source-micro (or source-micro 0)]
    (or
      (> source-major (:major target))
      (and (= source-major (:major target)) (> source-minor (:minor target)))
      (and (= source-major (:major target)) (= source-minor (:minor target)) (>= source-micro (:micro target))))))

(defn- possible-statement-statuses [application]
  (let [{version :version} (organization/get-krysp-wfs application)
        yht-version (mapping-common/get-yht-version (:permitType application) version)]
    (if (version-is-greater-or-equal yht-version {:major 2 :minor 1 :micro 5})
      statement-statuses-more-options
      statement-statuses)))
