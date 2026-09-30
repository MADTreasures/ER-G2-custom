// Content script of the probe's built-in extension (runs at document_start, isolated world).
//
// 1. Gives the page the bridge Even Hub apps use:
//      window.flutter_inappwebview.callHandler(name, payload) -> Promise   (page -> watch)
//      window._listenEvenAppMessage(message)                              (watch -> page)
//    The messages travel over a native port ("g2probe") to Kotlin (ProbeBridge), as JSON text in
//    { json: "…" } both ways: GeckoView carries port messages as GeckoBundles, which cannot hold
//    nested or mixed arrays, and the layout below is made of them.
// 2. Reports the layout of the visible page (text lines with their colour, pictures, surfaces
//    with a background colour) for the render test; see collectLayout(). Only what is visible.
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
//
// Only what is really visible counts. The DOM also holds closed menus, labels for screen readers,
// hidden overlays and things covered by dialogs; drawn as text or grounds they would ruin the
// glasses' picture. So text is checked against clipping ancestors and against what is painted on
// top of it, and pictures and backgrounds come from a paint map: every CELL CSS pixels the
// topmost element that paints something decides what is there.

const MAX_TEXTS = 1500;
const CELL = 8;
const PICTURE_TAGS = new Set(["IMG", "VIDEO", "CANVAS", "SVG", "PICTURE", "IFRAME", "EMBED", "OBJECT"]);
const SVG_NS = "http://www.w3.org/2000/svg";

function alphaOf(css) {
  const m = /rgba?\(([^)]*)\)/.exec(css || "");
  if (!m) return 0;
  const parts = m[1].split(/[\s,/]+/).filter(Boolean);
  if (parts.length < 4) return 1;
  return parts[3].endsWith("%") ? parseFloat(parts[3]) / 100 : parseFloat(parts[3]);
}

function shown(el) {
  if (typeof el.checkVisibility !== "function") return true;
  return el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true, opacityProperty: true, visibilityProperty: true });
}

/**
 * What an element paints under its content: "picture", ["surface", colour], "veil" (a translucent
 * colour or a gradient) or null. Masked elements (icons drawn with mask-image) count as content.
 */
function paintOf(el, cache) {
  if (cache.has(el)) return cache.get(el);
  let p = null;
  const style = shown(el) ? getComputedStyle(el) : null;
  const mask = style ? style.maskImage || style.webkitMaskImage || "none" : "none";
  if (style && mask === "none") {
    const image = style.backgroundImage || "none";
    if (PICTURE_TAGS.has(el.tagName.toUpperCase()) || el.namespaceURI === SVG_NS || image.indexOf("url(") >= 0) {
      p = "picture";
    } else {
      const a = alphaOf(style.backgroundColor);
      if (a >= 0.9) p = ["surface", style.backgroundColor];
      else if (a > 0.05 || image !== "none") p = "veil";
    }
  }
  cache.set(el, p);
  return p;
}

/**
 * The topmost painting element at a point: its paint and the element, or null for the bare page. A
 * veil over a picture (a gradient for the headline on a photo) still shows the picture.
 */
function painterAt(x, y, cache) {
  let veil = null;
  for (const el of document.elementsFromPoint(x, y)) {
    const p = paintOf(el, cache);
    if (!p) continue;
    if (p === "veil") {
      if (!veil) veil = { paint: p, el };
      continue;
    }
    if (veil && p !== "picture") return veil;
    return { paint: p, el };
  }
  return veil;
}

/** The part of a text line inside all clipping ancestors (overflow, clip, clip-path), or null. */
function clipped(el, r) {
  let x0 = r.left;
  let y0 = r.top;
  let x1 = r.right;
  let y1 = r.bottom;
  for (let a = el, n = 0; a && a !== document.body && a !== document.documentElement && n < 16; a = a.parentElement, n++) {
    const s = getComputedStyle(a);
    const clips = s.overflowX !== "visible" || s.overflowY !== "visible" || (s.clip && s.clip !== "auto") || (s.clipPath && s.clipPath !== "none");
    if (!clips) continue;
    const b = a.getBoundingClientRect();
    x0 = Math.max(x0, b.left);
    y0 = Math.max(y0, b.top);
    x1 = Math.min(x1, b.right);
    y1 = Math.min(y1, b.bottom);
    if (x1 - x0 < 2 || y1 - y0 < 2) return null;
  }
  // Mostly cut off (e.g. a label for screen readers in a 1 px box): not really there.
  if ((x1 - x0) * (y1 - y0) < 0.5 * r.width * r.height) return null;
  return { left: x0, top: y0, right: x1, bottom: y1, width: x1 - x0, height: y1 - y0 };
}

/**
 * Whether something unrelated paints over the text's element at a point. Text that ignores the
 * pointer (headlines laid over photos) is not in the hit list at all and counts as visible.
 */
function covered(el, x, y, cache) {
  if (getComputedStyle(el).pointerEvents === "none") return false;
  for (const top of document.elementsFromPoint(x, y)) {
    if (top === el || top.contains(el) || el.contains(top)) return false;
    if (paintOf(top, cache)) return true;
  }
  return false;
}

function collectLayout() {
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const cache = new Map();
  const box = (r) => [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)];
  const texts = [];

  const root = document.body || document.documentElement;
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const range = document.createRange();
  let node;
  while ((node = walker.nextNode()) && texts.length < MAX_TEXTS) {
    if (!node.nodeValue || !node.nodeValue.trim()) continue;
    const el = node.parentElement;
    if (!el || !shown(el)) continue;
    const color = getComputedStyle(el).color;
    range.selectNodeContents(node);
    for (const raw of range.getClientRects()) {
      if (raw.width <= 0 || raw.height <= 0 || raw.bottom <= 0 || raw.right <= 0 || raw.top >= vh || raw.left >= vw) continue;
      const r = clipped(el, raw);
      if (!r) continue;
      const cx = Math.min(vw - 1, Math.max(0, (r.left + r.right) / 2));
      const cy = Math.min(vh - 1, Math.max(0, (r.top + r.bottom) / 2));
      if (covered(el, cx, cy, cache)) continue;
      texts.push(box(r).concat([color]));
    }
  }

  // The paint map, merged into boxes: runs per row, extended downwards while they repeat.
  const pictures = [];
  const surfaces = [];
  let open = new Map();
  for (let y = 0; y < vh; y += CELL) {
    const row = [];
    for (let x = 0; x < vw; x += CELL) {
      const hit = painterAt(Math.min(vw - 1, x + CELL / 2), Math.min(vh - 1, y + CELL / 2), cache);
      const p = hit && hit.paint;
      const key = p === "picture" ? "picture" : Array.isArray(p) ? "s" + p[1] : "";
      const last = row[row.length - 1];
      if (last && last.key === key) last.x1 = x + CELL;
      else row.push({ key, x0: x, x1: x + CELL, color: Array.isArray(p) ? p[1] : null });
    }
    const next = new Map();
    for (const run of row) {
      if (!run.key) continue;
      const id = run.key + "|" + run.x0 + "|" + run.x1;
      const h = Math.min(CELL, vh - y);
      let b = open.get(id);
      if (b) {
        b.h += h;
      } else {
        b = { key: run.key, color: run.color, x: run.x0, y, w: Math.min(run.x1, vw) - run.x0, h };
        (run.key === "picture" ? pictures : surfaces).push(b);
      }
      next.set(id, b);
    }
    open = next;
  }

  const page = getComputedStyle(document.documentElement).backgroundColor;
  const body = document.body ? getComputedStyle(document.body).backgroundColor : "transparent";
  return {
    dpr: window.devicePixelRatio || 1,
    vw,
    vh,
    texts,
    pictures: pictures.map((b) => [b.x, b.y, b.w, b.h]),
    surfaces: surfaces.map((b) => [b.x, b.y, b.w, b.h, b.color]),
    page,
    body,
    url: String(location.href),
  };
}
