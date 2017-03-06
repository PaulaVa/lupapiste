*** Settings ***

Documentation   A new user signs company agreement
Resource        ../../common_resource.robot

*** Test Cases ***

Setup
  Go to  ${LAST EMAIL URL}
  Go to  ${LOGIN URL}

Bob decides to register his company, but then cancels his mind
  Wait and click  register-button
  Wait and click  xpath=//*[@data-test-id='register-company-start']
  Test id disabled  register-company-continue
  Select account type  account5
  Test id enabled  register-company-continue
  Click by test id  register-company-cancel
  Wait until  Element should be visible  xpath=//*[@data-test-id='register-company-start']

Bob decides to register his company after all, but still chickens out
  Wait and click  xpath=//*[@data-test-id='register-company-start']
  Select account type  account5
  Click enabled by test id  register-company-continue
  Wait until  Element should be visible  xpath=//*[@data-test-id='register-company-continue']
  Input text by test id  register-company-name        Peten rakennus Oy
  Input text by test id  register-company-y           2341528-4
  Input text by test id  register-company-firstName   Pete
  Input text by test id  register-company-lastName    Puuha
  Input text by test id  register-company-address1    Katukatu
  Input text by test id  register-company-zip         00001
  Input text by test id  register-company-po          Kunta
  Input text by test id  register-company-email       puuha.pete@pete-rakennus.fi
  Input text by test id  register-company-netbill     yinhang
  Input text by test id  register-company-personId    131052-308T
  Test id select is  register-company-language  fi
  Select From test id  register-company-pop  Basware Oyj (BAWCFI22)
  Click enabled by test id  register-company-continue
  Wait Until  Element Should Be Disabled  xpath=//*[@data-test-id='register-company-sign']
  Element Should Be Enabled  xpath=//*[@data-test-id='register-company-cancel']
  Toggle not Selected  register-company-agree
  Toggle toggle  register-company-agree
  Wait until  Element Should Be Enabled  xpath=//*[@data-test-id='register-company-sign']
  Wait until  Element Should Be Enabled  xpath=//*[@data-test-id='register-company-cancel']
  Click by test id  register-company-cancel
  Test id select is  register-company-language  fi
  Click by test id  register-company-cancel
  Account type selected  account5
  Click by test id  register-company-cancel
  Wait until  Element should be visible  xpath=//*[@data-test-id='register-company-start']

Bob decides to register his company after all, and this time he means it
  Wait and click  xpath=//*[@data-test-id='register-company-start']
  Select account type  account5
  Click by test id  register-company-continue
  Input text by test id  register-company-name        Peten rakennus Oy
  Input text by test id  register-company-y           2341528-4
  Input text by test id  register-company-firstName   Pete
  Input text by test id  register-company-lastName    Puuha
  Input text by test id  register-company-email       puuha.pete@pete-rakennus.fi
  Input text by test id  register-company-address1    Katukatu
  Input text by test id  register-company-zip         00001
  Input text by test id  register-company-po          Kunta
  Input text by test id  register-company-netbill     yinhang
  Input text by test id  register-company-personId    131052-308T
  Select from test id  register-company-language  sv
  Select From test id  register-company-pop  Basware Oyj (BAWCFI22)
  Click enabled by test id  register-company-continue
  Element Should Be Enabled  xpath=//*[@data-test-id='register-company-cancel']
  Element Should Be Disabled  xpath=//*[@data-test-id='register-company-sign']
  Toggle toggle  register-company-agree
  Wait until  Element Should Be Enabled  xpath=//*[@data-test-id='register-company-sign']
  Wait until  Element Should Be Enabled  xpath=//*[@data-test-id='register-company-cancel']
  Click Element  xpath=//*[@data-test-id='register-company-sign']

  Wait until  Element should be visible  xpath=//span[@data-test-id='onnistuu-dummy-status']
  Wait until  Element text should be  xpath=//span[@data-test-id='onnistuu-dummy-status']  ready
  Page Should Contain  131052-308T
  Click enabled by test id  onnistuu-dummy-success

Registrations succeeds, user gets email
  Wait until  Element should be visible  xpath=//section[@id='register-company-success']
  Open all latest emails
  Wait Until  Page Should Contain  puuha.pete@pete-rakennus.fi
  Page Should Contain  new-company-user
  Wait until  Element Text Should Be  xpath=//dd[@data-test-id='subject']  Lupapiste: Inbjudan att administrera Företagskonto i Lupapiste

Second link in email should lead to password reset
  Click Element  xpath=(//a[contains(., 'new-company-user')])
  Wait Until  Element should be visible  new-company-user
  Wait Until  Page should contain  2341528-4
  Page should contain  puuha.pete@pete-rakennus.fi
  Fill in new company password  new-company-user  company123

Login with the new password
  Login  puuha.pete@pete-rakennus.fi  company123
  User should be logged in  Pete Puuha
  Confirm notification dialog
  Language is  SV

Company details include company name, identifier and PDF link
  Click Element  user-name
  Open accordion by test id  mypage-company-accordion
  Wait Until  Element text should be  xpath=//span[@data-test-id='my-company-name']  Peten rakennus Oy
  Wait Until  Element text should be  xpath=//span[@data-test-id='my-company-id']  2341528-4
  Page should contain  /dev/dummy-onnistuu/doc/

Company info page has the registered information
  Click by test id  company-edit-info
  Test id input is  edit-company-name        Peten rakennus Oy
  Test id input is  edit-company-y           2341528-4
  Test id input is  edit-company-address1    Katukatu
  Test id input is  edit-company-zip         00001
  Test id input is  edit-company-po          Kunta
  Test id input is  edit-company-netbill     yinhang
  List selection should be  jquery=div[data-test-id=company-pop] select  Basware Oyj (BAWCFI22)
  [Teardown]  logout

*** Keywords ***

Select account type
  [Arguments]  ${type}
  Wait Until  Click Element  xpath=//*[@data-test-id='account-type-${type}']

Account type selected
  [Arguments]  ${type}
  Wait Until  Element should be visible  jquery=div.account-type-box[data-test-id=account-type-${type}].selected
