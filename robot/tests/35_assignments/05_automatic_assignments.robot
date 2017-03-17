*** Settings ***

Documentation   Automatic assignments are created as new attachments are added
Suite Setup     Apply minimal fixture now
Suite Teardown  Logout
Resource        ../../common_resource.robot
Resource        assignments_common.robot
Resource        ../06_attachments/attachment_resource.robot
Variables       ../06_attachments/variables.py

*** Test Cases ***

Pena logs in, creates and submits application
  Set Suite Variable  ${appname}  To Do
  Set Suite Variable  ${propertyid}  753-416-88-88
  Pena logs in
  Create application with state  ${appname}  ${propertyid}  kerrostalo-rivitalo  submitted
  Open tab  attachments

Pena uploads an application for which there is an automatic assignment trigger in Sipoo
  Add attachment file  tr[data-test-type='paapiirustus.asemapiirros']  ${TXT_TESTFILE_PATH}  oma piirustus
  Scroll and click test id  batch-ready

Pena doesn't see filters 'Ei tehtäviä' and 'Aita ja asema'
  Wait until  Element should be visible  xpath=//label[@data-test-id='other-filter-label']
  Wait until  Element should not be visible  xpath=//label[@data-test-id='assignment-not-targeted-filter-label']
  Wait until  Element should not be visible  xpath=//label[@data-test-id='assignment-dead1111111111111112beef-filter-label']
  Logout

Sonja logs in and opens application
  As Sonja
  Open application  ${appname}  ${propertyid}
  Open tab  attachments

Automatic assignment with the attachment as the target has been created
  Wait until  Element should contain  xpath=//div[@data-test-id='automatic-assignment-0']//div[@data-test-id='assignment-text']  Aita ja asema, 1

Sonja sees filters 'Ei tehtäviä' and 'Aita ja asema'
  Wait until  Checkbox wrapper not selected by test id  assignment-not-targeted-filter-checkbox
  Wait until  Checkbox wrapper not selected by test id  assignment-dead1111111111111112beef-filter-checkbox
  Scroll and click test id  assignment-dead1111111111111112beef-filter-checkbox
  Wait until  Total attachments row count is  1
  Scroll and click test id  assignment-dead1111111111111112beef-filter-checkbox

Sonja adds handler to application
  Click by test id  edit-handlers
  Wait test id visible  add-handler
  Click by test id  add-handler
  Edit handler  0  Sibbo Sonja  Käsittelijä
  Logout

Pena uploads two more applications
  As Pena
  Open application  ${appname}  ${propertyid}
  Open tab  attachments
  Upload attachment  ${TXT_TESTFILE_PATH}  Asemapiirros  oma piirustus  Yleisesti hankkeeseen
  Upload attachment  ${TXT_TESTFILE_PATH}  Asemapiirros  oma piirustus  Yleisesti hankkeeseen
  Logout

Assignments are assigned to Sonja
  As Sonja
  Open assignments search
  Open search tab  all
  Click by test id  toggle-advanced-filters
  Autocomplete selection is  div[@data-test-id="recipient-filter-component"]  Omat tehtäväni
  Xpath Should Match X Times  //table[@id="assignments-list"]//tbody/tr[@class="assignment-row"]  1
  Element text should be  xpath=(//table[@id="assignments-list"]//tbody/tr[@class="assignment-row"])[1]/td[@data-test-col-name='description']  Aita ja asema

Sonja can see that there are more attachments in the same assignment
  Open applications search
  Open application  ${appname}  ${propertyid}
  Open tab  attachments
  Wait until  Element should contain  xpath=//div[@data-test-id='automatic-assignment-0']//div[@data-test-id='assignment-text']  Aita ja asema, 3

Sonja completes the automatic assignment
  Click by test id  mark-assignment-complete
  Wait until  Element should not be visible  xpath=//div[@data-test-id='automatic-assignment']
  Logout

Pena uploads two more applications belonging in different automatic assignment trigger groups
  As Pena
  Open application  ${appname}  ${propertyid}
  Open tab  attachments
  Upload attachment  ${TXT_TESTFILE_PATH}  Asemapiirros  oma piirustus  Yleisesti hankkeeseen
  Upload attachment  ${TXT_TESTFILE_PATH}  ELY:n tai kunnan poikkeamapäätös  poikkeamapäätös  Yleisesti hankkeeseen
  Logout

Sonja opens attachments tab and sees 'ELY ja naapuri' filter
  As Sonja
  Open application  ${appname}  ${propertyid}
  Open tab  attachments
  Wait until  Checkbox wrapper not selected by test id  assignment-not-targeted-filter-checkbox
  Wait until  Checkbox wrapper not selected by test id  assignment-dead1111111111111112beef-filter-checkbox
  Wait until  Checkbox wrapper not selected by test id  assignment-dead1111111111111111beef-filter-checkbox

Sonja has two assignments
  Open assignments search
  Open search tab  all
  Click by test id  toggle-advanced-filters
  Autocomplete selection is  div[@data-test-id="recipient-filter-component"]  Omat tehtäväni
  Xpath Should Match X Times  //table[@id="assignments-list"]//tbody/tr[@class="assignment-row"]  2
  Element text should be  xpath=(//table[@id="assignments-list"]//tbody/tr[@class="assignment-row"])[1]/td[@data-test-col-name='description']  Aita ja asema
  Element text should be  xpath=(//table[@id="assignments-list"]//tbody/tr[@class="assignment-row"])[1]/td[@data-test-col-name='description']  Aita ja asema

Sonja can see two automatic assignments
  Open applications search
  Open application  ${appname}  ${propertyid}
  Open tab  attachments
  Wait until  Element should contain  xpath=//div[@data-test-id='automatic-assignment-0']//div[@data-test-id='assignment-text']  Aita ja asema, 1
  Wait until  Element should contain  xpath=//div[@data-test-id='automatic-assignment-1']//div[@data-test-id='assignment-text']  ELY ja naapuri, 1

Sonja deletes ELY:n tai kunnan poikkeamapäätös attachment, and the associated automatic assignment is deleted
  Wait Until  Delete attachment  ennakkoluvat_ja_lausunnot.elyn_tai_kunnan_poikkeamapaatos
  Wait until  Element should not be visible  xpath=//div[@data-test-id='automatic-assignment-1']

Sonja changes handler
  Click by test id  edit-handlers
  Wait test id visible  add-handler
  Click by test id  add-handler
  Edit handler  0  Sibbo Ronja  Käsittelijä

Sonja have only completed assignment
  Open assignments search
  Click by test id  toggle-advanced-filters
  Autocomplete selection is  div[@data-test-id="recipient-filter-component"]  Omat tehtäväni
  Xpath Should Match X Times  //table[@id="assignments-list"]//tbody/tr[@class="assignment-row"]  1
  Element text should be  xpath=(//table[@id="assignments-list"]//tbody/tr[@class="assignment-row"])[1]/td[@data-test-col-name='description']  Aita ja asema
  Logout

Ronja have one assignment
  As Ronja
  Open assignments search
  Open search tab  all
  Click by test id  toggle-advanced-filters
  Autocomplete selection is  div[@data-test-id="recipient-filter-component"]  Omat tehtäväni
  Xpath Should Match X Times  //table[@id="assignments-list"]//tbody/tr[@class="assignment-row"]  1
  Element text should be  xpath=(//table[@id="assignments-list"]//tbody/tr[@class="assignment-row"])[1]/td[@data-test-col-name='description']  Aita ja asema
