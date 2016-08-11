(ns lupapalvelu.attachment.conversion-test
  (:require [midje.sweet :refer :all]
            [lupapalvelu.attachment.conversion :refer :all]
            [lupapalvelu.pdf.pdfa-conversion :as pdfa]
            [lupapalvelu.pdf.libreoffice-conversion-client :as libre]
            [clojure.java.io :as io])
  (:import (java.io InputStream File)))

(facts "Conversion"
  ;; TODO remove attachemnt-type specific keys when convert-all-attachments feature is in PROD
  (fact "(Attachment type), content type, filename and content must be defined"
    (archivability-conversion nil {:foo :faa :attachment-type {:foo :faa}}) => (throws AssertionError))
  (fact "invalid mime"
    (archivability-conversion
      nil
      {:contentType "foo"
       :filename "foo.foo"
       :content (io/file "dev-resources/test-attachment.txt")
       :attachment-type {:foo :faa}}) => {:archivable false
                                          :archivabilityError :invalid-mime-type})
  (fact "TXT conversion"
    (let [result (archivability-conversion
                   nil
                   {:attachment-type {:foo :faa}
                    :filename "foo.txt"
                    :contentType "text/plain"
                    :content (io/file "dev-resources/test-attachment.txt")})]
      (if libre/enabled?
        result => (just {:archivabilityError nil
                         :archivable         true
                         :autoConversion     true
                         :content            (partial instance? InputStream)
                         :filename "foo.pdf"})
        result => (just {:archivabilityError :invalid-mime-type :archivable false}))))

  (fact "PDF conversion - no archive => not-validated"
    (archivability-conversion nil {:attachment-type {:foo :faa}
                                   :filename "foo.pdf"
                                   :contentType "application/pdf"
                                   :content (io/file "dev-resources/invalid-pdfa.pdf")}) => {:archivable false :archivabilityError :not-validated}
    (provided
      (lupapalvelu.pdf.pdfa-conversion/pdf-a-required? anything) => false))

  (when pdfa/pdf2pdf-enabled?
    (fact "PDF conversion - with archive => archivable"
      (archivability-conversion nil {:attachment-type {:foo :faa}
                                     :filename "foo.pdf"
                                     :contentType "application/pdf"
                                     :content (io/file "dev-resources/invalid-pdfa.pdf")}) => (contains {:archivabilityError nil
                                                                                                         :archivable         true
                                                                                                         :content            (partial instance? File)
                                                                                                         :filename           "foo-PDFA.pdf"})
      (provided
        (lupapalvelu.pdf.pdfa-conversion/pdf-a-required? anything) => true))))


