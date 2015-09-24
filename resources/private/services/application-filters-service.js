LUPAPISTE.ApplicationFiltersService = function() {
  "use strict";
  var self = this;

  var _savedFilters = ko.observableArray([]);

  self.selected = ko.observable();

  self.selected.subscribe(function(val) {
    _.forEach(_savedFilters(), function(f) {
      f.isSelected(false);
    });
    // val is not defined when selection is cleared
    if (val) {
      val.isSelected(true);
    }
  });

  self.savedFilters = ko.computed(function() {
    return _savedFilters();
  });

  self.defaultFilter = ko.pureComputed(function() {
    return _.find(_savedFilters(), function(f){
      return f.isDefaultFilter();
    });
  });

  self.defaultFilter.subscribe(function(val) {
    if (!self.selected()) {
      self.selected(val);
    }
  });

  function wrapFilter(filter) {
    filter.edit = ko.observable(false);
    filter.isSelected = ko.observable();
    filter.isDefaultFilter = ko.pureComputed(function () {return filter.id() === util.getIn(lupapisteApp.models.currentUser, ["defaultFilter", "id"]);});
    filter.removeFilter = function(filter) {
      ajax
      .command("remove-application-filter", {"filter-id": filter.id()})
      .error(util.showSavedIndicator)
      .success(function() {
        lupapisteApp.models.currentUser.applicationFilters.remove(function(f) {
          return ko.unwrap(f.id) === ko.unwrap(filter.id);
        });
        if (util.getIn(self.selected(), ["id"]) === ko.unwrap(filter.id)) {
          self.selected(null);
        }
      })
      .call();
    };
    filter.defaultFilter = function(filter) {
      // unset old or set new default filter
      var id = filter.isDefaultFilter() ? null : filter.id();
      ajax
      .command("update-default-application-filter", {"filter-id": id})
      .error(util.showSavedIndicator)
      .success(function() {
        if (ko.isObservable(lupapisteApp.models.currentUser.defaultFilter) && ko.isObservable(lupapisteApp.models.currentUser.defaultFilter().id)) {
          lupapisteApp.models.currentUser.defaultFilter().id(id);
        }
      })
      .call();
    };
    filter.selectFilter = function(filter) {
      _.forEach(_savedFilters(), function(f) {
        f.isSelected(false);
      });
      filter.isSelected(true);
      self.selected(filter);
    };
    return filter;
  }

  ko.computed(function() {
    _savedFilters(_(lupapisteApp.models.currentUser.applicationFilters())
      .map(wrapFilter)
      .reverse()
      .value());
  });

  self.addFilter = function(filter) {
    _savedFilters.remove(function(f) {
      return ko.unwrap(f.id) === ko.unwrap(filter.id);
    });
    if (_.isEmpty(_savedFilters())) {
      lupapisteApp.models.currentUser.defaultFilter({id: filter.id});
    }
    var wrapped = wrapFilter(ko.mapping.fromJS(filter));
    lupapisteApp.models.currentUser.applicationFilters.push(wrapped);
    self.selected(wrapped);
  };
};
