/*
 * Assignments for application
 *
 */
LUPAPISTE.AssignmentService = function(applicationAuthModel) {
  "use strict";
  var self = this;

  var _data = ko.observableArray([]);

  function targetTab(assignment) {
    switch (assignment.targets[0].group) {
      case "parties":
        return "parties";
      case "attachments":
        return "attachments";
      case "documents":
        return "info";
    }
    return "info";
  }

  function enrichAssignment(assignment) {
    return _.merge(assignment,
                   {createdState: _.find(assignment.states, function(state) { return state.type === "created"; }),
                    currentState: _.maxBy(assignment.states, "timestamp"),
                    targetTab: targetTab(assignment),
                    edit: ko.observable(false)});
  }


  function filterIncompleteAssignments(assignments) {
    return _.filter(assignments, function(a) { return _.get(a, "currentState.type") !== "completed"; });
  }

  self.assignments = ko.pureComputed(function() {
    return _.map(_data(), enrichAssignment);
  });

  self.incompleteAssignments = ko.pureComputed(function() {
    return filterIncompleteAssignments(self.assignments());
  });

  self.triggerAssignments = ko.pureComputed(function() {
    return _.filter(self.assignments(), function(a) { return a.trigger !== "user-created"; });
  });

  /*
   * Targets are two levels deep and represented as objects.
   * Keys are the "target groups", and value for each key is array of corresponding items in application.
   * Example with only parties group:
   * {"parties": [{id: "5808c517f16562feee6856fb", type: "paasuunnittelija", description: "Antero Arkkitehti"},
                  {id: "5808c517f16562feee6856fc", type: "suunnittelija", info-key: "info.text.key"}]}
   */
  self.targets = ko.observableArray([]);

  function assignmentTargetsQuery(id) {
    if (applicationAuthModel.ok("assignment-targets")) {
      ajax.query("assignment-targets", {id: id, lang: loc.getCurrentLanguage()})
        .success(function(resp) {
          self.targets(_.fromPairs(resp.targets));
        })
        .call();
    }
  }

  function assignmentsForApplication(id) {
    if (applicationAuthModel.ok("assignments-for-application")) {
      ajax.query("assignments-for-application", {id: id})
        .success(function(resp) {
          _data(resp.assignments);
          _.delay(window.Stickyfill.rebuild,0);
        })
        .call();
    }
  }

  hub.subscribe("assignmentService::saveAssignment", function(event) {
    var assignment = _.omit(event, "eventType");
    var commandName = util.isEmpty(assignment.assignmentId) ? "create-assignment" : "update-assignment";

    ajax.command(commandName, assignment)
      .success(function(resp) {
        util.showSavedIndicator(resp);
        assignmentsForApplication(event.id);
      })
      .call();
  });

  hub.subscribe("assignmentService::markComplete", function(event) {
    ajax.command("complete-assignment", {assignmentId: _.get(event, "assignmentId")})
      .success(function(resp) {
        util.showSavedIndicator(resp);
        hub.send("assignmentService::assignmentCompleted", null);
        var appId = util.getIn(event, ["applicationId"]);
        if (appId) { // refresh application assignments
          assignmentsForApplication(appId);
        }
      })
      .onError("error.assignment-not-completed", util.showSavedIndicator)
      .call();
  });

  hub.subscribe("assignmentService::targetsQuery", function(event) {
    assignmentTargetsQuery(_.get(event, "applicationId"));
  });

  hub.subscribe("assignmentService::applicationAssignments", function(event) {
    assignmentsForApplication(_.get(event, "applicationId"));
  });

  hub.subscribe("application-model-updated", function(event) {
    if (!_.isEmpty(event.applicationId)) {
      assignmentsForApplication(_.get(event, "applicationId"));
    }
  });


};
