*** Settings ***

Resource        ../../common_resource.robot
Resource        ../common_keywords/approve_helpers.robot
Resource        keywords.robot
Suite Setup     Initialize foreman

*** Test Cases ***

Applicant creates new application
  Pena logs in
  Create project application  open

Applicant invites Mikko
  Open tab  parties
  Open foreman accordions
  Invite Mikko

Applicant invites Solita
  Open tab  parties
  Open foreman accordions
  Invite company to application  Solita Oy

Solita accepts invitation
  Open last email
  Wait until  Element should contain  xpath=//dd[@data-test-id='to']  kaino@solita.fi
  Click Element  xpath=(//a[contains(., 'accept-company-invitation')])
  Wait until  Page should contain  Hakemus on liitetty onnistuneesti yrityksen tiliin.
  Go to login page

Mikko accepts invitation
  Mikko logs in
  Wait until  Element should be visible  xpath=//*[@data-test-id='accept-invite-button']
  Element Should Contain  xpath=//div[@class='invitation'][1]//h3  ${appname}, Sipoo,
  Element Text Should Be  xpath=//div[@class='invitation']//p[@data-test-id='invitation-text-0']  Tervetuloa muokkaamaan hakemusta
  Click by test id  accept-invite-button
  Wait until  Element should not be visible  xpath=//*[@data-test-id='accept-invite-button']
  Logout

Applicant sets Solita as hakija
  Pena logs in
  Open application  ${appname}  753-416-25-22
  Open tab  parties
  Open foreman accordions
  Scroll and click input  section[data-doc-type=hakija-r] input[value=yritys]
  Wait until  Select From List  xpath=//section[@data-doc-type="hakija-r"]//select[@name="company-select"]  Solita Oy (1060155-5)
  Wait Until  Textfield Value Should Be  //section[@data-doc-type="hakija-r"]//input[@data-docgen-path="yritys.yritysnimi"]  Solita Oy

Applicant cannot create foreman applications before verdict is given
  Element should be visible  xpath=//div[@data-test-id="invite-foreman-authority-info"]
  Element should not be visible  xpath=//button[@data-test-id="invite-foreman-button"]
  Element should not be visible  xpath=//div[@data-test-id="invite-foreman-button-info"]

Applicant sets his info to the applicant document
  Click by test id  hakija-r_append_btn
  Wait until  Select From List  xpath=(//section[@data-doc-type="hakija-r"])[2]//div[@data-select-one-of="henkilo"]//select[@name="henkilo.userId"]  Panaani Pena
  Wait Until  Textfield Value Should Be  xpath=(//section[@data-doc-type="hakija-r"])[2]//input[@data-docgen-path="henkilo.henkilotiedot.etunimi"]  Pena
  Logout

Sonja can invite foremen to application
  Sonja logs in
  Open application  ${appname}  753-416-25-22
  Open tab  parties
  Open foreman accordions
  Wait until  Element should be visible  xpath=//button[@data-test-id="invite-foreman-button"]
  Wait until  Element should be visible  xpath=//div[@data-test-id="invite-foreman-button-info"]
  Element should not be visible  xpath=//div[@data-test-id="invite-foreman-authority-info"]

Sonja invites foreman Teppo to application
  Click by test id  invite-foreman-button
  Input Text  invite-foreman-email  teppo@example.com
  Click by test id  application-invite-foreman
  Wait until  Click by test id  application-invite-foreman-close-dialog
  Wait until  Element should be visible  //section[@id='application']//span[@data-test-primary-operation-id='tyonjohtajan-nimeaminen-v2']
  ${foremanAppId} =  Get Text  xpath=//section[@id='application']//span[@data-test-id='application-id']
  Set Suite Variable  ${foremanAppId}  ${foremanAppId}
  Logout

Applicant sees sent invitation on the original application
  Pena logs in
  Open project application
  Open tab  parties
  Open foreman accordions
  Wait until  Element text should be  xpath=//ul[@data-test-id='invited-foremans']//span[@data-test-id='foreman-email']  (teppo@example.com)
  # Also in auth array
  Wait until  Is authorized party  teppo@example.com

Owner is invited to foreman app, approves invitation
  Open application by id  ${foremanAppId}
  Wait until  Element Text Should Be  xpath=//section[@id='application']//span[@data-test-id='application-id']  ${foremanAppId}
  Confirm yes no dialog
  Wait until  New modal mask is invisible


Applicant sees sent invitations on the foreman application
  Open tab  parties
  Wait until  Xpath Should Match X Times  //table//tr[@class="party"]  4
  Logout

Foreman can see application
  Teppo logs in
  Go to page  applications
  # Should work always because latest application is always at the top
  Wait until  Element text should be  xpath=//table[@id='applications-list']//tr[@data-test-address='${appname}'][1]/td[@data-test-col-name='operation']  Työnjohtajan nimeäminen
  Wait until  Element text should be  xpath=//table[@id='applications-list']//tr[@data-test-address='${appname}'][2]/td[@data-test-col-name='operation']  Asuinkerrostalon tai rivitalon rakentaminen

Foreman accepts invite to project application
  Open project application
  Wait until  Confirm yes no dialog
  Logout

Applicant returns to project application
  Pena logs in
  Open project application

Applicant upgrades foremans authorization to full write access
  Open tab  parties
  Element should be visible by test id  change-auth-teppo@example.com
  Click enabled by test id  change-auth-teppo@example.com
  Confirm yes no dialog
  Element should not be visible by test id  change-auth-teppo@example.com

Foreman application can't be submitted before subtype is selected
  Wait until  Element should be visible  xpath=//a[@data-test-id='test-application-app-linking-to-us']
  Click by test id  test-application-app-linking-to-us
  Wait until  Element should be visible  //section[@id='application']//span[@data-test-primary-operation-id='tyonjohtajan-nimeaminen-v2']
  Open tab  requiredFieldSummary
  Element should be disabled  xpath=//button[@data-test-id='application-submit-btn']
  Submit application error should be  error.foreman.type-not-selected

Foreman application can't be submitted before link permit is submitted
  Select from list by value  permitSubtypeSelect  tyonjohtaja-hakemus
  Open tab  requiredFieldSummary
  Wait for jQuery
  Element should be disabled  xpath=//button[@data-test-id='application-submit-btn']
  Wait until  Submit application error should be  error.not-submittable.foreman-link

Applicant can set Teppo as foreman substitute
  Open tab  parties
  Input text with jQuery  input[data-docgen-path="sijaistus.sijaistettavaHloEtunimi"]  Bob

Add phone number
  Input text with jQuery  input[data-docgen-path="yhteystiedot.puhelin"]  12345678

Application is submitted
  Open project application
  Wait Until  Element should contain  xpath=//*[@data-test-id='test-application-primary-operation']  Asuinkerrostalon tai rivitalon rakentaminen
  Submit application
  Logout

Original application is approved and given a verdict
  Sonja logs in
  Open application by id  ${foremanAppId}
  Open tab  requiredFieldSummary
  Click by test id  test-application-link-permit-lupapistetunnus
  Project application is open
  Approve application
  Open tab  verdict
  Submit empty verdict

All foremen table is shown on the Construction tab
  Open tab  tasks
  Element should contain  jquery=table.all-foremen-table tbody tr  (sijainen)
  Element should contain  jquery=table.all-foremen-table tbody tr  12345678
  Logout

Applicant can create foreman applications after verdict is given for the original application
  Sleep  2s
  Pena logs in
  Open project application
  Open tab  parties
  Open foreman accordions
  Element should not be visible  xpath=//div[@data-test-id="invite-foreman-authority-info"]
  Wait until  Element should be visible  xpath=//button[@data-test-id="invite-foreman-button"]
  Wait until  Element should be visible  xpath=//div[@data-test-id="invite-foreman-button-info"]
  Logout

Authority adds työnjohtaja task to original application
  Sonja logs in
  Open project application
  Add työnjohtaja task to current application  Ylitarkastaja
  Add työnjohtaja task to current application  Alitarkastaja
  Wait until  Xpath Should Match X Times  //div[@data-test-id="tasks-foreman"]//tbody/tr  2
  Logout

Applicant can link existing foreman application to foreman task
  Pena logs in
  Open project application
  Open tab  tasks
  Wait until  Element should be visible  xpath=//select[@data-test-id="foreman-selection-1"]
  Select From List By Value  xpath=//select[@data-test-id="foreman-selection-1"]  ${foremanAppId}
  # Sleep so the repository reload does not prune dom when waiting
  Sleep  2s
  Wait Until  List Selection Should Be  xpath=//select[@data-test-id="foreman-selection-1"]  ${foremanAppId}

Applicant can clear the link and change the foreman role
  Select From List By Index  xpath=//select[@data-test-id="foreman-selection-1"]  0
  # Sleep so the repository reload does not prune dom when waiting
  Sleep  2s
  Wait Until  List Selection Should Be  xpath=//select[@data-test-id="foreman-selection-1"]  Valitse...
  Select From List By Value  xpath=//select[@data-test-id="foreman-selection-0"]  ${foremanAppId}
  Sleep  2s
  Wait Until  List Selection Should Be  xpath=//select[@data-test-id="foreman-selection-0"]  ${foremanAppId}

Applicant can move to linked foreman application and back
  Scroll and click test id  foreman-application-link-${foremanAppId}
  Wait until  Element text should be  xpath=//span[@data-test-id='application-id']  ${foremanAppId}
  Scroll and click test id  test-application-link-permit-lupapistetunnus

Applicant can start invite flow from tasks tab
  Open tab  tasks
  Click enabled by test id  invite-other-foreman-button
  Wait until  Element should be visible  //div[@id='dialog-invite-foreman']
  Click by test id  cancel-foreman-dialog
  Click enabled by test id  invite-substitute-foreman-button
  Wait until  Element should be visible  //div[@id='dialog-invite-foreman']
  Click by test id  cancel-foreman-dialog

Applicant can invite additional foremen to application with verdict
  Wait and click   xpath=//div[@data-test-id='tasks-foreman']//tr[@data-test-name='Alitarkastaja']/td[@data-test-col-name='foreman-name-or-invite']/a
  Wait until  Element should be visible  invite-foreman-email
  Input Text  invite-foreman-email  teppo@example.com
  Click by test id  application-invite-foreman
  Wait until  Click by test id  application-invite-foreman-close-dialog
  Wait until  Element should be visible  //section[@id='application']//span[@data-test-primary-operation-id='tyonjohtajan-nimeaminen-v2']

Applicant invites foreman Mikko to application
  Open project application
  Open tab  parties
  Open foreman accordions
  Click by test id  invite-foreman-button
  Input Text  invite-foreman-email  mikko@example.com
  Click by test id  application-invite-foreman
  Wait until  Click by test id  application-invite-foreman-close-dialog
  Wait until  Element should be visible  //section[@id='application']//span[@data-test-primary-operation-id='tyonjohtajan-nimeaminen-v2']
  ${foremanAppId2} =  Get Text  xpath=//section[@id='application']//span[@data-test-id='application-id']
  Set Suite Variable  ${foremanAppId2}  ${foremanAppId2}
  Logout

Authority can view draft foreman application, but can't use commands
  # LPK-289
  Sonja logs in
  Open application by id  ${foremanAppId2}
  Wait until  Element should be visible  xpath=//section[@data-doc-type="hankkeen-kuvaus-minimum"]
  Open accordions  info
  Element should be disabled  xpath=//section[@data-doc-type="hankkeen-kuvaus-minimum"]//textarea

...on parties tab
  Open tab  parties
  Element should be disabled  xpath=//section[@data-doc-type="hakija-tj"]//div[@data-select-one-of="henkilo"]//select[@name="henkilo.userId"]
  Element should be disabled  xpath=//section[@data-doc-type="hakija-tj"]//div[@data-select-one-of="henkilo"]//input[@data-docgen-path="henkilo.henkilotiedot.etunimi"]

...on attachments tab
  Open tab  attachments
  Element should not be visible  jquery=div#application-attachments-tab button[data-test-id=add-attachment]
  Element should not be visible  jquery=div#application-attachments-tab button[data-test-id=add-attachment-templates]
  Page should not contain  jquery=div#application-attachments-tab div[data-test-id=attachment-operation-buttons] button:visible

...submit aplication
  Open tab  requiredFieldSummary
  Element should not be visible  xpath=//div[@id="application-requiredFieldSummary-tab"]//button[@data-test-id="application-submit-btn"]

...application actions
  # Application actions only exportPDF is visible
  Element should be visible  xpath=//div[@class="application_actions"]//button[@data-test-id="application-pdf-btn"]
  Element should not be visible  xpath=//div[@class="application_actions"]//button[@data-test-id="add-operation"]
  Element should not be visible  xpath=//div[@class="application_actions"]//button[@data-test-id="application-add-link-permit-btn"]
  Element should not be visible  xpath=//div[@class="application_actions"]//button[@data-test-id="application-cancel-btn"]
  Element should not be visible  xpath=//div[@class="application_actions"]//button[@data-test-id="application-cancel-authority-btn"]
  Logout

Frontend errors check
  There are no frontend errors

*** Keywords ***

Invite Mikko
  Invite count is  0
  Scroll and click test id  application-invite-paasuunnittelija
  Wait until  Element should be visible  invite-email
  Input Text  invite-text  Tervetuloa muokkaamaan hakemusta
  Element should be disabled  xpath=//*[@data-test-id='application-invite-submit']
  Input Text  invite-email  mikko@example
  Element should be disabled  xpath=//*[@data-test-id='application-invite-submit']
  Input Text  invite-email  mikko@example.com
  Element should be enabled  xpath=//*[@data-test-id='application-invite-submit']
  Scroll and click test id  application-invite-submit
  Wait until  Element should not be visible  invite-email
  Wait until  Invite count is  1
