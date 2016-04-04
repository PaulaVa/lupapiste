(ns lupapalvelu.pdf.libreoffice-conversion-client
  (:require [clj-http.client :as http]
            [taoensso.timbre :as timbre :refer [trace tracef debug debugf info infof warn warnf error errorf fatal fatalf]]
            [lupapalvelu.i18n :refer [localize]]
            [lupapalvelu.mime :as mime]
            [sade.core :refer [def-]]
            [sade.env :as env])
  (:import (org.apache.commons.io FilenameUtils)
           (java.io File)))


(def- url (str "http://" (env/value :libreoffice :host) ":" (or (env/value :libreoffice :port) 8001)))

(def enabled? (and (env/feature? :libreoffice) (env/value :libreoffice :host)))

(defn- convert-to-pdfa-request [filename content]
  (http/post url
             {:as               :stream
              :throw-exceptions false
              :multipart        [{:name      filename
                                  :part-name "file"
                                  :mime-type (mime/mime-type (mime/sanitize-filename filename))
                                  :encoding  "UTF-8"
                                  :content   content
                                  }]}))

(defn convert-to-pdfa [filename content]
  (try
    (let [{:keys [status body]} (convert-to-pdfa-request filename content)]
      (if (= status 200)
        {:filename   (str (FilenameUtils/removeExtension filename) ".pdf")
         :content    body
         :archivable true}
        (do
          (error "libreoffice conversion error: response status is" status " with body: " body)
          {:filename           filename
           :content            content
           :archivabilityError :libre-conversion-error})))

    (catch Exception e
      (error "libreoffice conversion error: " (.getMessage e))
      {:filename           filename
       :content            content
       :archivabilityError :libre-conversion-error})))

(defn generate-casefile-pdfa [application lang]
  (let [filename (str (localize lang "caseFile.heading") ".fodt")
        tmp-file (File/createTempFile (str "casefile-" (name lang) "-") ".fodt")]
    (write-history-libre-doc application lang tmp-file)
    (:content (convert-to-pdfa filename (io/input-stream tmp-file)))))


(defn generate-verdict-pdfa [application verdict-id paatos-id lang]
  (debug "Generating PDF/A for verdict: " verdict-id ", paatos: " paatos-id ", lang: " lang)
  (let [filename (str (localize lang "application.verdict.title") ".fodt")
        tmp-file (File/createTempFile (str "verdict-" (name lang) "-") ".fodt")]
    (write-verdict-libre-doc application verdict-id paatos-id lang tmp-file)
    (:content (convert-to-pdfa filename (io/input-stream tmp-file)))))