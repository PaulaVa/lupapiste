;(function() {
  "use strict";

  function isBlank(s) { var v = _.isFunction(s) ? s() : s; return !v || /^\s*$/.test(v); }
  function isPropertyId(s) { return /^[0-9\-]+$/.test(s); }

  var model = new function() {
    var self = this;

    self.goPhase1 = function() {
      $('.selected-location').hide();
      $("#create-part-1").show()
      $("#create-part-2").hide()
      $("#create-part-3").hide();
    };
    
    self.goPhase2 = function() {
      $("#create-part-1").hide();
      $("#create-part-2").show();
      window.scrollTo(0,0);
    };

    self.goPhase3 = function() {
      $("#create-part-2").hide();
      $("#create-part-3").show();
      window.scrollTo(0,0);
    };

    self.useManualEntry = ko.observable(false);
    
    self.municipalities = ko.observableArray([]);
    self.map = null;

    self.search = ko.observable("");
    self.x = ko.observable(0.0);
    self.y = ko.observable(0.0);
    self.address = ko.observable("");
    self.municipality = ko.observable("");
    self.municipalityCode = ko.observable(null);
    self.propertyId = ko.observable("");

    self.operation = ko.observable();
    self.message = ko.observable("");
    self.links = ko.observableArray();
    self.operations = ko.observable(null);
    self.requestType = ko.observable();

    self.addressOk = ko.computed(function() { return !isBlank(self.municipalityCode) && !isBlank(self.address); });

    self.clear = function() {

      self.goPhase1();
      if (!self.map) {
        self.map = gis.makeMap("create-map").center(404168, 7205000, 0);
        self.map.addClickHandler(self.click);
      }
      self.map.clear().updateSize();
      return self
        .search("")
        .x(0)
        .y(0)
        .address("")
        .municipality("")
        .municipalityCode(null)
        .propertyId("")
        .operation(null)
        .message("")
        .requestType(null)
        .goPhase1();
    };

    self.resetXY = function() { if (self.map) { self.map.clear(); } return self.x(0).y(0);  };
    self.setXY = function(x, y) { if (self.map) { self.map.clear().add(x, y); } return self.x(x).y(y); };
    self.center = function(x, y, zoom) { if (self.map) { self.map.center(x, y, zoom); } return self; };
    self.setPropertyId = function(value) { return  self.propertyId(value); };
    self.setMunicipality = function(value) { return isBlank(value) ? self.municipalityCode(null).municipality("") : self.municipalityCode(value).municipality(loc("municipality." + value)); };
    self.setAddress = function(data) { return data ? self.address(data.katunimi + " " + data.katunumero + ", " + data.kuntanimiFin) : self.address(""); };

    self.municipalityCode.subscribe(function(v) {
      self.operations(null).links.removeAll();
      if (!v || v.length === 0) { return; }
      ajax
        .query("municipality", {municipality: v})
        .success(function (data) { if (self.municipalityCode() === v) { self.operations(data.operations).links(data.links); }})
        .call();
    });

    //
    // Concurrency control:
    //

    self.updateRequestId = 0;
    self.beginUpdateRequest = function() { self.updateRequestId++; return self; };

    //
    // Callbacks:
    //

    // Called when user clicks on map:

    self.click = function(x, y) {
      self
        .setXY(x, y)
        .setPropertyId("")
        .setMunicipality("")
        .beginUpdateRequest()
        .searchMunicipality(x, y)
        .searchPropertyId(x, y)
        .searchAddress(x, y);
      return false;
    };

    // Search activation:

    self.searchNow = function() {
      $('.selected-location').show();
      self
        .resetXY()
        .setAddress(null)
        .setMunicipality(null)
        .setPropertyId(null)
        .beginUpdateRequest()
        .searchPointByAddressOrPropertyId(self.search());
      self.map.updateSize();
      return false;
    };

    self.searchPointByAddressOrPropertyId = function(value) { return isPropertyId(value) ? self.searchPointByPropertyId(value) : self.serchPointByAddress(value); };

    self.serchPointByAddress = function(address) {
      var requestId = self.updateRequestId;
      ajax
        .get("/proxy/get-address")
        .param("query", address)
        .success(function(result) {
          if (requestId === self.updateRequestId && result.data && result.data.length > 0) {
            var data = result.data[0],
                x = data.x,
                y = data.y;
            self
              .useManualEntry(false)
              .setXY(x, y)
              .center(x, y, 11)
              .setAddress(data)
              .beginUpdateRequest()
              .searchMunicipality(x, y)
              .searchPropertyId(x, y);
          }
        })
        .fail(_.partial(self.useManualEntry, true))
        .call();
      return self;
    };

    self.useManualEntry.subscribe(function() { console.log("useManualEntry=", self.useManualEntry()); });
    
    self.searchPointByPropertyId = function(propertyId) {
      var requestId = self.updateRequestId;
      ajax
        .get("/proxy/point-by-property-id")
        .param("property-id", propertyId)
        .success(function(result) {
          if (requestId === self.updateRequestId && result.data && result.data.length > 0) {
            var data = result.data[0],
                x = data.x,
                y = data.y;
            self
              .useManualEntry(false)
              .setXY(x, y)
              .center(x, y, 11)
              .setPropertyId(propertyId)
              .beginUpdateRequest()
              .searchMunicipality(x, y)
              .searchAddress(x, y);
          }
        })
        .fail(_.partial(self.useManualEntry, true))
        .call();
      return self;
    };

    self.onResponse = function(requestId, fn) {
      return function(result) { if (requestId === self.updateRequestId) fn(result); };
    };

    self.searchMunicipality = function(x, y) {
      var requestId = self.updateRequestId;
      ajax
        .query("municipality-by-location", {x: x, y: y})
        .success(self.onResponse(requestId, function(data) { self.setMunicipality(data.result); }))
        .error(self.onResponse(requestId, function() { self.setMunicipality(null); }))
        .call();
      return self;
    };

    self.searchPropertyId = function(x, y) {
      var requestId = self.updateRequestId;
      ajax
        .get("/proxy/property-id-by-point")
        .param("x", x)
        .param("y", y)
        .success(self.onResponse(requestId, self.setPropertyId))
        .call();
      return self;
    };

    self.searchAddress = function(x, y) {
      var requestId = self.updateRequestId;
      ajax
        .get("/proxy/address-by-point")
        .param("x", x)
        .param("y", y)
        .success(self.onResponse(requestId, self.setAddress))
        .call();
      return self;
    };

    self.getMunicipalityName = function(m) {
      return m.name[loc.getCurrentLanguage()];
    };

    self.create = function(infoRequest) {
      ajax.command("create-application", {
        infoRequest: infoRequest,
        operation: self.operation(),
        y: self.y(),
        x: self.x(),
        address: self.address(),
        propertyId: self.propertyId(),
        messages: isBlank(self.message()) ? [] : [self.message()],
        municipality: self.municipalityCode()
      })
      .success(function(data) {
        self.clear();
        window.location.hash = (infoRequest ? "!/inforequest/" : "!/application/") + data.id;
      })
      .call();
    };

    self.createApplication = self.create.bind(self, false);
    self.createInfoRequest = self.create.bind(self, true);

  }();

  function toLink(l) {
    return $("<li>").append($("<a>").attr("href", l.url).attr("target", "_blank").text(l.name[loc.getCurrentLanguage()]));
  }

  function generateInfo(value) {
    var e$ = $("<div>").attr("class", "tree-result").append($("<p>").text(loc(value.text)));
    var ul$ = $("<ul>");
    _.each(_.map(model.links(), toLink), function (l) {ul$.append(l);});
    return e$.append(ul$)[0];
  }

  hub.onPageChange("create", model.clear);

  ajax
    .query("municipalities-for-new-application")
    .success(function(data) { model.municipalities(data.municipalities); })
    .call();

  $(function() {

    ko.applyBindings(model, $("#create")[0]);

    $("#create-search")
      .keypress(function(e) {
        if (e.which == 13) model.searchNow();
      })
      .autocomplete({
        serviceUrl:      "/proxy/find-address",
        deferRequestBy:  500,
        noCache:         true,
        onSelect:        model.searchNow
      });

    function operations2tree(e) {
      var name = e[0],
          value = e[1],
          key = {text: name},
          target = _.isArray(value) ? _.map(value, operations2tree) : value;
      return [key, target];
    }

    var tree = $("#create .operation-tree").selectTree({
      title: $("#create-templates .tree-title"),
      last:  $("#create-templates .tree-last"),
      onSelect: function(v) { model.operation(v ? v.op : null); }
    });
    
    model.operations.subscribe(function(v) {
      tree.reset(_.map(v, operations2tree));
    });
    
    hub.subscribe({type: "keyup", keyCode: 37}, tree.goBack);
    
  });

})();
