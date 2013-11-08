;(function() {
  "use strict";

  function OrganizationsModel() {
    var self = this;

    self.organizations = ko.observableArray([]);
    self.pending = ko.observable();

    self.load = function() {
      ajax
        .query("organizations")
        .pending(self.pending)
        .success(function(d) {
          self.organizations(_.sortBy(d.organizations, function(d) { return d.name[loc.getCurrentLanguage()]; }));
        })
        .call();
    };

    self.loginAs = function(organization) {
      ajax
        .command("impersonate-authority", {organizationId: organization.id})
        .success(function(d) {
          console.log(d);
          console.log(organization);
        })
        .call();
      return false;
    };
  }

  var organizationsModel = new OrganizationsModel();

  function EditOrganizationModel() {
    var self = this;
    self.dialogSelector = "#dialog-edit-organization";
    self.errorMessage = ko.observable(null);

    // Model

    self.id = 0;
    self.applicationEnabled = ko.observable(false);
    self.inforequestEnabled = ko.observable(false);
    self.openInforequestEnabled = ko.observable(false);
    self.openInforequestEmail = ko.observable("");
    self.processing = ko.observable();
    self.pending = ko.observable();

    self.reset = function(organization) {
      self.id = organization.id;
      self.applicationEnabled(organization['new-application-enabled']);
      self.inforequestEnabled(organization['inforequest-enabled']);
      self.openInforequestEnabled(organization['open-inforequest'] || false);
      self.openInforequestEmail(organization['open-inforequest-email'] || "");
      self.processing(false);
      self.pending(false);
    };

    self.ok = ko.computed(function() {
      return true;
    });

    // Open the dialog

    self.open = function(organization) {
      self.reset(organization);
      LUPAPISTE.ModalDialog.open(self.dialogSelector);
    };

    self.onSuccess = function() {
      self.errorMessage(null);
      LUPAPISTE.ModalDialog.close();
      organizationsModel.load();
    };

    self.onError = function(resp) {
      self.errorMessage(resp.text);
    };

    self.updateOrganization = function() {
      var data = {organizationId: self.id,
                  inforequestEnabled: self.inforequestEnabled(),
                  applicationEnabled: self.applicationEnabled(),
                  openInforequestEnabled: self.openInforequestEnabled(),
                  openInforequestEmail: self.openInforequestEmail()};
      ajax.command("update-organization", data)
        .processing(self.processing)
        .pending(self.pending)
        .success(self.onSuccess)
        .error(self.onError)
        .call();
      return false;
    };

  }

  var editOrganizationModel = new EditOrganizationModel();

  hub.onPageChange("organizations", organizationsModel.load);

  $(function() {
    $("#organizations").applyBindings({
      "organizationsModel": organizationsModel,
      "editOrganizationModel": editOrganizationModel
    });
  });

})();
