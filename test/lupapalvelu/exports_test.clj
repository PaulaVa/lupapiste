(ns lupapalvelu.exports-test
  (:require [midje.sweet :refer :all]
            [midje.util :refer [testable-privates]]
            [clojure.set :refer [difference]]
            [lupapalvelu.exports :refer [kayttotarkoitus-hinnasto price-classes-for-operation]]
            [lupapalvelu.application :as app]
            [lupapalvelu.operations :as ops]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.document.schemas :as schemas]))

(testable-privates lupapalvelu.exports resolve-price-class)

(def keyset (comp set keys))

(fact "Every operation has price class definition"
  (difference (keyset ops/operations) (keyset price-classes-for-operation) ) => empty?)

(fact "Every kayttotarkoitus has price class"
  (let [every-kayttotarkoitus (remove (partial = "ei tiedossa") (map :name schemas/rakennuksen-kayttotarkoitus))]
    (difference (set every-kayttotarkoitus) (keyset @kayttotarkoitus-hinnasto))) => empty?)

(fact "Uusi asuinrakennus"
  (let [application (app/make-application "LP-123" "asuinrakennus" 0 0 "address" "01234567891234" "753" {:id "753-R"} false false [] {} 123)
        uusi-rakennus (domain/get-document-by-name application "uusiRakennus")]

    (fact "Default value '011 yhden asunnon talot' = C"
      (let [op (resolve-price-class application (first (:operations application)))]
        (:priceClass op) => "C"))

    (fact "Missing value defaults to C"
      (let [doc (assoc-in uusi-rakennus [:data :kaytto :kayttotarkoitus] {})
            application (assoc application :documents [doc])
            op (resolve-price-class application (first (:operations application)))]
        (:priceClass op) => "C"))

    (fact "Empty value defaults to C"
      (let [doc (assoc-in uusi-rakennus [:data :kaytto :kayttotarkoitus :value] "")
            application (assoc application :documents [doc])
            op (resolve-price-class application (first (:operations application)))]
        (:priceClass op) => "C"))

    (fact "021 rivitalot = B"
      (let [doc (assoc-in uusi-rakennus [:data :kaytto :kayttotarkoitus :value] "021 rivitalot")
            application (assoc application :documents [doc])
            op (resolve-price-class application (first (:operations application)))]
        (:priceClass op) => "B"))

    (fact "041 vapaa-ajan asuinrakennukset = C"
      (let [doc (assoc-in uusi-rakennus [:data :kaytto :kayttotarkoitus :value] "041 vapaa-ajan asuinrakennukset")
            application (assoc application :documents [doc])
            op (resolve-price-class application (first (:operations application)))]
        (:priceClass op) => "C"))

    (fact "121 hotellit yms = A"
      (let [doc (assoc-in uusi-rakennus [:data :kaytto :kayttotarkoitus :value] "121 hotellit yms")
            application (assoc application :documents [doc])
            op (resolve-price-class application (first (:operations application)))]
        (:priceClass op) => "A"))

    (fact "999 muualla luokittelemattomat rakennukset = D"
      (let [doc (assoc-in uusi-rakennus [:data :kaytto :kayttotarkoitus :value] "999 muualla luokittelemattomat rakennukset")
            application (assoc application :documents [doc])
            op (resolve-price-class application (first (:operations application)))]
        (:priceClass op) => "D"))))
