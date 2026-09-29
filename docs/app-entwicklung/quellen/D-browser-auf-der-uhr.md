# D: A browser or JS engine on the watch (research as of 2026-09-29)

Question: can the Pixel Watch 5 (45 mm LTE, Wear OS 7 = Android 17 / API 37) run Even Hub apps itself? Those are Vite-built web apps that talk to the host through `window.flutter_inappwebview.callHandler(...)`, and their glasses output comes from SDK calls rather than from the DOM. If the watch cannot run them, the fallback is the phone.

Research only. No project repo file was modified. Small downloads (AAR manifests, doc extracts, the SIGMETRICS slides) are in
a local research folder (not in the repo).

Labels:
- **[V] verified.** Checked against a primary source: official docs, AOSP source, or an artifact I downloaded and inspected (Maven AAR contents, manifests, npm/Maven metadata).
- **[R] reported.** From developers, press, store reviews or search snippets. Not checked by me.
- **[I] inference.** My own reasoning. It needs a test on the device.

---

## 0. Short answer

1. **There is no system WebView on Wear OS, and you cannot install one.**
   - [V] Official docs: "the android.webkit APIs aren't supported". AOSP `WebViewFactory` throws `UnsupportedOperationException` whenever the device lacks the system feature `android.software.webview`.
   - [R] Developers report exactly this crash on watches. Sideloading `com.google.android.webview` does not help.
2. **The watch runs a 32-bit userspace.**
   - [R] Pixel Watch 3 and Pixel Watch 4 both report `ro.product.cpu.abilist = armeabi-v7a,armeabi` (GitHub PRs dated 2026-09).
   - [I] Pixel Watch 5 uses the same SoC family, so it is probably the same. Check first with `adb shell getprop ro.product.cpu.abilist`.
   - Any native engine we bundle must therefore ship **armeabi-v7a**.
   - [V] Since 2026-09-15, Google Play also requires arm64-v8a next to the 32-bit libraries.
   - This ABI constraint rules out several candidates:
     - [V] WPEView ships arm64/x86_64 only.
     - [V] Javet's Node.js mode ships arm64/x86_64 only.
3. **GeckoView is the only production-grade, maintained, embeddable full web engine for this watch.**
   - [V] Current release 156.0.20260921121718, MPL-2.0, minSdk 26, has an armeabi-v7a AAR.
   - [V] It runs sessions without an Activity or View ("headless"), and the official way to reach page JS is a built-in WebExtension with native messaging.
   - [V] Cost: about 88 MB per ABI as AAR. The armeabi-v7a AAR holds about 137 MB of uncompressed native code.
   - [I] Expect 150 to 300 MB of RAM in use.
   - [R] Hobby Wear OS browsers built on GeckoView exist.
4. **A pure JS engine (QuickJS) plus a DOM shim (linkedom) plus host polyfills is feasible, small and fast to start, but it only fits simple apps.**
   - [V] QuickJS wrappers are about 0.7 MB for armeabi-v7a.
   - [I] Canvas, WebAssembly, CSS/layout-dependent code and heavier React/Vue apps will break. The polyfill surface keeps growing until you have rebuilt a browser.
5. **Phone fallback.**
   - [V] Use the Wear Data Layer: MessageClient for payloads up to 100 KB, ChannelClient for streams and larger data. It prefers Bluetooth and falls back to Wi-Fi or the cloud.
   - [R] Bluetooth throughput is about 50 to 200 KB/s. Watch Wi-Fi is about 1 to 5 MB/s.
   - [I] Even Hub container updates (text, lists, a 576x288 4-bit image of about 83 KB raw) fit comfortably.

**Recommendation order.**
1. **(a)** Spike GeckoView on the watch: a headless session plus a built-in extension bridge, then measure RAM and startup.
2. **(b)** Run a QuickJS + linkedom "lite runtime" in parallel for simple or text-only apps, and possibly as the default for apps that pass a compatibility check.
3. **(c)** Fall back to the phone (a normal Android WebView runs the app, and the SDK calls are relayed over the Data Layer) when:
   - the app needs WASM, heavy canvas or rich DOM/CSS behaviour, or
   - GeckoView RAM or battery on the watch turns out unacceptable, or
   - the watch is under memory pressure.

   Details are in §7.

---

## 1. Android System WebView on Wear OS

### 1.1 Official statements (quotes) [V]

- **Wear OS vs. mobile development** (table row "Connectivity"):
  > "Most mobile APIs are fully supported, but there are some limitations. For example, the android.webkit APIs aren't supported."

  https://developer.android.com/training/wearables/wear-v-mobile
- **Communicate directly over a network on standalone devices**:
  > "You can use protocols such as HTTP, TCP, and UDP. However, the android.webkit APIs, including the CookieManager class, are not available."

  https://developer.android.com/training/wearables/data/network-communication
- **Manage WebView objects**:
  > "The getCurrentWebViewPackage() method can return null if the device is set up incorrectly; doesn't support using WebView, such as a Wear OS device; or lacks an updatable WebView implementation."

  https://developer.android.com/develop/ui/views/layout/webapps/managing-webview
- **`PackageManager.FEATURE_WEBVIEW`**:
  > "The device has a full implementation of the android.webkit.* APIs. Devices lacking this feature will not have a functioning WebView implementation." Constant value: `"android.software.webview"`.

  https://developer.android.com/reference/android/content/pm/PackageManager#FEATURE_WEBVIEW
- **Wear OS 7 "What's new"** (2026-05) does not mention WebView, browsers or web content, so nothing has changed. [V: checked the post; absence of a mention]

  https://android-developers.googleblog.com/2026/05/whats-new-wear-os-7.html

### 1.2 Why a sideloaded WebView APK cannot help [V: AOSP source]

`frameworks/base/core/java/android/webkit/WebViewFactory.java` (main branch) contains:

```java
static boolean isWebViewSupported() {
    ... getPackageManager().hasSystemFeature(PackageManager.FEATURE_WEBVIEW);
}
...
if (!isWebViewSupported()) {
    // Device doesn't support WebView; don't try to load it, just throw.
    throw new UnsupportedOperationException();
}
```

- `getUpdateService()` also returns `null` when the feature is missing.
- System features come from the system image (`/system/etc/permissions`), not from installed apps. Installing Google's WebView APK therefore changes nothing. [V source; the feature-file mechanism is standard Android, I]
- Source: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/webkit/WebViewFactory.java

### 1.3 What developers report [R]

- IMA SDK on a Galaxy Watch 4, 2022:
  - Crash `java.lang.UnsupportedOperationException at android.webkit.WebViewFactory.getProvider(WebViewFactory.java:242)` during WebView construction.
  - Google's IMA team (April 2023): Wear OS is "unsupported".
  - https://groups.google.com/g/ima-sdk/c/9ewcqM-vGZY
- wearBrowsing, a Wear OS browser project, 2021: `Error inflating class android.webkit.WebView` with the same `UnsupportedOperationException` at `WebViewFactory.getProvider`. https://github.com/Juliandev02/wearBrowsing/issues/1
- AnkiDroid on Wear OS, 2021: "it doesn't have a built-in webview". The user installed `com.google.android.webview` and it was not recognised. The only workaround mentioned was rooting and modifying `framework-res.apk`. https://github.com/ankidroid/Anki-Android/issues/8992
- `MissingWebViewPackageException: Failed to load WebView provider: No WebView installed` is the phone-side variant, where the feature exists but the package is missing or disabled. Examples: https://github.com/google/accompanist/issues/1673 and https://github.com/react-native-webview/react-native-webview/issues/1624. On Wear OS you normally get the `UnsupportedOperationException` before that stage. [V from source; R for the device mapping]

### 1.4 Is any Google WebView installable on Wear OS?

- **No.** There is no Google-provided WebView package for Wear OS. [V: docs above] Even a sideloaded one would not be used. [V: source, §1.2]
- Hack-level option, not recommended [I]:
  - `WebViewUpgrade` (https://github.com/JonaNorman/WebViewUpgrade) loads a Chrome/WebView APK from the app's assets by hooking `WebViewUpdateService.waitForAndGetProvider` and `sProviderInstance`. [V: its source]
  - On Wear OS the `FEATURE_WEBVIEW` check throws before the hooked service is ever consulted, and the update service is `null`.
  - You would also have to force the hidden static `WebViewFactory.sWebViewSupported` (non-SDK API) and live without a WebView update service or the WebView sandbox services.
  - That is fragile and possibly breaks on every OS update. I found no evidence anyone has made it work on a watch.

### 1.5 How existing Wear OS browsers render pages

| App | Engine | Evidence |
|---|---|---|
| **Samsung Internet (Wear OS)** `com.sec.android.app.sbrowser` | Chromium-based, bundles its own engine | [R] Samsung Internet is Chromium-based; the Wear OS APKs are ~94-106 MB (v3.2-4.0 on APKMirror); it works on non-Samsung Wear OS watches incl. Pixel Watch (9to5Google). [I] ~100 MB APK size is only explainable with a bundled engine. https://9to5google.com/2022/12/16/wear-os-internet-browser-samsung/ , https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-internet-browser-wear-os/ |
| **Mini Web Browser (Wear OS)** `com.somyac.watch.browser` (100K+ installs, v1.4.1, 2026-09-20, APK ~38 MB per APKCombo) | **Gecko** (reported) | [R] Play review (Jan 2024): "Now uses a much newer Gecko engine version." https://play.google.com/store/apps/details?id=com.somyac.watch.browser |
| **JusBrowse Wrist** (GitHub, Aug 2026) | **GeckoView 153.0.20260810162159** | [R/V from repo README+gradle] "Wear OS browser with GeckoView", "Wear OS API 30+". https://github.com/shubh72010/JusBrowse-Wrist |
| **svinotaBrowser-WearOS** (GitHub, May 2026) | GeckoView ("now on Gecko") | [R] https://github.com/ssndash/svinotaBrowser-WearOS |
| **Cromite** (sideloaded `arm_ChromePublic.apk`, 32-bit) | Standalone Chromium build (own engine, not WebView) | [R] XDA "Official List of Sideloaded Apps ... Wear OS" (via search snippet; XDA blocks fetches): "Cromite ... best browser for Wear OS", "download ... arm_ChromePublic.apk (which is the 32-bit release)". https://xdaforums.com/t/official-list-of-sideloaded-apps-and-workarounds-for-wear-os-tested-on-galaxy-watch.4379825/ |
| **WIB / "Web Browser for Wear OS (Android Wear)"** by appfour | not documented | [R] 67 MB, last update 2020-11-25, "full fledged web browser running on your Android Wear smartwatch". [I] size implies a bundled engine. https://wear-internet-browser.en.aptoide.com/app |
| **WristWeb** `angel.gabaldon.wristweb` (10K+) | not documented | [R] https://www.androidauthority.com/new-wear-os-browser-3494001/ |
| "Wearfari", "Ultimate Browser" | not found for Wear OS | "Wafari" is an **Apple Watch** app (https://apps.apple.com/us/app/wafari-watch-browser/id6449930220) |

Conclusion:
- [V/R] Every real Wear OS browser brings its own engine: Gecko/GeckoView or a bundled Chromium.
- [R] Community apps show GeckoView runs on Wear OS watches.
- [R] Reviewers describe it as laggy and prone to occasional crashes on low-RAM watches. The WristWeb Play reviews say: "it's hard and laggy to run search engines on small low ram smart watches... will crash sometimes".

---

## 2. GeckoView (Mozilla, MPL-2.0)

### 2.1 Version, coordinates [V]

- Maven repo: `https://maven.mozilla.org/maven2/`. The docs ask for Java 17 compatibility flags. Quick start: https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/geckoview-quick-start.html
- Latest **release**: `org.mozilla.geckoview:geckoview:156.0.20260921121718`. The `maven-metadata.xml` has lastUpdated 2026-09-22.
- Other channels:
  - Beta: `geckoview-beta:158.0.20260928120211`
  - Nightly: `geckoview-nightly:159.0.20260928210305`
- `geckoview` / `geckoview-omni` is the all-ABI AAR (241.7 MB). The per-ABI artifacts are `geckoview-armeabi-v7a`, `geckoview-arm64-v8a` and `geckoview-x86_64`. Source: https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/maven-metadata.xml
- The POM declares the license "The Mozilla Public License, v. 2.0". Dependencies include kotlin-stdlib 2.4.10, androidx.core 1.19.0 and others.

### 2.2 Requirements (from the AAR's AndroidManifest.xml, v156) [V]

- `minSdkVersion 26`. The watch is API 37, and our project uses minSdk 33, so this is fine.
- `<uses-feature android:glEsVersion="0x00020000" android:required="true"/>` ("GeckoView requires OpenGL ES 2.0"). [I] The Adreno 702 supports GLES 3.x.
- Touchscreen, camera, location and microphone are all `required="false"`.
- **Multi-process.** GeckoView declares these services:
  - `:crashhelper`, `:media` (MediaManager), `:gmplugin`, `:socket`, `:gpu`, `:rdd`, `:utility`, `:ipdlunittest`
  - **40 content-process slots** `:tab_*_0..39` plus 40 `:isolatedTab_*` (`isolatedProcess="true"`)
  - a `zygoteTab` with `useAppZygote="true"`
  - Application `android:zygotePreloadName="org.mozilla.gecko.process.ZygotePreload"`.
- The architecture doc lists a main process, content processes, a socket process, a GPU process and an extension process. It also says: "We intentionally do not expose our process model to embedders." https://firefox-source-docs.mozilla.org/mobile/android/geckoview/contributor/geckoview-architecture.html
- Knobs that reduce the process count, from `GeckoRuntimeSettings.Builder`: `fissionEnabled(boolean)`, `extensionsProcessEnabled(boolean)`, `isolatedProcessEnabled(boolean)`, `lowMemoryDetection(boolean)`, `configFilePath(String)` (Gecko prefs/args/env from a file), and `javaScriptEnabled`. https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntimeSettings.Builder.html
  - [I] On the watch, set `fissionEnabled(false)` and `extensionsProcessEnabled(false)`. Then check with `ps` how many processes actually spawn.

### 2.3 Size per ABI (v156, inspected by reading each AAR's zip directory) [V]

| Artifact | AAR size | libxul.so (uncompressed / deflated) | All native libs (unc. / defl.) | omni.ja |
|---|---|---|---|---|
| `geckoview-armeabi-v7a` | 87.9 MB | 117.2 / 61.6 MB | 137.2 / 72.0 MB | 15.0 MB |
| `geckoview-arm64-v8a` | 90.3 MB | 152.6 / 64.5 MB | ~175 / ~74 MB | 15.0 MB |
| `geckoview-x86_64` | 95.3 MB | - | - | - |
| `geckoview` (omni, all ABIs) | 241.7 MB | - | - | - |

Download and install size:
- [I] Download is about 85-90 MB per ABI split.
- [I] On disk it is about 150 MB (armeabi-v7a) to about 190 MB (arm64), because modern AGP stores `.so` uncompressed by default.
- [V] Play caps the per-device compressed download of the base module at 200 MB, so this fits. https://support.google.com/googleplay/android-developer/answer/9859372
- [I] The watch has 64 GB of storage, so disk space is not the problem. Install time and update size are.

### 2.4 RAM

- [I] No trustworthy published per-process numbers for a single light page on GeckoView/Android exist. Expect roughly:
  - 60-120 MB for the parent process (Java + Gecko main)
  - 40-100 MB per content process
  - plus the socket/GPU/utility processes
  - That is about 150-300 MB PSS in total.
- On a 3 GB watch that is workable in the foreground. Under memory pressure the LMK will kill content processes first.
- [V] The architecture doc warns: "Because all priorities are waived when the app is in the background, it's not infrequent that Android kills some of GeckoView's services, while still leaving the main process alive."
- Measure it: `adb shell dumpsys meminfo <pkg>` for every `:tab*`, `:socket`, `:gpu` and similar process, and `GeckoRuntime` `onCrash`/`onKill` via `ContentDelegate`.

### 2.5 Wear OS attempts and issues

- [R] GeckoView-based Wear OS browsers exist (Mini Web Browser, JusBrowse Wrist, svinotaBrowser; see §1.5).
- [R] Users report lag and occasional crashes on low-RAM watches.
- [V] Mozilla has no official Wear OS support statement, and I found no Bugzilla entries about watches.
- Risks [I]:
  - 32-bit address space on armeabi-v7a (Gecko still ships v7a, [V]).
  - Content processes are killed in the background.
  - Install size.
  - Cold start of several seconds on 4x Cortex-A53.
  - Battery cost of a JIT engine plus multiple processes.

### 2.6 Headless or offscreen operation

- [V] The GeckoView JUnit docs say: "By default, GeckoSession tests are run headless, i.e. they don't necessarily run within an Android Activity UI." A `GeckoSession` can therefore be opened on a `GeckoRuntime` and load pages without a `GeckoView` widget. https://firefox-source-docs.mozilla.org/mobile/android/geckoview/contributor/junit.html
- **Timer throttling trap** [V]:
  - `GeckoSession.setActive(boolean)` is documented as representing "if the session is currently visible or not". Setting it inactive "will significantly reduce its memory footprint".
  - The architecture doc: a session without a surface is de-prioritised. `setPriorityHint(PRIORITY_HIGH)` boosts the process priority.
  - MDN on setTimeout: "**Firefox for Android has a minimum timeout of 15 minutes for inactive tabs and may unload them entirely.**"
  - Sources: https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.html and https://developer.mozilla.org/en-US/docs/Web/API/Window/setTimeout
- To avoid the throttling [I]:
  - Keep the session `setActive(true)` and `setPriorityHint(PRIORITY_HIGH)`.
  - Optionally feed it a real but invisible Surface through `acquireDisplay()` / `GeckoDisplay.surfaceChanged(...)`, for example from an `ImageReader` or `SurfaceTexture`, so `requestAnimationFrame` and the refresh driver keep ticking.
  - Host it in a foreground service. [V] Wear OS 7 guidance for long-running work is an ongoing notification with `OngoingActivity` or a Live Update: https://developer.android.com/training/wearables/ongoing-activity
  - Verify on the device that `setInterval(…, 100)` keeps a 100 ms cadence with the screen off (ambient) and on.
- [V] The WebExtension background page, a "window object that doesn't paint to a surface", also runs JS without any session. It could host logic too, but it has the extension origin and CSP. [I]

### 2.7 Script injection at document start and the JS-to-native bridge [V]

The documented way is a **built-in WebExtension**. The JUnit docs state: "The only supported way of accessing a web page for embedders is to write a built-in WebExtension and install it." https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/web-extensions.html

- **Install.** `runtime.getWebExtensionController().ensureBuiltIn("resource://android/assets/evenbridge/", "evenbridge@ourapp")`. `resource://android/` points to the APK root.
- **Permissions.**
  - `"nativeMessaging"`, `"nativeMessagingFromContent"` and `"geckoViewAddons"`. These are available only to built-in (privileged) extensions.
  - The nativeApp id is any `[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*` string passed to `setMessageDelegate`.
- **Content script** with `"run_at": "document_start"`:
  - [V: MDN BCD] Firefox 128+ also supports `"world": "MAIN"` in `content_scripts`. GeckoView 156 is well past that.
  - Page-visible objects can be exported from the ISOLATED world with `exportFunction(fn, window, {defineAs})` or `window.wrappedJSObject.x = cloneInto(obj, window, {cloneFunctions:true})`.
  - Promises must be created with `new window.Promise(...)` and results passed through `cloneInto`.
  - https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Sharing_objects_with_page_scripts
- **Native side.**
  - Content-script messages: `session.getWebExtensionController().setMessageDelegate(ext, delegate, "evenhost")`.
  - One-off calls arrive at `MessageDelegate.onMessage(nativeApp, message, sender)`, which can return a `GeckoResult` as the reply.
  - Streams: `browser.runtime.connectNative("evenhost")` leads to `onConnect(Port)`, and `port.postMessage(JSONObject)` sends native-to-JS pushes.
  - Messages sent before the delegate is registered are queued.

Sketch (ISOLATED content script, document_start) [I, built from the documented APIs]:

```js
// content.js (document_start)
const port = browser.runtime.connectNative("evenhost");
const pending = new Map(); let seq = 0;
port.onMessage.addListener(m => {
  if (m.kind === "reply") { const p = pending.get(m.id); pending.delete(m.id); p && p(cloneInto(m.result, window)); }
  else if (m.kind === "push" && window.wrappedJSObject._listenEvenAppMessage)
    window.wrappedJSObject._listenEvenAppMessage(cloneInto(m.msg, window)); // host -> app
});
const bridge = { callHandler(name, ...args) {
  return new window.Promise(res => { const id = ++seq; pending.set(id, res);
    port.postMessage({ id, name, args: JSON.parse(JSON.stringify(args)) }); }); } };
window.wrappedJSObject.flutter_inappwebview = cloneInto(bridge, window, { cloneFunctions: true });
```

Serving the app [I]:
- Vite output uses `<script type="module" crossorigin>`. Module scripts from `file://` or `resource://` origins run into CORS/origin trouble.
- Serve the unpacked `.ehpk`/`dist` from an in-app HTTP server on `127.0.0.1`, one port or path per app so each app gets its own origin for localStorage/IndexedDB.
- Match the content script to `http://127.0.0.1/*`.

### 2.8 License notes

- [V] MPL-2.0 is file-level copyleft (FAQ, https://www.mozilla.org/en-US/MPL/2.0/FAQ/):
  - Our own new files are not "Modifications" and can stay proprietary.
  - When distributing binaries we must "inform the recipients where they can get the source for the MPLed code".
- [V] The AAR also contains `liblgpllibs.so`, `libmozavcodec.so` and `libmozavutil.so` (FFmpeg-derived).
  - [I] These are LGPL components shipped as separate shared libraries. Keep them as unmodified, replaceable `.so` files and include the notices. `about:license` in Gecko lists all components.

---

## 3. Running web apps without a full browser: embeddable JS engines and DOM shims

### 3.1 JS engines for Android (versions from Maven Central and Maven metadata, ABIs from inspecting the AARs) [V unless marked]

| Engine / wrapper | Latest (date) | armeabi-v7a? | Native size (v7a, unc./defl.) | ES modules | WebAssembly | Notes |
|---|---|---|---|---|---|---|
| QuickJS via **quickjs-kt** `io.github.dokar3:quickjs-kt(-android)` | 1.0.15 (2026-09-03) | yes | 0.66 / 0.38 MB | yes (`asModule`, lazy module loader) | no [I] | Kotlin coroutines/async bindings, interrupts/timeouts. https://github.com/dokar3/quickjs-kt |
| QuickJS via **quickjs-wrapper** `wang.harlon.quickjs:wrapper-android` | 3.2.3 (2025-07-01) | yes | 0.95 / 0.51 MB | yes (module loader, bytecode) | no [I] | Java API, promises, bytecode compile. https://github.com/HarlonWang/quickjs-wrapper |
| QuickJS via **Zipline** `app.cash.zipline:zipline-android` | 1.27.0 (2026-04-02) | yes | 0.74 MB | - | no [I] | Focus on Kotlin/JS modules; README: "should not be used to execute untrusted code". https://github.com/cashapp/zipline |
| QuickJS itself | 2026-06-04 release | - | "367 KiB of x86 code" hello world | yes | not provided [I] | "supports the ES2025 specification". https://bellard.org/quickjs/ |
| **J2V8** `com.eclipsesource.j2v8:j2v8` | 6.3.4 (2025-11-14) | yes | 46.1 / 11.8 MB | via V8 [I] | V8 has it [I] | Mature JNI V8 binding. |
| **Javet V8** `com.caoccao.javet:javet-v8-android` | 6.0.1 (2026-09-20) | yes | 96.5 / 24.0 MB | yes [I] | yes [I] | README: Node.js v26.9.0 + V8 v15.4.80.9; Android arm/arm64/x86/x86_64. https://github.com/caoccao/Javet |
| **Javet Node.js** `javet-node-android` | 6.0.1 | **no** (arm64-v8a + x86_64 only) | - | - | - | Would allow jsdom, but not on a 32-bit watch userspace. |
| **nodejs-mobile** | v18.20.4 (2024-10-07) | yes [R: release page] | - | - | - | Node 18 (EOL). Current jsdom needs Node >= 22. https://github.com/nodejs-mobile/nodejs-mobile/releases |
| **Hermes** `com.facebook.hermes:hermes-android` | 260318099.0.4 (2026-09-27) | - | - | **no** ("ES modules ... In Progress") | no [I] | Also excludes local eval, `with`, Realms. React-Native/JSI oriented, no plain Java API [I]. https://github.com/facebook/hermes/blob/main/doc/Features.md |
| **Rhino** `org.mozilla:rhino` | 1.9.1 (2026-02-15) | JVM | - | no [I] | no | Android: "minSdk is 26", "runs only in interpreted mode". https://github.com/mozilla/rhino/blob/master/it-android/README.md |

### 3.2 DOM emulations (npm registry, 2026-09-29) [V]

| Package | Latest | Runs without Node? |
|---|---|---|
| **linkedom** | 0.18.13 (2026-07-07) | **Yes, via `linkedom/worker`.** README: "works with deno, Web, and Service Workers, and it's not strictly coupled with NodeJS ... this export does not include `canvas`". Deps: htmlparser2, css-select, cssom, uhyphen, html-escaper. Caveats in the README: not spec-complete, live collections don't update, "If you need to pretend your NodeJS ... is a browser, please use JSDOM". https://github.com/WebReflection/linkedom |
| **happy-dom** | 20.14.5 (2026-09-12) | **No.** `engines.node >=20`, deps `ws`, `@types/node`. |
| **happy-dom-without-node** | 14.12.3 (**2024-06-21**, stale) | Intended for non-Node use, but 6 major versions behind. |
| **jsdom** | 30.1.1 (2026-09-22) | **No.** `engines.node ^22.22.2 \|\| ^24.15.0 \|\| >=26`, deps undici, tough-cookie, etc. It would need a Node runtime, which does not exist for 32-bit Android in a current version (see 3.1). |

Canvas options:
- [V] `canvas` (node-canvas) is Cairo plus a Node addon. `@napi-rs/canvas` and `skia-canvas` are Node native addons. None of these runs in QuickJS.
- [V] `pureimage` is a pure-JS 2D canvas subset (deps pngjs, jpeg-js, opentype.js). [I] It needs Buffer/stream shims and is slow.
- [V] `canvaskit-wasm` needs WebAssembly, so it only works with V8 (J2V8/Javet), not QuickJS.
- [V] `@nativescript/canvas` 2.1.18 is a "DOM Canvas API for NativeScript" backed by native code. [I] It is a useful reference for bridging `CanvasRenderingContext2D` onto `android.graphics.Canvas`/Skia.
- [I] The realistic approach is a host-implemented `HTMLCanvasElement`/`CanvasRenderingContext2D` subset (fillRect, drawImage, fillText, getImageData, toDataURL/toBlob) backed by `android.graphics.Bitmap`/`Canvas`.

### 3.3 What a Vite-built Even Hub app needs from the host [I]

| Web API | QuickJS/V8 + linkedom approach | Effort |
|---|---|---|
| ES modules, dynamic `import()` | QuickJS module loader. Alternatively re-bundle `dist/` to one IIFE/ESM with esbuild at install time. Parse `index.html` and run its `<script type=module>` in order. | low-med |
| `window`, `document`, `location`, `navigator`, `history` | linkedom `parseHTML(indexHtml)`, plus host-provided globals | med |
| `setTimeout`/`setInterval`/`requestAnimationFrame`/`queueMicrotask`/`performance.now` | Host timers (Kotlin coroutine or Handler) calling back into the engine. Pump QuickJS pending jobs after every callback. | low |
| `fetch`, `XMLHttpRequest`, `Headers/Request/Response`, `AbortController` | Bridge to OkHttp. Apply the manifest whitelist like the Even app does. | med |
| `WebSocket` | Bridge to OkHttp WebSocket | med |
| `localStorage`/`sessionStorage` | SharedPreferences/DataStore per package_id | low |
| `IndexedDB` | Hard. A polyfill over SQLite (e.g., fake-indexeddb in memory plus persistence). | high |
| `TextEncoder/Decoder`, `URL`, `URLSearchParams`, `atob/btoa`, `crypto.getRandomValues`, `structuredClone`, `Blob`, `FileReader` | Small polyfills (core-js/whatwg-url) or host functions | low-med |
| Canvas 2D / `Image` decode / `OffscreenCanvas` | Host-implemented subset on Android Bitmap/Canvas | high |
| WebAssembly | Not available in QuickJS. V8 (J2V8/Javet) has it. [I] | engine choice |
| CSS, layout, `getBoundingClientRect`, `ResizeObserver`, fonts/`measureText` | Not available (linkedom has no layout) | cannot |
| React 18/19 / Vue 3 mounting | Usually works on linkedom for render/state. Effects that measure layout or rely on browser event dispatch may break. | test per app |
| `window.flutter_inappwebview.callHandler` + `window._listenEvenAppMessage` | Trivial host binding | low |

Realism [I]:
- For the typical Even Hub app, a QuickJS/V8 "lite runtime" is realistic: logic plus SDK calls plus fetch/WebSocket/timers/localStorage, where the DOM is only an optional settings page that nobody on the watch sees.
- It is fast (no process spawn) and tiny (under 1 MB for QuickJS).
- It is not a general solution:
  - Apps that render images through `<canvas>`, use WASM (image codecs, audio DSP, zstd), or depend on layout will fail or need per-API work.
  - QuickJS is an interpreter with no JIT, so CPU-heavy apps will be slow on Cortex-A53.
  - V8 via J2V8 or Javet gives a JIT and WASM for 12-24 MB compressed, but brings the same DOM/canvas gaps.
- A compatibility probe helps: load the app, record which globals and APIs it touches, and route it to the lite runtime, to GeckoView or to the phone.

---

## 4. Chromium-based and other bundled engines (2025-2026)

- **Crosswalk.** Dead. The last release was Crosswalk 23.
  - [R] https://evanshortiss.com/crosswalk-android-webview
  - [R] The pwnall ChromeView proof of concept is unmaintained: https://github.com/pwnall/chromeview
- **No maintained "bundled Chromium WebView" library.**
  - [R] Asked in the Cromite discussion whether Google offers this, the maintainer uazo said (2025-09-17): "No, google does not offer this feature by default. The closest thing seems to be that webview_instrumentation_apk".
  - Alternatives named there: "Crosswalk Native (Chromium 77-based)", Tencent X5 (China ecosystem, closed), and WebViewUpgrade (hooks, see §1.4).
  - https://github.com/uazo/cromite/discussions/1836
  - [I] Building Chromium's `android_webview`/content shell as your own AAR is possible in principle. It means a multi-GB Chromium build, per-ABI output of about 100 MB or more, and its own security-update treadmill.
- **Standalone Chromium browsers (Cromite, Samsung Internet)** run on Wear OS but are apps, not libraries (§1.5). [R]
- **WPE WebKit for Android (Igalia, `org.wpewebkit.wpeview:wpeview`)**:
  - [V] Version 0.3.3 (Maven, 2026-03-17). The AAR is 175 MB and contains **only arm64-v8a and x86_64**, so it will not run on a 32-bit watch userspace.
  - [R] Blog of 2026-09-11: rebuilt on WPEPlatform with a `WebView`-like Java API. Still called "a very valuable experiment". https://blogs.igalia.com/alex/2026/09/11/wpe-webkit-on-android-now-with-wpeplatform/ , https://github.com/Igalia/wpe-android
- **Servo**:
  - [R] v0.5.0 (2026-08-31) brought Android work (ServoView, Kotlin JNI). Nightly AARs exist for armv7 and aarch64.
  - [I] It is experimental and the embedding/JS-bridge API is immature.
  - https://github.com/servo/servo/releases/tag/v0.5.0 , https://book.servo.org/design-documentation/android.html
- **Conclusion** [I]: for a 32-bit Wear OS userspace in 2026, **GeckoView is the only maintained, production-used, embeddable full engine** with an armeabi-v7a build and a documented JS-to-native channel.

---

## 5. Pixel Watch 5 hardware

| Item | Value | Source |
|---|---|---|
| Announced / released | 2026-08-12 / 2026-08-20 | [R] GSMArena https://www.gsmarena.com/google_pixel_watch_5-14860.php ; Google blog |
| SoC | **Qualcomm Snapdragon W5 Gen 2 "Accelerated"** + "power-efficient dual-chip architecture"; "50% more RAM and a 12% CPU boost — making it 20% faster" | [V official] https://blog.google/products-and-platforms/devices/pixel/pixel-watch-5/ |
| Co-processor | Cortex-M55 | [R] Android Authority https://www.androidauthority.com/google-pixel-watch-5-launch-3695435/ |
| CPU / GPU | 4x Cortex-A53 @ 1.7 GHz; Adreno 702 | [R] GSMArena; 9to5Google spec leak https://9to5google.com/2026/08/04/pixel-watch-5-specs-leak-2/ |
| RAM | **3 GB** (was 2 GB) | [R] GSMArena, 9to5Google; consistent with Google's "50% more RAM" [V] |
| Storage | **64 GB eMMC** | [R] Android Authority; GSMArena |
| Battery (45 mm) | 465 mAh, "up to 40 hours" with AOD | [R] Android Authority; Google blog [V] for 40 h |
| Connectivity | LTE variants (41 & 45 mm), Wi-Fi 802.11 a/b/g/n/ac/6 dual-band, Bluetooth 6.0, NFC, UWB | [R] GSMArena, Android Authority, 9to5Google |
| OS | Wear OS 7 (Android 17) | [R] Android Authority/GSMArena; Wear OS 7 = Android 17 [V] https://android-developers.googleblog.com/2026/05/whats-new-wear-os-7.html |
| **Userspace ABI** | Pixel Watch 3 and 4: **32-bit (`armeabi-v7a,armeabi`)**; PW4 install of an arm64-only APK failed with `INSTALL_FAILED_NO_MATCHING_ABIS` (2026-09-10) | [R] https://github.com/JddAndrewLauren/hummingbird/pull/810 , https://github.com/aaronj1335/workout/pull/9 ; PW5: [I] likely identical, **verify** with `adb shell getprop ro.product.cpu.abilist` |
| Play 64-bit rule | From 2026-09-15 new Wear OS apps/updates with native code must include 64-bit libs "in addition to 32-bit versions"; Play keeps delivering 32-bit to 32-bit devices | [V] https://android-developers.googleblog.com/2026/04/get-your-wear-os-apps-ready-for-64-bit-requirement.html |

---

## 6. Phone fallback: transport options and throughput

### 6.1 Wear OS Data Layer (Play services) [V]

- Official docs:
  - https://developer.android.com/training/wearables/data/overview
  - https://developer.android.com/training/wearables/data/client-types
  - https://developer.android.com/training/wearables/data/data-items
  - https://developer.android.com/training/wearables/data/sync
- Paths:
  - Direct over Bluetooth ("a single encrypted channel between the devices ... managed by Google Play services").
  - Otherwise "Data is automatically routed through Google Cloud when Bluetooth is unavailable" (end-to-end encrypted).
- Same package name and signing key on both devices. Android phones only, not iOS.
- **MessageClient:**
  - RPC-style `sendMessage()` / `sendRequest()`, "best effort ... no built-in retry".
  - **No payloads over 100 KB.**
  - "Bluetooth preferred, but can use Wi-Fi if it's the only type of connection available". It needs a connected node.
- **ChannelClient:**
  - A "bidirectional communication pipe" with a continuous byte stream.
  - Supports more than 100 KB. Bluetooth preferred, Wi-Fi possible. No persistence.
  - Use cases include streamed voice data.
- **DataClient:**
  - Persistent and synced. DataItem payload is up to 100 KB, with Assets for larger data.
  - Non-urgent items "might delay up to 30 minutes", so call `setUrgent()`.
- Docs caution: "these are the only APIs you can use to set up communication between these devices. For example, don't try to open low-level sockets to create a communication channel."
- The project already uses it: Faceclaw's `wear/` companion speaks JSON over MessageClient (see research file C, §3).

### 6.2 Throughput and latency numbers

- **Bluetooth file transfer on Wear OS** [R]: "typically 50–200 KB/s" and "maxes at about 200 KB/s on watches". AnExplorer guide: https://anexplorer.io/transfer/watch-to-phone
- **Watch Wi-Fi** [R]: "expect 1–5 MB/s depending on the watch model". Pixel Watch 3 is listed at "3–5 MB/s". The watch is said to "throttle Wi-Fi aggressively to save battery". Same source.
- **ChannelClient** [R, search snippet only; the page blocks fetching]: "it took about ten seconds to transfer a 7 MB image", about 0.7 MB/s. The transport (BT or Wi-Fi) is unknown. https://medium.com/@dertefter/building-a-file-manager-for-wear-os-5f0445844f9b
- **BLE reference** [R]: about 90-100 kB/s with a phone on one end under good settings. https://punchthrough.com/maximizing-ble-throughput-on-ios-and-android/
- **Latency and handover pathologies** [R]: SIGMETRICS 2019, "Understanding the Networking Performance of Wear OS" (8 watches):
  - "E2E latency is dramatically inflated to 30+ seconds for high bitrate traffic" when internet traffic is proxied through the phone (phone TCP buffer bufferbloat).
  - "BT-WiFi handovers may take 60+ seconds".
  - "BT download experiences frequent 'blackout' periods".
  - https://dl.acm.org/doi/10.1145/3322205.3311074 (slides: https://pages.cpsc.ucalgary.ca/~carey/CPSC641/slides/measurement/WearOS-Sigmetrics19.pdf)
- [I] Expected MessageClient round trip for small messages over a healthy BT link is tens of ms, with spikes of hundreds of ms when the BT radio leaves sniff mode. No authoritative number was found, so measure it with a ping/pong over `sendRequest`.
- **Payload sizing** [I]:
  - A full 576x288 4-bit frame is 82,944 bytes raw and fits in one MessageClient message. After LZ4/zlib it is usually much smaller.
  - Text/list container JSON is under 2 KB.
  - At 50-200 KB/s over BT, an Even Hub page update costs about 1-2 messages and well under 1 s. Image-heavy apps at 5-10 fps are not realistic over BT; about 1-2 fps full-frame or partial rectangles only.

### 6.3 Direct sockets (Wi-Fi/LTE)

- Allowed as normal networking [V]. For "high-bandwidth network access ... request connectivity with a high-bandwidth transport, such as Wi-Fi" use `ConnectivityManager.requestNetwork(TRANSPORT_WIFI)` plus `bindProcessToNetwork()`. Release it afterwards. "Acquiring a network might not be instantaneous". https://developer.android.com/training/wearables/data/network-communication
- Watch to phone on the same LAN [R/I]:
  - Low-level sockets work on watches ([R] XDA "Sockets on WearOS", Galaxy Watch4).
  - They go against the Data Layer caution above.
  - They need both devices on the same Wi-Fi, or the phone hotspot, and discovery via NSD/mDNS.
  - While Bluetooth-connected, the watch's default network is the BT proxy through the phone, so bind the process to Wi-Fi explicitly. [V: docs say traffic "is generally proxied through the phone"]
- Over LTE [I]: both ends dial out to a relay (WebSocket/QUIC). Latency is roughly carrier RTT, 40-150 ms. Costs battery.

---

## 7. Recommendation order (what to try first)

**Step 0: facts on the device (half a day)** [I]
- Run `adb shell getprop ro.product.cpu.abilist` on the PW5.
- Check `pm has-feature android.software.webview` (expect false).
- Check free RAM in `dumpsys meminfo` with our app plus BLE running.

**Step 1: GeckoView spike on the watch (try first)** [I, built on the verified APIs in §2]
1. Add `org.mozilla.geckoview:geckoview-armeabi-v7a` and `-arm64-v8a` (156.x) with Play ABI splits.
2. Create `GeckoRuntime` with `fissionEnabled(false)` and `extensionsProcessEnabled(false)`.
3. Open one `GeckoSession`, headless, in a foreground service with an OngoingActivity. Call `setActive(true)` and `setPriorityHint(PRIORITY_HIGH)`.
4. Install a built-in extension with a `document_start` content script that defines `window.flutter_inappwebview.callHandler`, plus a native Port (§2.7).
5. Serve `dist/` from `127.0.0.1`.
6. Measure: cold start (runtime plus first page), PSS of all processes, timer cadence with the screen on, off and ambient, battery per hour, and survival after 30 minutes in the background.
7. Run 3 real Even Hub apps: one text app, one using canvas/images, and one React/Vue app.
8. **Go/no-go**:
   - Cold start of about 5 s or less.
   - Total PSS of about 300 MB or less.
   - No content-process kills while in the foreground service.
   - Acceptable battery drain.

**Step 2: "lite runtime" in parallel** [I]
- QuickJS (quickjs-kt; 0.7 MB) with `linkedom/worker`.
- Polyfills: timers, fetch, WebSocket, localStorage, TextEncoder, URL, crypto, and a minimal canvas bridge.
- Run apps whose probe shows no WASM, no layout and at most simple canvas.
- Benefits: instant start, tiny RAM (roughly 10-30 MB [I]), no extra processes.
- If apps need JIT or WASM, try **J2V8 6.3.4** (armeabi-v7a, 11.8 MB deflated) or **Javet V8 6.0.1** (24 MB).
- **Do not plan on Node-based jsdom or happy-dom**: Javet Node has no armeabi-v7a, and nodejs-mobile is Node 18 (EOL).

**Step 3: phone fallback (when to switch)** [I]
- Switch when any of these holds:
  - The app uses WASM, WebGL, rich canvas animation, getUserMedia/Web Audio or layout-dependent code, and GeckoView is not viable.
  - GeckoView fails the go/no-go.
  - The watch reports memory pressure (`onTrimMemory`, GeckoView `onKill`).
  - The user is on the phone anyway.
- How it works:
  - Run the app in an ordinary `android.webkit.WebView` in our phone companion, with the same bridge shim.
  - Relay SDK calls as JSON over **MessageClient**, and images over **ChannelClient** or MessageClient if under 100 KB.
  - The watch keeps BLE to the glasses and renders the containers.
  - Keep the phone in the loop only for app logic, and send deltas rather than frames.

**Not recommended** [I]:
- Bundling a Chromium build yourself.
- The WebViewUpgrade/`sWebViewSupported` reflection hack.
- WPE (no armeabi-v7a).
- Servo (experimental).

---

## 8. Source list (all accessed 2026-09-29)

Official Android / Wear OS:
- https://developer.android.com/training/wearables/wear-v-mobile
- https://developer.android.com/training/wearables/data/network-communication
- https://developer.android.com/develop/ui/views/layout/webapps/managing-webview
- https://developer.android.com/reference/android/content/pm/PackageManager (FEATURE_WEBVIEW)
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/webkit/WebViewFactory.java
- https://developer.android.com/training/wearables/data/overview
- https://developer.android.com/training/wearables/data/client-types
- https://developer.android.com/training/wearables/data/data-items
- https://developer.android.com/training/wearables/data/sync
- https://developer.android.com/training/wearables/ongoing-activity
- https://android-developers.googleblog.com/2026/04/get-your-wear-os-apps-ready-for-64-bit-requirement.html
- https://android-developers.googleblog.com/2026/05/whats-new-wear-os-7.html
- https://support.google.com/googleplay/android-developer/answer/9859372

Mozilla / GeckoView:
- https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/maven-metadata.xml (and per-ABI AARs/POM, v156.0.20260921121718)
- https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/geckoview-quick-start.html
- https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/web-extensions.html
- https://firefox-source-docs.mozilla.org/mobile/android/geckoview/contributor/geckoview-architecture.html
- https://firefox-source-docs.mozilla.org/mobile/android/geckoview/contributor/junit.html
- https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.html
- https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntimeSettings.Builder.html
- https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/content_scripts
- https://github.com/mdn/browser-compat-data (webextensions/manifest/content_scripts.json: `world` Firefox 128)
- https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Sharing_objects_with_page_scripts
- https://developer.mozilla.org/en-US/docs/Web/API/Window/setTimeout
- https://www.mozilla.org/en-US/MPL/2.0/FAQ/

Developer reports / apps:
- https://groups.google.com/g/ima-sdk/c/9ewcqM-vGZY
- https://github.com/Juliandev02/wearBrowsing/issues/1
- https://github.com/ankidroid/Anki-Android/issues/8992
- https://github.com/google/accompanist/issues/1673
- https://github.com/shubh72010/JusBrowse-Wrist
- https://github.com/ssndash/svinotaBrowser-WearOS
- https://play.google.com/store/apps/details?id=com.somyac.watch.browser
- https://play.google.com/store/apps/details?id=angel.gabaldon.wristweb
- https://9to5google.com/2022/12/16/wear-os-internet-browser-samsung/
- https://9to5google.com/2023/10/18/wear-os-4-browser-default/
- https://www.androidauthority.com/new-wear-os-browser-3494001/
- https://wear-internet-browser.en.aptoide.com/app
- https://xdaforums.com/t/official-list-of-sideloaded-apps-and-workarounds-for-wear-os-tested-on-galaxy-watch.4379825/
- https://github.com/JddAndrewLauren/hummingbird/pull/810
- https://github.com/aaronj1335/workout/pull/9

JS engines / DOM:
- https://github.com/dokar3/quickjs-kt
- https://github.com/HarlonWang/quickjs-wrapper
- https://github.com/cashapp/zipline
- https://bellard.org/quickjs/
- https://github.com/caoccao/Javet
- https://github.com/nodejs-mobile/nodejs-mobile/releases
- https://github.com/facebook/hermes/blob/main/doc/Features.md
- https://github.com/mozilla/rhino (it-android/README.md)
- https://github.com/WebReflection/linkedom
- npm registry: jsdom, happy-dom, happy-dom-without-node, linkedom, canvas, pureimage, @napi-rs/canvas, skia-canvas, canvaskit-wasm, @nativescript/canvas
- Maven Central metadata for all listed Android artifacts

Other engines:
- https://github.com/uazo/cromite/discussions/1836
- https://github.com/JonaNorman/WebViewUpgrade
- https://blogs.igalia.com/alex/2026/09/11/wpe-webkit-on-android-now-with-wpeplatform/
- https://github.com/Igalia/wpe-android
- https://github.com/servo/servo/releases/tag/v0.5.0
- https://book.servo.org/design-documentation/android.html
- https://evanshortiss.com/crosswalk-android-webview

Hardware / transport:
- https://blog.google/products-and-platforms/devices/pixel/pixel-watch-5/
- https://www.gsmarena.com/google_pixel_watch_5-14860.php
- https://www.androidauthority.com/google-pixel-watch-5-launch-3695435/
- https://9to5google.com/2026/08/04/pixel-watch-5-specs-leak-2/
- https://anexplorer.io/transfer/watch-to-phone
- https://dl.acm.org/doi/10.1145/3322205.3311074
- https://punchthrough.com/maximizing-ble-throughput-on-ios-and-android/
