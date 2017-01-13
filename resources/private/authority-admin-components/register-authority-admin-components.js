jQuery(document).ready(function() {
  "use strict";

  var components = [{name: "server-settings"},
                    {name: "suti-api"},
                    {name: "suti-admin"},
                    {name: "municipality-maps"},
                    {name: "municipality-maps-layers"},
                    {name: "municipality-maps-map"},
                    {name: "inspection-summary-templates-list"}];

  ko.registerLupapisteComponents(components);
});
