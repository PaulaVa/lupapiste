(ns lupapalvelu.email-test
  (:require [lupapalvelu.email :refer :all]
            [midje.sweet :refer :all]
            [midje.util :refer [testable-privates]]
            [clojure.java.io :as io]))

(testable-privates lupapalvelu.email preprocess-context)

(facts "apply-template"
  (facts "invalid template"
   (apply-template "does-not-exist.md" {:receiver "foobar"}) => (throws IllegalArgumentException))

  (against-background [(fetch-template "master.md" "en")      => "{{>header}}\n\n{{>body}}\n\n{{>footer}}"
                       (fetch-template "header.md" "en")      => "# {{header}}"
                       (fetch-template "footer.md" "en")      => "## {{footer}}"
                       (fetch-template "test.md"   "en")      => "This is *test* message for {{applicationRole.hakija}} {{receiver}} [link text](http://link.url \"alt text\")"
                       (find-resource "html-wrap.html" "en")  => (io/input-stream (.getBytes "<html><body></body></html>"))]
    (facts "header and footer"
      (let [[plain html] (apply-template "test.md" {:header "HEADER" :footer "FOOTER" :receiver "foobar" :lang "en"})]
        plain => "\nHEADER\n\nThis is test message for applicant foobar link text: http://link.url \n\nFOOTER\n"
        html => "<html><body><h1>HEADER</h1><p>This is <em>test</em> message for applicant foobar <a href=\"http://link.url\" title=\"alt text\">link text</a></p><h2>FOOTER</h2></body></html>"))))

(facts "User language markdown templates: test body"
       (fetch-template "testbody.md") => (contains "suomi")
       (fetch-template "testbody.md" "sv") => (contains "Svenska")
       (fetch-template "testbody.md" "fi") => (contains "suomi")
       (fetch-template "testbody.md" "cn") => (contains "suomi"))

(facts "User language markdown templates: footer"
       (fetch-template "footer.md") => (contains "automaattinen")
       (fetch-template "footer.md" "sv") => (contains "automatiskt")
       (fetch-template "footer.md" "fi") => (contains "automaattinen")
       (fetch-template "footer.md" "cn") => (contains "automaattinen"))

(facts "User language html templates: test body"
       (fetch-template "testbody.html") => (contains "<em>suomi")
       (fetch-template "testbody.html" "sv") => (contains "<em>Svenska")
       (fetch-template "testbody.html" "fi") => (contains "<em>suomi")
       (fetch-template "testbody.html" "cn") => (contains "<em>suomi"))

(defn mail-check [s & [html?]]
  (fn [[txt html]]
    (fact "text" txt => (contains (str "\n" (when html? "    ") s) :gaps-ok))
    (fact "html" html => (contains (str (if html? "<em>" "<p>") s) :gaps-ok))))

(facts "Apply markdown template"
       (let [ctx {:hej "Morgon" :moi "Mortonki"}]
         (apply-template "testbody.md" ctx) => (mail-check "suomi Mortonki")
         (apply-template "testbody.md" (assoc ctx :lang "cn"))
         => (mail-check "suomi Mortonki")
         (apply-template "testbody.md" (assoc ctx :lang "sv"))
         => (mail-check "Svenska Morgon")))

(facts "Apply html template"
       (let [ctx {:hej "Morgon" :moi "Mortonki"}]
         (apply-template "testbody.html" ctx) => (mail-check "suomi Mortonki" true)
         (apply-template "testbody.html" (assoc ctx :lang "cn"))
         => (mail-check "suomi Mortonki" true)
         (apply-template "testbody.html" (assoc ctx :lang "sv"))
         => (mail-check "Svenska Morgon" true)))

(facts "localization-keys-from-template"

  (fact "finds both escaped and unescaped variables"
    (localization-keys-from-template "{{this}} {{& and.this }} {{{and.also.this}}}")
    => [["this"] ["and" "this"] ["and" "also" "this"]])

  (fact "ignores comments"
    (localization-keys-from-template "{{!not.mistaken.for.variable}}") => [])

  (fact "ignores everything within a section"
    (localization-keys-from-template "{{#section}}\n{{this.is.ignored}} {{/section}} {{this.is.not}}")
    => [["this" "is" "not"]]
    (localization-keys-from-template "{{^inverted-section}} {{ignored}}\n{{/inverted-section}}") => []))

(against-background [(fetch-template "test.md"      anything)   => "Just a dummy template"
                     (fetch-template "i18n-test.md" "en")       => "{{applicationRole.authority}} {{applicationRole.foreman}}"
                     (fetch-template "i18n-test.md" "fi")       => "{{applicationRole.authority}}"
                     (fetch-template "nonexistent.md" anything) => "{{nonexistent.localization.key}}"]
  (facts "preprocess-context"

    (fact "replaces function values in the context by calling them with the language (:lang) as argument"
      (preprocess-context "test.md" {:lang "fi" :nationality #(get {"fi" "Finnish"} % "unknown")}) => {:lang "fi" :nationality "Finnish"})

    (fact "adds localizations from i18n files for keys present in the template but missing from the context"
      (preprocess-context "i18n-test.md" {:lang "fi"}) => {:lang            "fi"
                                                           :applicationRole {:authority "viranomainen"}})

    (fact "gives context localizations precedence over those in i18n files"
      (preprocess-context "i18n-test.md" {:lang "en" :applicationRole {:authority "definitely not 'authority'"}})
      => {:lang "en" :applicationRole {:authority "definitely not 'authority'" :foreman "supervisor"}})

    (fact "throws when it encounters a missing localization key in the template"
      (preprocess-context "nonexistent.md" {:lang "sv"}) => (throws #"No localization"))))