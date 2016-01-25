var hub = (function() {
  "use strict";

  var nextId = 0;
  var subscriptions = { };
  var debugEvents = false;

  function setDebug(b) {
    debug("Hub debug setting is " + b);
    debugEvents = b;
  }

  function Subscription(listener, filter, oneshot) {
    this.listener = listener;
    this.filter = filter;
    this.oneshot = oneshot;
    this.deliver = function(e) {
      for (var k in this.filter) {
        if (this.filter[k] !== e[k]) {
          return false;
        }
      }
      this.listener(e);
      return true;
    };
  }

  function subscribe(filter, listener, oneshot) {
    if (!(listener && listener.call)) { throw "Parameter 'listener' must be a function"; }
    var id = nextId;
    nextId += 1;
    if (_.isString(filter)) { filter = { eventType: filter }; }
    subscriptions[id] = new Subscription(listener, filter, oneshot);
    return id;
  }

  function unsubscribe(id) {
    delete subscriptions[id];
  }

  function makeEvent(eventType, data) {
    var e = {eventType: eventType};
    for (var key in data) {
      if (data.hasOwnProperty(key)) {
        e[key] = data[key];
      }
    }
    return e;
  }

  function send(eventType, data) {
    var count = 0;
    var event = makeEvent(eventType, data || {});

    if (debugEvents) {
      debug(event);
    }

    for (var id in subscriptions) {
      var s = subscriptions[id];
      if (s.deliver(event)) {
        if (s.oneshot) {
          unsubscribe(id);
        }
        count++;
      }
    }
    return count;
  }

  // Helpers for page change events:
  function onPageLoad(pageId, listener, oneshot) {
    return hub.subscribe({eventType: "page-load", pageId: pageId}, listener, oneshot);
  }
  function onPageUnload(pageId, listener, oneshot) {
    return hub.subscribe({eventType: "page-unload", pageId: pageId}, listener, oneshot);
  }

  return {
    subscribe:      subscribe,
    unsubscribe:    unsubscribe,
    send:           send,
    onPageLoad:     onPageLoad,
    onPageUnload:   onPageUnload,
    setDebug:       setDebug
  };

})();
