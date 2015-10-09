(ns lupapalvelu.document.ymparisto-schemas
  (:require [lupapalvelu.document.schemas :refer :all]
            [lupapalvelu.document.tools :refer :all]))

#_(def sijainti (body simple-osoite
                 {:name "karttapiirto" :type :text :max-len 4000}))

(def kesto (body {:name "kesto" :type :group
                  :body [{:name "alku" :type :date}
                         {:name "loppu" :type :date}
                         {:name "arki", :type :group, :body [{:name "arkiAlkuAika" :type :time} {:name "arkiLoppuAika" :type :time}]}
                         {:name "lauantai", :type :group, :body [{:name "lauantaiAlkuAika" :type :time} {:name "lauantaiLoppuAika" :type :time}]}
                         {:name "sunnuntai", :type :group, :body [{:name "sunnuntaiAlkuAika" :type :time} {:name "sunnuntaiLoppuAika" :type :time}]}]}))


(def kesto-mini
  (schema-body-without-element-by-name kesto "arki" "lauantai" "sunnuntai"))

(def maatila (body {:name "omistaja" 
                    :type :group
                    :body [{:name "etunimi" :type :string}
                           {:name "sukunimi" :type :string}]}
                   {:name "tilatiedot" 
                    :type :group 
                    :body [{:name "tilatunnus" :type :string}]}
                   (update-in simple-osoite [0 :body] (fn [field] (map #(dissoc % :required) field)))
                   (update-in yhteystiedot [0 :body] (fn [field] (map #(dissoc % :required) field)))))

(def maatila-suppea (body {:name "etunimi" :type :string}
                          {:name "sukunimi" :type :string}
                          {:name "tilatunnus" :type :string}))

(def ymparistolupa (body {:name "lupaviranomainen" :type :string :size "l"}
                         {:name "lupapaatostiedot" :type :string :size "l"}
                         {:name "voimassaoloaika"
                          :type :group
                          :body [{:name "alku" :type :date}
                                 {:name "loppu" :type :date}]}))

(def meluilmoitus (body
                    {:name "rakentaminen" :type :group
                     :body [{:name "melua-aihettava-toiminta" :type :select :sortBy :displayname
                             :body [{:name "louhinta"}
                                    {:name "murskaus"}
                                    {:name "paalutus"}]}
                            {:name "muu-rakentaminen" :type :string :size "m"}
                            {:name "kuvaus" :type :text :max-len 4000}
                            {:name "koneet" :type :text :max-len 4000}]}

                    {:name "tapahtuma" :type :group
                     :body [{:name "nimi" :type :string :size "m"}
                            {:name "ulkoilmakonsertti" :type :checkbox}
                            {:name "kuvaus" :type :text}] }
                    {:name "melu" :type :group
                     :body [{:name "melu10mdBa" :type :string :size "s"}
                            {:name "paivalla" :type :string :size "s"}
                            {:name "yolla" :type :string :size "s"}
                            {:name "mittaus" :type :string :size "m"}]}))

(def pima (body {:name "kuvaus" :type :text :max-len 4000}))

(def ottamismaara (body
                    {:name "kokonaismaara" :type :string :unit "m3" :size "m"}
                    {:name "vuotuinenOtto" :type :string :unit "m3" :size "m"}
                    {:name "ottamisaika" :type :string :unit "y" :size "m"}))

(def ottamis-suunnitelma (body
                           {:name "selvitykset" :type :group
                            :body [{:name "toimenpiteet" :type :text :max-len 4000}
                                   {:name "tutkimukset" :type :text :max-len 4000}
                                   {:name "ainesLaatu" :type :text :max-len 4000}
                                   {:name "ainesMaara" :type :text :max-len 4000}]}
                           {:name "Luonnonolot" :type :group
                            :body [{:name "maisemakuva" :type :text :max-len 4000}
                                   {:name "kasvillisuusJaElaimmisto" :type :text :max-len 4000}
                                   {:name "kaavoitustilanne" :type :text :max-len 4000}]}
                           {:name "pohjavesiolot" :type :group
                            :body [{:name "luokitus" :type :text :max-len 4000}
                                   {:name "suojavyohykkeet" :type :text :max-len 4000}]}
                           {:name "vedenottamot" :type :text :max-len 4000}
                           {:name "vakuus" :type :select :sortBy :displayname
                            :body [{:name "EiAloiteta"}
                                   {:name "Rahaa"}
                                   {:name "Pankkitakaus"}]}))

(def jatteen-keraystoiminta-ilmoitus
  (body
    {:name "toiminnan-muoto"
     :type :select
     :required true
     :body [{:name "uusiToiminta"}
            {:name "muutosToimintaan"}
            {:name "olemassaOlevaToiminta"}]}
    {:name "keraystoiminnan-jarjestaja"
     :type :group
     :required true
     :body [{:name "kunnanKerays" :type :checkbox}
            {:name "tuottajanKerays" :type :checkbox}
            {:name "muuKerays" :type :checkbox}
            {:name "muuKeraysValue" :type :string}]}
    {:name "jatteen-vastuullinen"
     :type :group
     :required true
     :body [{:name "kunnanJate" :type :checkbox}
            {:name "tuottajanJate" :type :checkbox}
            {:name "muuJate" :type :checkbox}
            {:name "muuJateValue" :type :string}]}
    {:name "vastaanottopaikat-liitteena" :type :checkbox}))

(def luonnonmuistomerkin-rauhoittaminen
  (body
    {:name "muistomerkki-perustelut-rauhoitukselle" :type :group
     :group-help "muistomerkki-perustelut-rauhoitukselle.help"
     :body [{:name "kohteen-nimi" :type :string :size "l" :required true}
            kuvaus
            {:name "muita-tietoja" :type :text :max-len 4000 :required false :layout :full-width}]}

   {:name "muistomerkki-kaytto-ja-hoito" :type :group
    :group-help "muistomerkki-kaytto-ja-hoito.help"
    :body [{:name "ei-nahtavyyskohde" :type :checkbox :required true :layout :full-width}]}))

(def tiedot-sailiosta
  {:name "tiedot-sailiosta" :type :group
    :body [{:name "kaytosta-poistamisen-syy" :type :select :sortBy :displayname :required true
            :other-key "muu-syy"
            :body [{:name "lammistysmuodon-vaihtaminen"}
                   {:name "oljylammistyslaitteiston-uusiminen"}]}
           {:name "muu-syy" :type :string}
           {:name "kaytosta-poistamisen-ajankohta" :type :date :required true}
           {:name "kaytosta-poiston-jalkeen" :type :group :layout :horizontal :required true
            :body [{:name "tyhjennetty" :type :checkbox}
                   {:name "puhdistettu" :type :checkbox}
                   {:name "tarkastettu" :type :checkbox}]}
           {:name "sailion-pienin-etaisyys-rakennuksesta" :type :string :subtype :number :size "s" :unit "m" :required true}
           {:name "sailion-pienin-etaisyys-rakennuksesta-mista-mitattu" :type :string :size "l" :required true}
           {:name "koko" :type :string :subtype :number :size "s" :unit "m3" :required true}
           {:name "materiaali" :type :select :sortBy :displayname :required true
            :other-key "muu-materiaali"
            :body [{:name "metalli"}
                   {:name "muovi-tai-lasikuitu"}]}
           {:name "muu-materiaali" :type :string}
           {:name "onko-sailion-pohja-alempana-kuin-rakennuksen-perusteet" :type :group :layout :horizontal
            :body [{:name "onko-sailion-pohja-alempana-kuin-rakennuksen-perusteet-kylla" :type :checkbox}
                   {:name "onko-sailion-pohja-alempana-kuin-rakennuksen-perusteet-paljonko" :type :string :subtype :number :size "s" :unit "m"}]}
           {:name "sailio-sijaitsee-bunkkerissa" :type :select :required true
            :body [{:name "kylla"}
                   {:name "ei"}]}
           {:name "sailio-sijaitsee-tarkealla-pohjavesialueella" :type :select :required true
            :body [{:name "kylla"}
                   {:name "ei"}
                   {:name "ei-tietoa"}]}
           {:name "sailion-kunto" :type :text :max-len 4000 :layout :full-width :required true}
           {:name "onko-tapahtunut-vuotoja" :type :group :layout :horizontal
            :body [{:name "on-tapahtunut-vuotoja" :type :checkbox}
                   {:name "vuotoja-tapahtunut-vuonna" :type :string :subtype :number :min-len 4 :max-len 4 :size "s"}]}
           {:name "onko-tapahtunut-ylitayttoja" :type :group :layout :horizontal
            :body [{:name "on-tapahtunut-ylitayttoja" :type :checkbox}
                   {:name "ylitayttoja-tapahtunut-vuonna" :type :string :subtype :number :min-len 4 :max-len 4 :size "s"}]}
           {:name "oljysailion-putkijarjestelma" :type :group
            :group-help "oljysailion-putkijarjestelma.help"
            :body [{:name "oljysailion-putkijarjestelma" :type :select :sortBy :displayname :required true
                    :body [{:name "1-putkijarjestelma"}
                           {:name "2-putkijarjestelma"}]}]}
           ]})

(def kaytostapoistetun-sailion-jattaminen-maaperaan
  (body
    {:name "tiedot-kiinteistosta" :type :group
     :body (body
             {:name "kiint-omistaja-jos-ei-hakija" :type :string :size "l"}
             {:name "osoite"
                     :type :group
                     :blacklist [turvakielto]
                     :body [{:name "katu" :type :string :subtype :vrk-address}
                            {:name "postinumero" :type :string :subtype :zip :size "s"}
                            {:name "postitoimipaikannimi" :type :string :subtype :vrk-address :size "m"}]})}
   {:name "maahan-jattamisen-perustelut" :type :group :layout :vertical
    :body [{:name "sailion-poistaminen-vahingoittaa-rakenteita" :type :checkbox}
           {:name "sailion-poistaminen-teknisesti-vaikeata" :type :checkbox}
           {:name "sailion-poistaminen-muut-perustelut" :type :checkbox}
           {:name "sailion-kunto" :type :text :max-len 4000 :required true}]}
   tiedot-sailiosta))

(def koeluontoinen-toiminta
  (body
    {:name "kuvaus-toiminnosta"
     :type :text
     :required true
     :max-len 4000
     :placeholder "koeluontoinen-toiminta.kuvaus.placeholder"}
    {:name "raaka-aineet"
     :type :text
     :max-len 4000
     :placeholder "koeluontoinen-toiminta.kuvaus.placeholder"}
    {:name "paastot"
     :type :text
     :max-len 4000
     :placeholder "koeluontoinen-toiminta.kuvaus.placeholder"}
    {:name "ymparistonsuojeluselvitys"
     :type :text
     :max-len 4000
     :placeholder "koeluontoinen-toiminta.kuvaus.placeholder"}))

(def maa-ainesten-kotitarveotto
  (body
    {:name "kotitarveoton-kesto"
     :type :group
     :body [{:name "alkanut" :type :string :subtype :number :required true}
            {:name "jatkuu-vuoteen" :type :string :subtype :number :required true}]}
    {:name "kotitarveoton-maarat"
     :type :group
     :body [{:name "kokonaismaara" :type :string :size "m" :subtype :decimal :unit :k-m3 :required true}
            {:name "maaran-jakautuminen"
             :type :group
             :body [{:name "kalliokivi" :type :string :size "s" :subtype :decimal :unit :k-m3}
                    {:name "sora-ja-hiekka" :type :string :size "s" :subtype :decimal :unit :k-m3}
                    {:name "siltti-ja-savi" :type :string :size "s" :subtype :decimal :unit :k-m3}
                    {:name "moreeni" :type :string :size "s" :subtype :decimal :unit :k-m3}
                    {:name "eloperainen-maalaji" :type :string :size "s" :subtype :decimal :unit :k-m3}
                    {:name "muu" :type :string :size "m" :unit :k-m3}]}]}))

(def ilmoitus-poikkeuksellisesta-tilanteesta
  (body
    {:name "tilanne" :type :select :required true
             :other-key "muu-kertaluonteinen-tapaus"
             :body [{:name "onnettomuus"}
                    {:name "tuotantohairio"}
                    {:name "purkutyo"}]}
    {:name "muu-kertaluonteinen-tapaus" :type :string}
    {:name "paastot-ja-jatteet" :type :group
     :body [{:name "paaston-aiheuttama-vaara" :type :text :max-len 4000 :required true}
            {:name "jatteen-nimi-olomuoto-ominaisuudet" :type :text :max-len 4000 :required true}
            {:name "jatteen-maarat" :type :text :max-len 4000 :required true}
            {:name "muut-paastot-olomuoto-ominaisuudet" :type :text :max-len 4000 :required true}]}
    {:name "jatehuollon-jarjestaminen" :type :group
     :body [{:name "keraily-varastointi-kuljetus-kasittely" :type :text :max-len 4000 :required true}]}))

(def maastoliikennelaki-kilpailut-ja-harjoitukset
  (body
    {:name "toiminnan-laatu" :type :group
     :group-help "maastoliikennelaki-kilpailut-ja-harjoitukset.toiminnan-laatu.help"
     :body [kuvaus]}
    {:name "toiminnan-vaikutukset" :type :group
     :group-help "maastoliikennelaki-kilpailut-ja-harjoitukset.toiminnan-vaikutukset.help"
     :body [{:name "tiedot-vaikutuksista-luonnolle" :type :text :max-len 4000 :required true}]}))

(def elainmaarat [{:name "ryhma" 
                   :type :select 
                   :label false
                   :body (map (partial hash-map :name) 
                              ["lyspylehmat" "emolehmat" "hiehotLihanaudatSiitossonnit" "nuorkarja"
                               "emakotPorsaineen" "sateliittiemakotPorsaineen" "lihasiatSiitossiat"
                               "joutilaatEmakot" "vieroitetutPorsaat"
                               "hevoset" "ponit" "lampaatUuhetKaritsoineen" "vuohetKututKileineen" 
                               "lattiakanatBroileremot" "hakkikanat" "kalkkunat" "broileritKananuorikot"
                               "ankatHanhet" "sorsat"])}
                  {:name "nykyinen" :i18nkey "nykyinen-elainmaara" :label false :type :string :unit "kpl" :size "m"}
                  {:name "maksimi" :i18nkey "maksimi-elainmaara" :label false :type :string :unit "kpl" :size "m"}])

(def lannan-varastointi-ilmoitus (body {:name "toimenpide"
                                        :type :group
                                        :body [{:name "tapa"
                                                :type :select
                                                :required true
                                                :layout "full-width"
                                                :body [{:name "poikkeaminenVarastointitilavuudesta"}
                                                       {:name "muuLannanKaukovarastointi"}]}]}

                                       {:name "elainmaarat" :type :table :repeating true :body elainmaarat}
                                       {:name "muutElaimet"
                                        :repeating true
                                        :type :table
                                        :body [{:name "kuvaus" :label false :type :string}
                                               {:name "nykyinen" :i18nkey "nykyinen-elainmaara" :label false :type :string :unit "kpl" :size "m"}
                                               {:name "maksimi" :i18nkey "maksimi-elainmaara" :label false :type :string :unit "kpl" :size "m"}]}

                                       {:name "vastaanotettuLanta"
                                        :type :group
                                        :repeating true
                                        :body (body
                                               {:name "kuvaus" :type :string}
                                               {:name "maara" :type :string :unit "m3" :size "m"}
                                               (map #(assoc % :size "m") maatila-suppea))}

                                       {:name "lantajarjestelma"
                                        :type :group
                                        :body [{:name "lietelanta" :type :checkbox}
                                               {:name "kuivalantaJaVirtsa" :type :checkbox}
                                               {:name "kuivikelanta" :type :checkbox}
                                               {:name "kuivikepohja" :type :checkbox}
                                               {:name "tyhjennysvali" :type :string :unit "kuukautta" :size "m"}
                                               {:name "kaytettyKuivike" :type :string}]}

                                       {:name "omatVarastot"
                                        :type :group
                                        :body (body (map (partial hash-map :type :string :unit "m3" :size "m" :name)
                                                         ["kuivalantala" "virtsasailio" "lietesailioTaiKuilu" "muuSailio"
                                                          "kuivikepohja" "kompostialusta"])                                                     
                                                    {:name "muutVarastot"
                                                     :type :group
                                                     :body [{:name "tyyppi" :type :string}
                                                            {:name "tilavuus" :type :string :unit "m3" :size "m"}]}
                                                    {:name "suppeaJaloittelualue"
                                                     :type :group
                                                     :body [{:name "jaloittelualue" :type :string :unit "m2" :size "m"}
                                                            {:name "varastointitilavuus" :type :string :unit "m3" :size "m"}]})}
                                       {:name "yhteinenVarasto"
                                        :type :group
                                        :body [{:name "tyyppi" :type :string}
                                               {:name "tilavuus" :type :string :unit "m3" :size "m"}
                                               {:name "kayttajat" :type :table :repeating true :body (map #(assoc % :label false) maatila-suppea)}]}

                                       {:name "selostusElaimienOleskelusta" :type :text :max-len 1000}
                                       {:name "poikkeamissuunnitelma" :type :text :max-len 4000}
                                       
                                       {:name "poikkeamistapa"
                                        :type :group
                                        :body [{:name "tapaA"
                                                :type :group
                                                :repeating true
                                                :body [{:name "hyodyntava-maatila" :type :group :body maatila}
                                                       {:name "ymparistolupa" :type :group :body ymparistolupa}
                                                       {:name "lantamaara" :type :string :unit "m3" :size "m"}]}

                                               {:name "tapaB" 
                                                :type :group
                                                :repeating true
                                                :body [{:name "varastoiva-maatila" :type :group :body maatila}
                                                       {:name "varastointitapa" :type :text :max-len 1000}
                                                       {:name "lantamaara" :type :string :unit "m3" :size "m"}]}

                                               {:name "tapaC" 
                                                :type :group
                                                :repeating true
                                                :body [{:name "hyodyntava-maatila" :type :group :body maatila}
                                                       {:name "hyodyntamispaikka" :type :text :max-len 1000}
                                                       {:name "lantamaara" :type :string :unit "m3" :size "m"}]}

                                               {:name "tapaD"
                                                :type :group
                                                :repeating true
                                                :body [{:name "patterinSijaintipaikka" :type :group :body maatila}
                                                       {:name "peruslohko"
                                                        :type :table
                                                        :repeating true
                                                        :body [{:name "nimi" :label false :type :string}
                                                               {:name "tunnus" :label false :type :string}
                                                               {:name "pintaala" :label false :type :string}]}
                                                       {:name "perustamistapaJaPeittaminen" :type :text}
                                                       {:name "lannanMaara" :type :string :unit "m3" :size "m"}
                                                       {:name "patterienLukumaara" :type :string :unit "kpl" :size "m"}
                                                       {:name "etaisyydet"
                                                        :type :group
                                                        :body [{:name "etaisyysTalouskaivoon" :type :string :unit "m" :size "m"}
                                                               {:name "etaisyysValtaojaan" :type :string :unit "m" :size "m"}
                                                               {:name "etaisyysVesistoon" :type :string :unit "m" :size "m"}]}
                                                       {:name "patterinLevitysaika" :type :group :body [{:name "alku" :type :date}
                                                                                                        {:name "loppu" :type :date}]}]}]}))

(defschemas
  1
  [{:info {:name "meluilmoitus"
           :order 50}
    :body meluilmoitus}
   {:info {:name "pima"
           :order 51}
    :body pima}
   {:info {:name "ymp-ilm-kesto-mini"
           :order 60}
    :body kesto-mini}
   {:info {:name "ymp-ilm-kesto"
           :order 60}
    :body kesto}
   {:info {:name "ottamismaara"
           :order 50}
    :body ottamismaara}
   {:info {:name "ottamis-suunnitelma"
           :order 51}
    :body ottamis-suunnitelma}
   {:info {:name "maa-ainesluvan-omistaja"
           :i18name "osapuoli"
           :order 3
           :type :party}
    :body party}
   {:info {:name "ottamis-suunnitelman-laatija"
           :i18name "osapuoli"
           :order 4
           :type :party}
    :body party}
   {:info {:name "ymp-maksaja"
           :i18name "osapuoli"
           :repeating false
           :order 6
           :removable false
           :approvable true
           :subtype :maksaja
           :type :party}
     :body maksaja}
   {:info {:name "yl-hankkeen-kuvaus"
           :order 1}
    :body [kuvaus
           {:name "peruste" :type :text :max-len 4000 :required false :layout :full-width}]}
   {:info {:name "maa-aineslupa-kuvaus"
           :order 1}
    :body [kuvaus]}
   {:info {:name "luonnonmuistomerkin-rauhoittaminen"
           :approvable true
           :order 1}
    :body luonnonmuistomerkin-rauhoittaminen}
   {:info {:name "kaytostapoistetun-sailion-jattaminen-maaperaan"
           :approvable true
           :order 1}
    :body kaytostapoistetun-sailion-jattaminen-maaperaan}
   {:info {:name "ilmoitus-poik-tilanteesta"
           :order 1}
    :body ilmoitus-poikkeuksellisesta-tilanteesta}
   {:info {:name "jatteen-kerays"}
    :body jatteen-keraystoiminta-ilmoitus}
   {:info {:name "koeluontoinen-toiminta"}
    :body (body
            koeluontoinen-toiminta
            kesto-mini)}
   {:info {:name "maastoliikennelaki-kilpailut-ja-harjoitukset"
           :approvable true
           :order 1}
    :body maastoliikennelaki-kilpailut-ja-harjoitukset}
   {:info {:name "maa-ainesten-kotitarveotto"
           :approvable true}
    :body maa-ainesten-kotitarveotto}
   {:info {:name "yl-maatalous-hankkeen-kuvaus"
           :order 1}
    :body [kuvaus
           {:name "tilatunnus" :type :string}]}
   {:info {:name "lannan-varastointi"
           :section-help "lannan-varastointi-kuvaus.help"}
    :body lannan-varastointi-ilmoitus}

   {:info {:name "paatoksen-toimitus"
           :order 9999}
    :body [{:name "paatoksenToimittaminen" :type :select :sortBy :displayname
            :body [{:name "Noudetaan"}
                   {:name "Postitetaan"}]}]}
   ])

