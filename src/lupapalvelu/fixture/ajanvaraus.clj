(ns lupapalvelu.fixture.ajanvaraus
  (:require [lupapalvelu.mongo :as mongo]
            [lupapalvelu.fixture.core :refer :all]
            [sade.core :refer :all]
            [lupapalvelu.calendars-api]))

(def users
  [;; Solita admin:  admin / admin
   {:id "777777777777777777000099"
    :email "admin@solita.fi"
    :enabled true
    :role "admin"
    :firstName "Admin"
    :lastName "Admin"
    :phone "03030303"
    :username "admin"
    :private {:password "$2a$10$WHPur/hjvaOTlm41VFjtjuPI5hBoIMm8Y1p2vL4KqRi7QUvHMS1Ie"
              :apikey "5087ba34c2e667024fbd5992"}}

   ;; Sipoo

   ;; Simo Suurvisiiri - Sipoon R paakayttaja:  sipoo / sipoo
   {:id "50ac77ecc2e6c2ea6e73f83e"
    :email "admin@sipoo.fi"
    :enabled true
    :role "authorityAdmin"
    :orgAuthz {:753-R-TESTI #{:authorityAdmin}}
    :firstName "Simo"
    :lastName "Suurvisiiri"
    :username "sipoo"
    :private {:password "$2a$10$VFcksPILCd9ykyl.1FIhwO/tEYby9SsqZL7GsIAdpJ1XGvAG2KskG"
              :apikey "50ac788ec2e6c2ea6e73f83f"}}

   ;; Sonja Sibbo - Sipoon lupa-arkkitehti:  sonja / sonja
   {:id "777777777777777777888823"
    :username "sonja"
    :role "authority"
    :enabled true
    :email "sonja.sibbo@sipoo.fi"
    :orgAuthz {:753-R-TESTI #{:authority :approver}
               :753-YA #{:authority :approver}
               :998-R-TESTI-2 #{:authority :approver}}
    :firstName "Sonja"
    :lastName "Sibbo"
    :phone "03121991"
    :street "Katuosoite 1 a 1"
    :zip "33456"
    :city "Sipoo"
    :private {:password "$2a$10$s4OOPduvZeH5yQzsCFSKIuVKiwbKvNs90f80zc57FDiPnGjuMbuf2"
              :apikey "5056e6d3aa24a1c901e6b9d1"}
    :applicationFilters [{:id "foobar"
                          :title "Foobar"
                          :sort {:asc false
                                 :field "modified"}
                          :filter {:handlers []
                                   :tags []
                                   :operations []
                                   :organizations []
                                   :areas []}}
                         {:id "barfoo"
                          :title "Barfoo"
                          :sort {:asc false
                                 :field "modified"}
                          :filter {:handlers []
                                   :tags []
                                   :operations []
                                   :organizations []
                                   :areas []}}]}
   ])

(deffixture "ajanvaraus" {}
  (mongo/clear!)
  (mongo/insert-batch :ssoKeys [{:_id "12342424c26b7342d92a4321" :ip "127.0.0.1" :key "ozckCE8EESo+wMKWklGevQ==" :crypto-iv "V0HaDa6lpWKj+W0uMKyHBw=="}
                                {:_id "12342424c26b7342d92a9876" :ip "109.204.231.126" :key "ozckCE8EESo+wMKWklGevQ==" :crypto-iv "V0HaDa6lpWKj+W0uMKyHBw=="}])
  (mongo/insert-batch :users users)
  (lupapalvelu.calendars-api/delete-calendar "777777777777777777888823"))