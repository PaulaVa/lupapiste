(ns lupapalvelu.document.maa-aines-canonical-test
  (:require [midje.sweet :refer :all]
            [sade.util :as util]
            [lupapalvelu.document.maa-aines-canonical :as mac]
            [lupapalvelu.factlet :refer :all]
            [lupapalvelu.document.canonical-test-common :refer :all]
            [lupapalvelu.document.ymparisto-schemas]))

(def application {:id "LP-638-2014-00001"
                  :attachments []
                  :auth [{:lastName "Borga" :firstName "Pekka" :username "pekka" :type "owner" :role "owner" :id "777777777777777777000033"}]
                  :authority {:role "authority" :lastName "Borga" :firstName "Pekka" :username "pekka" :id "777777777777777777000033"}
                  :address "Londb\u00f6lentie 97"
                  :created 1391415025497
                  :documents [yrityshakija]
                  :drawings drawings
                  :infoRequest false
                  :location {:x 428195.77099609 :y 6686701.3931274}
                  :neighbors {}
                  :modified 1391415696674
                  :municipality "638"
                  :operations [{:id "abba1" :name "maa-aineslupa" :created 1391415025497}]
                  :openInfoRequest false
                  :opened 1391415025497
                  :organization "638-R"
                  :permitType "MAL"
                  :permitSubtype nil
                  :propertyId "63844900010004"
                  :schema-version 1
                  :sent nil
                  :started nil
                  :state "submitted"
                  :statements statements
                  :submitted 1391415717396
                  :title "Londb\u00f6lentie 97"})

(facts* "maa-aineslupa to canonical"
  (let [canonical (mac/maa-aines-canonical application "fi") => truthy
        MaaAinesluvat (:MaaAinesluvat canonical) => truthy
        toimituksenTiedot (:toimituksenTiedot MaaAinesluvat) => truthy
        aineistonnimi (:aineistonnimi toimituksenTiedot) => (:title application)

        maa-aineslupa-asiatieto (:maaAineslupaAsiatieto MaaAinesluvat) => truthy
        maa-aineslupa (:MaaAineslupaAsia maa-aineslupa-asiatieto)
        kasittelytietotieto (:kasittelytietotieto maa-aineslupa) => truthy

        luvanTunnistetiedot (:luvanTunnistetiedot maa-aineslupa) => truthy
        LupaTunnus (:LupaTunnus luvanTunnistetiedot) => truthy
        muuTunnustieto (:muuTunnustieto LupaTunnus) => truthy
        MuuTunnus (:MuuTunnus muuTunnustieto) => truthy
        tunnus (:tunnus MuuTunnus) => (:id application)
        sovellus (:sovellus MuuTunnus) => "Lupapiste"

        lausuntotieto (:lausuntotieto maa-aineslupa) => truthy
        Lausunto (:Lausunto (first lausuntotieto)) => truthy
        viranomainen (:viranomainen Lausunto) => "Paloviranomainen"
        pyyntoPvm (:pyyntoPvm Lausunto) => "2013-09-17"
        lausuntotieto (:lausuntotieto Lausunto) => truthy
        annettu-lausunto (:Lausunto lausuntotieto) => truthy
        lausunnon-antanut-viranomainen (:viranomainen annettu-lausunto) => "Paloviranomainen"
        varsinainen-lausunto (:lausunto annettu-lausunto) => "Lausunto liitteen\u00e4."
        lausuntoPvm (:lausuntoPvm annettu-lausunto) => "2013-09-17"

        hakemus (-> maa-aineslupa :hakemustieto :Hakemus) => seq
        ]

    (fact "Canonical model has all fields"
      (util/contains-value? canonical nil?) => falsey)

    (fact "hakija"
      (:hakija hakemus)
      =>
      {:nimi {:sukunimi "Yritt\u00e4j\u00e4", :etunimi "Pertti"},
       :puhelin "060222155",
       :sahkopostiosoite "tew@gjr.fi"
       :osoite {:osoitenimi {:teksti "H\u00e4meenkatu 3 "},
                                      :postitoimipaikannimi "kuuva",
                                      :postinumero "43640"}})

;(clojure.pprint/pprint hakemus)

    (facts "sijainti"
      (fact "osoite"
        (let [sijainti (-> maa-aineslupa :sijaintitieto :Sijainti) => truthy
             osoite (:osoite sijainti)]

          (:osoitenimi osoite) => {:teksti "Londb\u00f6lentie 97"}
          (:piste sijainti) => {:Point {:pos "428195.77099609 6686701.3931274"}}))


      )

    ; (clojure.pprint/pprint canonical)
))