(ns lupapalvelu.copy-application-test
  (:require [clojure.data :refer [diff]]
            [clojure.walk :as walk]
            [lupapalvelu.application :as app]
            [lupapalvelu.attachment :as attachment]
            [lupapalvelu.copy-application :refer :all]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.organization :as org]
            [lupapalvelu.test-util :refer [walk-dissoc-keys]]
            [lupapalvelu.user :as usr]
            [midje.sweet :refer :all]
            [sade.coordinate :as coord]
            [sade.schema-generators :as ssg]
            [sade.util :as util]))

(defn dissoc-ids-and-timestamps [application]
  (walk-dissoc-keys application :id :created :modified :ts))

(def users
  {"source-user" {:id        "source-user"
                  :firstName "Source"
                  :lastName  "User"
                  :role      :applicant}
   "copying-user" {:id        "copying-user"
                   :firstName "New"
                   :lastName  "User"
                   :role      :applicant}})

(defn pointing-to-operations-of [application]
  (let [operations (conj (:secondaryOperations application) (:primaryOperation application))]
    (fn [op-infos]
      (every? (fn [op-info]
                (= (:name (util/find-by-id (:id op-info) operations))
                   (:name op-info)))
              op-infos))))

(with-redefs [coord/convert (fn [_ _ _ coords] (str "converted " coords))
              usr/get-user-by-id (fn [id] (get users id))
              lupapalvelu.copy-application/empty-document-copy (fn [document _ & _] document)]
  (let [source-user (get users "source-user")
        user        (get users "copying-user")
        source-created 12345
        created 23456
        municipality "753"
        organization {:id "753-R"
                      :operations-attachments
                      {:kerrostalo-rivitalo
                      [["paapiirustus"
                        "asemapiirros"]
                       ["paapiirustus"
                        "pohjapiirustus"]]}}
        raw-new-app (app/make-application "LP-123" "kerrostalo-rivitalo" 0 0
                                          "address" "01234567891234" municipality
                                          organization false false ["message1" "message2"] source-user
                                          source-created nil)
        source-app (-> raw-new-app
                       (assoc :attachments (attachment/make-attachments 999 :draft
                                                                        [{:type (ssg/generate attachment/Type)}
                                                                         {:type (ssg/generate attachment/Type)}]
                                                                        nil false true true))
                       (update :documents conj {:id "extra-document"}))]
    (facts new-application-copy
      (facts "No options specified"
        (let [new-app (new-application-copy source-app user organization created {})
              [new old _] (diff new-app source-app)]

          (fact "the application is copied almost verbatim"
            (let [[only-new only-old _] (diff (dissoc-ids-and-timestamps new-app)
                                              (dissoc-ids-and-timestamps source-app))]
              (keys only-new)  ; gives the same result as (keys only-old)
              => (just [:auth :attachments :comments :history] :in-any-order))
            (keys new) ; gives the same result as (keys old)
            => (just [:auth :attachments :comments :created :documents :history :id :modified :primaryOperation]
                     :in-any-order))

          (fact "the operation info of attachments in copied application points to the new copied operations"
                (->> new-app :attachments (map :op)) => (pointing-to-operations-of new-app))

          (fact "application is created and modified now"
            (:created new-app) => created
            (:modified new-app) => created)

          (fact "application has new history"
            (map :ts (:history new-app)) =>  (has every? #(= created %)))

          (fact "id references are updated"
            (->> new :documents (map (comp :id :op :schema-info)) (remove nil?))
            => (has every? #(= % (-> new :primaryOperation :id))))

          (fact "document creation time is the same as the application's"
            (->> new-app :documents (map :created))
            => (has every? #(= created %)))

          (fact "comments are not copied by default"
            (:comments new-app) => empty?)

          (fact "attachments are overridden with those of a normal new application"
            (= (dissoc-ids-and-timestamps (select-keys new-app [:attachments]))
               (dissoc-ids-and-timestamps (select-keys raw-new-app [:attachments])))  => true?)

          (fact "user is the owner of the new application, previous owner invited as writer"
            (:auth source-app) => [(assoc source-user :role :owner :type :owner :unsubscribed false)]
            (:auth new-app) => [(assoc user :role :owner :type :owner :unsubscribed false)
                                (assoc source-user
                                       :role :reader
                                       :invite {:created created
                                                :email nil
                                                :inviter user
                                                :role :writer
                                                :user source-user})])))

      (fact "If documents are not copied or overridden, those of normal new application are created"
        (let [new-app (new-application-copy source-app user organization created
                                            (update default-copy-options :whitelist
                                                    (partial remove #{:documents})))]
          (dissoc-ids-and-timestamps (:documents new-app)) => (dissoc-ids-and-timestamps (:documents raw-new-app))))

      (against-background
       (app/make-application-id anything) => "application-id-753"
       (org/get-organization (:id organization)) => organization))))
