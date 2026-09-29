# B: Even Hub, the official third-party app platform for Even G2 (research as of 2026-09-29)

Research only. No project repo was modified. Raw downloads (the docs pages as markdown, npm tarballs, cloned official repos, ToS JSON) are in
a local research folder (not in the repo).

How sources are labelled:
- **[OFF]**: official Even Realities source (hub.evenrealities.com/docs, the npm packages under `@evenrealities`, the GitHub org `even-realities`, support.evenrealities.com, evenrealities.com).
- **[LOCAL]**: this project's own reverse-engineering notes (Faceclaw spec 07 / 04 / 02, Faceclaw notes).
- **[COMM]**: community or secondary source, used only to fill gaps.
- **[UNVERIFIED]**: a claim I could not confirm, or one that conflicts with another source.

Main official sources:
- Developer docs (VitePress, "Last updated" 2026-06..08-29): https://hub.evenrealities.com/docs/get-started/overview. There are 35 pages, all downloaded as `raw/docs_docs_*.md`.
- The developer portal (Nuxt SPA) is at https://hub.evenrealities.com. Its public base URL is `https://evenhub.evenrealities.com` and its CDN is `https://cdn-pub.evenhub.evenrealities.com`.
- npm packages:
  - SDK `@evenrealities/even_hub_sdk`: https://www.npmjs.com/package/@evenrealities/even_hub_sdk
  - CLI `@evenrealities/evenhub-cli`: https://www.npmjs.com/package/@evenrealities/evenhub-cli
  - Simulator `@evenrealities/evenhub-simulator`: https://www.npmjs.com/package/@evenrealities/evenhub-simulator
  - Font metrics `@evenrealities/pretext`
  - `@evenrealities/even-terminal`
- GitHub:
  - https://github.com/even-realities/evenhub-templates
  - https://github.com/even-realities/everything-evenhub (Claude Code plugin with 13 skills)
  - https://github.com/even-realities/EH-InNovel (demo)
  - https://github.com/even-realities/lvgl-sys-v9 (the simulator's LVGL bindings)
  - https://github.com/even-realities/EvenDemoApp (G1-era BLE demo)
- Legal documents on support.evenrealities.com (fetched through the Zendesk API, all "updated 2026-09-23"):
  - Even Hub ToS for users: https://support.evenrealities.com/hc/en-us/articles/15606749676175-Even-Hub-Terms-of-Service
  - Even Hub Developer Platform ToS: https://support.evenrealities.com/hc/en-us/articles/15606676690703-Even-Hub-Developer-Platform-Terms-of-Service
  - Developer DPA: https://support.evenrealities.com/hc/en-us/articles/15606721200911
  - Even Realities App ToS: https://support.evenrealities.com/hc/en-us/articles/14270548833551-Even-Realities-App-Terms-of-Service
  - Consumer help article "Even Hub": https://support.evenrealities.com/hc/en-us/articles/15688149217167-Even-Hub
  - Specs: https://support.evenrealities.com/hc/en-us/articles/13499229138959-Specs

---

## 1. How an Even Hub app is built

### 1.1 Technology and where the code runs

- **[OFF]** Today there is only one live "surface": **plugins**. A plugin is a web app (HTML, CSS, JS/TS) that uses the Even Hub SDK. Any stack works (Vite, React, plain JS). The docs list three more surfaces as "coming": dashboard widgets, dashboard layouts, and AI skills. Source: https://hub.evenrealities.com/docs/get-started/overview
- **[OFF] Where the code runs.** Architecture page, quoted:
  > "The phone runs the Even Realities App (Flutter), which hosts your plugin in a WebView - Chromium on Android, WKWebView on iOS. Your app logic runs inside that WebView. The Even Realities App relays everything to and from the glasses over Bluetooth."
  > "The glasses render UI containers and emit input events - presses, scrolls, swipes. Apart from native scroll handling, no app logic runs on them."

  Source: https://hub.evenrealities.com/docs/get-started/architecture. The FAQ says the same thing: "app state lives in the WebView on the phone. The glasses are a render target, not a state store."
- **[OFF] Bridge.** The runtime is the "Even App WebView with `window.flutter_inappwebview.callHandler`" (SDK README 0.0.16).
  - Web to host: `callHandler('evenAppMessage', {type:'call_even_app_method', method, data})`.
  - Host to web: `window._listenEvenAppMessage({type:'listen_even_app_data', method, data})`. The push methods are `evenAppBridgeReady`, `deviceStatusChanged`, `evenHubEvent`, `evenAppLaunchSource` and `appLocationChanged`.
  - Source: `index.d.ts` enums `EvenAppMethod`, `EvenAppMessageType`, `BridgeEvent` in the SDK tarball.
  - The host (Flutter/Dart) maps the JSON onto protobuf commands. The type comments cite `EvenHub.pbenum.dart`, `EvenHub.pb.dart` and PB names such as `APP_REQUEST_CREATE_PAGE_SUCCESS`, and sends them over BLE.
  - **[LOCAL]** Those BLE commands travel on the EvenHub service sid `0xE0` (spec 07 §A.3, §C).
- **[OFF] What the glasses execute.** The glasses run an LVGL-based firmware that composites the containers. It handles native scrolling of lists and overflowing text, and it draws the contextual-menu overlay itself "without waking your WebView". It LZ4-decompresses image payloads; the FAQ says LZ4 was chosen for "the glasses' RTOS memory budget".
  - **[LOCAL]** The stock LVGL composes a 576x288 4-bit buffer and copies it into a 640x480 framebuffer (spec 02 §1.1). This is reverse-engineered and not an official figure.
- **[OFF] Packaged apps.** The `.ehpk` bundles the built `dist/`. The Even app downloads it from Even Hub and runs it in the WebView.
  - **[LOCAL]** The EHPK is downloaded from a pre-signed CDN URL and unpacked locally (spec 07 §C.2, §C.13).
  - During development the WebView loads a dev-server URL through the QR sideload instead.
  - **[COMM]** The older community notes (even-g2-notes/architecture.md) still describe the "regular web apps hosted on your own server" model. That describes the QR/URL flow, not packaged apps.
- **[OFF] Background behaviour** (https://hub.evenrealities.com/docs/build/background-lifecycle):
  - iOS WKWebView: "The WebView keeps running."
  - Android: it "May be suspended under memory pressure... in-memory state is gone".
  - localStorage survives. WebSockets, audio capture and location streams must be re-armed on return.
  - SDK 0.0.10 changelog: "Enhanced WebView background keep-alive".
  - SDK 0.0.16: "Fixed repeated execution of setTimeout / setInterval callbacks". The SDK's package.json lists `./src/shadow-timers.ts` as a side effect, so the SDK patches the timer functions (compare Faceclaw's host-driven timers in spec 07 §C.4).
- **[UNVERIFIED]** The official Claude Code plugin skill `background-state` describes a host "Headless WebView migration" on backgrounding, driven by `window.__getStateSnapshot()` / `__restoreState`. It also describes the APIs `setBackgroundState` / `onBackgroundRestore`. **These names are not exported by SDK 0.0.16**; I grepped both the d.ts and the dist. They may be unreleased. Source: https://github.com/even-realities/everything-evenhub/blob/main/plugins/everything-evenhub/skills/background-state/SKILL.md
- **[OFF] PWA alternative**, quoted: "If you'd rather stay outside the Even Hub distribution flow, build a Progressive Web App and point users at your hosted URL ... skip the dev portal entirely - no packaging, no review." (architecture page). The docs do not explain how a PWA reaches the glasses bridge without the Even app. **[UNVERIFIED]** In practice a URL only gets the bridge through the Developer-Mode QR scan.

### 1.2 Packages and versions (npm registry, checked 2026-09-29)

| Package | Latest | Date | Notes |
|---|---|---|---|
| `@evenrealities/even_hub_sdk` | **0.0.16** | 2026-09-24 | `minAppVersion` 2.2.10 (0.0.15 and later). The docs still say "current 0.0.14" (2026-08-20, floor 2.2.9). License MIT; the LICENSE file says "Copyright (c) 2025 Yangshun Tay", which looks like template boilerplate. `dist` is **javascript-obfuscator'd**; the typings (`index.d.ts`, with Chinese comments) are the reference. ESM+CJS, Node ^20 or >=22. |
| `@evenrealities/evenhub-cli` | 0.1.14 | 2026-08-20 | Binaries `evenhub` and `eh`. Built with Bun. The EHPK packer is a Rust to WASM module (`create_ehpk`, uses the zstd and flate2 crates). No license field. |
| `@evenrealities/evenhub-simulator` | **0.9.5** | 2026-09-01 | The docs say 0.9.3. Tauri-style Rust app with LVGL (`even-realities/lvgl-sys-v9`), shipped as per-platform binaries `@evenrealities/sim-{darwin,linux,win32}-{x64,arm64}`. MIT. |
| `@evenrealities/pretext` | 0.1.4 | 2026-04-16 | "Pixel-accurate font measurement library for Even Realities G2 glasses" (LVGL metrics). MIT. |
| `@evenrealities/even-terminal` | 0.10.5 | 2026-09-24 | Laptop-side server that renders Claude Code / Codex onto the G2 through the Even app (see §4). |

SDK version history (from the SDK README changelog and https://hub.evenrealities.com/docs/reference/changelog):

| Version | Changes |
|---|---|
| 0.0.1 | 2026-01-22: bridge, storage, device info, EvenHub protocol, events. |
| 0.0.8 | Launch source, startup containers raised from 4 to 12, IMU. |
| 0.0.10 | Background keep-alive. |
| 0.0.11 | 2026-06-22: location, album, camera, mic source glasses/phone. |
| 0.0.12 | 2026-07-10: `zOrderIndex`, LZ4 image compression. |
| 0.0.13 | Adds the `minAppVersion` npm field (2.2.6). |
| 0.0.14 | 2026-08-20: contextual menu, long press, `textColor` 0..4, audio `direction` and `speakerRole`; floor raised to 2.2.9. |
| 0.0.15 | Floor 2.2.10; long-press `sysEvent` keeps `eventSource`. |
| 0.0.16 | Timer fix. |

### 1.3 Project layout (official)

From https://hub.evenrealities.com/docs/get-started/quickstart/first-app:
```
my-first-app/
├── src/main.ts        ← entry
├── public/icon.png    ← greyscale app icon (24×24)
├── index.html         ← Vite HTML entry
├── package.json, vite.config.ts, tsconfig.json
└── app.json           ← Even Hub manifest (the only Even-specific file)
```
- Route A is `npm create vite@latest ... vanilla-ts`, then `npm i @evenrealities/even_hub_sdk@latest`, then `evenhub init` (which writes only app.json).
- Route B is `npx degit even-realities/evenhub-templates/{minimal|text-heavy|asr|image}`. The templates are MIT, "Copyright (c) 2026 David Yu / Even Realities".

### 1.4 Manifest `app.json`

Sources: https://hub.evenrealities.com/docs/ship/packaging, and the CLI zod schema in `main.js`.

| Field | Required | Rule |
|---|---|---|
| `package_id` | yes | regex `^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$`. No hyphens, underscores or uppercase. Permanent once Released. |
| `edition` | yes | must be `"202601"` (platform contract version) |
| `name` | yes | at most 20 chars. Review also rejects names containing "Even" (case-insensitive). |
| `version` | yes | `x.y.z` |
| `min_app_version` | no | Stamped at pack time from the SDK's npm `minAppVersion`. The CLI keeps the higher of the two values. |
| `min_sdk_version` | yes | string, e.g. `"0.0.14"` |
| `entrypoint` | yes | path inside the build folder (e.g. `index.html`) |
| `permissions` | yes (may be `[]`) | array of `{name, desc(1–300 chars), whitelist?}`. `name` is one of `network`, `location`, `g2-microphone`, `phone-microphone`, `album`, `camera`. `whitelist` applies only to `network`. |
| `supported_languages` | yes | from `en, de, fr, es, it, zh, ja, ko` |

- Store-listing metadata such as tagline (at most 50 chars in the CLI error table) and description (at most 1024) are entered in the portal. The store icon is drawn in a 24x24 1-bit editor in the portal and must be built from 2x2 pixel blocks (https://hub.evenrealities.com/docs/build/design-guidelines).
- **[LOCAL]** Faceclaw's parser also accepts legacy key spellings, the old map shape `{name:[whitelist]}`, and `privacy_link` and icon keys (spec 07 §C.2). The official CLI rejects the map shape.

### 1.5 Package format `.ehpk` and CLI

- **[OFF]** `evenhub pack app.json dist -o myapp.ehpk [--sdk-ver X] [-c/--check package-id availability] [--no-ignore] [--enforce-manual-version]`. Other commands are `evenhub init`, `evenhub qr --url http://<lan-ip>:5173`, and `evenhub login` (it calls `https://hub.evenrealities.com/api/v1/auth/{login,refresh,self_check}` and `/api/v1/apps/check`; override with `EVENHUB_BASE_URL`). Source: https://hub.evenrealities.com/docs/reference/cli
- **[OFF]** CLI README, quoted: "**An `.ehpk` cannot currently be opened or run directly. To test one on a device, upload it through the EvenHub site, then open it from the Even app on your phone.**"
- **[OFF]** FAQ: "Practical cap is currently ~10 MB - larger packages still upload but degrade install UX over BLE."
- **[OFF] vs [LOCAL] conflict on the format.** The official glossary calls `.ehpk` "a zip of your built web assets plus the manifest". The CLI's WASM packer links zstd, and Faceclaw's parser (spec 07 §C.2) documents a custom container instead:
  - magic `EHPK`
  - records `0xE4` FILE / `0xE5` DIR / `0xE3` FOOTER
  - names and data XOR'd with `"EVEN REALITIES"`
  - zstd-compressed data
  - a SHA-512 trailer
  - `app.json` at the root, the app under `dist/`

  **The glossary's "zip" is wrong or simplified.** Older names `.ehp` and `.evenpkg` are deprecated (glossary). The consumer help article still says ".ehp files".
- The docs warn twice: "Never bundle secrets or API keys into the `.ehpk`. Once a build is Released, anyone can extract its contents."

### 1.6 Local development and testing

Source: https://hub.evenrealities.com/docs/test/.

1. **Simulator.** `evenhub-simulator http://localhost:5173`. See §4.2.
2. **Local Testing (QR sideload).**
   - Developer Mode has no toggle: sign in at hub.evenrealities.com/login with the same account, then force-quit and reopen the Even app. The Even Hub tab then shows a developer section with **Scan QR**.
   - The WebView loads your LAN dev server, with Vite HMR.
   - It "dies when the WebView backgrounds".
   - A phone-side dev console shows logs.
3. **Private Testing.** Upload the `.ehpk` as a private build in the portal, then Me → Apps → Private builds → Install. This gives real permission prompts, but the app "don't pass the 5-minute lock test".
4. **Beta Testing.** Create Beta groups by email in the portal, push the build, and testers use Me → Beta tester → Install. This is "the only mode that behaves identically to a Released app".

### 1.7 SDK API surface (0.0.14–0.0.16)

The authoritative source is `index.d.ts` in the npm tarball. The docs are https://hub.evenrealities.com/docs/build/{page-lifecycle,display,device-apis,contextual-menu}.

**Bridge setup**
- `waitForEvenAppBridge()`. Always await it first; "Calling SDK methods before the bridge is ready silently no-ops".
- `EvenAppBridge.getInstance()`.
- `callEvenApp(method, params)`, a generic escape hatch.

**Page (glasses UI) methods**

| Method | Returns / behaviour |
|---|---|
| `createStartUpPageContainer({containerTotalNum, listObject[], textObject[], imageObject[], menuObject?})` | Call it exactly once. Returns 0 ok, 1 invalid, 2 oversize, 3 out of memory. |
| `rebuildPageContainer(...)` | Full redraw, flickers, returns boolean. Omitting `menuObject` clears the menu. |
| `textContainerUpgrade({containerID, containerName, content, contentOffset?, contentLength?, textColor?})` | Flicker-free in-place update. |
| `updateImageRawData({containerID, containerName, imageData})` | Returns `success`, `imageException`, `imageSizeInvalid`, `imageToGray4Failed` or `sendFailed`. Calls must not be concurrent. LZ4-compressed in transit from 0.0.12. Paced to a 100 ms floor from 0.0.14. |
| `shutDownPageContainer(0 or 1)` | 1 shows the system exit dialog and is **mandatory on the root page for review**. |

**Container limits** (display docs and SDK typings)
- Totals:
  - `containerTotalNum` 1–12.
  - At most 8 text/list containers and at most 4 image containers.
  - Exactly one container has `isEventCapture:1`. It must be text or list; image containers cannot capture.
- Every container:
  - `containerName` at most 16 chars, unique.
  - `containerID` unique.
  - Coordinates 0–576 / 0–288.
  - `zOrderIndex` is all-or-nothing per page and unique.
- Text and list containers only:
  - `borderWidth` 0–5, `borderColor` 0–15 (the docs also say 0–16 for text), `borderRadius` 0–10, `paddingLength` 0–32.
  - No background fill.
- Text:
  - Content is at most 1000 chars on create/rebuild and at most 2000 on `textContainerUpgrade`. The simulator enforces 999 bytes.
  - Left/top aligned only. Wraps at the container width.
  - Overflow scrolls in firmware when the container captures events.
  - About 400–500 chars fill the screen.
  - `textColor` is a brightness from 0 to 4 (default 4). It is not a colour.
- List:
  - At most 20 items of at most 64 chars each. The simulator enforces 63 bytes.
  - Firmware-driven selection and scroll.
  - No in-place update; changing it needs a rebuild.
  - The pretext skill says "List items are 40px tall". **[COMM]** says item height = containerHeight / itemCount.
- Image:
  - Width 20–288 and height 20–144 per container.
  - 4-bit greyscale.
  - Input can be `number[]`, `Uint8Array`, `ArrayBuffer` or base64. The host converts PNG/BMP or raw gray8/gray4 ("imageToGray4").
  - A new image container starts empty until the first update.
- Contextual menu (`menuObject.menuItems`, 0.0.14 and later, Even App 2.2.9 and later):
  - At most 10 items; `itemName` at most 32 UTF-8 bytes; `itemID` a non-zero unique uint32.
  - The OS owns the Display-off, Brightness and "Close <app>" slots.
  - A selection arrives as `menuItemClickEvent{itemID}`, bracketed by FOREGROUND_ENTER (4) and FOREGROUND_EXIT (5).
  - The menu opens on the gesture "tap then long press".

**Events** (`onEvenHubEvent(cb)` delivers `{listEvent|textEvent|sysEvent|audioEvent|menuItemClickEvent}`)
- `OsEventTypeList` values:
  - CLICK 0, SCROLL_TOP 1, SCROLL_BOTTOM 2, DOUBLE_CLICK 3
  - FOREGROUND_ENTER 4, FOREGROUND_EXIT 5
  - ABNORMAL_EXIT 6, SYSTEM_EXIT 7 (`systemExitReasonCode`)
  - IMU_DATA_REPORT 8
  - LONG_PRESS 9, LONG_PRESS_RELEASE 10
- `EventSourceType`: 1 right temple, 2 R1 ring, 3 left temple.
- Quirk: "SDK normalizes 0 to undefined in some cases", so check for `CLICK_EVENT || undefined`.
- Routing depends on which container type captures: a text container gives `textEvent`, a list gives `listEvent` (with `currentSelectItemIndex/Name`). Long press always arrives as `sysEvent`.

**Other bridge APIs**

| Area | API and behaviour |
|---|---|
| Lifecycle / launch | `onLaunchSource(cb)` gives `appMenu` or `glassesMenu`, pushed once. |
| Device | `getDeviceInfo()` returns `{model: g1\|g2\|ring1, sn, status}`. `onDeviceStatusChanged(cb)` reports connectType, isWearing, batteryLevel, isCharging, isInCase. |
| User | `getUserInfo()` returns uid, name, avatar, country. |
| Storage | `setLocalStorage(k, v)` / `getLocalStorage(k)` store host-side strings. WebView `localStorage` and IndexedDB also work: "survives suspension, kill, and update. Cleared on uninstall", sandboxed per `package_id` (FAQ). **[COMM]** even-g2-notes claims the opposite, that browser localStorage does not survive inside `.ehpk`. [UNVERIFIED conflict] |
| Microphone | `audioControl(isOpen, AudioInputSource.Glasses\|Phone)`. Glasses mode needs the startup page first. Audio arrives as `audioEvent{audioPcm: Uint8Array (PCM 16 kHz s16le mono), source, direction (raw int16 tag or null), speakerRole self\|other\|unknown}`. The glasses deliver a single processed stream from the 4-mic array; per-mic capture and DOA are "on the roadmap". The simulator sends 100 ms per event (3200 B). Permission is `g2-microphone` or `phone-microphone`. |
| IMU | `imuControl(isOpen, ImuReportPace.P100..P1000)`. The docs say "These are protocol pacing codes, not literal Hz values". The official plugin's sdk-reference skill says "P100 = 100 ms = 10 Hz" [UNVERIFIED conflict]. Samples arrive as `sysEvent{eventType:8, imuData:{x,y,z}}`; a units table is "still TBD". |
| Location | `getAppLocation({accuracy, timeoutMs})`, `startAppLocationUpdates({accuracy, intervalMs, distanceFilter})`, `onAppLocationChanged`, `stopAppLocationUpdates`. Permission `location`. |
| Photos | `pickImageFromAlbum()` / `captureImageFromCamera()` return an `AppImageAsset{path, name, mimeType, size, base64}` taken on the phone. Permissions `album` / `camera`. |
| Network | Ordinary `fetch`, XHR and WebSocket from the WebView. They are gated by the manifest whitelist (enforced by the Even app, "no traffic generated at all" otherwise) **and** by CORS. See §4.1. |
| Phone notifications | **Not available.** FAQ: "Can I receive push notifications? **No.** Plugins are foreground-only on the glasses. The phone app receives notifications and may surface them in its own UI." |

**What the SDK does not expose** (device-apis page): "No direct Bluetooth access, no arbitrary pixel drawing, no audio output, no text alignment, no font control, no background colors, no per-item list styling, no programmatic scroll position, no animations, no glasses-side camera ... images are greyscale-only." There is also no haptics (FAQ). Enterprise/government customers can ask for "deeper hardware access, custom firmware behavior, white-labeled distribution" at software@evenrealities.com.

**Doc inconsistencies found**
- The FAQ mentions a "user-info permission", but no such manifest permission exists.
- The FAQ says to use `bridge.getDeviceInfo().locale`, but `DeviceInfo` has no `locale` field in the d.ts.

---

## 2. Store: publishing, review, listing, install, terms

### 2.1 Publishing and review flow

Source: https://hub.evenrealities.com/docs/ship/app-submission.

- **States:** Draft (upload the `.ehpk`) → Test (private/beta installable) → Submitted (locked; withdrawing needs support) → Released (public, **immutable, no rollback, fix-forward only**). A rejection returns the build to Draft with notes. Email comes from `noreply@evenrealities.com` for decisions only.
- **Reviewer flow:** assignment is automated. The reviewer installs the build as a beta tester, tests it against a fixed rubric, then approves or rejects. The rubric covers:
  - **Manifest:** package_id and name rules, no "Even" in the name, only permissions that are used, a changelog for updates.
  - **Listing:** a legible monochrome icon and background, screenshots from the simulator, the name matching app.json, no impersonation.
  - **Privacy:** the privacy policy covers every permission, and "Backend service domains ... documented and traceable to the developer".
  - **First run:** never a black screen; setup is remembered; CORS works.
  - **Locked-phone operation:** the core flow works on glasses plus ring alone; alive after 2 min idle and after a 5-min lock.
  - **Exit:** root double-tap → `shutDownPageContainer(1)`, and the phone-side WebView closes afterwards; first-party apps (Conversate) launch afterwards without restart.
  - **Content:** "No medical diagnosis, financial advice, or emergency-routing functionality", no NSFW.
- **Developer ToS §3.3** lists plug-in types Even Hub "currently does not support":
  > "financial products and services; health-related content and services; medical treatment...; education and training services; instant messaging services; applications or services specifically directed to children; and other applications that Even Realities ... may create material legal, security, safety, privacy, or platform-integrity risks".
- **Appeals:** through the Support Center within 30 days (§3.5). Even Realities may use third-party reviewers (§3.3).
- Every hotfix goes through review too (FAQ).
- Regions: "Not currently", meaning every Released app is globally visible (FAQ).

### 2.2 Listing and install (user side)

Sources: https://support.evenrealities.com/hc/en-us/articles/15688149217167-Even-Hub, the packaging docs, and the FAQ.

- The store is the **"Even Hub" tab** in the Even Realities App. Users browse by category (Productivity, Lifestyle, Entertainment, ...) or search, then open a detail page that previews the glasses UI, then tap **Install**.
- "All plugin data is cached locally on your device, and the number of plugins you can store depends on your phone's system memory capacity."
- Installed plugins appear as cards on the app's Home page, where they can be launched, reordered, or deleted with a long press.
- "You have full control over which apps appear in the display menu of your glasses ... the system will automatically **cloud-sync** your menu settings to your Even G2 glasses."
- Management happens under Profile → "My plugins" (Public / Beta), including Update and swipe-to-uninstall. Beta plugins are joined "via exclusive email invitation links".
- Launch is "from the glasses menu or from the app's Even Hub tab" (packaging docs).
- `min_app_version` blocks a plugin **at open** on an older Even app, and the block is shown on the glasses (https://hub.evenrealities.com/docs/reference/versioning).
- **[COMM]** Even Hub launched on 2026-04-03 with about 50 apps and a claimed "2,000+ developers". Sources: https://www.digitaltrends.com/wearables/even-realities-launches-even-hub-to-turn-g2-smart-glasses-into-a-full-app-ecosystem/, https://extentos.com/docs/ecosystem/platforms/even-realities. The announcement was on 2026-03-26: https://9to5google.com/2026/03/26/even-realities-even-hub-apps-and-better-conversate-mode/
- **[LOCAL]** The store backend is private and not documented publicly (details omitted in this copy).

### 2.3 Paid apps

- **[OFF]** FAQ: "Can I price my app? **TBD.** No paid distribution yet."
- **[OFF]** Even Hub ToS §3.4:
  > "Even Hub is currently provided free of charge to both users and Developers. ... Even Realities reserves the right to introduce or change fees ... provided that reasonable prior notice will be given ... Any fees charged by a Developer for a Developer Plug-in are solely between you and the Developer."

  So there is no store billing, but a developer's own account or subscription model is not excluded. The ToS also mentions third-party plug-in "account systems independent of Even Realities account" (§2.1).

### 2.4 Terms: relevant clauses (quoted)

**Even Hub Terms of Service (users)**, last updated 2026-03-24. The contracting party is Hong Kong Even Realities Limited; in the EEA it is Even Realities GmbH, Berlin. Governing law is Hong Kong, with arbitration and consumer carve-outs.

- §2.2: "You may use the Even Hub to browse, locate, view, use, license, subscribe to, or download Plug-ins for your Products."
- §2.3: developers "are solely responsible for all aspects of the Plug-ins they publish, including functionality, security, availability, and support."
- §2.4: Even Realities may "suspend, discontinue, or deprecate all or part of the Even Hub or Plug-ins at any time ... without prior notice".
- §3.5: Even Realities records "plugin browsing, addition, and usage history". "Even Realities does not store or control Plug-in Content Data".
- §5: "Even Realities **does not host Developer Plug-ins** appearing on the Even Hub". This conflicts with the architecture page's "Even Hub Cloud (distribution & hosting)" and with the observed CDN download.
- §7.1: on termination the user must "delete, uninstall, and/or otherwise discontinue all use of any Plug-ins obtained from the Even Hub."
- There is **no explicit clause about installing apps outside the official app, or through third-party clients.**

**Even Hub Developer Platform Terms of Service**, last updated 2026-03-24:

- §5.1 (licence to developers and **reverse-engineering ban**):
  > "Developers may not use, access, integrate, modify, translate, or otherwise exploit Even Hub Developer Platform, EVEN Devices and products, the Even Realities website, or any other platform, system, software, code, content, or materials of any form developed, operated, or owned by Even Realities, nor may they reverse engineer any of the foregoing or use them to create derivative products."
- §5.2 (**licence developers grant to Even**): publishing grants "a non-exclusive, transferable, sublicensable, royalty-free, worldwide license to host, use, distribute, modify, run, reproduce, publicly perform or display, translate, and create derivative works from the plugin ... for any commercial purpose related to the operation, provision, or improvement of the Even Hub Developer Platform or Even Realities' products". It includes the right to "place content (including advertisements) around the Plug-in". The licence term "is not limited by the term of this Agreement". "Nothing in this Agreement transfers or assigns to Even Realities any of your intellectual property rights in your Plug-ins". Even may build competing features.
- §6.1 "**Dynamic loading and code behavior**":
  > "Plug-ins may not contain functions intended to change the behavior of the application itself or circumvent the Even Hub Developer Platform review mechanism (for example, hot code updates, or dynamically downloading and executing unverified scripts)."

  Also under §6.1: no probing or scanning of Even systems; no mining; no "simulated keystrokes, virtual location, or automated bulk operations" that interfere with the platform. Even may demand source code for compliance or security review, and developers must run security scans every 6 months.
- §6.2 (API restrictions):
  - "(c) accessing or using the API primarily to create a product or service that substantially replicates the core functionality of the Even Hub Developer Platform"
  - "(e) attempting to reverse engineer or otherwise derive the source code, trade secrets, or proprietary technology of the API or services"
  - "(g) using any scraping ... to extract data from the API"
  - "(k) processing integration data to develop, improve, or train artificial intelligence or machine learning applications or models"
  - "(l) requesting ... end-user tokens, credentials ... for any purpose other than Plug-in identity authentication".
- §4.1: every published plug-in needs a public privacy policy. §4.2 forbids collecting PCI data, PHI, government IDs, or "access credentials and authentication keys (such as API keys, MFA / OTP codes, or passwords)". Voice data requires explicit consent.
- §7: confidentiality covers "API keys, interface specifications" and all "Pre-Release Materials".
- §9: Even may suspend with or without notice, or terminate on 30 days' notice.

**Even Realities App Terms of Service** (effective 2026-07-25). The licence is "personal ... non-commercial". The user may not:
- "Copy, reproduce, adapt, **reverse engineer, decompile, disassemble**, or otherwise create derivative works based on any of the Even Realities Services";
- "Use illegal or inappropriate methods ... including but not limited to extracting source code, hacking, cracking, distributing counterfeit software";
- "Develop, use, or distribute any software, script, code, plug-in unit, programs, or applications that may cause an unfair competitive advantage";
- "Use the Services to or permit, enable, or assist a third party to create competing products or services".

The user must also "hold in the strictest confidence all code and any technical elements". There is one carve-out: "Good-faith security research and vulnerability reporting conducted in accordance with Even Realities' Vulnerability Disclosure Policy are not prohibited". The general website ToS (https://support.evenrealities.com/hc/en-us/articles/14290554040335-Terms-of-Service) repeats the reverse-engineering ban and says Even Realities owns the "firmware".

**Interpretation [UNVERIFIED, not legal advice].** Nothing explicitly forbids sideloading `.ehpk` files into a non-official host. However, re-implementing the store client (the impersonation in spec 07 §C.13) runs into several clauses:
- Developer ToS §6.2(c), (e) and (g), if the person is a developer;
- the App ToS reverse-engineering and "competing products" clauses;
- the general "any use ... other than as specifically authorized ... is strictly prohibited".

Mandatory law, such as the interoperability rules in EU Directive 2009/24/EC Art. 5(3)/6 and the ToS's own consumer carve-out, may limit these clauses for EEA users. This needs a legal check.

---

## 3. Display and input constraints of the G2 (for app design)

| Item | Value | Source |
|---|---|---|
| App canvas per eye | **576 x 288 px**, origin top-left; binocular (one micro-LED per lens) | [OFF] display docs, overview |
| Colour | **Monochrome green, 4-bit = 16 levels**. White renders as bright green; black is off/transparent. No fill or background. | [OFF] display docs |
| Text brightness | `textColor` 0–4 (5 levels); borders use 0–15 | [OFF] |
| Font | "a single LVGL font baked into firmware. No font selection, no size control, not monospaced. Characters outside the font set are silently dropped". No emoji. Use `@evenrealities/pretext` for metrics. **[LOCAL]** It is a 20 px proportional font, roughly a 50x10 character grid. | [OFF] display docs, FAQ; [LOCAL] spec 02 |
| Marketing panel spec | "Resolution 640*350, FoV 27.5°, Refresh rate 60Hz, Brightness 1200 nits, Micro LED, Green, Four Microphones, BLE 5.4, IP65" | [OFF] https://www.evenrealities.com/smart-glasses and support "Specs" |
| Physical framebuffer | **640 x 480** A4 per lens. The 576x288 stock band is copied into it; the onboarding vertical offset explains 480→288 and the "distance" setting explains 640→576. | [LOCAL] spec 02 §1.1, Faceclaw notes/apps.txt. **Not official.** |
| BLE | The dev docs say "Bluetooth LE 5.2"; the product spec says "BLE 5.4" | [OFF] (conflict) |
| Transfer / update rate | FAQ: "BLE bandwidth (~10–30 KB/s) limits practical frame rate". Images are paced to a 100 ms floor (0.0.14). Everything must be serialized. See the detail below this table. | [OFF] FAQ, display docs, everything-evenhub glasses-ui skill, templates/asr README; [COMM] https://github.com/nickustinov/even-g2-notes/blob/main/docs/performance.md; [LOCAL] spec 02 |
| Temple touchpads | "Press, double press, swipe up, swipe down, long press and release", on both temples. Events carry a source (right = 1, left = 3). | [OFF] overview, device-apis |
| R1 ring | Optional; "Same gestures as the Even G2", source = 2 | [OFF] |
| OS-reserved gestures | "tap then long press" raises the system contextual menu. Root-page double tap must open the exit dialog, which is a review rule. | [OFF] |
| IMU | accelerometer/gyroscope x/y/z; units TBD | [OFF] |
| Mic | 4-mic array, "single stream, 16 kHz PCM" | [OFF] overview |
| None | camera, speaker, haptics | [OFF] |
| Store icon | 24x24, 1-bit, built from 2x2 blocks | [OFF] design-guidelines |

Transfer and update rate detail:
- **[OFF]** The official plugin's glasses-ui skill says: "Image frames cost ~0.5s to ~2s each over BLE ... design turn-based". It also says to serialize all bridge calls and to add per-call timeouts ("a single flaky hop can hang ~30s").
- **[OFF]** The ASR template debounces glasses renders to 120 ms.
- **[COMM]** Measured on firmware 2.2.7 with SDK 0.0.13: `updateImageRawData` costs about 104 ms fixed plus about 3.9 ms/KB of gray4; `textContainerUpgrade` about 83 ms; `rebuildPageContainer` about 165 ms. The maximum is about 9.5 fps for a single image container.
- **[LOCAL]** Effective throughput is about 8.8 KB/s.

Design patterns listed in the official design guidelines:
- `>` as a cursor
- toggling `borderWidth` for selection
- stacked text containers for rows
- `━─` progress bars
- pre-paginating at about 400–500 chars
- an image-first app placing a full-screen `' '` text container with `isEventCapture:1` behind the images.

Official Figma: https://www.figma.com/design/X82y5uJvqMH95jgOfmV34j/Even-Realities---Software-Design-Guidelines--Public-

---

## 4. Off-phone computation and simulator

### 4.1 Cloud and back-end calls (the official model)

- **[OFF]** The only documented route is ordinary web networking from the plugin WebView. `fetch`, XHR and **WebSockets** are all "same whitelist rules". Source: https://hub.evenrealities.com/docs/build/networking
  - Hosts must be declared in `app.json` as a `network` permission `whitelist`, as full origins such as `https://api.example.com`: "bare hostnames and wildcards aren't supported".
  - The official ASR template also puts `wss://stream.yourprovider.com` in the whitelist.
  - "HTTPS in production. Plain http:// is only useful for local dev".
  - The Even app enforces the whitelist ("Anything not in the whitelist is blocked - no traffic generated at all"). CORS must still pass: "The whitelist is not a CORS bypass."
  - For third-party APIs without CORS, "proxy through a server you control ... then put that server's domain in the app.json whitelist".
  - `evenhub pack` rejects an empty whitelist (templates/asr README).
- **[OFF]** The docs recommend your own backend or proxy for API keys ("Move third-party keys behind a server-side proxy you control").
  - Example: the official `asr` template streams mic PCM (16 kHz s16le) to a cloud STT provider of your choice over WebSocket or HTTP. See https://github.com/even-realities/evenhub-templates/tree/main/asr
  - Reviewers require "Backend service domains ... documented and traceable to the developer".
- **[OFF]** Even Realities offers **no compute or back-end service for plugins**. It stores only "Plug-in Usage Records" and "will not collect, process, or store" Plug-in Content Data (Dev ToS §4.3). Store-side fees and subscriptions don't exist.
- **[OFF] Limits:**
  - "Can I make network calls while backgrounded? No. WebView is suspended on background; in-flight requests stall" (FAQ). This conflicts in part with the lifecycle page, which says iOS keeps running, and with the review rubric's locked-phone requirements.
  - Dev ToS §6.1 forbids "hot code updates, or dynamically downloading and executing unverified scripts". So a backend may compute and return data, but not ship new code for the plugin to execute.
- **[OFF] Even Terminal** (`@evenrealities/even-terminal` 0.10.5, npm) is an Even-published example of heavy work done off the phone.
  - It runs a local HTTP server on a laptop (port 3456), spawns Claude Code or Codex, "renders it onto the G2's 576×288 canvas, and translates R1 ring gestures back into keyboard events".
  - The Even app connects to it over LAN, Tailscale, or a pinggy/bore/ngrok tunnel.
  - It is a first-party tool, not an Even Hub plugin template. How the Even app side is enabled is not documented; [UNVERIFIED] it may be a built-in Even app feature.
- **[OFF]** For more than the public SDK allows, Even offers enterprise and government (2B/2G) partnerships at software@evenrealities.com.

### 4.2 Simulator and emulator

- **[OFF]** `@evenrealities/evenhub-simulator` 0.9.5 runs on macOS, Linux and Windows, x64 and arm64. Usage: `evenhub-simulator [--glow] [--automation-port N] [--aid <audio dev>] <url>`.
  - It is a desktop app that renders a 576x288 green LVGL canvas next to an inspectable WebView hosting your app.
  - It supports mic input from a desktop audio device, screenshots, and `RUST_LOG=debug` call tracing.
  - Docs: https://hub.evenrealities.com/docs/test/simulator
  - Quoted: "**Not an emulator.** The simulator is a Node + LVGL window that mimics how containers, text, and events look on the glasses. It is not a hardware emulator. Performance, frame pacing, BLE timing, and real-device quirks are not reproduced."
  - Caveats listed in the docs:
    - Fonts and grey levels are not exact.
    - Image size limits are not enforced.
    - The LZ4 path is not decompressed.
    - Status events are not emitted (user and device are hardcoded).
    - Before v0.6.1, `eventSource` was hardcoded to 1 and `imuData` was null.
- **[OFF]** Headless automation (0.7.1 and later) through `--automation-port 9898`:
  - `GET /api/ping`
  - `GET /api/screenshot/glasses` (576x288 RGBA; brightness is in the alpha channel)
  - `GET /api/screenshot/webview`
  - `GET|DELETE /api/console[?since_id=]`
  - `POST /api/input {action: up|down|click|double_click|long_press|long_press_release|context_menu}`
- **[COMM]** Community tools:
  - https://github.com/BxNxM/even-dev ("Even Realities Hub Simulator - multi application test environment")
  - ER Studio IDE: https://github.com/gabrielevierti/er-studio
  - `@penta2himajin/even-deskless`
  - `even-sim-recorder`
  - `even-toolkit`: https://github.com/fabioglimb/even-toolkit
- **[OFF]** An AI-tooling path is endorsed: the `everything-evenhub` Claude Code plugin, with skills quickstart, template, build-and-deploy, glasses-ui, handle-input, device-features, test-with-simulator, simulator-automation, font-measurement, sdk-reference, cli-reference, design-guidelines and background-state. Sources: https://github.com/even-realities/everything-evenhub and https://hub.evenrealities.com/docs/learn/claude-code

---

## 5. Cross-check against our local notes (spec 07 §C / 04 / Faceclaw notes)

- **Consistent:** the 576x288 canvas; the event enum; text brightness 0..4; the menu limits (10 items / 32 bytes); image formats (PNG/BMP/raw gray8/gray4); the `flutter_inappwebview` bridge with `evenAppMessage`; the manifest fields and permission names; the audio format (16 kHz s16le mono).
- **Local notes and official docs differ:**
  1. Spec 07 guessed the `audioControl` and `imuControl` payload field names. The SDK public API is `audioControl(isOpen, source)` and `imuControl(isOpen, reportFrq)`. The PB model `ImuCtrlCmd` has `iMUReportEn` and `reportFrq`, and `AudioInputSource` is `"glasses"` or `"phone"`. The raw JSON the SDK sends is obfuscated, so it can't be confirmed from the package; the exact wire key names still need a capture.
  2. Faceclaw's `getGlassesInfo` naming matches `EvenAppMethod.GetGlassesInfo = "getGlassesInfo"` (the public SDK method is `getDeviceInfo()`).
  3. Our notes say `shutDownPageContainer(1)` is "declined" by Faceclaw. Officially it shows the system exit dialog, and it is mandatory on the root page.
  4. The official whitelist is enforced by the Even app; Faceclaw does not enforce it.
  5. The official `.ehpk` is described as a "zip", but it is actually EHPK/XOR/zstd, as Faceclaw found.
  6. New since our notes:
     - SDK 0.0.15 and 0.0.16 (Even App floor 2.2.10; `eventSource` on long press; timer fix);
     - `audioEvent.source`, `direction` and `speakerRole`;
     - `pickImageFromAlbum` / `captureImageFromCamera` returning base64 assets (Faceclaw returns `null`);
     - location options `accuracy` and `distanceFilter`.
- The MentraOS assessment (faceclaw notes) is not directly relevant to Even Hub. It notes that MentraOS also treats the G2 as a device with a 640x200 canvas, which is **not** the Even Hub model.
