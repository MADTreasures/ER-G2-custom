# A — Faceclaw's EvenHub compatibility layer (runtime; store details omitted)

Source: faceclaw clone at HEAD `2052e26` (2026-09-28, just after 0.8.0; `app/version.ts` says `FACECLAW_VERSION = "0.8.0"`).
All paths below are relative to the repo root. `file:line` citations were checked against this checkout.
Legend: **[V]** = verified in code/docs; **[I]** = my inference / not verifiable from the repo.

Scope read: every file in `app/apps/evenhub/` (≈6.2k lines), the native WebView hosts
(`App_Resources/Android/src/main/java/com/faceclaw/app/FaceclawEvenHub*.kt`,
`App_Resources/iOS/src/FaceclawEvenHubWebView.{h,m}`,
`native/kotlin/shared/.../evenhub/EvenHubAssetServer.kt`, `.../callbacks/FaceclawEvenHubListener.kt`),
callers (Files, Launcher, Developer, dashboard-controller, wear-remote), `faceclaw-extensions/`,
CHANGELOG, README, PRIVACY, website, notes, tests. For comparison I also pulled the public npm
typings of `@evenrealities/even_hub_sdk` 0.0.14 and 0.0.16 from npm.

Note: the design doc the code keeps citing, `notes/evenhub_compatibility.txt`, and the reference
unpacker `../../../../ehpk-unpacker/ehpk_unpack.py` (ehpk.ts:8) are **not in the public repo and
never were** (`git log --all -- notes/evenhub_compatibility.txt` is empty). The same goes for
`notes/firmware-download.md` and `captures/even-2.2.8-base.apk` cited in even-api.ts:281-282. [V]

---

## 0. Architecture in one paragraph

A Hub app is an ordinary web app (HTML/JS bundle) plus `app.json`, packed as `.ehpk`. Faceclaw
unpacks it and runs it in **a phone WebView** (Android `android.webkit.WebView` subclass, iOS
`WKWebView`). A shim injected at document start supplies `window.flutter_inappwebview.callHandler`,
which the Even SDK uses (the stock Even app is Flutter + flutter_inappwebview). Calls land in
`EvenHubSession` (TypeScript, NativeScript), which keeps the app's page-container state (text / list /
image containers) and **composites it phone-side into an 8-bit gray 576×288 image** with
Faceclaw's own drawing code. That image is one window of Faceclaw's shell and reaches the glasses
through Faceclaw's normal display pipeline (custom-firmware display commands). The stock firmware
never sees the app's containers: per notes/ios-protocol-sync.md:7-8, "The EvenHub page contains only
the input text container. Display commands and cleanup use SID `0xf0`". Input (ring, temple
touchpads, Wear OS watch) is routed by the shell to the focused window and translated back into
EvenHub events. [V]

```
EHPK ──unpack──► dist/ ──served offline──► WebView (app JS + SDK)
                                             │ flutter_inappwebview.callHandler('evenAppMessage', json)
                                             ▼
                              EvenHubSession (TS)  ◄── ring/touchpad/watch gestures (shell)
                              ├─ containers.ts (parse)      ├─ mic/IMU/compass routers (BLE)
                              ├─ compositor.ts → GrayImage  └─ phone GPS, settings, assistant tools
                              ▼
                     Faceclaw shell window → display list → BLE (CFW) → G2
```

---

## 1. STORE (omitted in this copy)

The original notes describe Faceclaw's client for Even's private store server (endpoints, login,
request signing). This project does not rebuild that access (see `../05_EvenHub-Apps.md` §7), so
those details are left out here. Kept: the package format (§1.6), which is needed to import
.ehpk files you legitimately have.

### 1.6 The .ehpk format [V] (ehpk.ts; test packer in tests/evenhub-install-performance.test.cjs:6-14)

Produced by `evenhub pack` (`@evenrealities/evenhub-cli`) (ehpk.ts:4).
- **Header (20 bytes)**: bytes 0-3 = `"EHPK"`; u32 LE at offset 8 = offset of the first record
  (normally 20). Other header bytes are not interpreted.
- **Records**: `[type:u8][0xBA 0xA9 0xBA]` (as LE u32: `0xBAA9BAE4` for a file)
  - `0xE4` FILE (16-byte header): u32 compressed length @4, u32 uncompressed size @8, bytes 12-13
    unused by Faceclaw, u16 name length @14; then name, then data.
  - `0xE5` DIR: u16 length @6, skipped (8+length).
  - `0xE3` FOOTER: SHA-512 of all preceding bytes — "an integrity check only, not a signature; we
    skip verifying it" (ehpk.ts:9-10). Parsing stops here.
- **Obfuscation/compression**: name and data are each XORed with the repeating ASCII key
  `"EVEN REALITIES"` (key index restarts per field), data is **zstd**-compressed (pure-JS `fzstd`)
  (ehpk.ts:5-7, 15, 47-53, 117). No encryption, no signature. The `public_key` field returned by the
  download API is ignored (typed at even-api.ts:60, never used).
- Paths validated: no `\`, NUL, `:`, empty, `.` or `..` segments (ehpk.ts:21-24).
- Contents: `app.json` + `dist/**`.
- Lazy index: only the needed records are decompressed (ehpk.ts:79-122); install touches only
  manifest/HTML/icon (test "never decompress the large app engine").

`app.json` fields used (ehpk.ts:31-45, 131-158; permissions.ts:104-146; installed-apps.ts:257):
`package_id|packageId`, `name`, `version`, `entrypoint` (relative to `dist/`, default `index.html`,
must exist), `permissions` (current: array of `{name, desc|description, whitelist}`; older: map
`{"network":[hosts], "fs":[paths]}`), privacy URL (`privacy_link|privacyLink|privacy_policy_url|
privacyPolicyUrl`, https only), icon (`icon|icon_path|iconPath|app_icon|appIcon`). Known permission
names: `network, location, g2-microphone, phone-microphone, album, camera, fs` (permissions.ts:5-8).
The network whitelist is parsed but **"not yet enforced"** (ehpk.ts:38-39; FaceclawEvenHubWebViewClient.kt:28-29).

## 2. RUNTIME

### 2.1 Where and how an app executes [V]

**Android** (app/apps/evenhub/webview.ts; App_Resources/Android/...):
- `FaceclawEvenHubWebView` (subclass of `android.webkit.WebView`, i.e. the **system WebView /
  Chromium**) created on a `MutableContextWrapper` (webview.ts:56-61). Settings: JS on, DOM storage
  on, file access off, media autoplay allowed (webview.ts:62-66).
- Entry: `https://<packageId-sanitized>.evenhub.invalid/<entrypoint>` (fake per-app origin → per-app
  web storage isolation) (webview.ts:37-41, 105). `FaceclawEvenHubWebViewClient.shouldInterceptRequest`
  serves that host from `<unpackDir>/dist` with path-escape and symlink checks via the shared Kotlin
  `EvenHubAssetServer` (resolve / isInsideRoot / MIME table) and **splices the bridge `<script>` right
  after `<head>`** of every HTML file; any other host goes to the real network
  (FaceclawEvenHubWebViewClient.kt:19-79; EvenHubAssetServer.kt:25-59).
- Dev URL apps: loaded from the network; shim installed with
  `WebViewCompat.addDocumentStartJavaScript` (needs WebView 83+) restricted to the page origin,
  fallback: evaluate on `onPageStarted` (FaceclawEvenHubDocumentStart.kt:11-49; webview.ts:84-97).
- JS→host channel: `addJavascriptInterface(FaceclawEvenHubJsBridge, "__faceclawEvenHub")` with
  `postMessage(handlerName, argsJson, callId)` and `wakeTimers()`; calls bounced to the main thread
  (FaceclawEvenHubJsBridge.kt:17-31). Host→JS: `webView.evaluateJavascript` (webview.ts:109-115).
- Console → logcat tag `FaceclawEvenHubConsole` (FaceclawEvenHubChromeClient.kt).

**iOS** (webview.ios.ts; App_Resources/iOS/src/FaceclawEvenHubWebView.m):
- `WKWebView`; packaged app served from custom scheme `faceclaw-ehpk://app/<entrypoint>` via
  `WKURLSchemeHandler` (.m:23-28, 155-171); bridge = `WKUserScript` at document start +
  `window.webkit.messageHandlers.faceclaw` (webview.ios.ts:6-21; .m:50-53).
- Storage: per-package persistent `WKWebsiteDataStore` on iOS 17+, otherwise non-persistent (.m:40-49).
- Main-frame navigation outside the app origin is blocked (.m:123-142) (Android has no such block).
- The iOS side does **not** use the shared Kotlin `EvenHubAssetServer` despite its doc comment; only
  Android calls it (grep).

### 2.2 The bridge (wire contract) [V] (session.ts:1-19, 90-111, 759-1006)

- Shim (ES5) defines `window.flutter_inappwebview.callHandler(name, ...args)` returning a Promise
  keyed by id; the host resolves it with `window.__fcResolve(id, ok, value)` (session.ts:90-111, 964-968).
- App→host: `callHandler('evenAppMessage', '<json>')`, json = `{type:"call_even_app_method", method,
  data}`. Return value of the handler resolves the SDK promise (session.ts:9-11, 766-792).
- Host→app: `window._listenEvenAppMessage({type:"listen_even_app_data", method, data})`
  (session.ts:970-975).
- Wire shapes "captured empirically from even_hub_sdk 0.0.12" and updated for 0.0.14 (session.ts:5-6;
  CHANGELOG 0.6.3). npm now has 0.0.16 (2026-09-24); its `EvenAppMethod` enum is **identical** to
  0.0.14's 16 methods (checked in the downloaded typings) — payload-level changes in 0.0.15/0.0.16
  were not checked (the SDK bundle is obfuscated). SDK license on npm: MIT.

**Methods handled (`dispatch`, session.ts:794-844)** — this covers every `EvenAppMethod` in SDK 0.0.14/0.0.16:

| SDK method | Faceclaw behaviour |
|---|---|
| `createStartUpPageContainer` | parse page, returns 0; one-shot: a 2nd call waits 2 s and returns 1 like stock (:846-864). Emits deferred FOREGROUND_ENTER. |
| `rebuildPageContainer` | replace whole page, `true` (also used as create) (:866-874) |
| `textContainerUpgrade` | match by `containerID` (name optional); stock "write at offset and truncate" semantics; optional `textColor` 0..4 (:893-923) |
| `updateImageRawData` | PNG (upng-js), uncompressed BMP 1/4/8/24/32-bit, or raw 8bpp/4bpp sized to the container; returns 0/1/3 (:925-939, 1368-1567). JPEG etc. → 1 |
| `shutDownPageContainer` | `exitMode 1` (ask-to-quit) → **not** quit, focus goes to app switcher; else close after 200 ms (:941-956) |
| `setLocalStorage` / `getLocalStorage` | NativeScript `ApplicationSettings` key `evenhub:<pkg>:ls:<key>` (:806-810, 958-960) |
| `getUserInfo` | stub `{uid:0, name:"Faceclaw", avatar:"", country:""}` (:811-812) |
| `getGlassesInfo` | stub `{model:"g2", sn:"FACECLAW-G2", status:{connected, isWearing:true, batteryLevel:100, …}}` (:813-825) |
| `audioControl` | needs a declared mic permission; registers with mic router; **iOS: returns false** (:643-669) |
| `imuControl` | freq snapped to 100..1000; **iOS: returns false**; enable-flag field name guessed from 9 spellings, default "on" (:737-757, 1288-1309) |
| `getAppLocation` / `start…/stopAppLocationUpdates` | needs declared `location` + OS fine-location; phone GPS; continues in background (:671-725) |
| `pickImageFromAlbum` / `captureImageFromCamera` | **unsupported → `null`** (:836-839) |
| anything else | logged, `null` (:840-842) |

**Pushes (host→app)**: `evenAppLaunchSource {launchSource:"glassesMenu"}` and a single static
`deviceStatusChanged {sn:"FACECLAW-G2", connected, isWearing:true, batteryLevel:100, …}` after page
load (never updated) (session.ts:433-448); `evenHubEvent` with `type` `sysEvent | listEvent |
audioEvent | menuItemClickEvent` (:535-542, 635-641, 729-735, 990-1006); `appLocationChanged`
(:710-713). The SDK's listener set (0.0.16 typings: `onLaunchSource, onDeviceStatusChanged,
onEvenHubEvent, onAppLocationChanged`) is thus covered; `textEvent` is never emitted (clicks are
always `sysEvent`, as on stock hardware, session.ts:477-482). Protobuf-style zero-field elision is
emulated (:1461-1472).

Event codes (session.ts:69-80): CLICK 0, SCROLL_TOP 1, SCROLL_BOTTOM 2, DOUBLE_CLICK 3,
FOREGROUND_ENTER 4, FOREGROUND_EXIT 5, SYSTEM_EXIT 7, IMU_DATA_REPORT 8, LONG_PRESS 9,
LONG_PRESS_RELEASE 10. `eventSource`: right arm 1, ring **and Wear OS watch** 2, left arm 3 (:1444-1459).

**Timer/rAF shim** (session.ts:112-223): `setTimeout/setInterval/clear*/requestAnimationFrame/
cancelAnimationFrame` are replaced by queues run by `window.__fcTick()`, which the native host calls
(Android: adaptive 16 ms…1 s, `wakeTimers` for early work, FaceclawEvenHubWebViewHost.kt:102-160,
310-319; iOS: fixed 60 Hz NSTimer calling `__fcTimerTick`/`__fcRafTick`, .m:88-101). Reason:
Chromium throttles page timers when the screen is off / page hidden. Consequence for porting: **a
page with this shim and no host tick never fires a timer.**

**Faceclaw-only extensions** (`window.getFaceclawExtensions()`, separate RPC channel
`faceclawExt` / `__fcExtResolve` / `__fcExtEvent`, session.ts:226-336, 1008-1197; typed package
`faceclaw-extensions/` (MIT)): `getVersion`, `returnToAppSwitcher`, `quit`,
`addWindowLifecycleListener` (visible/hidden/focused/blurred), `getConfiguredApiKeys`,
`requestApiKeyAccess`, `playBuzzer` (piezo, chunked at 48 steps), `addCompassListener`,
`createLayout` / `replaceLayout` (extended 576×452 canvas, no container limit, `preserve`),
`setAssistantTools` (app tools exposed to Faceclaw's voice assistant, namespaced by package).

### 2.3 Containers, layout, compositing [V]

- Model (containers.ts:9-85): `text` (x,y,w,h, border ≤5 px, radius, padding, isEventCapture,
  zOrderIndex, content, textColor 0..4), `image` (decoded 8bpp gray pixels), `list` (itemNames,
  itemWidth, isItemSelectBorderEn, host-local selectedIndex). Page = `listObject[]`,
  `imageObject[]`, `textObject[]` + `menuObject.menuItems` (≤10, non-zero unique uint32 id, name ≤32
  UTF-8 bytes) (containers.ts:174-279). Loose key matching accepts camelCase and PB names like
  `Container_ID` and numeric strings (containers.ts:1-7, 98-131).
- Stock limits (exactly one event-capture container, ≤8 text, ≤4 image) are **logged, not enforced**
  (session.ts:876-891).
- Compositor (compositor.ts): 576×288 canvas (:26-27); paint order = explicit zOrderIndex if any,
  else images under lists/text (:100-114); text drawn with **Even's 20 px firmware font** + wrapping
  measured with `@evenrealities/pretext` so line breaks match stock (:5-7; graphics/evenhub-font.ts:1-21);
  brightness levels → grays [0,64,128,191,255] (:31-41); images smaller than their container tile
  (stock quirk, :84-92); lists drawn with Faceclaw's animated `Menu` (outline around selected item,
  others dimmed; bounce at ends) (list-menu.ts). Known deviations listed at compositor.ts:9-13.
- The Latin/Greek/Cyrillic/emoji glyphs are **extracted from the stock firmware image** (downloaded
  from Even's CDN) into app storage and "not distributed with Faceclaw"; CJK is bundled Source Han
  Sans (OFL); Roboto is the fallback until extraction (evenhub-font.ts:1-40; firmware-builder.ts:56-71).
- Window: an in-process shell window, `heightMode "medium"` = top bar + exactly 288 px content
  (evenhub-window.ts:65; shell/geometry.ts:76-100); in 640-wide full-panel mode the 576 image is
  centred (evenhub-window.ts:24-33). `createLayout` switches to `"max"` (576×452) (session.ts:1084-1100;
  evenhub-window.ts:99-101). Frames then go through Faceclaw's shell/display-list/BLE path like any
  native Faceclaw app.

### 2.4 Input routing [V]

- Shell → focused window → `EvenHubAppLayer.handleInput` → `session.handleGesture` (evenhub-window.ts:37-44).
  Sources: R1 ring, left/right temple touchpads, Wear OS watch (reported as ring), phone-screen
  simulated input, and the token-authenticated `faceclaw-input` network port (scripts/faceclaw-input.md).
- click → `listEvent CLICK` if the capture container is a list, else `sysEvent CLICK`;
  double-click → `sysEvent DOUBLE_CLICK`; scroll → list selection moves **locally** (no round trip),
  event only at a boundary; otherwise `sysEvent SCROLL_TOP/BOTTOM` (session.ts:477-550).
- Long-press is reserved for Faceclaw's system menu; tap-then-hold opens the window menu (which holds
  the app's `menuObject` entries + "Show phone UI") **and** is also sent to the app as LONG_PRESS /
  LONG_PRESS_RELEASE (session.ts:497-514; evenhub-window.ts:66-81, 105-117). The app owns
  double-click, so the guaranteed exit is the menu.
- No text input path to Hub apps (no `receiveTextInput` in evenhub-window.ts; only the store has it).
- **Microphone** (mic-router.ts): one G2 mic shared; an app that called `audioControl(true)` receives
  audio only while it is the foreground window, the phone screen is on and no assistant/STT modal is
  active; frames = decoded G2 mic PCM (16 kHz mono S16LE, app/native/voice-control.ts:213-217) sent as
  a JSON number array in `audioEvent {audioPcm, direction:null, speakerRole:"unknown"}`
  (session.ts:626-641). The SDK's `source` (glasses vs phone mic) is ignored — always the G2 mic.
  Android only (`startRawCapture` returns false on iOS, voice-control.ts:230-231).
- **IMU** (imu-router.ts): cmd-19 accelerometer stream, foreground app only, not screen-gated,
  "historically unreliable … best-effort" (:1-15); delivered as `sysEvent {eventType:8, imuData:{x,y,z}}`.
- **Compass** (compass-router.ts): extension only; CFW magnetometer stream, foreground only, adds the
  Compass app's wearer calibration, declination and true heading (:1-44).
- **Location**: phone GPS (not glasses).

### 2.5 Permissions model [V] (permissions.ts:1-15; permission-dialog.ts)

Declared in app.json; confirmed once on the glasses at install, or every time an *uninstalled*
package is run from Files. The dialog must be scrolled to the bottom before Allow is live; it offers
"Privacy policy" when a URL exists (permission-dialog.ts:17-28, 155-209). After that, **every
declared permission counts as granted**. Runtime checks exist only for mic (needs any mic
permission) and location (needs `location`, plus the Android runtime permission). Network is not
enforced (any host reachable); album/camera are unsupported anyway; IMU has no permission. Launching
an already-installed app shows no dialog. Dev-URL apps get all permissions (manager.ts:185-191).

### 2.6 Background behaviour [V]

- Multiple Hub apps run **concurrently**, each with its own glasses window and persistent WebView,
  kept alive until explicitly closed, "no memory eviction yet" (manager.ts:1-13).
- Android keep-alive tricks (FaceclawEvenHubWebViewHost.kt:26-87, 281-308; FaceclawEvenHubWebView.kt):
  all WebViews sit full-size in an overlay *behind* the NativeScript UI (occluded but "visible");
  `onWindowVisibilityChanged` always reports VISIBLE so Chromium never freezes the renderer when
  Faceclaw is backgrounded; renderer priority pinned IMPORTANT; `resumeTimers()/onResume()`; host
  ticks keep timers/rAF running with the screen off. Relies on Faceclaw's foreground service.
- Focus changes → FOREGROUND_ENTER/EXIT sysEvents; extension lifecycle events (session.ts:552-610).
  `shutDownPageContainer(1)` only defocuses. Closing sends SYSTEM_EXIT, releases mic/IMU/compass,
  location, assistant tools, then destroys the WebView after 100 ms (session.ts:1218-1256).
- iOS: 0.8.0 fixed "Hub apps on iOS no longer have their timers stopped while the phone app is in the
  background" (CHANGELOG:51); the .m ticks whenever iOS grants execution time (.m:87-101).

### 2.7 Phone-side UI [V]

A Hub app's own HTML UI (settings pages etc.) is hidden by default ("glasses-first"). It is shown by
the window menu item "Show phone UI" or the app-icon button in the phone action bar while the app is
foreground on the glasses (manager.ts:1-13, 226-260; app/phone-ui/main-view-model.ts:779-795; commit
e879c58). Android: the overlay is raised with a 56 dp top bar (app name + ✕), Back/✕ hide it
(FaceclawEvenHubWebViewHost.kt:214-252, 321-345; manager.ts:68-78). iOS: host view with a "Back to
Faceclaw" button (.m:64-86, 107-114). The store itself and all dialogs (permissions, API keys) are
rendered on the glasses.

---

## 3. STATUS

- **Android: working, the primary target.** README:7 "This app runs on Android"; README:71 and
  145-154 "Mostly-compatible … runs EvenHub apps through an emulation layer; you may run into bugs …
  test in the stock Android app before reporting". [V] (I could not run it on hardware.)
- **iOS: developer beta** (build from source only, no App Store/TestFlight; CHANGELOG 0.7.1).
  EvenHub status then: "Partially working. Hub apps install and run, sometimes. They freeze when the
  app is in the background or the phone is locked" (CHANGELOG:80); background timers fixed in 0.8.0
  (CHANGELOG:51). In code on iOS: mic and IMU return false (session.ts:650, 744), privacy policies open
  in Safari (privacy-policy.ios.ts), unpacking runs in a Worker with 180 s timeout
  (unpack-runtime.ios.ts). Tests include a simulator probe of the WKWebView host
  (tests/ios-evenhub-native.cjs: ES modules, localStorage, fetch, Image, timers, rAF, WebAssembly,
  path traversal). [V]
- **Maintenance:** 47 commits touch `app/apps/evenhub` between 2026-08-11 ("First-pass EvenHub
  compatibility layer", 5d29d91) and 2026-09-27 (45d2ece "Optimize phone CPU usage"); store
  integration since 2026-08-13 (add8763); SDK 0.0.14 features 2026-08-31 (052398d); iOS port
  2026-09-08/19. Very active. [V]
- **Unreleased 0.8.1 fixes** (CHANGELOG:3-11): "Fix a crash on install of larger EvenHub apps"
  (8798198, see §1.5), permission-dialog line wrapping (77bda86), phone-UI top bar + action-bar
  button (e879c58), menu animations/style matching (aeeea5e), CPU optimisations. 0.8.0: login form
  fixes (09a59b9, 662cbd7), installer re-render fix (a6291cf).
- **Known limitations / unsupported (all [V] unless noted):**
  - `pickImageFromAlbum`, `captureImageFromCamera` → null.
  - `getUserInfo`, `getGlassesInfo`, `deviceStatusChanged` are fixed fakes (battery 100 %, always
    worn, sn FACECLAW-G2); no real device-status updates.
  - Phone microphone source not supported (always G2 mic); no `direction`/`speakerRole`.
  - Mic/IMU not on iOS; IMU best-effort; IMU/audio payload field names partly guessed.
  - `shutDownPageContainer(1)` does not quit (by design).
  - Stock page limits not enforced; list rendering is Faceclaw's animated menu, not pixel-identical;
    unknown whether stock lays narrow list items side by side (compositor.ts:9-13).
  - Image formats: PNG, uncompressed BMP, raw gray only.
  - Network whitelist not enforced; EHPK SHA-512 not verified; no signature check.
  - No paid apps, no automatic updates, no text input to Hub apps.
  - Text rendering needs the fonts extracted from stock firmware (otherwise Roboto fallback).
  - Behaviour of SDK 0.0.15/0.0.16 payload changes unknown [I].

---

## 4. PORTABILITY

### 4.1 What depends on what [V]

| Layer | Files | Depends on |
|---|---|---|
| EHPK parsing | ehpk.ts, package-extraction.ts | pure TS + `fzstd`; file I/O via `native/file-access` |
| Store client | even-api.ts, updates.ts | `fetch`, HMAC-SHA256 (platform), device id, settings, native binary download |
| Page model + compositor | containers.ts, compositor.ts, list-menu.ts | pure TS `GrayImage`/`Menu`; `EvenHubFont` needs NativeScript `File` + native LVGL font reader + fonts extracted from firmware; `@evenrealities/pretext` |
| Session / bridge host | session.ts | NativeScript `ApplicationSettings`; mic/IMU/compass routers (BLE to glasses via Faceclaw's communicator), location (Android services), toolRegistry, sound payloads |
| App execution | webview.ts, webview.ios.ts + Kotlin/ObjC hosts | a **full browser engine** (system WebView / WKWebView): request interception or custom scheme, document-start script injection, JS↔native bridge, `evaluateJavascript` |
| Glasses output | shell windows | Faceclaw's shell, display list, CFW BLE protocol on the phone |

What the Hub apps themselves need from the engine [V from the iOS probe test, I for general]:
ES modules, `fetch`/XHR to arbitrary hosts, `localStorage` (and likely IndexedDB), `Image` decoding,
timers + rAF, **WebAssembly**, and in practice Canvas 2D (apps that render bitmaps and send PNGs via
`updateImageRawData`; session.ts:113-116 mentions "canvas apps that render on a rAF loop"), plus
DOM/CSS for their phone UI. `@evenrealities/pretext` is used by apps too (evenhub-font.ts:12-15).
So a bare JS engine is not sufficient for real-world apps.

**The clean seam:** the session only talks to its WebView through
`EvenHubWebViewHandle = { evaluateJs(js), destroy() }` (session.ts:341-346, 424-426) plus inbound
`session.handleBridgeCall(handlerName, argsJson, callId)` and `session.webViewLoaded()`
(webview.ts:68-75). Everything host→page is a string of JS calling one of
`__fcResolve, _listenEvenAppMessage, __fcExtResolve, __fcExtEvent, __fcExtInvokeTool`. A remote
page could implement this over any byte stream. [V]

### 4.2 (a) Wear OS watch (no system WebView)

- Faceclaw's watch app (`wear/`) is a separate Kotlin/Gradle remote; it runs no Hub code, it only
  lists/launches installed Hub apps on the phone and sends gestures (wear/README.md:45,
  wear/PROTOCOL.md:81; watch input is reported to Hub apps as the ring, session.ts:1444-1459). [V]
- To *run* the Hub runtime on the watch you would have to replace the WebView with an embedded
  engine: **GeckoView** (brings its own Gecko, does not need the system WebView; large per-ABI
  download; messaging via a WebExtension/native-messaging port instead of `addJavascriptInterface`;
  request interception for the offline origin) or a JS engine (QuickJS/Hermes/V8) **plus** DOM,
  Canvas (e.g. Skia-backed), fetch, localStorage and WebAssembly polyfills — only viable for a subset
  of simple apps. [I]
- Also to reimplement: the TS host (session, containers, compositor, font, list menu) either in
  Kotlin or inside that JS engine (the watch app is not NativeScript); the BLE link to the G2 or a
  relay to the phone (all glasses I/O incl. mic/IMU/compass/buzzer is in the phone app); the
  settings/storage; timer driving. The Hub apps' phone UI has no meaningful place on a watch. CPU,
  RAM (43 MB packages!) and battery are serious constraints. [I]
- Realistic conclusion: not a port but a rewrite; the practical watch role is input (already done). [I]

### 4.3 (b) Desktop browser / headless browser on a PC, driven over the network

Feasible and comparatively cheap, because the protocol is tiny JSON RPC and the host seam is narrow. [I, based on V facts above]

Option B1 — *remote WebView, host stays on the phone* (smallest change):
- PC: Playwright/Chromium (headed or headless) loads the app's `dist/` from a local HTTP server
  (unpack with ehpk.ts + fzstd in Node — pure TS), injects `EVENHUB_BRIDGE_INJECT_SCRIPT` and the
  extensions script via `addInitScript`, and replaces `window.__faceclawEvenHub.postMessage` with a
  WebSocket/`exposeBinding` relay. Either keep the timer shim and call `__fcTick()` from the relay,
  or strip the timer part (headed desktop Chrome doesn't need it; with the shim and no tick,
  timers never fire).
- Phone: a new `EvenHubWebViewHandle` implementation whose `evaluateJs` sends the string to the PC
  and which feeds inbound messages to `session.handleBridgeCall` — plus a network listener with auth
  (Faceclaw has none for this; the only server is the token-authenticated input port on
  127.0.0.1/Tailscale :8791, `FaceclawRemoteInput.kt`, scripts/faceclaw-input.md — a usable pattern).
- Everything else (compositor, input routing, mic/IMU/compass via BLE, GPS, permissions, buzzer,
  assistant tools, glasses output) keeps working unchanged on the phone. Costs: one network round
  trip per input→render cycle; mic PCM as JSON number arrays (~32 KB/s raw → roughly 100 KB/s JSON);
  the app's phone UI moves to the PC.
- The existing Developer "Load app from URL/QR" only moves *serving* to the PC; execution is still
  in the phone WebView (manager.ts:155-208). [V]

Option B2 — *whole runtime on the PC*: run a Node port of EvenHubSession + compositor
(needs replacements for NativeScript `ApplicationSettings`, `File`, the LVGL font reader, the routers
and location), then ship either finished 576×288 gray frames or container ops to the phone, which
would need a new "remote window" API in the shell (none exists). Sensors (mic/IMU/compass/GPS) must be
streamed back from the phone. More work than B1, same network dependency. [I]

Either way the glasses link stays on the phone (Faceclaw's BLE stack is phone code; shared Kotlin
covers Android+iOS only). [V for current code]

---

## 5. LEGAL / TERMS hints

- README:3-5 "entirely unofficial, and comes with no support or warranty from Even Realities or from
  anyone"; website footer "unofficial, and is not affiliated with Even Realities" (website/index.html:146,
  install.html:75, privacy.html:75). License GPL-3.0; `faceclaw-extensions` is MIT. [V]
- PRIVACY:70-75 (and website/privacy.html:54-55): "The EvenHub app store is subject to Even
  Realities' privacy policy: https://support.evenrealities.com/hc/en-us/articles/14127464826511-Privacy-Policy.
  Apps downloaded from the EvenHub app store come with their own privacy policies." [V]
- README:182-187: third-party integrations must be listed in PRIVACY; "For services that involve a
  user-provided API key, we assume that the user agreed to any terms associated with that service …
  For services that don't involve API keys, more caution may be required." [V]
- The store client uses private storefront endpoints of the official Even app, signs requests with a key
  embedded in Faceclaw and identifies itself to the server as the official Android app; no Even terms are
  shown at login. Details deliberately left out of this copy (see 05 §7). [V]
- Per-app privacy policies are surfaced before first run/install ("Privacy policy" action in the
  permission dialog; commit 526a236 "Display link to EvenHub apps' privacy policy on first run");
  shown in an isolated, bridge-free WebView dialog or the system PDF viewer on Android, Safari on iOS
  (privacy-policy.ts:12-70, privacy-policy.ios.ts:1-5). [V]
- IP caution: Even's firmware UI fonts are extracted from the user's own stock-firmware download and
  "not distributed with Faceclaw" (graphics/evenhub-font.ts:4-7); the Even SDK is not bundled (each
  app carries its own copy). [V]
- Sharing Faceclaw's third-party API keys with Hub apps requires explicit per-session consent on the
  glasses (api-key-dialog.ts:26-31). [V]
- README:150-154 asks users to reproduce bugs in the stock Android app before reporting them to the
  app's creator, and developers to test in the stock app before submitting to the Hub. [V]
