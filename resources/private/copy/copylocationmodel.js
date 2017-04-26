LUPAPISTE.CopyApplicationLocationModel = function() {
  "use strict";

  var self = this;
  LUPAPISTE.LocationModelBase.call(self,
      {mapId:"copy-map", initialZoom: 2, zoomWheelEnabled: true,
       afterClick: _.partial(hub.send, "track-click", {category:"Copy", label:"map", event:"mapClick"}),
       popupContentModel: "section#map-popup-content"});

  self.municipalitySupported = ko.observable(true);
  ko.computed(function() {
    var code = self.municipalityCode();
    self.municipalitySupported(true);
    if (code) {
      municipalities.findById(code, function(m) {
        self.municipalitySupported(m ? true : false);
      });
    }
  });

  //
  // Search API
  //

  self.searchPoint = function(value, searchingListener) {
    if (!_.isEmpty(value)) {
      self.reset();
      return util.prop.isPropertyId(value) ? self._searchPointByPropertyId(value, searchingListener) : self._searchPointByAddress(value, searchingListener);
    }
    return self;
  };

  //
  // Private functions
  //

  self._searchPointByAddress = function(address, searchingListener) {
    locationSearch.pointByAddress(self.requestContext, address, function(result) {
        if (result.data && result.data.length > 0) {
          var data = result.data[0],
              x = data.location.x,
              y = data.location.y;
          self
            .setXY(x,y).center(13)
            .setAddress(data)
            .beginUpdateRequest()
            .searchPropertyId(x, y);
        }
      }, self.onError, searchingListener);
    return self;
  };

  self._searchPointByPropertyId = function(id, searchingListener) {
    locationSearch.pointByPropertyId(self.requestContext, id, function(result) {
        if (result.data && result.data.length > 0) {
          var data = result.data[0],
              x = data.x,
              y = data.y;
          self
            .setXY(x,y).center(14)
            .propertyId(util.prop.toDbFormat(id))
            .beginUpdateRequest()
            .searchAddress(x, y);
        }
      }, self.onError, searchingListener);
    return self;
  };

  self.proceed = _.partial(hub.send, "copy-step-2");

};

LUPAPISTE.CopyApplicationLocationModel.prototype = _.create(LUPAPISTE.LocationModelBase.prototype, {"constructor":LUPAPISTE.CopyApplicationLocationModel});
