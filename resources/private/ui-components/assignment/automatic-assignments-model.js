LUPAPISTE.AutomaticAssignmentsModel = function(params) {
  "use strict";
  var self = this;
  self.params = params;
  ko.utils.extend(self, new LUPAPISTE.ComponentBaseModel());

  self.assignments = params.assignments;
  self.authModel = params.authModel;

  self.assignmentText = function(assignment) {
    return (assignment.recipient ?
              assignment.recipient.firstName + " " + assignment.recipient.lastName + ": " :
            "")
      + loc("application.assignment.automatic.target.attachment.message") + ": "
      + assignment.description + ", "
      + assignment.targets.length + " "
      + loc("application.assignment.automatic.target.attachment." + (assignment.targets.length === 1 ? "singular" : "plural"));
  };

  self.markComplete = function(assignment) {
    self.sendEvent("assignmentService", "markComplete",
                   {assignmentId: assignment.id, applicationId: params.applicationId});
  };
};