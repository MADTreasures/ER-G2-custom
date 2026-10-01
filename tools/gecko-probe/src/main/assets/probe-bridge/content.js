// Content script of the probe's built-in extension (runs at document_start, isolated world).
//
// 1. Gives the page the bridge Even Hub apps use:
//      window.flutter_inappwebview.callHandler(name, payload) -> Promise   (page -> watch)
//      window._listenEvenAppMessage(message)                              (watch -> page)
//    The messages travel over a native port ("g2probe") to Kotlin (ProbeBridge), as JSON text in
//    { json: "…" } both ways: GeckoView carries port messages as GeckoBundles, which cannot hold
//    nested or mixed arrays, and the layout report is made of them.
// 2. Reports the layout of the visible page (text lines with their colour, pictures, surfaces
//    with a background colour) for the render test, with collectLayout(). Only what is visible.
// 3. Prepares the second capture of the render test with stage(): animations held, then all text
//    transparent, so the difference between both captures is exactly the glyphs.
// stage() and collectLayout() come from page-layout.js (web-raster/src/main/js/, copied here by the
// build), which the manifest loads before this file.
"use strict";

const port = browser.runtime.connectNative("g2probe");
const pending = new Map();
let seq = 0;

function send(message) {
  port.postMessage({ json: JSON.stringify(message) });
}

port.onMessage.addListener((wrapped) => {
  let m;
  try {
    m = JSON.parse(wrapped.json);
  } catch (e) {
    return;
  }
  if (m.kind === "reply") {
    const resolve = pending.get(m.id);
    pending.delete(m.id);
    if (resolve) resolve(m.result === undefined ? null : m.result);
  } else if (m.kind === "push") {
    const listener = window.wrappedJSObject._listenEvenAppMessage;
    if (typeof listener === "function") listener(cloneInto(m.msg, window));
  } else if (m.kind === "stage") {
    stage(m.stage);
    // Two frames later the change is painted.
    requestAnimationFrame(() => requestAnimationFrame(() => send({ kind: "staged", id: m.id })));
  } else if (m.kind === "layout") {
    let layout;
    try {
      layout = collectLayout();
    } catch (e) {
      layout = { error: String(e) };
    }
    send({ kind: "layout", id: m.id, layout });
  }
});

const bridge = {
  callHandler(name, payload) {
    return new window.Promise(exportFunction((resolve) => {
      const id = ++seq;
      pending.set(id, (result) => resolve(result !== null && typeof result === "object" ? cloneInto(result, window) : result));
      send({ kind: "call", id, name: String(name), payload: payload === undefined || payload === null ? null : String(payload) });
    }, window));
  },
};
window.wrappedJSObject.flutter_inappwebview = cloneInto(bridge, window, { cloneFunctions: true });

send({ kind: "hello", url: String(location.href), t: performance.now() });
