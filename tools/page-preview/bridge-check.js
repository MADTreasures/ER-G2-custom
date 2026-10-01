// Bridge check (tools/page-preview): runs the content script of the watch's browser
// (app/src/main/assets/webbridge/content.js, with Readability and web-raster's page-layout.js) in
// Chromium, with a stand-in for GeckoView's native port, on two made-up local pages, and checks what
// the engine relies on: reading mode on and off, text fields and their labels, typing with and without
// Enter, scrolling the window and inner boxes, the layout report and the stages of the double capture.
// Chromium stands in for GeckoView (no isolated world here); no network needed.
//
//   node tools/page-preview/bridge-check.js
//
// Needs Node and Playwright with Chromium, like capture.js.
"use strict";
const fs = require("fs");
const path = require("path");
let playwright;
try {
  playwright = require("playwright");
} catch (e) {
  playwright = require(path.join(require("child_process").execSync("npm root -g").toString().trim(), "playwright"));
}
const { chromium } = playwright;
const REPO = path.join(__dirname, "../..");
const ext = path.join(REPO, "app/src/main/assets/webbridge");
const scripts = [
  fs.readFileSync(path.join(ext, "readability/Readability-readerable.js"), "utf8"),
  fs.readFileSync(path.join(ext, "readability/Readability.js"), "utf8"),
  fs.readFileSync(path.join(REPO, "web-raster/src/main/js/page-layout.js"), "utf8"),
  fs.readFileSync(path.join(ext, "content.js"), "utf8"),
];
const MOCK = `
window.__sent = []; window.__listeners = [];
window.browser = { runtime: { connectNative(name) { window.__native = name; return {
  postMessage(m) { window.__sent.push(JSON.parse(m.json)); },
  onMessage: { addListener(f) { window.__listeners.push(f); } } }; } } };
window.__deliver = (m) => window.__listeners.forEach((f) => f({ json: JSON.stringify(m) }));
`;
const para = (n) => Array.from({ length: n }, (_, i) =>
  `<p>Absatz ${i + 1}: Die Brille zeigt Web-Seiten als grünes Raster. Dunkel ist durchsichtig, hell leuchtet. ` +
  `Deshalb macht der Lesemodus aus jedem Artikel helle Schrift auf schwarzem Grund, gross und ohne Werbung, Menüs und Bilder hinter der Schrift. ` +
  `So bleibt alles lesbar, auch beim Gehen.</p>`).join("\n");
const ARTICLE = `<!doctype html><html lang="de"><head><title>Brille – Testartikel</title>
<meta name="viewport" content="width=device-width"></head><body style="background:#fff;color:#222">
<nav><a href="#a">Start</a> <a href="#b">News</a> <a href="#c">Sport</a></nav>
<div class="ad-banner">Werbung Werbung Werbung</div>
<article><h1>Brille – Testartikel</h1><p class="byline">Von Test Autorin</p>${para(12)}</article>
<footer>Impressum · Datenschutz</footer></body></html>`;
const FORM = `<!doctype html><html><head><title>Suche</title><meta name="viewport" content="width=device-width"></head>
<body><form action="/gesucht" id="f"><label for="q">Suchbegriff</label> <input id="q" name="q" value="Kat">
<input id="pw" type="password" placeholder="Passwort"><textarea id="t" aria-label="Kommentar"></textarea>
<button>Los</button></form><div style="height:3000px">lang</div>
<div id="box" style="position:fixed;top:0;left:0;right:0;height:300px;overflow:auto"><div style="height:2000px">innen</div></div>
</body></html>`;

let failures = 0;
function check(name, ok, detail) {
  console.log((ok ? "ok   " : "FAIL ") + name + (ok ? "" : "  " + JSON.stringify(detail)));
  if (!ok) failures++;
}

async function page(browser, html, route) {
  const ctx = await browser.newContext({ viewport: { width: 384, height: 173 }, deviceScaleFactor: 1.5, isMobile: true, hasTouch: true });
  const p = await ctx.newPage();
  await p.route("http://test.local/**", (r) => r.fulfill({ contentType: "text/html; charset=utf-8", body: route(r.request().url()) }));
  await p.goto("http://test.local/" + html);
  await p.evaluate(MOCK);
  for (const s of scripts) await p.addScriptTag({ content: s });
  return p;
}
const sent = (p) => p.evaluate(() => window.__sent);
const deliver = (p, m) => p.evaluate((m) => window.__deliver(m), m);
const wait = (p, ms) => p.waitForTimeout(ms);

(async () => {
  const browser = await chromium.launch();
  // 1. An article in reading mode.
  let p = await page(browser, "artikel", () => ARTICLE);
  check("connects to the native app g2web", (await p.evaluate(() => window.__native)) === "g2web");
  check("says hello first", (await sent(p))[0].kind === "hello");
  await deliver(p, { kind: "config", reader: true });
  await wait(p, 300);
  let reader = (await sent(p)).filter((m) => m.kind === "reader");
  check("article is shown in reading mode", reader.length === 1 && reader[0].on && reader[0].readable, reader);
  const look = await p.evaluate(() => ({ h1: document.querySelector("article h1")?.textContent, nav: !!document.querySelector("nav"),
    bg: getComputedStyle(document.body).backgroundColor, color: getComputedStyle(document.body).color,
    font: getComputedStyle(document.body).fontSize, paras: document.querySelectorAll("article p").length, title: document.title }));
  check("reading view: title, no menu, black ground, light text, 18px", look.h1 === "Brille – Testartikel" && !look.nav &&
    look.bg === "rgb(0, 0, 0)" && look.color === "rgb(242, 242, 242)" && look.font === "18px" && look.paras >= 12, look);
  await deliver(p, { kind: "layout", id: 5 });
  await wait(p, 100);
  const layout = (await sent(p)).find((m) => m.kind === "layout" && m.id === 5);
  check("layout of the reading view has text lines", layout && layout.layout.texts.length > 5 && layout.layout.vw === 384, layout && layout.layout.texts.length);
  await deliver(p, { kind: "stage", id: 6, stage: "freeze" });
  await deliver(p, { kind: "stage", id: 7, stage: "hide-text" });
  await wait(p, 200);
  check("stages are answered", (await sent(p)).filter((m) => m.kind === "staged").map((m) => m.id).join() === "6,7");
  await deliver(p, { kind: "stage", id: 8, stage: "restore" });
  // Our style sheets are no change of the page.
  const changedBefore = (await sent(p)).filter((m) => m.kind === "changed").length;
  await wait(p, 900);
  const changedAfter = (await sent(p)).filter((m) => m.kind === "changed").length;
  check("stages do not count as changes", changedAfter === changedBefore, { changedBefore, changedAfter });
  const y0 = await p.evaluate(() => window.scrollY);
  await deliver(p, { kind: "scroll", dy: 130 });
  await wait(p, 50);
  const y1 = await p.evaluate(() => window.scrollY);
  check("scrolls the window by CSS pixels", y1 - y0 === 130, { y0, y1 });
  await wait(p, 600);
  check("scrolling reports a change", (await sent(p)).filter((m) => m.kind === "changed").length > changedAfter);
  await p.context().close();

  // 2. A form page: no reading mode, fields, typing, Enter, inner scroller.
  p = await page(browser, "suche", (url) => (url.includes("gesucht") ? "<title>Gesucht</title>" + decodeURIComponent(url) : FORM));
  await deliver(p, { kind: "config", reader: true });
  await wait(p, 300);
  reader = (await sent(p)).filter((m) => m.kind === "reader");
  check("form page: no reading mode", reader.length === 1 && !reader[0].on && !reader[0].readable, reader);
  await p.focus("#q");
  await wait(p, 150);
  let fields = (await sent(p)).filter((m) => m.kind === "field");
  check("a focused field is reported with its label and value", fields.length === 1 && fields[0].field.label === "Suchbegriff" &&
    fields[0].field.value === "Kat" && !fields[0].field.password && !fields[0].field.multiline, fields);
  await p.focus("#pw");
  await wait(p, 150);
  fields = (await sent(p)).filter((m) => m.kind === "field");
  check("a password field: label from the placeholder, no value", fields.length === 2 && fields[1].field.password &&
    fields[1].field.label === "Passwort" && fields[1].field.value === "", fields[1]);
  await p.focus("#t");
  await wait(p, 150);
  fields = (await sent(p)).filter((m) => m.kind === "field");
  check("a text area: several lines, label from aria-label", fields[2] && fields[2].field.multiline && fields[2].field.label === "Kommentar", fields[2]);
  await deliver(p, { kind: "type", text: "Zwei\nZeilen", enter: false });
  check("typing into a text area", (await p.evaluate(() => document.querySelector("#t").value)) === "Zwei\nZeilen");
  await p.evaluate(() => { window.__input = 0; document.querySelector("#q").addEventListener("input", () => window.__input++); });
  await p.focus("#q");
  await wait(p, 150);
  // Scroll while a fixed inner box covers the middle: the inner box scrolls when the window cannot.
  await p.evaluate(() => window.scrollTo(0, 0));
  await deliver(p, { kind: "scroll", dy: 100 });
  const s1 = await p.evaluate(() => ({ win: window.scrollY, box: document.querySelector("#box").scrollTop }));
  check("the window scrolls first", s1.win === 100 && s1.box === 0, s1);
  await p.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
  const end = await p.evaluate(() => window.scrollY);
  await deliver(p, { kind: "scroll", dy: 100 });
  const s2 = await p.evaluate(() => ({ win: window.scrollY, box: document.querySelector("#box").scrollTop }));
  check("at the window's end the box under the middle scrolls", s2.win === end && s2.box === 100, s2);
  const nav = p.waitForNavigation({ timeout: 3000 }).catch(() => null);
  await deliver(p, { kind: "type", text: "Katzen im Schnee", enter: true });
  const typed = await p.evaluate(() => ({ value: document.querySelector("#q")?.value, input: window.__input })).catch(() => null);
  await nav;
  check("Enter sends the form with the typed text", p.url().includes("gesucht?q=Katzen+im+Schnee"), { url: p.url(), typed });
  await p.context().close();

  // 3. Reading mode wanted later, and off again: an article switched on by message.
  p = await page(browser, "artikel2", () => ARTICLE);
  await deliver(p, { kind: "config", reader: false });
  await wait(p, 300);
  reader = (await sent(p)).filter((m) => m.kind === "reader");
  check("without reading mode the article stays, but is readable", reader.length === 1 && !reader[0].on && reader[0].readable, reader);
  check("the page itself is untouched", await p.evaluate(() => !!document.querySelector("nav")));
  await deliver(p, { kind: "reader", on: true });
  await wait(p, 300);
  reader = (await sent(p)).filter((m) => m.kind === "reader");
  check("switched on later", reader.length === 2 && reader[1].on, reader);
  await p.context().close();

  await browser.close();
  console.log(failures ? `${failures} FAILED` : "all ok");
  process.exit(failures ? 1 : 0);
})();
