// Content script of the watch's browser (built-in extension "g2web", 05 §10). It runs at
// document_start in the top frame of every page the browser shows, in Gecko's isolated world, and
// talks to GeckoWebPage (Kotlin) over the native port "g2web", as JSON text in { json: "…" } both ways:
// GeckoView carries port messages as GeckoBundles, which cannot hold the nested arrays of the layout.
//
//   page → watch   hello · layout · staged · field (a text field got or lost the cursor) ·
//                  reader (reading mode shown or not, the page is an article) · changed
//   watch → page   config (reading mode wanted) · reader · layout · stage · scroll · type
//
// stage() and collectLayout() come from page-layout.js (web-raster/src/main/js/, copied by the build),
// Readability and isProbablyReaderable from readability/ (Mozilla, Apache-2.0); the manifest loads
// them before this file.
"use strict";

const port = browser.runtime.connectNative("g2web");

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
  switch (m.kind) {
    case "config":
      readerWanted = !!m.reader;
      whenLoaded(decideReader);
      break;
    case "reader":
      readerWanted = !!m.on;
      decideReader();
      break;
    case "layout": {
      let layout;
      try {
        layout = collectLayout();
      } catch (e) {
        layout = { error: String(e) };
      }
      send({ kind: "layout", id: m.id, layout });
      break;
    }
    case "stage":
      // Our own style sheets are no change of the page.
      quietUntil = performance.now() + 500;
      stage(m.stage);
      // Two frames later the change is painted.
      requestAnimationFrame(() => requestAnimationFrame(() => send({ kind: "staged", id: m.id })));
      break;
    case "scroll":
      scrollPage(Number(m.dy) || 0);
      break;
    case "type":
      typeText(String(m.text || ""), m.enter !== false);
      break;
  }
});

send({ kind: "hello", url: String(location.href) });

// --- Changes ---------------------------------------------------------------------------------------
// The watch takes a new picture when the page changed by itself (content arrived, pictures loaded,
// it scrolled); at most one note every 400 ms, the watch thins them out further.

let changeTimer = null;
let quietUntil = 0;

function changed() {
  if (changeTimer !== null || performance.now() < quietUntil) return;
  changeTimer = setTimeout(() => {
    changeTimer = null;
    send({ kind: "changed" });
  }, 400);
}

new MutationObserver(changed).observe(document, { subtree: true, childList: true, characterData: true, attributes: true });
document.addEventListener("load", changed, true);
window.addEventListener("load", changed);
window.addEventListener("scroll", changed, { passive: true });

// --- Scrolling ---------------------------------------------------------------------------------------

/** Scrolls the page by dy CSS pixels; pages that scroll an inner box (app-like sites) scroll that. */
function scrollPage(dy) {
  if (!dy) return;
  const before = window.scrollY;
  window.scrollBy({ top: dy, left: 0, behavior: "instant" });
  if (window.scrollY === before) {
    const box = innerScroller(dy);
    if (box) box.scrollBy({ top: dy, left: 0, behavior: "instant" });
  }
  changed();
}

/** The box under the middle of the window that can still scroll in that direction. */
function innerScroller(dy) {
  let el = document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2);
  for (; el && el !== document.body && el !== document.documentElement; el = el.parentElement) {
    const overflow = getComputedStyle(el).overflowY;
    if (!/(auto|scroll|overlay)/.test(overflow) || el.scrollHeight <= el.clientHeight + 1) continue;
    if (dy > 0 ? el.scrollTop + el.clientHeight < el.scrollHeight - 1 : el.scrollTop > 0) return el;
  }
  return null;
}

// --- Text fields -------------------------------------------------------------------------------------
// A field with the cursor is told to the watch, which asks the wearer for the text (keyboard or
// voice) and sends it back with "type".

const TEXT_TYPES = new Set(["", "text", "search", "email", "url", "tel", "password", "number"]);
let fieldTimer = null;
let lastField = "null";

function focused() {
  let el = document.activeElement;
  while (el && el.shadowRoot && el.shadowRoot.activeElement) el = el.shadowRoot.activeElement;
  return el;
}

function editable(el) {
  if (!el || el.disabled || el.readOnly) return false;
  if (el.isContentEditable) return true;
  if (el.tagName === "TEXTAREA") return true;
  return el.tagName === "INPUT" && TEXT_TYPES.has(String(el.type || "").toLowerCase());
}

function labelOf(el) {
  const clean = (s) => String(s || "").replace(/\s+/g, " ").trim().slice(0, 80);
  const aria = el.getAttribute("aria-label");
  if (aria && clean(aria)) return clean(aria);
  if (el.labels && el.labels.length && clean(el.labels[0].textContent)) return clean(el.labels[0].textContent);
  for (const name of ["placeholder", "title", "name"]) {
    const v = el.getAttribute(name);
    if (v && clean(v)) return clean(v);
  }
  return "";
}

function describe(el) {
  const password = String(el.type || "").toLowerCase() === "password";
  return {
    label: labelOf(el),
    value: password || el.isContentEditable ? "" : String(el.value || "").slice(0, 200),
    password,
    multiline: el.tagName === "TEXTAREA" || !!el.isContentEditable,
  };
}

function reportField() {
  clearTimeout(fieldTimer);
  fieldTimer = setTimeout(() => {
    const el = focused();
    const field = editable(el) ? describe(el) : null;
    const key = JSON.stringify(field);
    if (key === lastField) return;
    lastField = key;
    send({ kind: "field", field });
  }, 60);
}

document.addEventListener("focusin", reportField, true);
document.addEventListener("focusout", reportField, true);

/** Puts text into the field with the cursor, as if typed, and with enter sends it. */
function typeText(text, enter) {
  const el = focused();
  if (!editable(el)) {
    lastField = "null";
    send({ kind: "field", field: null });
    return;
  }
  if (el.isContentEditable) {
    el.textContent = text;
  } else {
    // The element's own setter, so pages that track the value (React and the like) see the change.
    const proto = el.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, "value").set.call(el, text);
  }
  el.dispatchEvent(new Event("input", { bubbles: true }));
  el.dispatchEvent(new Event("change", { bubbles: true }));
  if (enter) pressEnter(el);
  changed();
}

function pressEnter(el) {
  const init = { key: "Enter", code: "Enter", keyCode: 13, which: 13, bubbles: true, cancelable: true };
  const go = el.dispatchEvent(new KeyboardEvent("keydown", init));
  el.dispatchEvent(new KeyboardEvent("keypress", init));
  el.dispatchEvent(new KeyboardEvent("keyup", init));
  // Script-made key events do not send a form by themselves.
  if (go && el.form && el.tagName !== "TEXTAREA") {
    if (typeof el.form.requestSubmit === "function") el.form.requestSubmit();
    else el.form.submit();
  }
}

// --- Reading mode ------------------------------------------------------------------------------------
// An article shows as its text alone, large and light on black: on the glasses the black is
// see-through and the text bright, without menus, ads and pictures behind letters.

let readerWanted = false;
let readerOn = false;

const READER_CSS = `
:root { color-scheme: dark; }
html, body { background: #000 !important; color: #f2f2f2 !important; margin: 0; }
body { font: 18px/1.4 sans-serif; padding: 6px 10px 48px; overflow-wrap: break-word; }
h1 { font-size: 22px; line-height: 1.25; margin: 4px 0 6px; }
h2, h3, h4, h5 { font-size: 19px; line-height: 1.3; margin: 16px 0 6px; }
p, li, dd { margin: 0 0 10px; }
a { color: #fff !important; text-decoration: underline; }
img, video, svg, picture, canvas, iframe { max-width: 100% !important; height: auto !important; }
figure { margin: 10px 0; }
figcaption, .g2-meta, small { font-size: 15px; color: #bbb; }
pre, code { white-space: pre-wrap; font-size: 15px; }
table { border-collapse: collapse; font-size: 15px; }
td, th { border: 1px solid #555; padding: 2px 4px; }
blockquote { margin: 10px 0; padding-left: 10px; border-left: 2px solid #777; }
.g2-meta { margin: 0 0 14px; }
`;

/** Once the page is complete, or 2.5 s after its document is, when slow extras keep it loading. */
function whenLoaded(fn) {
  if (document.readyState === "complete") {
    setTimeout(fn, 0);
    return;
  }
  let done = false;
  const once = () => {
    if (done) return;
    done = true;
    fn();
  };
  window.addEventListener("load", once, { once: true });
  if (document.readyState === "interactive") setTimeout(once, 2500);
  else document.addEventListener("DOMContentLoaded", () => setTimeout(once, 2500), { once: true });
}

function decideReader() {
  let readable = readerOn;
  if (!readerOn) {
    try {
      readable = typeof isProbablyReaderable === "function" && isProbablyReaderable(document);
    } catch (e) {
      readable = false;
    }
    if (readerWanted && readable) {
      try {
        showReader();
      } catch (e) {
        readerOn = false;
      }
    }
  }
  send({ kind: "reader", on: readerOn, readable });
}

function showReader() {
  const article = new Readability(document.cloneNode(true), { charThreshold: 300 }).parse();
  if (!article || !article.content) return;
  const doc = document;
  const html = doc.createElement("html");
  html.setAttribute("lang", article.lang || doc.documentElement.getAttribute("lang") || "");
  if (article.dir) html.setAttribute("dir", article.dir);
  const head = doc.createElement("head");
  const viewport = doc.createElement("meta");
  viewport.setAttribute("name", "viewport");
  viewport.setAttribute("content", "width=device-width, initial-scale=1");
  head.appendChild(viewport);
  const title = doc.createElement("title");
  title.textContent = article.title || doc.title;
  head.appendChild(title);
  const style = doc.createElement("style");
  style.textContent = READER_CSS;
  head.appendChild(style);

  const body = doc.createElement("body");
  const main = doc.createElement("article");
  if (article.title) {
    const h1 = doc.createElement("h1");
    h1.textContent = article.title;
    main.appendChild(h1);
  }
  const meta = [article.siteName, article.byline].filter((s) => s && String(s).trim()).join(" · ");
  if (meta) {
    const p = doc.createElement("p");
    p.className = "g2-meta";
    p.textContent = meta;
    main.appendChild(p);
  }
  const parsed = new DOMParser().parseFromString(article.content, "text/html");
  for (const node of Array.from(parsed.body.childNodes)) main.appendChild(doc.importNode(node, true));
  body.appendChild(main);
  html.appendChild(head);
  html.appendChild(body);
  doc.replaceChild(html, doc.documentElement);
  // In case the page's rules forbid our style sheet: the essentials as inline style, which they allow.
  for (const el of [html, body]) {
    el.style.setProperty("background", "#000", "important");
    el.style.setProperty("color", "#f2f2f2", "important");
  }
  body.style.setProperty("font", "18px/1.4 sans-serif");
  body.style.setProperty("margin", "0");
  body.style.setProperty("padding", "6px 10px 48px");
  window.scrollTo(0, 0);
  readerOn = true;
  changed();
}
