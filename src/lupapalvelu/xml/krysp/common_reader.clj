(ns lupapalvelu.xml.krysp.common-reader
  (:require [ring.util.codec :as codec]
            [lupapalvelu.permit :as permit]
            [sade.strings :as ss]
            [sade.xml :as sxml]))


;; Object types (URL encoded)
(def building-type    "typeName=rakval%3AValmisRakennus")
(def rakval-case-type "typeName=rakval%3ARakennusvalvontaAsia")
(def poik-case-type   "typeName=ppst%3APoikkeamisasia,ppst%3ASuunnittelutarveasia")
;; (def ya-type          "typeName=yak%3AYleisetAlueet")
(def yl-case-type     "typeName=ymy%3AYmparistolupa")
(def mal-case-type    "typeName=ymm%3AMaaAineslupaAsia")
(def vvvl-case-type   "typeName=ymv%3AVapautus")

;;(def kt-case-type-prefix  "typeName=kiito%3A")

(def rakennuksen-kiinteistotunnus "rakval:rakennustieto/rakval:Rakennus/rakval:rakennuksenTiedot/rakval:rakennustunnus/rakval:kiinttun")

;; Object types as enlive selector
(def case-elem-selector #{[:RakennusvalvontaAsia]
                          [:Poikkeamisasia]
                          [:Suunnittelutarveasia]
                          [:Sijoituslupa]
                          [:Kayttolupa]
                          [:Liikennejarjestelylupa]
                          [:Tyolupa]
                          [:Ilmoitukset]
                          [:Ymparistolupa]
                          [:MaaAineslupaAsia]
                          [:Vapautus]})

(def outlier-elem-selector #{[:Lohkominen]
                             [:Rasitetoimitus]
                             [:YleisenAlueenLohkominen]
                             [:KiinteistolajinMuutos]
                             [:YhtAlueenOsuuksienSiirto]
                             [:KiinteistojenYhdistaminen]
                             [:Halkominen]
                             [:KiinteistonMaaritys]
                             [:Tilusvaihto]})

;; Only those types supported by Facta are included.
(def kt-types (let [elems (map (comp (partial str "kiito:") name)
                               [:KiinteistolajinMuutos
                                :KiinteistojenYhdistaminen
                                :Lohkominen
                                :YleisenAlueenLohkominen
                                :Rasitetoimitus])]
                (str "typeName=" (ss/join "," elems))))

(defmulti get-tunnus-xml-path
  "Get path for reading xml without ns."
  {:arglists '([permit-type search-type])}
  (fn [permit-type & args]
    (keyword permit-type)))

(defmethod get-tunnus-xml-path :default
  [permit-type search-type]
  (case search-type
    :application-id  [:LupaTunnus :muuTunnustieto :MuuTunnus :tunnus]
    :kuntalupatunnus [:LupaTunnus :kuntalupatunnus]))

(defmethod get-tunnus-xml-path :KT
  [permit-type search-type]
  [:hakemustunnustieto :Hakemustunnus :tunnus])

(defmulti get-tunnus-path
  "Get url path for fetching xmls from krysp."
  {:arglists '([permit-type search-type])}
  (fn [permit-type & args]
    (keyword permit-type)))

(defmethod get-tunnus-path :default
  [permit-type search-type]
  (let [prefix (permit/get-metadata permit-type :wfs-krysp-url-asia-prefix)]
    (->> (map (partial str "yht") (get-tunnus-xml-path permit-type search-type))
         (ss/join "/")
         (str prefix))))

(defmethod get-tunnus-path :KT
  [permit-type search-type]
  "kiito:toimitushakemustieto/kiito:Toimitushakemus/kiito:hakemustunnustieto/kiito:Hakemustunnus/yht:tunnus")

(defn- property-is-equal-to
  [property value]
  (str "<PropertyIsEqualTo>"
       "<PropertyName>" (sxml/escape-xml property) "</PropertyName>"
       "<Literal>" (sxml/escape-xml value) "</Literal>"
       "</PropertyIsEqualTo>"))

(defn property-equals
  "Returns URL-encoded search parameter suitable for 'filter'"
  [property value]
  (->> (property-is-equal-to property value)
       (codec/url-encode)))

(defn property-in
  "Returns WFS 1.1.0 compatible URL-encoded search parameter suitable for 'filter'"
  [property values]
  (if (> 2 (count values))
    (property-equals property (first values))
    (->> (map (partial property-is-equal-to property) values)
         (apply str)
         (ss/wrap-with-tag "Or")
         (codec/url-encode))))

(defn wfs-krysp-url [server object-type filter]
  (let [server (if (ss/contains? server "?")
                 (if (ss/ends-with server "&")
                   server
                   (str server "&"))
                 (str server "?"))]
    (str server "request=GetFeature&" object-type "&filter=" filter)))

(defn wfs-krysp-url-with-service [server object-type filter]
  (str (wfs-krysp-url server object-type filter) "&service=WFS"))
