/**
* -- FOR DEVELOPMENT USE ONLY --
* Lupapiste - SitoGis - SpatialWeb integration API
* @constructs LupapisteApi
*/
function LupapisteApi() {

}

/**
* @typedef PermitFilter
* @type {object}
* @property {string} id - asiointitunnus
*/

/**
* Show permits on map by a filter
* @static
* @param {Array<PermitFilter>} permits Permits from Lupapiste view
*/
LupapisteApi.showPermitsOnMap = function (permits) {
  console.log(_.keys(permits));
  hub.send("show-dialog", {title: "LupapisteApi.showPermitsOnMap",
                           component: "ok-dialog",
                           componentParams: {text: JSON.stringify(permits, null, 2)}});
};

/**
* Show point on map
* @static
* @param {PermitFilter} filter Filter for lupapiste api
*/
LupapisteApi.showPermitOnMap = function (permit) {
  console.log(_.keys(permit));
  hub.send("show-dialog", {title: "LupapisteApi.showPermitOnMap",
                           component: "ok-dialog",
                           componentParams: {text: JSON.stringify(permit, null, 2)}});
};

/**
* Opens Lupapiste tab and shows a permit
* @static
* @param {string} id Permit id (asiointitunnus)
*/
LupapisteApi.openInLupapiste = function (id) {

};

/**
* Opens SitoGis tab and shows a permit
* @static
* @param {string} id Permit id (asiointitunnus)
* @param {string} type Permit type ('application' or 'inforequest')
*/
LupapisteApi.openPermit = function (id, type) {
  hub.send("show-dialog", {title: "LupapisteApi.openPermit",
                           component: "ok-dialog",
                           componentParams: {text: id + "(" + type + ")"}});
};

/**
* Opens SitoGis KRYSP page for permit
* @static
* @param {string} id Permit id (asiointitunnus)
*/
LupapisteApi.integrationSent = function (id) {
  hub.send("show-dialog", {title: "LupapisteApi.integrationSent",
                           component: "ok-dialog",
                           componentParams: {text: id}});
};

/**
* Queries SitoGis if the permit is there
* @static
* @param {string} id Permit id (asiointitunnus)
* @returns {boolean} is the permit in SitoGis?
*/
LupapisteApi.isInSitoGis = function (id) {

};
