// Page layout for the glasses (web-raster): what a browser engine painted, as the DOM knows it.
//
// One file for every place that turns a web page into the glasses' picture: the browser of the watch
// app (app/…/webbridge/), the Gecko test (tools/gecko-probe/…/probe-bridge/) and the page preview
// (tools/page-preview/capture.js). The Android builds copy it next to their extension's content.js,
// which is listed after it in the manifest, so both share one scope.
//
//   stage(name)      the double capture: "freeze" holds animations and videos, "hide-text" makes
//                    all text transparent, "restore" undoes both; the difference between a capture
//                    before and after "hide-text" is exactly the glyphs
//   collectLayout()  text lines with their colour, pictures and opaque surfaces of the visible
//                    page, in CSS pixels; LayoutParser (web-raster) reads it
"use strict";

// --- Stages of the double capture ---------------------------------------------------------------

const STAGE_CSS = {
  freeze: "*, *::before, *::after { animation-play-state: paused !important; transition: none !important; caret-color: transparent !important; }",
  // Only the letters' fill: `color` stays, so borders, icons and underlines in the text colour
  // (currentColor) are the same in both captures and do not count as glyphs.
  "hide-text": "*, *::before, *::after { -webkit-text-fill-color: transparent !important; text-shadow: none !important; " +
    "-webkit-text-stroke-width: 0 !important; }",
};
const stageStyles = [];

/** "freeze" holds animations and videos, "hide-text" makes all text transparent, "restore" undoes both. */
function stage(name) {
  if (name === "restore") {
    while (stageStyles.length) stageStyles.pop().remove();
    return;
  }
  if (!STAGE_CSS[name]) return;
  if (name === "freeze") {
    for (const video of document.querySelectorAll("video")) {
      try {
        video.pause();
      } catch (e) {
        // A video that cannot be paused keeps running; its box is a picture anyway.
      }
    }
  }
  const style = document.createElement("style");
  style.textContent = STAGE_CSS[name];
  (document.head || document.documentElement).appendChild(style);
  stageStyles.push(style);
}

// --- Layout ----------------------------------------------------------------------------------------
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
 * What is painted at a point: the ground (the first opaque background, looking through pictures
 * and icons, so a white header stays one surface around its logo; null when a veil lies over it
 * or nothing paints) and all pictures down to that background (an icon on a photo: the photo is
 * there too; a gradient veil over a photo still shows the photo).
 */
function painterAt(x, y, cache) {
  let ground;
  const pictures = [];
  for (const el of document.elementsFromPoint(x, y)) {
    const p = paintOf(el, cache);
    if (!p) continue;
    if (p === "picture") {
      pictures.push(el);
      continue;
    }
    if (p === "veil") {
      if (ground === undefined) ground = null;
      continue;
    }
    if (ground === undefined) ground = { paint: p, el };
    break;
  }
  return { ground: ground || null, pictures };
}

/** An inline SVG's parts belong to the whole drawing. */
function pictureElement(el) {
  return el.namespaceURI === SVG_NS && el.ownerSVGElement ? el.ownerSVGElement : el;
}

/** Vector drawings (inline SVG, SVG files) are graphics, never photos. */
function isVector(el) {
  if (el.namespaceURI === SVG_NS) return true;
  const src = el.tagName.toUpperCase() === "IMG" ? String(el.currentSrc || el.src || "") : "";
  return /\.svg(\?|#|$)/i.test(src) || src.startsWith("data:image/svg");
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

  // The paint map: every CELL CSS pixels the topmost opaque background (for the grounds) and the
  // pictures above it. Surfaces are merged into boxes (runs per row, extended downwards while
  // they repeat); each picture gets the boxes of its own cells.
  const surfaces = [];
  const cellsOf = new Map();
  let open = new Map();
  for (let y = 0; y < vh; y += CELL) {
    const row = [];
    for (let x = 0; x < vw; x += CELL) {
      const at = painterAt(Math.min(vw - 1, x + CELL / 2), Math.min(vh - 1, y + CELL / 2), cache);
      for (const found of at.pictures) {
        const el = pictureElement(found);
        if (!cellsOf.has(el)) cellsOf.set(el, new Set());
        cellsOf.get(el).add(y * 100000 + x);
      }
      const p = at.ground && at.ground.paint;
      const key = Array.isArray(p) ? "s" + p[1] : "";
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
        b = { color: run.color, x: run.x0, y, w: Math.min(run.x1, vw) - run.x0, h };
        surfaces.push(b);
      }
      next.set(id, b);
    }
    open = next;
  }

  // A picture's cells end on the CELL grid; where the element's own edge lies within a cell of
  // that, the edge is the element's (a small logo keeps its whole height). Edges further in come
  // from something covering it and stay.
  const pictures = [];
  for (const [el, cells] of cellsOf) {
    const r = el.getBoundingClientRect();
    const vector = isVector(el);
    const boxes = [];
    let openRuns = new Map();
    for (let y = 0; y < vh; y += CELL) {
      const next = new Map();
      let x0 = -1;
      for (let x = 0; x <= vw; x += CELL) {
        const on = x < vw && cells.has(y * 100000 + x);
        if (on && x0 < 0) x0 = x;
        if (!on && x0 >= 0) {
          const id = x0 + "|" + x;
          let b = openRuns.get(id);
          if (b) b.h += Math.min(CELL, vh - y);
          else boxes.push((b = { x: x0, y, w: Math.min(x, vw) - x0, h: Math.min(CELL, vh - y) }));
          next.set(id, b);
          x0 = -1;
        }
      }
      openRuns = next;
    }
    const snap = (cell, edge) => (Math.abs(edge - cell) <= CELL ? edge : cell);
    for (const b of boxes) {
      const x0 = snap(b.x, Math.max(0, r.left));
      const y0 = snap(b.y, Math.max(0, r.top));
      const x1 = snap(b.x + b.w, Math.min(vw, r.right));
      const y1 = snap(b.y + b.h, Math.min(vh, r.bottom));
      if (x1 - x0 >= 1 && y1 - y0 >= 1) {
        const box = [Math.round(x0), Math.round(y0), Math.round(x1 - x0), Math.round(y1 - y0)];
        pictures.push(vector ? box.concat(["g"]) : box);
      }
    }
  }

  const page = getComputedStyle(document.documentElement).backgroundColor;
  const body = document.body ? getComputedStyle(document.body).backgroundColor : "transparent";
  return {
    dpr: window.devicePixelRatio || 1,
    vw,
    vh,
    texts,
    pictures,
    surfaces: surfaces.map((b) => [b.x, b.y, b.w, b.h, b.color]),
    page,
    body,
    url: String(location.href),
  };
}
