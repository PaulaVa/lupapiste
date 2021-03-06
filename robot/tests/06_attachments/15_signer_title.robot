*** Settings ***

Documentation  Signed icon title
Suite setup    Apply minimal fixture now
Resource       ../../common_resource.robot
Resource       attachment_resource.robot
Variables      variables.py

*** Variables ***

${appname}     Signature
${propertyId}  753-416-6-1

*** Test Cases ***

# ------------------
# Mikko
# ------------------

Mikko logs in and creates application
  Mikko logs in
  Create application the fast way  ${appname}  ${propertyId}  pientalo

Mikko uploads attachment
  Open tab  attachments
  Upload attachment  ${PNG_TESTFILE_PATH}  Muu liite  Other  ${EMPTY}

Mikko signs attachment
  Sign all attachments  mikko123

Signed icon title is Mikko Intonen
  Signed icon title is  Mikko Intonen

Mikko invites Pena to the application
  Invite pena@example.com to application
  [Teardown]  Logout

# ------------------
# Pena
# ------------------

Pena logs in and accepts invitation
  Pena logs in
  Wait test id visible  accept-invite-button
  Click enabled by test id  accept-invite-button
  Open application  ${appname}  ${propertyId}

Pena checks the signed icon title
  Open tab  attachments
  Signed icon title is  Mikko Intonen

Pena signs the attachment
  Sign all attachments  pena

Pena is now also on the icon title
  Signed icon title is  Mikko Intonen\\nPena Panaani

Pena adds new attachment version
  Open attachment details  muut.muu
  Add attachment version  ${TXT_TESTFILE_PATH}
  Wait until  Click by test id  back-to-application-from-attachment

There is no more signed icon
  Wait until  Attachment indicator icon should be visible  state  muut.muu
  Attachment indicator icon should not be visible  signed  muut.muu
  [Teardown]  Logout


*** Keywords ***

Signed icon title is
  [Arguments]  ${title}
  Wait Until  Attachment indicator icon should be visible  signed  muut.muu
  Javascript?  $("[data-test-icon=signed-icon]").attr( "title") === "${title}"
