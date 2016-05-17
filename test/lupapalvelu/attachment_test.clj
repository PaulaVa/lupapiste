(ns lupapalvelu.attachment-test
  (:require [clojure.string :as s]
            [midje.sweet :refer :all]
            [midje.util :refer [testable-privates]]
            [sade.strings :refer [encode-filename]]
            [sade.env :as env]
            [monger.operators :refer :all]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.attachment :refer :all]
            [lupapalvelu.attachment-type :as att-type]
            [lupapalvelu.attachment-metadata :refer :all]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.states :as states]
            [lupapalvelu.user :as user]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [clojure.test :refer [is]]
            [schema.core :as sc]
            [sade.schemas :as ssc]
            [sade.schema-generators :as ssg]))

(testable-privates lupapalvelu.attachment
                   attachment-file-ids
                   version-number
                   latest-version-after-removing-file
                   make-version
                   build-version-updates
                   default-metadata-for-attachment-type
                   create-appeal-attachment-data!)

(def ascii-pattern #"[a-zA-Z0-9\-\.]+")

(defspec make-attachement-spec
  (prop/for-all [attachment-id           ssg/object-id
                 now                     ssg/timestamp
                 target                  (ssg/generator Target)
                 required?               gen/boolean
                 requested-by-authority? gen/boolean
                 locked?                 gen/boolean
                 application-state       (gen/elements states/all-states)
                 operation               (ssg/generator Operation)
                 attachment-type         (ssg/generator Type)
                 metadata                (ssg/generator  {sc/Str sc/Str})
                 ;; Optional parameters
                 contents                (ssg/generator (sc/maybe sc/Str))
                 read-only?              (ssg/generator (sc/maybe sc/Bool))
                 source                  (ssg/generator (sc/maybe Source))]
                (let [validation-error (->> (make-attachment now target required? requested-by-authority? locked? application-state operation attachment-type metadata attachment-id contents read-only? source)
                                            (sc/check Attachment))]
                  (nil? validation-error))))

(facts "Test file name encoding"
  (fact (encode-filename nil)                                 => nil)
  (fact (encode-filename "foo.txt")                           => "foo.txt")
  (fact (encode-filename (apply str (repeat 255 \x)))         => (apply str (repeat 255 \x)))
  (fact (encode-filename (apply str (repeat 256 \x)))         => (apply str (repeat 255 \x)))
  (fact (encode-filename (apply str (repeat 256 \x) ".txt"))  => (has-suffix ".txt"))
  (fact (encode-filename "\u00c4\u00e4kk\u00f6si\u00e4")      => (just ascii-pattern))
  (fact (encode-filename "/root/secret")                      => (just ascii-pattern))
  (fact (encode-filename "\\Windows\\cmd.exe")                => (just ascii-pattern))
  (fact (encode-filename "12345\t678\t90")                    => (just ascii-pattern))
  (fact (encode-filename "12345\n678\r\n90")                  => (just ascii-pattern)))

(def test-attachments [{:id "1", :latestVersion {:version {:major 9, :minor 7}}}])

(facts "Facts about next-attachment-version"
  (fact (next-attachment-version {:major 1 :minor 1} {:role :authority})  => {:major 1 :minor 2})
  (fact (next-attachment-version {:major 1 :minor 1} {:role :dude})       => {:major 2 :minor 0})
  (fact (next-attachment-version nil {:role :authority})  => {:major 0 :minor 1})
  (fact (next-attachment-version nil {:role :dude})       => {:major 1 :minor 0}))

(fact "version number"
  (version-number {:version {:major 1 :minor 16}}) => 1016)

(fact "Find latest version"
  (let [application {:attachments [{:id :attachment1
                                    :versions []}
                                   {:id :attachment2
                                    :versions [{:version { :major 1 :minor 0 }
                                                :fileId :file1
                                                :originalFileId :originalFileId1}
                                               {:version { :major 1 :minor 1 }
                                                :fileId :file2
                                                :originalFileId :originalFileId2}]}]}
        attachment (last (:attachments application))]
    (latest-version-after-removing-file attachment :file1) => {:version {:major 1 :minor 1}
                                                               :fileId :file2
                                                               :originalFileId :originalFileId2}

    (attachment-file-ids attachment) => (just #{:file1 :originalFileId1 :file2 :originalFileId2})
    (attachment-latest-file-id application :attachment2) => :file2))

(fact "make attachments"
  (make-attachments 999 :draft [{:type :a} {:type :b}] false true true) => (just
                                                                             [{:id "123"
                                                                               :locked false
                                                                               :modified 999
                                                                               :op nil
                                                                               :state :requires_user_action
                                                                               :target nil
                                                                               :type :a
                                                                               :applicationState :draft
                                                                               :contents nil
                                                                               :signatures []
                                                                               :versions []
                                                                               :auth []
                                                                               :notNeeded false
                                                                               :required true
                                                                               :requestedByAuthority true
                                                                               :forPrinting false
                                                                               :readOnly false}
                                                                              {:id "123"
                                                                               :locked false
                                                                               :modified 999
                                                                               :op nil
                                                                               :state :requires_user_action
                                                                               :target nil
                                                                               :type :b
                                                                               :applicationState :draft
                                                                               :contents nil
                                                                               :signatures []
                                                                               :versions []
                                                                               :auth []
                                                                               :notNeeded false
                                                                               :required true
                                                                               :requestedByAuthority true
                                                                               :forPrinting false
                                                                               :readOnly false}])
  (provided
    (mongo/create-id) => "123"))

(fact "attachment can be found with file-id"
  (get-attachment-info-by-file-id {:attachments [{:versions [{:fileId "123"}
                                                             {:fileId "234"}]}
                                                 {:versions [{:fileId "345"}
                                                             {:fileId "456"}]}]} "456") => {:versions [{:fileId "345"}
                                                                                                       {:fileId "456"}]})

(let [attachments [{:id 1 :versions [{:fileId "11"} {:fileId "21"}]}
                   {:id 2 :versions [{:fileId "12"} {:fileId "22"}]}
                   {:id 3 :versions [{:fileId "13"} {:fileId "23"}]}]]

  (facts "create-sent-timestamp-update-statements"
    (create-sent-timestamp-update-statements attachments ["12" "23"] 123) => {"attachments.1.sent" 123
                                                                              "attachments.2.sent" 123}))

(fact "All attachments are localized"
  (let [attachment-group-type-paths (->>
                                      (vals att-type/attachment-types-by-permit-type)
                                      set
                                      (apply concat)
                                      (partition 2)
                                      (map (fn [[g ts]] (map (fn [t] [g t]) ts)))
                                      (apply concat))]
    (fact "Meta: collected all types"
      (set (map second attachment-group-type-paths)) => att-type/all-attachment-type-ids)

    (doseq [lang ["fi" "sv"]
            path attachment-group-type-paths
            :let [i18n-path (cons :attachmentType path)
                  args (map name (cons lang i18n-path))
                  info-args (concat args ["info"])]]

      (fact {:midje/description (str lang " " (s/join "." (rest args)))}
        (apply i18n/has-term? args) => true)

      (fact {:midje/description (str lang " " (s/join "." (rest info-args)))}
        (apply i18n/has-term? info-args) => true))))


(fact "make attachments with metadata"
  (let [types-with-metadata [{:type :a :metadata {"foo" "bar"}} {:type :b :metadata {"bar" "baz"}}]]
    (make-attachments 999 :draft types-with-metadata false true true) => (just
                                                                           [{:id "123"
                                                                             :locked false
                                                                             :modified 999
                                                                             :op nil
                                                                             :state :requires_user_action
                                                                             :target nil
                                                                             :type :a
                                                                             :applicationState :draft
                                                                             :contents nil
                                                                             :signatures []
                                                                             :versions []
                                                                             :auth []
                                                                             :notNeeded false
                                                                             :required true
                                                                             :requestedByAuthority true
                                                                             :forPrinting false
                                                                             :metadata {"foo" "bar"}
                                                                             :readOnly false}
                                                                            {:id "123"
                                                                             :locked false
                                                                             :modified 999
                                                                             :op nil
                                                                             :state :requires_user_action
                                                                             :target nil
                                                                             :type :b
                                                                             :applicationState :draft
                                                                             :contents nil
                                                                             :signatures []
                                                                             :versions []
                                                                             :auth []
                                                                             :notNeeded false
                                                                             :required true
                                                                             :requestedByAuthority true
                                                                             :forPrinting false
                                                                             :metadata {"bar" "baz"}
                                                                             :readOnly false}])
    (provided
      (mongo/create-id) => "123")))

(facts "facts about attachment metada"
  (fact "visibility"
    (let [no-metadata {}
          no-metadata2 {:metadata {}}
          nakyvyys-not-public {:metadata {:nakyvyys "test"}}
          jluokka-nakyvyys-not-public {:metadata {:nakyvyys "test"
                                                  :julkisuusluokka "test2"}}
          nakyvyys-public {:metadata {:nakyvyys "julkinen"
                                      :julkisuusluokka "test2"}}
          jluokka-public {:metadata {:nakyvyys "test"
                                     :julkisuusluokka "julkinen"}}
          both-public {:metadata {:nakyvyys "test"
                                  :julkisuusluokka "julkinen"}}
          only-julkisuusluokka {:metadata {:julkisuusluokka "julkinen"}}]

      (public-attachment? no-metadata) => true
      (public-attachment? no-metadata2) => true
      (public-attachment? nakyvyys-not-public) => false
      (public-attachment? jluokka-nakyvyys-not-public) => false
      (fact "julkisuusluokka overrules nakyvyys" (public-attachment? nakyvyys-public) => false)
      (public-attachment? jluokka-public) => true
      (public-attachment? both-public) => true
      (public-attachment? only-julkisuusluokka) => true)))


(defspec make-version-new-attachment 20
  (prop/for-all [attachment      (ssg/generator Attachment {Version nil  [Version] (gen/elements [[]])})
                 file-id         (ssg/generator ssc/ObjectIdStr)
                 archivability   (ssg/generator (sc/maybe {:archivable sc/Bool
                                                           :archivabilityError (apply sc/enum archivability-errors)
                                                           :missing-fonts [sc/Str]}))
                 general-options (ssg/generator {:filename sc/Str
                                                :content-type sc/Str
                                                :size sc/Int
                                                :now ssc/Timestamp
                                                :user user/SummaryUser
                                                :stamped (sc/maybe sc/Bool)})]
                (let [options (merge {:file-id file-id :original-file-id file-id} archivability general-options)
                      version (make-version attachment options)]
                  (and (not (nil? (get-in version [:version :minor])))
                       (not (nil? (get-in version [:version :major])))
                       (= (:fileId version)         file-id)
                       (= (:originalFileId version) file-id)
                       (= (:created version) (:now options))
                       (= (:user version) (:user options))
                       (= (:filename version) (:filename options))
                       (= (:contentType version) (:content-type options))
                       (= (:size version) (:size options))
                       (= (:stamped version) (:stamped options))
                       (= (:archivable version) (:archivable options))
                       (= (:archivabilityError version) (:archivabilityError options))
                       (= (:missing-fonts version) (:missing-fonts options))))))

(defspec make-version-update-existing 20
  (prop/for-all [[attachment options] (gen/fmap (fn [[att ver fids opt]] [(-> (update att :versions assoc 0 (assoc ver :originalFileId (first fids)))
                                                                              (assoc :latestVersion (assoc ver :originalFileId (first fids))))
                                                                          (assoc opt :file-id (last fids) :original-file-id (first fids))])
                                                (gen/tuple (ssg/generator Attachment {Version nil [Version] (gen/elements [[]])})
                                                           (ssg/generator Version)
                                                           (gen/vector-distinct ssg/object-id {:num-elements 2})
                                                           (ssg/generator {:filename sc/Str
                                                                           :content-type sc/Str
                                                                           :size sc/Int
                                                                           :now ssc/Timestamp
                                                                           :user user/SummaryUser})))]
                (let [version (make-version attachment options)]
                  (and (= (:version version) (get-in attachment [:latestVersion :version]))
                       (= (:fileId version) (:file-id options))
                       (= (:originalFileId version) (:original-file-id options))
                       (= (:created version) (:now options))
                       (= (:user version) (:user options))
                       (= (:filename version) (:filename options))
                       (= (:contentType version) (:content-type options))
                       (= (:size version) (:size options))))))

(defspec build-version-updates-new-attachment 20
  (prop/for-all [application    (ssg/generator {:state sc/Keyword})
                 attachment     (ssg/generator Attachment {Version nil [Version] (gen/elements [[]])})
                 version-model  (ssg/generator Version)
                 options        (ssg/generator {:now ssc/Timestamp
                                                :target (sc/maybe Target)
                                                :user user/SummaryUser
                                                :stamped (sc/maybe sc/Bool)
                                                :comment? sc/Bool
                                                :comment-text sc/Str})]
                (let [updates (build-version-updates application attachment version-model options)]
                  (and (= (get-in updates [$addToSet :attachments.$.auth :role]) (if (:stamped options) :stamper :uploader))
                       (= (get-in updates [$set :modified] (:now options)))
                       (= (get-in updates [$set :attachments.$.modified] (:now options)))
                       (= (get-in updates [$set :attachments.$.state] (:state options)))
                       (if (:target options)
                         (= (get-in updates [$set :attachments.$.target] (:target options)))
                         (not (contains? (get updates $set) :attachments.$.target)))
                       (= (get-in updates [$set :attachments.$.latestVersion] version-model))
                       (= (get-in updates [$set "attachments.$.versions.0"] version-model))))))

(defspec build-version-updates-update-existing-version 20
  (prop/for-all [application    (ssg/generator {:state sc/Keyword})
                 [attachment version-model] (gen/fmap (fn [[att fids]]
                                                        (let [ver (assoc (get-in att [:versions 1]) :originalFileId (first fids))]
                                                          [(-> (assoc-in att [:versions 1] ver)
                                                               (assoc :latestVersion ver))
                                                           ver]))
                                                      (gen/tuple (ssg/generator Attachment {Version   nil
                                                                                            [Version] (gen/vector-distinct (ssg/generator Version) {:min-elements 3})})
                                                                 (gen/vector-distinct ssg/object-id {:num-elements 2})))
                 options (ssg/generator {:now ssc/Timestamp
                                         :user user/SummaryUser})]
                (let [updates (build-version-updates application attachment version-model options)]
                  (and (not (contains? (get updates $set) :attachments.$.latestVersion))
                       (= (get-in updates [$set "attachments.$.versions.1"] version-model))))))


(facts "appeal attachment updates"
  (against-background
    [(lupapalvelu.pdf.pdfa-conversion/pdf-a-required? anything) => false]
    (fact "appeal-attachment-data"
      (let [file-id  (mongo/create-id)
            file-obj {:content nil,
                      :content-type "application/pdf",
                      :content-length 123,
                      :file-name "test-pdf.pdf",
                      :metadata {:uploaded 12344567, :linked false},
                      :application nil
                      :fileId file-id}
            command {:application {:state :verdictGiven}
                     :created 12345
                     :user {:id "foo" :username "tester" :role "authority" :firstName "Tester" :lastName "Testby"}}
            result-attachment (create-appeal-attachment-data!
                                command
                                (mongo/create-id)
                                :appeal
                                file-obj)]
        (fact "Generated attachment data is valid (no PDF/A generation)"
          (sc/check Attachment result-attachment) => nil)))))
