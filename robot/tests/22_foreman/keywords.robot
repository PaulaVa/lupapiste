*** Settings ***

Library        Collections

*** Keywords ***

Initialize
  ${applicationIds} =  Create List
  Set Suite Variable  ${applicationIds}
  ${applications} =  Create List
  Set Suite Variable  ${applications}
  ${foremanApps} =  Create List
  Set Suite Variable  ${foremanApps}

Mikko creates new application
  ${secs} =  Get Time  epoch
  # appname contains always the last created application
  Set Suite Variable  ${appname}  foreman-app${secs}
  Append To List  ${applications}  ${appname}
  Create application the fast way  ${appname}  753-416-25-22  kerrostalo-rivitalo
  ${newApplicationid} =  Get Text  xpath=//span[@data-test-id='application-id']
  Set Suite Variable  ${newApplicationid}
  Append To List  ${applicationIds}  ${newApplicationId}
  Set Suite Variable  ${applicationIds}

Mikko navigates to application
  Open application  ${appname}  ${newApplicationid}

Mikko goes back to project application
  Click by test id  test-application-link-permit-lupapistetunnus
  Wait until  Element should be visible  //section[@id='application']//span[@data-test-primary-operation-id='kerrostalo-rivitalo']

Foreman opens application
  [Arguments]  ${index}
  ${foremanAppId} =  Get From List  ${foremanApps}  ${index}
  Open application by id  ${foremanAppId}

Mikko invites foreman to application
  Open tab  parties
  Click by test id  invite-foreman-button
  Input Text  invite-foreman-email  teppo@example.com
  Click by test id  application-invite-foreman
  Wait until  Click by test id  application-invite-foreman-close-dialog
  Wait until  Element should be visible  //section[@id='application']//span[@data-test-primary-operation-id='tyonjohtajan-nimeaminen-v2']
  ${foremanAppId} =  Get Text  xpath=//section[@id='application']//span[@data-test-id='application-id']
  Append To List  ${foremanApps}  ${foremanAppId}

Foreman applies personal information to the foreman application
  [Arguments]  ${index}
  Foreman opens application  ${index}
  Wait until  Confirm yes no dialog
  Open tab  parties
  Wait until  Click by test id  fill-info-button

Foreman accepts invitation and fills info
  Wait until  Click by test id  accept-invite-button
  Wait until  Element should not be visible  xpath=//section[@id='application']//button[@data-test-id='accept-invite-button']
  Wait until  Click by test id  fill-info-button

Foreman sets role and difficulty to foreman application
  [Arguments]  ${index}  ${role}  ${difficulty}
  Foreman opens application  ${index}
  Deny yes no dialog
  Open tab  parties
  Foreman accepts invitation and fills info
  Wait until  Select From List by test id  kuntaRoolikoodi  ${role}
  Wait until  Select From List by test id  patevyysvaatimusluokka  ${difficulty}

Open application by id
  [Arguments]  ${appId}
  Go to page  applications
  Wait until  Click element  xpath=//table[@id='applications-list']//tr[@data-id='${appId}']/td
  Wait for jQuery

  Wait until  Element Should Be Visible  application
  Wait until  Element Text Should Be  xpath=//section[@id='application']//span[@data-test-id='application-id']  ${appId}

Foreman history should have text X times
  [Arguments]  ${text}  ${times}
  Xpath Should Match X Times  //foreman-history//td[text()='${text}']  ${times}

Foreman can see the first related construction info on the second foreman application
  Open application at index  ${appname}  753-416-25-22  1
  Open tab  parties
  ${permitId} =   Get From List  ${applicationIds}  0
  Wait until  Textfield Value Should Be  xpath=//input[@data-test-id='muutHankkeet.0.luvanNumero']  ${permitId}

Foreman logs in
  Logout
  Teppo logs in

Add työnjohtaja task to current application
  [Arguments]  ${role}
  Open tab  tasks
  Click enabled by test id  application-new-task
  Wait until  Element should be visible  dialog-create-task
  Select From List By Value  choose-task-type   task-vaadittu-tyonjohtaja
  Input text  create-task-name  ${role}
  Click enabled by test id  create-task-save
  Wait until  Element should not be visible  dialog-create-task


