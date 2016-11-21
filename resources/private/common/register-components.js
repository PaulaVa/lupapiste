jQuery(document).ready(function() {
  "use strict";

  var components = [
    {name: "modal-dialog"},
    {name: "message-panel"},
    {name: "approval", synchronous: true},
    {name: "fill-info", synchronous: true},
    {name: "foreman-history", synchronous: true},
    {name: "foreman-other-applications", synchronous: true},
    {name: "docgen-group", synchronous: true},
    {name: "docgen-repeating-group", synchronous: true},
    {name: "docgen-table", synchronous: true},
    {name: "docgen-huoneistot-table", synchronous: true},
    {name: "docgen-input", synchronous: true},
    {name: "docgen-string", model: "docgen-input-model", template: "docgen-input-template", synchronous: true},
    {name: "docgen-checkbox", model: "docgen-input-model", template: "docgen-input-template", synchronous: true},
    {name: "docgen-text", model: "docgen-input-model", template: "docgen-input-template", synchronous: true},
    {name: "docgen-select", synchronous: true},
    {name: "docgen-button", synchronous: true},
    {name: "docgen-date", synchronous: true},
    {name: "docgen-time", template: "docgen-input-template", synchronous: true},
    {name: "docgen-review-buildings", synchronous: true},
    {name: "docgen-building-select", synchronous: true},
    {name: "construction-waste-report", synchronous: true},
    {name: "attachments-multiselect"},
    {name: "attachment-details"},
    {name: "attachments-change-type"},
    {name: "authority-select"},
    {name: "base-autocomplete", model: "autocomplete-base-model"},
    {name: "autocomplete"},
    {name: "export-attachments"},
    {name: "neighbors-owners-dialog"},
    {name: "neighbors-edit-dialog"},
    {name: "company-selector"},
    {name: "company-invite"},
    {name: "company-invite-dialog"},
    {name: "submit-button-group"},
    {name: "yes-no-dialog"},
    {name: "yes-no-select-dialog"},
    {name: "yes-no-button-group"},
    {name: "company-registration-init"},
    {name: "invoice-operator-selector"},
    {name: "ok-dialog"},
    {name: "ok-button-group"},
    {name: "company-edit"},
    {name: "organization-name-editor"},
    {name: "tags-editor"},
    {name: "upload"},
    {name: "openlayers-map"},
    {name: "vetuma-init"},
    {name: "vetuma-status"},
    {name: "help-toggle"},
    {name: "address"},
    {name: "applications-search"},
    {name: "applications-search-tabs"},
    {name: "applications-search-results"},
    {name: "applications-search-filter"},
    {name: "applications-search-filters-list"},
    {name: "applications-search-paging"},
    {name: "applications-foreman-search-filter", model: "applications-search-filter-model"},
    {name: "applications-foreman-search-tabs", template: "applications-search-tabs-template"},
    {name: "applications-foreman-search-filters-list", template: "applications-search-filters-list-template"},
    {name: "applications-foreman-search-results"},
    {name: "assignments-search-tabs", template: "applications-search-tabs-template"},
    {name: "assignments-search-results"},
    {name: "assignments-search-filter"},
    {name: "autocomplete-tags", template: "autocomplete-tags-components-template"},
    {name: "autocomplete-operations", template: "autocomplete-tags-components-template"},
    {name: "autocomplete-organizations", template: "autocomplete-tags-components-template"},
    {name: "autocomplete-areas", template: "autocomplete-tags-components-template"},
    {name: "autocomplete-handlers"},
    {name: "autocomplete-recipient"},
    {name: "autocomplete-application-tags", template: "autocomplete-tags-components-template"},
    {name: "autocomplete-assignment-targets", template: "autocomplete-tags-components-template"},
    {name: "add-property"},
    {name: "add-property-dialog"},
    {name: "autocomplete-saved-filters"},
    {name: "indicator"},
    {name: "indicator-icon"},
    {name: "accordion"},
    {name: "date-field", model: "input-field-model"},
    {name: "text-field", model: "input-field-model"},
    {name: "textarea-field", model: "input-field-model"},
    {name: "checkbox-field", model: "input-field-model"},
    {name: "select-field"},
    {name: "radio-field"},
    {name: "search-field"},
    {name: "maaraala-tunnus", synchronous: true},
    {name: "property-group", synchronous: true},
    {name: "link-permit-selector"},
    {name: "password-field"},
    {name: "accordion-toolbar", synchronous: true},
    {name: "group-approval", synchronous: true},
    {name: "submit-button"},
    {name: "remove-button"},
    {name: "publish-application"},
    {name: "move-to-proclaimed"},
    {name: "move-to-verdict-given"},
    {name: "move-to-final"},
    {name: "bulletin-versions"},
    {name: "bulletin-tab"},
    {name: "bulletin-comments"},
    {name: "infinite-scroll"},
    {name: "statements-tab"},
    {name: "statements-table"},
    {name: "statement-edit"},
    {name: "statement-edit-reply"},
    {name: "statement-reply-request"},
    {name: "statement-control-buttons"},
    {name: "statement-attachments"},
    {name: "guest-authorities"},
    {name: "bubble-dialog"},
    {name: "application-guests"},
    {name: "side-panel"},
    {name: "conversation"},
    {name: "authority-notice"},
    {name: "authorized-parties"},
    {name: "person-invite"},
    {name: "company-invite-bubble"},
    {name: "operation-editor"},
    {name: "document-identifier"},
    {name: "change-state"},
    {name: "verdict-appeal"},
    {name: "verdict-appeal-bubble"},
    {name: "file-upload"},
    {name: "form-cell"},
    {name: "cell-text", model: "cell-model"},
    {name: "cell-span"},
    {name: "cell-textarea", model: "cell-model"},
    {name: "cell-date"},
    {name: "cell-select"},
    {name: "review-tasks"},
    {name: "task"},
    {name: "ram-links"},
    {name: "attachments-listing"},
    {name: "attachments-accordions"},
    {name: "attachments-listing-accordion"},
    {name: "attachments-table"},
    {name: "attachments-operation-buttons"},
    {name: "rollup"},
    {name: "rollup-button"},
    {name: "rollup-status-button"},
    {name: "filters"},
    {name: "suti-display"},
    {name: "change-email"},
    {name: "side-panel-info"},
    {name: "info-link"},
    {name: "targeted-attachments"},
    {name: "open-3d-map"},
    {name: "extension-applications"},
    {name: "create-assignment"},
    {name: "organization-links"},
    {name: "accordion-assignments"},
    {name: "assignment-editor"},
    {name: "attachment-type-id"},
    {name: "state-icons"},
    {name: "docgen-calculation"},
    {name: "docgen-footer-sum"}
];

  ko.registerLupapisteComponents(components);
});
