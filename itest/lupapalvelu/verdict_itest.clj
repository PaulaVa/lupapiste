(ns lupapalvelu.verdict-itest
  (:require [midje.sweet :refer :all]
            [lupapalvelu.itest-util :refer :all]
            [lupapalvelu.factlet :refer :all]
            [lupapalvelu.domain :as domain]))

(fact* "Give verdict"
  (last-email) ; Inbox zero

  (let [application-id  (create-app-id pena :municipality sonja-muni :address "Paatoskuja 9")
        resp            (command pena :submit-application :id application-id) => ok?
        application     (query-application pena application-id)
        email           (last-email) => truthy]
    (:state application) => "submitted"
    (:to email) => (contains (email-for-key pena))
    (:subject email) => "Lupapiste.fi: Paatoskuja 9 - hakemuksen tila muuttunut"
    (get-in email [:body :plain]) => (contains "Vireill\u00e4")
    email => (partial contains-application-link? application-id "applicant")

    (let [new-verdict-resp (command sonja :new-verdict-draft :id application-id) => ok?
          verdict-id (:verdictId new-verdict-resp) => truthy
          resp        (command sonja :save-verdict-draft :id application-id :verdictId verdict-id :backendId "aaa" :status 42 :name "Paatoksen antaja" :given 123 :official 124 :text "" :agreement false :section "") => ok?
          application (query-application sonja application-id)
          verdict     (first (:verdicts application))
          paatos      (first (:paatokset verdict))
          poytakirja  (first (:poytakirjat paatos))]
      (count (:verdicts application)) => 1
      (count (:paatokset verdict)) => 1
      (count (:poytakirjat paatos)) => 1

      (:kuntalupatunnus verdict) => "aaa"
      (:status poytakirja) => 42
      (:paatoksentekija poytakirja) => "Paatoksen antaja"
      (get-in paatos [:paivamaarat :anto]) => 123
      (get-in paatos [:paivamaarat :lainvoimainen]) => 124

      (fact "Comment verdict"
        (command sonja :add-comment :id application-id :text "hello" :to nil :target {:type "verdict" :id verdict-id} :openApplication false :roles [:authority]) => ok?
        (fact "Nobody got mail" (last-email) => nil))

      (fact "Upload attachment to draft"
        (upload-attachment-to-target sonja application-id nil true verdict-id "verdict")
        (fact "Nobody got mail" (last-email) => nil))

      (fact "Pena does not see comment or attachment"
        (let [{:keys [comments attachments]} (query-application pena application-id)]
          (count comments) => 0
          (count (keep :latestVersion attachments)) => 0))

      (fact "Sonja sees comment and attachment"
        (let [{:keys [comments attachments]} (query-application sonja application-id)]
          (count comments) => 2 ; comment and new attachment auto-comment
          (count (keep :latestVersion attachments)) => 1))

      (fact "Comment verdict, target is Ronja"
        (command sonja :add-comment :id application-id :text "hello" :to ronja-id :target {:type "verdict" :id verdict-id} :openApplication false :roles [:authority]) => ok?
        (let [email (last-email)]
          (fact "Ronja got mail"
            email => map?
            (:to email) => (contains (email-for "ronja"))
            (let [[href a-id v-id] (re-find #"(?sm)http.+/app/fi/authority#!/verdict/([A-Za-z0-9-]+)/([0-9a-z]+)" (get-in email [:body :plain]))]
              a-id => application-id
              v-id => verdict-id))))

      (fact "Publish verdict" (command sonja :publish-verdict :id application-id :verdictId verdict-id) => ok?)

      (fact "Authority is still able to add an attachment"
        (let [application (query-application sonja application-id)
              first-attachment (get-in application [:attachments 0])]

          (let [email (last-email)]
            (:to email) => (contains (email-for-key pena))
            (:subject email) => "Lupapiste.fi: Paatoskuja 9 - p\u00e4\u00e4t\u00f6s"
            email => (partial contains-application-link-with-tab? application-id "verdict" "applicant"))

          (:state application) => "verdictGiven"
          (upload-attachment sonja (:id application) first-attachment true)
          (upload-attachment pena (:id application) first-attachment false))))))

(fact "Fetch verdict from KRYSP backend"
  (last-email) ; Inbox zero

  (let [application (create-and-submit-application mikko :municipality sonja-muni :address "Paatoskuja 17")
        application-id (:id application)
        attachment-count (-> application :attachments count)
        resp (command sonja :check-for-verdict :id application-id)
        app-with-verdict (query-application mikko application-id)
        verdict-id1 (-> app-with-verdict :verdicts first :id)
        verdict-id2 (-> app-with-verdict :verdicts second :id)]

    (:organization application) => "753-R"
    resp => ok?

    (fact "No verdicts in the beginnig"
      (-> application :verdicts count) => 0)

    (fact "Two verdicts from fixture"
      (-> app-with-verdict :verdicts count) => 2)

    (fact "Applicant receives email"
      (let [email (last-email)]
       (:to email) => (contains (email-for-key mikko))
       (:subject email) => "Lupapiste.fi: Paatoskuja 17 - p\u00e4\u00e4t\u00f6s"
       email => (partial contains-application-link-with-tab? application-id "verdict" "applicant")))

    (fact "There is one more attachments, see krysp/sample/verdict.xml"
      (-> app-with-verdict :attachments count) => (inc attachment-count))

    (fact "Lupaehdot, see krysp/sample/verdict.xml"
      (-> application :tasks count) => 0
      (-> app-with-verdict :tasks count) => 9)

    (facts "Delete verdicts"
     (command sonja :delete-verdict :id application-id :verdictId verdict-id1) => ok?
     (command sonja :delete-verdict :id application-id :verdictId verdict-id2) => ok?
     (let [app-without-verdict (query-application mikko application-id)]
       (fact "Tasks have been deleted"
         (-> app-without-verdict :tasks count) => 0)
       (fact "Attachment has been deleted"
         (-> app-without-verdict :attachments count) => attachment-count)
       (fact "Verdicts have been deleted"
         (-> app-without-verdict :verdicts count) => 0)
       )
     )

    ))

(fact "Rakennus & rakennelma"
  (let [application (create-and-submit-application mikko :municipality sonja-muni :address "Paatoskuja 17")
        application-id (:id application)
        _ (command sonja :check-for-verdict :id application-id) => ok?
        application (query-application mikko application-id)
        buildings   (:buildings application)
        {:as building1}   (first buildings)
        {:as building2}   (second buildings)
        {:as building3}   (last buildings)]

    (count buildings) => 3

    (:buildingId building1) => "101"
    (:propertyId building1) => "18601234567890"
    (:index building1) => "1"
    (:usage building1) => "039 muut asuinkerrostalot"
    (:area building1) => "2000"
    (:created building1) => "2013"

    (:buildingId building2) => "102"
    (:propertyId building2) => "18601234567891"
    (:index building2) => "2"
    (:usage building2) => "719 muut varastorakennukset"
    (:area building2) => "20"
    (:created building2) => "2013"

    (:buildingId building3) => "103"
    (:propertyId building3) => "18601234567892"
    (:index building3) => "3"
    (fact "kayttotarkoitus is never nil" (:usage building3) => "")
    (:area building3) => "22"
    (:created building3) => "2013"))
