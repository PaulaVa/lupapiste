LUPAPISTE.BulletinsSearchModel = function(params) {
  "use strict";
  var self = this;

  // Query object
  var query = params.query;

  self.bulletinService = params.bulletinService;

  self.searchText = query.searchText || ko.observable();
}
