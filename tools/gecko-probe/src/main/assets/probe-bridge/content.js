// Content script of the probe's built-in extension (runs at document_start, isolated world).
//
// 1. Gives the page the bridge Even Hub apps use:
//      window.flutter_inappwebview.callHandler(name, payload) -> Promise   (page -> watch)
//      window._listenEvenAppMessage(message)                              (watch -> page)
//    The messages travel over a native port ("g2probe") to Kotlin (ProbeBridge), as JSON text in
//    { json: "…" } both ways: GeckoView carries port messages as GeckoBundles, which cannot hold
//    nested or mixed arrays, and the layout below is made of them.
// 2. Reports the layout of the visible page (text lines with their colour, pictures, surfaces
//    with a background colour) for the render test; see collectLayout().
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

// --- Layout for the render test -------------------------------------------------------------------

const MAX_TEXTS = 1500;
const MAX_ELEMENTS = 4000;

function collectLayout() {
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const visible = (r) => r.width > 0 && r.height > 0 && r.bottom > 0 && r.right > 0 && r.top < vh && r.left < vw;
  const box = (r) => [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)];
  const texts = [];
  const pictures = [];
  const surfaces = [];

  const root = document.body || document.documentElement;
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const range = document.createRange();
  let node;
  while ((node = walker.nextNode()) && texts.length < MAX_TEXTS) {
    if (!node.nodeValue || !node.nodeValue.trim()) continue;
    const el = node.parentElement;
    if (!el) continue;
    const style = getComputedStyle(el);
    if (style.visibility === "hidden" || style.display === "none" || parseFloat(style.opacity) === 0) continue;
    range.selectNodeContents(node);
    for (const r of range.getClientRects()) {
      if (visible(r)) texts.push(box(r).concat([style.color]));
    }
  }

  const elements = root.getElementsByTagName("*");
  const count = Math.min(elements.length, MAX_ELEMENTS);
  for (let i = 0; i < count; i++) {
    const el = elements[i];
    const r = el.getBoundingClientRect();
    if (!visible(r)) continue;
    const style = getComputedStyle(el);
    const tag = el.tagName.toUpperCase();
    const image = style.backgroundImage;
    if (tag === "IMG" || tag === "VIDEO" || tag === "CANVAS" || tag === "SVG" || tag === "PICTURE" || tag === "IFRAME" ||
        (image && image.indexOf("url(") >= 0)) {
      pictures.push(box(r));
    }
    const bg = style.backgroundColor;
    if (bg && bg !== "transparent" && !/,\s*0\)$/.test(bg) && r.width * r.height >= 400) {
      surfaces.push(box(r).concat([bg]));
    }
  }
  const page = getComputedStyle(document.documentElement).backgroundColor;
  const body = document.body ? getComputedStyle(document.body).backgroundColor : "transparent";
  return { dpr: window.devicePixelRatio || 1, vw, vh, texts, pictures, surfaces, page, body, url: String(location.href) };
}
