LUPAPISTE.BulletinCommentsModel = function(params) {
  "use strict";
  var self = this;

  ko.utils.extend(self, new LUPAPISTE.ComponentBaseModel(params));

  self.showVersionComments = params.showVersionComments;

  self.bulletin = params.bulletin

  self.comments = ko.computed(function() {
    return util.getIn(self, ["bulletin", "comments", util.getIn(self, ["showVersionComments", "id"])], []);
  });

  self.hideComments = function() {
    self.showVersionComments(undefined);
  };

  self.proclaimedHeader = ko.pureComputed(function() {
    var start  = util.getIn(self, ["showVersionComments", "proclamationStartsAt"], "");
    var end    = util.getIn(self, ["showVersionComments", "proclamationEndsAt"], "");
    var amount = util.getIn(self.comments().length);
    console.log("start", start, end, amount);
    console.log("foo", self.showVersionComments());
    if (start && end) {
      return "Hakemuksen kuulutusaikana " + moment(start).format("D.M.YYYY") + " - " + moment(end).format("D.M.YYYY") +
        " annettuja mielipiteitä ja muistutuksia yhteensä " + amount + " kpl."
    }
  });
};