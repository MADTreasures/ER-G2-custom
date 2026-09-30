// A minimal stand-in for Even's SDK (@evenrealities/even_hub_sdk): the same bridge calls, so the
// test apps talk to the watch the way real Even Hub apps do.
"use strict";
window.even = {
  listeners: [],
  call(method, data) {
    const payload = JSON.stringify({ type: "call_even_app_method", method, data: data || {} });
    return window.flutter_inappwebview.callHandler("evenAppMessage", payload);
  },
  on(listener) {
    this.listeners.push(listener);
  },
};
window._listenEvenAppMessage = (message) => {
  for (const listener of window.even.listeners) listener(message);
};
