// Page preview (tools/page-preview): opens real web pages in headless Chromium in the glasses'
// window (576 x 260 device pixels = 384 CSS px wide at 1.5x, as in the Gecko probe) with the
// probe's own collectLayout() from content.js, and saves a screenshot and the layout per window.
// Chromium stands in for GeckoView, which needs a watch or an emulator. Then:
//   PREVIEW_DIR=<dir> ./gradlew :gecko-probe:testArmv7DebugUnitTest --tests '*PagePreviewTest*'
// turns them into glasses pictures with LayoutParser and web-raster (<dir>/views/*.png).
//
//   node tools/page-preview/capture.js <dir> [page id filter]
//
// Needs Node and Playwright with Chromium (npm i -g playwright && npx playwright install chromium).
"use strict";
const path = require("path");
const fs = require("fs");
let playwright;
try {
  playwright = require("playwright");
} catch (e) {
  playwright = require(path.join(require("child_process").execSync("npm root -g").toString().trim(), "playwright"));
}
const { chromium } = playwright;
const OUT = path.join(process.argv[2] || "page-preview", "raw");
const only = process.argv[3];
const content = fs.readFileSync(path.join(__dirname, "../gecko-probe/src/main/assets/probe-bridge/content.js"), "utf8");
const layoutSrc = content.slice(content.indexOf("const MAX_TEXTS"));
// GeckoView's user agent on the watch, so sites serve the same mobile layout.
const UA = "Mozilla/5.0 (Android 17; Mobile; rv:157.0) Gecko/157.0 Firefox/157.0";
const PAGES = [
  { id: "01-wikipedia", url: "https://de.m.wikipedia.org/wiki/Brille", scroll: [0, 450] },
  { id: "02-srf", url: "https://www.srf.ch/news", scroll: [0, 700] },
  { id: "03-hackernews", url: "https://news.ycombinator.com/", scroll: [0, 500] },
  { id: "04-evenrealities", url: "https://www.evenrealities.com/", scroll: [0, 900] },
  { id: "05-github-dunkel", url: "https://github.com/jimrandomh/faceclaw", dark: true, scroll: [0, 700] },
  { id: "06-apple", url: "https://www.apple.com/ch-de/", scroll: [0, 900] },
  { id: "07-nasa", url: "https://www.nasa.gov/", scroll: [0, 900] },
  { id: "08-mdn", url: "https://developer.mozilla.org/de/docs/Web/HTML", scroll: [0, 700] },
];
// Cookie dialogs: take the most restrictive choice, as a careful wearer would (also inside iframes).
async function dismissConsent(page) {
  const names = [/^\s*nur notwendige/i, /^\s*nur erforderliche/i, /^\s*alle ablehnen/i, /^\s*ablehnen/i, /^\s*reject all/i, /^\s*decline/i, /^\s*only necessary/i];
  for (const frame of page.frames()) {
    for (const name of names) {
      const b = frame.getByRole("button", { name }).first();
      if (await b.isVisible().catch(() => false)) {
        await b.click({ timeout: 3000 }).catch(() => {});
        await page.waitForTimeout(1500);
        return String(name);
      }
    }
  }
  return null;
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const proxy = process.env.HTTPS_PROXY ? { server: process.env.HTTPS_PROXY } : undefined;
  const browser = await chromium.launch({ proxy });
  for (const p of PAGES) {
    if (only && !p.id.includes(only)) continue;
    const ctx = await browser.newContext({
      viewport: { width: 384, height: 174 }, deviceScaleFactor: 1.5, isMobile: true, hasTouch: true,
      locale: "de-CH", timezoneId: "Europe/Zurich", colorScheme: p.dark ? "dark" : "light", userAgent: UA,
    });
    const page = await ctx.newPage();
    const t0 = Date.now();
    try {
      await page.goto(p.url, { waitUntil: "domcontentloaded", timeout: 45000 });
      await page.waitForLoadState("load", { timeout: 20000 }).catch(() => {});
      await page.waitForTimeout(2500);
    } catch (e) {
      console.log(p.id, "LOAD FAILED", String(e).split("\n")[0]);
      await ctx.close();
      continue;
    }
    const loadMs = Date.now() - t0;
    const consent = await dismissConsent(page);
    if (consent) console.log(p.id, "consent dismissed with", consent);
    for (const y of p.scroll) {
      await page.evaluate((y) => window.scrollTo(0, y), y);
      await page.waitForTimeout(1500);
      const scrollY = await page.evaluate(() => window.scrollY);
      const layout = await page.evaluate(`(() => { ${layoutSrc}\n return collectLayout(); })()`).catch((e) => ({ error: String(e) }));
      const base = `${OUT}/${p.id}-${String(y).padStart(4, "0")}`;
      await page.screenshot({ path: base + ".png" });
      fs.writeFileSync(base + ".json", JSON.stringify({ id: p.id, url: page.url(), title: await page.title(), scrollY, loadMs, layout }));
      console.log(p.id, "y=" + scrollY, "texts=" + (layout.texts || []).length, "pictures=" + (layout.pictures || []).length, "surfaces=" + (layout.surfaces || []).length, "vw=" + layout.vw, layout.error || "");
    }
    await ctx.close();
  }
  await browser.close();
})();
