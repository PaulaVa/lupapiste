*** Settings ***

Documentation   User changes account details
Suite teardown  Logout
Resource       ../../common_resource.robot

*** Test Cases ***

## For some strange reason, firstName and lastName fields are left blank.

Mikko goes to own page
  Mikko logs in
  Click Element  user-name
  Wait for Page to Load  Mikko  Intonen
  Title Should Be  Lupapiste

There is no company info
  Element should not be visible  //div[@data-test-id='my-company']

Mikko changes his name and experience
  Change Textfield Value  firstName  Mikko  Mika
  Change Textfield Value  lastName  Intonen  Intola
  Select From List  xpath=//select[@data-test-id='architect-degree-select']  Arkkitehti
  Change Textfield Value  architect.graduatingYear  2000  2001
  Change Textfield Value  architect.fise  f  fise
  Checkbox Should Not Be Selected  allowDirectMarketing
  Select Checkbox  allowDirectMarketing

  Save User Data
  Wait until  Page should contain  Tallennettu
  User should be logged in  Mika Intola

Name and experience should have changed in Swedish page too
  Language To  SV
  Wait for Page to Load  Mika  Intola
  User should be logged in  Mika Intola
  Checkbox Should Be Selected  allowDirectMarketing
  Wait until  List Selection Should Be  architect.degree  Arkitekt
  Textfield Value Should Be  architect.graduatingYear  2001
  Textfield Value Should Be  architect.fise  fise

Mika changes the name and experience back
  Change Textfield Value  firstName  Mika  Mikko
  Change Textfield Value  lastName  Intola  Intonen
  Select From List  xpath=//select[@data-test-id='architect-degree-select']  Timmerman
  Change Textfield Value  architect.graduatingYear  2001  2000
  Textfield Value Should Be  architect.graduatingYear  2000
  Change Textfield Value  architect.fise  fise  f
  Save User Data
  Wait until  Page should contain  Sparad

Name and experience should have changed in Finnish page too
  Language To  FI
  Wait for Page to Load  Mikko  Intonen
  User should be logged in  Mikko Intonen
  Checkbox Should Be Selected  allowDirectMarketing
  Wait until  List Selection Should Be  architect.degree  Kirvesmies
  Wait until  Textfield Value Should Be  architect.graduatingYear  2000
  Textfield Value Should Be  architect.fise  f

*** Keywords ***

Save User Data
  Click enabled by test id  save-my-userinfo

Wait for Page to Load
  [Arguments]  ${firstName}  ${lastName}
  Wait Until  Element Should be visible  //*[@data-test-id='save-my-userinfo']
  Wait Until  Textfield Value Should Be  firstName  ${firstName}
  Wait Until  Textfield Value Should Be  lastName   ${lastName}

Change Textfield Value
  [Arguments]  ${field}  ${old}  ${new}
  Wait Until  Element Should be visible  //*[@data-test-id='save-my-userinfo']
  Wait Until  Textfield Value Should Be  ${field}  ${old}
  Input Text  ${field}  ${new}
  Textfield Value Should Be  ${field}  ${new}
  Focus  street
