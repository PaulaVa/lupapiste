(ns lupapalvelu.document.model-test
  (:use [lupapalvelu.document.model]
        [lupapalvelu.document.schemas]
        [midje.sweet]))

;; Simple test schema:

(def schema {:info {:name "test-model"
                    :version 1}
             :body [{:name "a" :type :group
                     :body [{:name "aa" :type :string}
                            {:name "ab" :type :string :min-len 2 :max-len 3}
                            {:name "b" :type :group
                             :body [{:name "ba" :type :string :min-len 2}
                                    {:name "bb" :type :boolean}]}
                            {:name "c" :type :list
                             :body [{:name "ca" :type :string}
                                    {:name "cb" :type :checkbox}]}]}]})

(def schema-with-repetition {:info {:name "repetition-model" :version 1}
                             :body [{:name "single" :type :string}
                                    {:name "repeats" :type :group :repeating true
                                     :body [{:name "single2" :type :string}
                                            {:name "repeats2" :type :string :subtype :digit :repeating true}]}]})

;; Tests for internals:

(def find-by-name #'lupapalvelu.document.model/find-by-name)

(facts "Facts about internals"
  (fact (find-by-name (:body schema) ["a"])          => (-> schema :body first))
  (fact (find-by-name (:body schema) ["a" "aa"])     => {:name "aa" :type :string})
  (fact (find-by-name (:body schema) ["a" "b" "bb"]) => {:name "bb" :type :boolean})
  (fact (find-by-name (:body schema) ["a" "b" "bc"]) => nil))

;; Validation tests:

#_(facts "Simple validations"
  (fact (validate-updates schema [["a.ab" "foo"]]) => [])
  (fact (validate-updates schema [["a.ab" "f"]]) => [["a.ab" :warn "illegal-value:too-short"]])
  (fact (validate-updates schema [["a.ab" "foooo"]]) => [["a.ab" :err "illegal-value:too-long"]])
  (fact (validate-updates schema [["a.ab" "f"] ["a.ab" "foooo"]]) => [["a.ab" :warn "illegal-value:too-short"] ["a.ab" :err "illegal-value:too-long"]]))

#_(facts "with real schemas - important field for paasuunnittelija"
  (let [schema (schemas "paasuunnittelija")]
    (fact (validate-updates schema [["henkilotiedot.etunimi" "Tauno"]])          => [])
    (fact (validate-updates schema [["henkilotiedot.etunimiz" "Tauno"]])         => [["henkilotiedot.etunimiz" :err "illegal-key"]])
    (fact (validate-updates schema [["henkilotiedot.sukunimi" "Palo"]])          => [])
    (fact (validate-updates schema [["henkilotiedot.etunimi" "Tauno"]
                                    ["henkilotiedot.sukunimi"  "Palo"]])         => [])
    (fact (validate-updates schema [["henkilotiedot.etunimi" "Tauno"]
                                    ["henkilotiedot.sukunimiz" "Palo"]])         => [["henkilotiedot.sukunimiz" :err "illegal-key"]])
    (fact (validate-updates schema [["yhteystiedot.email" "tauno@example.com"]]) => [])
    (fact (validate-updates schema [["yhteystiedot.puhelin" "050"]])             => [])))

#_(facts "Repeating section"
  (fact "Single value contains no nested sections"
    (validate-updates schema-with-repetition [["single.1.single2"]])           => [["single.1.single2" :err "illegal-key"]])
  (fact "Repeating section happy case"
    (validate-updates schema-with-repetition [["repeats.1.single2" "foo"]])    => [])
  (fact "Invalid key under nested section"
    (validate-updates schema-with-repetition [["repeats.1.single3" "foo"]])    => [["repeats.1.single3" :err "illegal-key"]])
  (fact "Unindexed repeating section"
    (validate-updates schema-with-repetition [["repeats.single2" "foo"]])      => [["repeats.single2" :err "illegal-key"]])
  (fact "Repeating string, 0"
    (validate-updates schema-with-repetition [["repeats.1.repeats2.0" "1"]])   => [])
  (fact "Repeating string, 1"
    (validate-updates schema-with-repetition [["repeats.1.repeats2.1" "foo"]]) => [["repeats.1.repeats2.1" :warn "illegal-number"]]))

(facts "has-errors?"
  (has-errors? [])                  => false
  (has-errors? [{:result [:warn]}]) => false
  (has-errors? [{:result [:warn]}
                {:result [:err]}])  => true)

; field type validation

(facts "dates"
  (validate {:type :date} "abba") => [:warn "invalid-date-format"]
  (validate {:type :date} "") => nil
  (validate {:type :date} "11.12.2013") => nil)

;;
;; validate-document
;;

(defn valid? [document]
  (or (fact (validate-document document) => '()) true))

(defn invalid? [document]
  (or (fact (validate-document document) => (has some not-empty)) true))

(defn invalid-with? [result]
  (fn [document]
    (or (fact (validate-document document) => (has some (contains {:result result}))) true)))

(facts "validate-document"
  {:schema {:info {:name "schema"}
            :body [{:name "a" :type :group
                    :body [{:name "aa" :type :string}
                           {:name "ab" :type :string :min-len 2 :max-len 3}]}]}
   :data {:a {:aa {:value "kukka"}
              :ab {:value "123"}}}} => valid?

  {:schema {:info {:name "schema"}
            :body [{:name "a" :type :group
                    :body [{:name "aa" :type :string}
                           {:name "ab" :type :string :min-len 2 :max-len 3}]}]}
   :data {:c {:aa {:value "kukka"}
              :ab {:value "123"}}}} => invalid?)

;;
;; Updates
;;

(fact "updating document"
  (apply-update  {} [:b :c] "kikka") => {:data {:b {:c {:value "kikka"}}}}
  (apply-updates {} [[[:b :c] "kikka"]
                     [[:b :d] "kukka"]]) => {:data {:b {:c {:value "kikka"}
                                                        :d {:value "kukka"}}}})

;;
;; VRK-rules validation
;;

(use 'lupapalvelu.document.tools)
(use 'lupapalvelu.document.schemas)

(def uusi-rakennus
  (let [schema (schemas "uusiRakennus")
        data   (create-document-data schema dummy-values)]
    {:schema schema
     :data   data}))

(facts "VRK-validations"

  (fact "uusi rakennus is valid"
    uusi-rakennus => valid?)

  (fact "Puutalossa saa olla korkeintaan 4 kerrosta"
    (-> uusi-rakennus
      (apply-update [:rakenne :kantavaRakennusaine] "puu")
      (apply-update [:mitat :kerrosluku] "5")) => (invalid-with? [:warn "vrk:BR106"])))
