# C. Faceclaw app model, extension API, Wear protocol, remote/network mechanisms, throughput

Source: Faceclaw clone (https://github.com/jimrandomh/faceclaw, HEAD 2052e26, 2026-09-28; CHANGELOG says 0.8.0 released 2026-09-26, 0.8.1 unreleased).
Paths below are relative to that clone unless prefixed with `ER-G2-custom:`. `KT/` abbreviates
`native/kotlin/shared/src/commonMain/kotlin/com/faceclaw/app/`.

Legend: **[V]** = verified by reading code/docs at the cited lines. **[I]** = inferred (reasoned from code, not stated or not measured).

---

## 0. Big picture (one paragraph)

Faceclaw is a phone-hosted window system for the G2. Everything is **rendered on the phone**:
apps paint 8-bit grayscale `GrayImage`s (0 = transparent, quantized to 4 bpp on the wire) plus a
list of "deferred draws" (glyphs, icon images, retained display lists). A Kotlin compositor keeps
per-window surfaces, composites a 640x480 frame, diffs it against what the glasses hold, and ships
the delta over BLE as firmware draw calls (RLE pixel boxes + cached-image/glyph draws + a "root
display list" that the firmware replays, which enables glasses-side depth and animation since
0.8.0). There are three kinds of "app": (a) **compiled-in native apps** (TypeScript, in
`app/apps/*`, registered statically), (b) **EvenHub web apps** (Even Realities' official SDK; HTML/JS
in a phone WebView, loaded from `.ehpk` packages, the official store, or **a live http(s) URL**),
optionally using the **`faceclaw-extensions`** JS API; (c) nothing else - there is no dynamic
native-app loading. Remote/network mechanisms: a token-authenticated **TCP JSON input port
(8791)**, a dial-out **WebSocket assistant bridge** where the phone is an **MCP server**, the
**g2mirror** WebSocket terminal protocol, and the **Wear OS Data Layer** remote.

---

## 1. Native Faceclaw apps

### 1.1 Definition and registration [V]

- `AppDefinition` (`app/apps/app-definition.ts:16-41`): `{ appId, title, icon, renderIcon?,
  launch(ctx, params?), boot?(ctx), showInLauncher?, openSharedText?, glanceboard? }`.
  Doc comment: "Each app directory's index.ts default-exports one of these ... Meant to grow into the
  sole export of an app package (the boundary for sandboxing and dynamic loading)" (`:9-15`) - i.e.
  dynamic native loading is a stated future goal, not implemented.
- `AppContext` (`app-definition.ts:82-112`) is "deliberately the only channel from app definitions
  back into the controller": `appId, apps, actions (LayerActions minus requestRender), launchApp,
  uninstallApp, launchInProcessApp(windowId, surfaceId, create), ensureWorkerHost(createWorker),
  submitWindowFrame(surfaceId, planes, paintMs, frameId), setWindowSurfaceVisible,
  requestShellRender, appendLog, setTextEditorHost`.
- Registry: `ALL_APPS` static array in `app/apps/all-apps.ts:34-61` ("The sole registry: the
  controller, launcher, and assistant tools all discover apps here"). 26 entries: launcher,
  ai-chat, timer, calculator, terminal, files, music, nightscout, transcribe, teleprompter,
  microphones, notifications, calendar, weather, navigate, compass, roam, blocks, minesweeper,
  freecell, pinball, flappy, developer, evenhub, glanceboard, settings.
- Installed EvenHub packages are added to the launcher/assistant dynamically from a JSON index in
  the settings store, *not* via `ALL_APPS` (`app/apps/evenhub/installed-apps.ts:2-7`; app id
  `installedEvenHubAppId(packageId)` `:40`).

### 1.2 Two hosting models [V]

1. **In-process (main JS thread)** - e.g. calendar (`app/apps/calendar/index.ts:5-10`), nightscout
   (`app/apps/nightscout/index.ts`), developer, evenhub store:
   `launch: ctx => ctx.launchInProcessApp(WINDOW_ID, SURFACE_ID, createXxxWindow)`.
   `createInProcessWindow(options)` (`app/ui/shell/in-process-window.ts:84`; options `:~8-55`):
   `appId, windowId, title, iconLetter, icon?, drawIcon?, closeable, heightMode?, menuItems?()
   (tap-then-hold context menu), holdToTalk?, actions, receiveTextInput?, onFocus?, baseLayer,
   submitFrame(planes, paintMs, frameId), setSurfaceVisible, removeSurface?, reconfigureSurface?,
   onClosed?`. Returns `{ window: ShellWindow, stack: LayerStack, requestRender, setHeightMode }`.
2. **Worker-hosted (one Web Worker per app, possibly many windows)** - flappy, pinball, blocks,
   minesweeper, freecell, roam, navigate, terminal (`*.worker.ts`). `launchWorkerAppWindow(ctx,
   {createWorker, windowId, title, iconLetter, icon, matchExistingBy?, acceptsDirectional?})`
   (`app-definition.ts:128-168`); e.g. `app/apps/flappy/index.ts:3-16`.
   The shell<->worker protocol is small JSON over postMessage (`app/ui/shell/worker-window.ts:12-165`):
   - host->worker `WorkerAppMessage`: `check-idle, shutdown, navigation-sensors, open-window
     {windowId, surfaceId, title, viewport}, resize-window, close-window, input {windowId, event,
     frameId, focused}, text-input {text, submit?}, render, foreground, input-focus, screen {on},
     tool-call {callId, windowId, name, args}`.
   - worker->host `WorkerAppReply`: `worker-idle, worker-stopped, buzzer-sequence {payload},
     navigation-sensors, open-url, surface-frame {surfaceId,w,h,pixels,draws?} (iOS only), worker-ready,
     yield-focus, focus-window, wake-window, open-window-request {…, heightMode?}, set-title,
     set-attention, set-icon-activity, start-voice-input, close-window-request, open-system-menu,
     set-window-gestures {hasAppMenu, claimsLongPress}, open-settings, start-text-setting-edit,
     end-text-setting-edit, set-tray-icon {w,h,pixels[]}, set-tools {tools: ToolSpec[]},
     tool-result, publish-state {key, state}`.
   - Pixels do **not** cross postMessage on Android: the worker calls
     `getActiveDisplay().submitSurfaceFrame(buffer, surfaceId, x, y, w, h, fingerprint, paintMs,
     frameId, draws)` straight into Java (`app/native/active-display.ts`; flappy `:772-780`).
   - Note (for our spec): `notes/mentraos-compat-assessment.md:55-60` explicitly calls this
     worker protocol the natural seam for hosting external apps ("apps are already isolated
     contexts speaking a small typed JSON vocabulary ... with pixels out-of-band"). **[V]** (their
     note) / its suitability as a network protocol is **[I]**.

### 1.3 Lifecycle [V]

- `boot(ctx)` once at shell startup (launcher pins its window; nightscout starts its tray icon,
  `nightscout/index.ts`); `launch(ctx)` opens or focuses a window.
- `ShellWindow` (`app/ui/shell/shell.ts:79-166`) callbacks: `handleInput(event, frameId)`,
  `requestRender`, `relayout?`, `hitTest?(x,y)` (phone mirror touch), `receiveTextInput?(text,
  {submit})`, `setForeground?`, `onFocus?(lastInput)`, `setScreenOn?`, `setInputFocus?` (also
  covers overlays/screen-off so games pause), `close?`, flags `closeable, hasAppMenu?,
  claimsLongPress?, holdToTalk?, acceptsDirectional?, heightMode`.
- Worker lifecycle: `open-window` -> worker paints and submits; `foreground`/`screen`/`input-focus`
  messages; last window closed -> `check-idle` -> `worker-idle` -> `shutdown` -> `worker-stopped`
  (`worker-window.ts:17-37`). Open apps are persisted/restored (`app/ui/shell/open-apps-persistence.ts`).
- EvenHub apps get FOREGROUND_ENTER/EXIT sys events (4/5) and, via extensions, `windowLifecycle`
  visible/hidden/focused/blurred (`app/apps/evenhub/session.ts:604-608`).

### 1.4 Windows, layers, geometry [V]

- Screen 640x480 per lens (`app/graphics/image.ts:7-8`). Shell chrome: sidebar strip 64 px
  (`app/ui/shell/geometry.ts` `SIDEBAR_WIDTH=64`), top bar 28 px (`TOP_BAR_HEIGHT`). Window height
  modes `min` (288 px band incl. top bar -> 576x260 content), `medium` (316 px band -> 576x288
  content = EvenHub raster), `max` (full 480 -> 576x452). Display-mode setting `576x288`,
  `576x480`, `640x480` (in 640x480 the sidebar overlays, content 640 wide).
  `appViewportSize()` `geometry.ts:~138-143`. Vertical position of the band is a user setting.
- `Layer` interface (`app/ui/layers.ts:82-117`): `paint(ctx, paintBelow): GrayImage`,
  `handleInput(event, ctx)`, optional `depth`, `paintParts()`, `dimUnderneath`, `acceptsDirectional`,
  `hitTest`, `onRemoved`, `receiveTextInput`. `PaintBelow` (`:67`) = "keep the layers below visible";
  each layer becomes its own plane.
- `LayerStack` (`layers.ts:119+`): push/pop/popIfTop/clearToBase/popThrough; base layer never popped.
- `LayerActions` (`layers.ts:12-39`): `requestRender, disconnect, startTextSettingEdit(s)` (phone
  keyboard form), `endTextSettingEdit, startVoiceCapture(endpointing?), stopVoiceCapture,
  start/stopContinuousVoiceCapture, playBuzzerSequence(payload)`.
- `Plane` (`app/graphics/plane.ts:14-25`): `{ image, x, y, depth?, dimUnderneath?, shellKey? }`;
  frames are arrays of planes composited in order; raster value 0 transparent, 1 = black.
- Shell extras: tray icons in the top bar (`shell.setTrayIcon(ownerId, GrayImage|null)`,
  `shell.ts:510`), attention flags, notifications modal, system menu (long-press), app context
  menu (tap-then-hold), voice dialog, keyboard dialog, assistant overlay
  (`shell.sendToAssistant` `:1266`, `sendTextToForegroundWindow` `:1113`).

### 1.5 Drawing API [V]

`GrayImage` (`app/graphics/image.ts:143+`): `clear, setPixel, getPixel, fillRect, drawRect,
drawLine, drawText(font,x,y,text,value), drawTextWrapped, drawGlyph, drawImage(source,x,y)`
(deferred, becomes on-glasses cached image when possible), `drawMenuSelection(...)` (retained
highlighted row with depth/animation), `drawDisplayList(list,x,y,w,h,depth)` (`:541`),
`drawDepthImage(source,x,y,depth,masked)` (`:547`), `dimmed(f)`, `withDrawsBaked()`, `fingerprint()`.
Values are 0..255 gray; only 16 levels survive (4 bpp).

Menus: `Menu<T>` core (`app/ui/menu-core.ts:100`, `MenuOptions` `:51`) - vertical list, variable row
heights, unselectable rows, pixel scrolling, glasses-side animated scroll/bounce via display lists
(`:79-98`); `MenuLayer` + `MenuItem {label, onSelect(ctx), disabled?, description?, render?}`
(`app/ui/menu.ts:72, 203`); `MenuLayout {depth?, x|'center', y, width, showBorder, min/maxHeight,
footer, dimUnderneath, opaque, squareCorners}` (`menu.ts:~19-45`). Animation durations: highlight
240 ms (`app/ui/menu-highlight-motion.ts:3`), bounce 320 ms (`app/ui/menu-scroll-motion.ts:7`).
Icon grid (`app/ui/icon-grid.ts`), window-menu, input-dialog, modal-layer in `app/ui/shell/`.

### 1.6 Fonts [V]

- UI fonts: bitmap BDF faces `terminus`/`terminusv` or user TTF files at chosen size, three roles
  (small/medium/large) + separate terminal font (`app/graphics/ui-fonts.ts:1-40`; bundled
  `app/fonts/{terminus,terminusv,source-han-sans,ttf}`). All rendered **on the phone**.
- On-glasses text: glyph rasters registered in a Kotlin `GlyphAtlas`; firmware **font
  resources** hold 96 glyph slots (ASCII 32..127) with u16 offsets, each glyph a cached image
  (`KT/graphics/FontResourceAtlas.kt:3-38`); the `TEXT` draw op draws a run from such a font. Only
  ASCII qualifies (`app/graphics/glyph-wire.ts:~68-77` `representableGlyph`: encoding 32..127).
- Firmware built-in font strings: `STOCK_FONT_STRING` op (UTF-8, <=255 bytes) using the stock
  20 px EvenHub/LVGL fonts (`KT/g2protocol/DrawProtocol.kt:161-170`, `TexturePlanner.kt:354-409`).
  Phone-side replication font is extracted from the stock firmware image at install time
  (`app/g2/firmware-fonts.ts:1-9`, not redistributed).
- Everything else (non-ASCII in UI fonts, arbitrary raster) is baked into pixels.

### 1.7 Input events [V]

`InputEvent` (`app/ui/gestures.ts:24-76`), each with `timestampMs` and optional raw `ringInput`:
`ring-press` (touch-down; ring or watch), `click`, `double-click`, `scroll-up`, `scroll-down`,
`long-press`, `long-press-release`, `short-then-long-press` (tap-then-hold = app context menu),
`system-menu-opened`, `swipe-up/down/left/right` (watch only), `display-wake`, `wakeword`
("Hey Even"), `unknown`. Sources: `ring | left-arm | right-arm | watch` (`:9`); firmware source codes
`TOUCH_EVENT_FROM_GLASSES_R=1, RING=2, GLASSES_L=3, WATCH=4` (`app/g2/events.ts:2-11`).
`directionalFallback` maps swipes to scroll/click/double-click for layers without
`acceptsDirectional` (`gestures.ts:132-146`). `PressTracker` pairs a `ring-press` with the gesture it
starts (`:95-112`).
- IMU: accelerometer x/y/z as sys-events after an ImuCtrl enable (report "pace" codes 100..1000,
  not Hz), marked experimental/unreliable (`app/native/imu.ts:1-35`,
  `app/apps/developer/accelerometer-demo.ts:9-14`; decode `KT/g2protocol/G2Event.kt` sysEvent field 3).
- Compass (CFW msg 10), ambient light (16), ring battery (17) (`KT/g2protocol/CfwMessageType.kt`).
- Head-up / wear detection / display wake via firmware patches (`app/g2/firmware/cfw-patches.ts`
  descs `:124-160, :184-190`).
- Voice: glasses mic (LC3) -> STT providers (onboard Moonshine/Whisper, ElevenLabs, Soniox,
  OpenAI) via `LayerActions.startVoiceCapture`; watch mic via Wear system speech -> text.
- Raw ring metadata (tick, type, aux, speed) via Faceclaw/19 SysEvent field 100 (`G2Event.kt`).

### 1.8 Glanceboard / widgets / settings [V]

- `GlanceboardProvider` on one AppDefinition (`app-definition.ts:44-70`): sleep-time board shown
  on tap/hold/head-tilt while the shell is asleep; `size`, `depth()`, `createBoard(requestRender)
  -> {start, stop, paint(): GrayImage}`.
- `GlanceWidget {start(requestRender), stop(), paint(image)}` in slots (first layout = four
  288x144 quadrants; `tall` widgets span two) (`app/apps/glanceboard/widget.ts`); ids
  `system-card | nightscout | compass | music | calendar | terminal`.
- Settings: typed `ConfigSettingBoolean/Enum/String` objects with `id, label, storageKey,
  defaultValue, description` (`app/ui/dashboard-settings.ts:102, 128, 189`); the Settings app's
  sections are a hard-coded list (`app/ui/dashboard/settings-menus.ts:92-98`). Apps can deep-link
  (`AppLaunchParams.section`). Storage: settings store (SharedPreferences), shared across isolates.

### 1.9 `developer/load-app.ts` = dynamic loading of **EvenHub web apps only** [V]

- `LoadAppFromUrlLayer` (`app/apps/developer/load-app.ts:21-114`): opens the phone text editor on
  `developerAppUrlSetting`, validates `isLoadableAppUrl` (http/https only), calls
  `openEvenHubUrl(ctx, url)`; draft persists "so reloading after an edit-and-rebuild is one click".
- `LoadAppFromQrLayer` (`:121-205`): phone camera QR scan -> same.
- Both route to `launchUrl` (`app/apps/evenhub/manager.ts:165-174`): synthesizes a manifest
  (`packageId = dev.url.<host+path>`, version `dev`, **all permissions granted**: network,
  location, g2-microphone, phone-microphone) (`:186-208`); reloading the same URL replaces the
  running instance. `normalizeAppUrl` accepts bare `example.com/app` as https (`:177-183`).
- Entries in Developer app: `developer-app.ts:104-124`. Other loaders: Files app opens `.ehpk`
  (`app/apps/files/index.ts:65`), EvenHub store downloads via Even's private store server
  (details omitted, see ../05_EvenHub-Apps.md §7).
- So "apps" loadable at runtime = HTML/JS served over the network or packed as `.ehpk`; code runs
  in the **phone WebView**, not on the glasses. No native/TS app loading. **[V]**

### 1.10 EvenHub host (the de-facto third-party app platform) [V]

- `.ehpk` format (`app/apps/evenhub/ehpk.ts:1-11, 80-122`): magic `EHPK`, records `0xE4` file /
  `0xE5` dir / `0xE3` footer, each entry zstd-compressed and XOR'd with "EVEN REALITIES"; trailing
  SHA-512 integrity (not a signature, not verified). `app.json` manifest: `package_id, name,
  version, entrypoint (default index.html), permissions (network whitelist not enforced),
  privacy link` (`ehpk.ts:31-45, 135-158`).
- Runtime: each app = own glasses window + persistent WebView, concurrent, keeps running in
  background (`manager.ts:1-13`). Phone UI optional overlay.
- JS bridge (`session.ts:1-18, 79-223`): `flutter_inappwebview.callHandler('evenAppMessage',
  json{type:'call_even_app_method', method, data})`; host->web via
  `window._listenEvenAppMessage({type:'listen_even_app_data', method:'evenHubEvent', data:{type:
  sysEvent|textEvent|listEvent|audioEvent, jsonData}})`. Injected host-driven timers/rAF so apps run
  with the phone screen off (`:108-120`).
- Methods handled (`session.ts:794-846`): `createStartUpPageContainer, rebuildPageContainer,
  textContainerUpgrade, updateImageRawData (PNG or BMP incl. 1-bit; :1519-1536), shutDownPageContainer,
  set/getLocalStorage, getUserInfo, getGlassesInfo (faked), audioControl (G2 mic PCM, foreground
  only; mic-router.ts:1-14), getAppLocation/start/stopAppLocationUpdates, imuControl`;
  album/camera pickers return null.
- Page model (`containers.ts:9-80`): text / image / list containers with x,y,w,h, border, padding,
  zOrderIndex, isEventCapture; list selection host-local; contextual menu <=10 items, name <=32 B;
  stock limits 8 text / 4 image / exactly 1 event-capture container (`session.ts:876-891`).
  Canvas 576x288 composited on the phone (`compositor.ts:1-27`), text in Even's 20 px font; 5 text
  brightness levels.
- Event constants: CLICK 0, SCROLL_TOP 1, SCROLL_BOTTOM 2, DOUBLE_CLICK 3, FOREGROUND_ENTER 4,
  FOREGROUND_EXIT 5, SYSTEM_EXIT 7, IMU_DATA_REPORT 8, LONG_PRESS 9, LONG_PRESS_RELEASE 10
  (`session.ts:69-80`).

### 1.11 Glasses-side display list (protocol revision 35, since 0.8.0) [V]

Required firmware revision: `REQUIRED_FACECLAW_FIRMWARE_VERSION = 35` (`app/g2/firmware-compat.ts:29`);
newer revisions accepted ("revisions only add to the contract", `:99-100`).

**CFW message types** (`KT/g2protocol/CfwMessageType.kt`): BUZZER 5, DIAGNOSTICS 7, COMPASS 10,
CLEANUP 11, AMBIENT_LIGHT 16, RING_BATTERY 17, UPLOAD_RESOURCE 21, EVICT_RESOURCE 22, PANEL_READ/WRITE/
PATTERN 23-25, **DRAW_CALLS 26, SET_ROOT_DISPLAY_LIST 27, PRESENT 28, CREATE_SURFACE 29**,
BRIGHTNESS 30; flag bit 128 LENSES_DIFFER. (3,6,8,9,15,19,20 are retired wire IDs still used as
phone-internal optimizer record formats.)

**Draw ops** (`KT/g2protocol/DrawCallType.kt`): BOUNDING_BOX 1 (RLE pixels), RECT_COPY 2,
STOCK_FONT_STRING 3, IMAGE 4, TEXT 5, REMAP_COLORS 6 (16-entry LUT for even/odd checkerboard,
used for dimming/dither), DISPLAY_LIST 7 (play a list resource), ROUNDED_RECT 8, CLEAR 9.

**Call header** (`DrawProtocol.kt:41-59`): `u8 op, u8 flags, [u16 target if flag 1],
[s8 depth if flag 2], [s16 x, s16 y, u16 w, u16 h clip if flag 4 (rev 35)], args`.
Flags: RESOURCE_TARGET 1 (draw into an image resource instead of screen), DEPTH 2, CLIP 4
(`DrawFlags.kt:8-12`). Special resource ids: SCREEN 65535 (the uncomposed frame), CURRENT 65534.

**Args**:
- ROUNDED_RECT: `ext x, ext y, u16 w, u16 h, u16 radius, u8 bg(0-15), u8 border(0-15 | 16 = none)`
  (`DrawProtocol.kt:70-90`).
- IMAGE: `u16 resource, ext x, ext y, u8 options` (options: low 4 bits brightness mask, 16
  transparent, 32 inverse) (`:108-122`, `DrawFlags.kt:18-20`).
- RECT_COPY: `u16 source, ext x, ext y, u16 w, u16 h, ext dx, ext dy` (`:124-142`).
- CLEAR: `u8 color` (whole target or clip) (`:148-151`).
- TEXT: `u16 font, ext x, ext y, u8 options, u8 len, bytes` (`:176-187`).
- STOCK_FONT_STRING: `s16 x, s16 y, u8 options, u8 len, utf8` (`:161-170`).
- DISPLAY_LIST: `u16 list id` (nesting; clip/depth inherited) (`:189-190`).
- REMAP_COLORS: `u16 x,y,w,h, 8 B even LUT, 8 B odd LUT` (rev 33) (`:193-204`).
- BOUNDING_BOX: `u8 flags, compact (x/4,y/2,w/4,h/2 u8) | u16 x,y,w,h, RLE` where RLE token
  `(run<<4)|color` for runs <=15, else `color, u8 run` or `color, 0, u16 run` (`:219-271`).
- Sequences: `u16 count, (u16 len, call)*` (<=4096 calls); DRAW message = `[26] + sequence`,
  list resource = `[type 2] + sequence`, ROOT = `[27] u16 id`, PRESENT = `[28]` (`:92-106`).
  Messages split at 65535 B (`:335-354`).

**Numbers & animation** ("ext" values): extended varint (1-5 bytes, prefix-coded) or `0xFF,
varint len, bytecode` expression (`app/graphics/draw-expression.ts:16-24, 102-108`). Stack VM
(<=32 entries, <=900 bytes code): I32, F32, DUP/DROP/SWAP, int/float add/sub/mul/div/mod/neg/min/max,
I2F/F2I, `TIME` (clamped ms since PRESENT; marks animation pending), LERP, SMOOTHSTEP,
EASE_{IN,OUT,IN_OUT}_{QUAD,CUBIC}; `ELAPSED` (128) is bridge-only, bound natively before sending
(`draw-expression.ts:1-10, 49-100, 111-160`; Kotlin `KT/g2protocol/DrawExpression.kt:3-35`
`DrawValue.animate(from,to,durationMs)`). So x/y of rects, images, text, rect-copies can be
**time-animated on the glasses without further BLE traffic**. Width/height/colors are not
expressions (fixed ints) [V from grammar].

**Depth (stereo z)**: per-call s8 depth shifts the image horizontally per lens: left `floor(d/2)`,
right `-floor((d+1)/2)` (`DrawProtocol.kt:65-68`); positive = nearer (`app-definition.ts:57`).

**Resources** (`KT/g2protocol/ResourceCacheState.kt:20-33`): firmware arena `CACHE_SIZE = 196608` B
(192 KiB) incl. a 512-entry table; `RESOURCE_COUNT = 512`, `MAX_RESOURCE_SIZE = 65536`, 16 B block
header + 4-byte alignment; types IMAGE 0, FONT 1, DISPLAY_LIST 2; image flags LARGE 4 (u16 w/h),
RLE 8. Raw image = `[flags][w][h] + 4bpp packed ((w+1)/2 per row)`. Upload message `[21] u16 n,
(u16 id, u32 total, u16 offset, u16 count, bytes)*`, evict `[22] u16 n, u16 id*`, create-surface
`[29] 1 0 u16 id + w/h` (`:117-161`). Phone-side LRU mirrors firmware residency.

**Frame path** (`KT/g2protocol/ScenePlanner.kt:15-57`): per frame -> upload/evict resources ->
pixel deltas as BOUNDING_BOX calls in 32-row bands (`:58-75`) or TexturePlanner cached draws ->
build root display list = `screenCopy (RECT_COPY SCREEN)` + shell surface IMAGE calls with depth +
retained selections/lists (`KT/graphics/ShellScene.kt:17-32`) -> SET_ROOT_DISPLAY_LIST -> PRESENT.
The firmware then re-renders the root list on its own timer while expressions are pending
(reference player re-renders every 45 ms: `KT/graphics/DisplayListPlayer.kt:3-41`; actual firmware
tick rate **[I]** - not stated in this repo; firmware source is in jimrandomh/g2flash).

**TS->Kotlin bridge record** (not firmware wire): `encodeDisplayList` in
`app/graphics/display-list.ts:69-111` - `[7] u32 len, s16 x, s16 y, u16 w, u16 h, s8 depth, u32 token,
u32 elapsed, u16 nres, (u16 w,u16 h, gray8 pixels)*, u16 ncalls, (u8 op|128clip, s16 depth, [clip],
args)*`; bridge-only op DRAWS 32 replays glyph/icon records (glyph record 12 B, image record 9 B;
`glyph-wire.ts:28-31`). Kotlin parses it in `KT/graphics/FrameDisplayList.kt`. Presentation record
kinds `GLYPH 0, TEXTURE_IMAGE 1, FIRMWARE_TEXT 2, MENU_SELECTION 3, TRANSPARENT_IMAGE 4,
MASKED_IMAGE 5, DISPLAY_LIST 7` (`app/graphics/presentation-wire.ts:4-13`).
Our vendored core has the Kotlin half (e.g. `ER-G2-custom:faceclaw-core/src/commonMain/kotlin/com/faceclaw/app/g2protocol/DrawProtocol.kt`,
`.../net/RemoteInputSession.kt`) **[V: files exist]**.

---

## 2. `faceclaw-extensions/` - JS extension API for EvenHub apps [V]

- Not a native plugin system: an npm package (`faceclaw-extensions` 0.1.0, MIT, type-only + tiny
  helper) for **EvenHub web apps** to detect Faceclaw (`faceclaw-extensions/README.md:1-25`,
  `package.json`). Single injected global `window.getFaceclawExtensions` (undefined outside
  Faceclaw). "New capabilities are only ever added" (`README.md:24-25`).
- API (`faceclaw-extensions/src/index.ts:182-262`, README table `:73-85`): `getVersion()`,
  `returnToAppSwitcher()`, `quit()`, `addWindowLifecycleListener(visible|hidden|focused|blurred)`,
  `getConfiguredApiKeys()` / `requestApiKeyAccess(["openai"|"anthropic"|"soniox"|"elevenlabs"|
  "mapbox"])` (glasses consent prompt), `playBuzzer([{freq, duty?, ms}])`,
  `addCompassListener(reading)` (raw/magnetic/true heading), `createLayout/replaceLayout(layout)`
  (**extended layout: full 576x452 area, no container-count limit, `preserve` by name**),
  `setAssistantTools([{name, description, parameters (JSON schema), availability: open|foreground,
  handler}])` -> tools appear to the assistant as `app.<packageName>.<name>`.
- Host implementation: injected script `buildFaceclawExtensionsScript` (`app/apps/evenhub/session.ts:237-336`)
  - RPC via `__faceclawEvenHub.postMessage("faceclawExt", JSON[method,...params], id)`,
  replies `__fcExtResolve`, events `__fcExtEvent`, tool invocations `__fcExtInvokeTool` ->
  `faceclawExtToolResult`. Host dispatch `dispatchExtension` (`session.ts:1028-1052`).
- Packaging/loading: the app is a normal EvenHub app (`evenhub pack` -> `.ehpk`, or served from a
  URL); the extension lib is just imported at build time.

---

## 3. `wear/` - Faceclaw's own Wear OS companion [V]

- Role: **remote control + status mirror only; talks to the phone, never to the glasses**
  (`wear/README.md:3-6`, `wear/PROTOCOL.md:127-132`). Wear OS 3+ (API 30), Kotlin + Compose.
- UI: full-screen touch pad (tap=select, double-tap / two-finger tap = back, hold = system menu,
  tap-then-hold = app menu, 4-way swipes = spatial nav, crown = prev/next, two-finger swipe = page,
  experimental accelerometer "tip taps"), side buttons, tray with Assistant / Type into app / Apps /
  Status (`README.md:10-49`). Ambient mode, wake-tap handling (`:50-73`).
- Transport: **Wearable Data Layer** (Play services), same applicationId `com.faceclaw.app` and
  signing key required; UTF-8 JSON payloads; capabilities `faceclaw_phone` / `faceclaw_watch`
  (`PROTOCOL.md:127-136`). Phone side `App_Resources/.../FaceclawWearBridge.kt`,
  `app/native/wear-bridge.ts`, `app/g2/wear-remote.ts`; watch side
  `wear/app/src/main/kotlin/com/faceclaw/wear/Protocol.kt`, `PhoneLink.kt`.
- Watch -> phone (MessageClient) (`PROTOCOL.md:141-155`):
  - `/faceclaw/input {gesture, steps?}` gestures: click, double-click, scroll-up/down (steps 1-12),
    long-press, long-press-start, long-press-release, short-then-long-press, wakeword,
    swipe-up/down/left/right.
  - `/faceclaw/press` (no payload) - **touch-down, added in 2052e26**: each first-finger touch-down
    or stem-button down, delivered as `ring-press` source `watch` ahead of the gesture; no `seq`,
    never acked, only sent when mirrored state says connected + screen on
    (`PhoneLink.kt` `sendPress()`; phone `wear-remote.ts` handles `WEAR_PATHS.press` ->
    `injectInput("ring-press")` if remote enabled and phase connected/charging). Used by Flappy/
    Pinball to act on touch-down (`app/ui/gestures.ts:26-32, 88-112`).
  - `/faceclaw/command {command,...}`: launch-app {appId}, focus-window, close-window, sidebar,
    wake, sleep, lock, unlock, connect, disconnect, close-assistant, display-mode {576x288|576x480|
    640x480}.
  - `/faceclaw/assistant {text}`, `/faceclaw/text {text}` (<=2000 chars, `wear-remote.ts` MAX_TEXT_LENGTH),
    `/faceclaw/state/request`, `/faceclaw/battery {battery, charging}`.
  - All except state/request, battery, press carry `seq` and get `/faceclaw/ack {seq, ok, jsReady,
    message}`.
- Phone -> watch: `/faceclaw/ack`, `/faceclaw/battery/request`, `/faceclaw/event` (`{type:
  "assistant", phase thinking|streaming(<=4/s)|done|error|closed, text}`, `{type:"alert", text}`),
  and DataClient item `/faceclaw/state` (JSON: protocol 1, version, phase, connected, screenOn,
  locked, worn, listening, battery, charging, silentMode, foreground, windows[], apps[],
  displayMode, remoteEnabled, crownClockwiseNext, canUnlock, mirrorAssistant,
  assistantAvailable), re-sent on change and at least every 30 s (`PROTOCOL.md:157-206`;
  `wear-remote.ts:43, 97-101`).
- No glasses frame/pixel mirroring to the watch (grep of `wear/app` finds none) **[V]**.

---

## 4. Network / remote input and control

### 4.1 Input tokens + `faceclaw-input` CLI (new in 0.8.0) [V]

- Docs `scripts/faceclaw-input.md`; CLI `scripts/faceclaw-input.cjs` (Node 18+, interactive mode via
  Enquirer editor `faceclaw-input-editor.cjs`); server: `app/remote/{protocol,service,listeners}.ts`
  + `KT/net/RemoteInputSession.kt` (shared Android/iOS socket server) + Android
  `App_Resources/Android/src/main/java/com/faceclaw/app/FaceclawRemoteInput.kt`.
- Transport: **plain TCP, port 8791**, one newline-terminated UTF-8 JSON request per connection,
  one JSON reply line; frame <=64 KiB; 5 s read + 5 s dispatch deadline; one connection at a time
  per listening address (`faceclaw-input.md:132-162`; `app/remote/protocol.ts:58`;
  `RemoteInputSession.kt:207-212`). No TLS - relies on binding only to **127.0.0.1 and Tailscale
  (or a chosen tunnel) addresses**, never wildcard/LAN (`faceclaw-input.md:24-41`); USB via
  `adb forward tcp:8791 tcp:8791`.
- Auth: tokens `fc1_<64 hex>`, only SHA-256 hash stored, <=32 tokens, per-token permissions
  `input | text | assistant`, constant-time compare (`protocol.ts:78-118`). Listener runs only while
  >=1 token exists (`service.ts:37-44`).
- Requests (`protocol.ts:120-156`): `{"version":1,"token":…,"action":"input","gesture":…,
  "source":"watch"|"ring"}` (gestures: click, double-click, long-press, short-then-long-press,
  scroll-up/down, swipe-up/down/left/right; swipes need source watch), `{"action":"text","text",
  "submit"?}` (<=8000 UTF-16 units, rejected while locked, needs a text-accepting window),
  `{"action":"assistant","text"}`, `{"action":"ping","permission"?}`. Replies `{"ok":true}` or
  `{"ok":false,"error":bad_request|unauthorized|forbidden|locked|unavailable|failed|timeout,
  "message"}`.
- Gaps (relevant to us): **no `ring-press`/touch-down, no long-press-start/release, no commands
  (launch/focus), no output/state/display stream** in this API. Input only.

### 4.2 `app/g2/wear-remote.ts` [V]
Meaning layer of the watch protocol (section 3): validates kinds/commands (`:45-89`), injects
gestures into the same synthetic-ring path as phone test buttons, publishes debounced state
(150 ms debounce, 30 s refresh), streams assistant replies (>=250 ms interval), polls watch
battery every 5 min (`:91-101`). Host interface `WearRemoteHost` (`:104+`).

### 4.3 Assistant tools and remote agent bridge [V]
- `ToolRegistry` (`app/assistant/tool-registry.ts:1-90`): `ToolSpec {name, description,
  inputSchema, availability: always|installed|open|foreground, proactive?, timeoutMs?}` (default
  10 s), `ToolResult {ok, content?, error?}`. Apps contribute tools via worker `set-tools`
  (`worker-window.ts:142-156`, prefixed `app.<appId>.`) or EvenHub `setAssistantTools`.
- `window-tools.ts` (`:1-12, 79-264`): `apps.launch`, `apps.list_windows`, `apps.focus_window`,
  `apps.close_window`, `apps.list_folders`, `apps.move_to_folder`, `apps.remove_from_folder`,
  `apps.disband_folder`; re-registered when installed EvenHub apps change. `system-tools.ts`:
  `glasses.get_state`, `glasses.show_alert`, `calendar.list_events`, `media.*`, `notifications.*`.
- External mode: phone **dials out** a WebSocket `ws://host:port` (no TLS in code, `bridge-client.ts:158`)
  to a user-run bridge server; JSON frames with `chan: ctl|chat|mcp`: `ctl hello {version, token,
  deviceName, capabilities:["chat","mcp"]}`/hello-ack/ping/pong/error; `chat utterance {turnId,
  text, ctx}`, text-delta, tool-activity, turn-done/turn-error, cancel; `mcp {msg: JSON-RPC}` where
  **the phone is an MCP server** (`initialize`, `tools/list`, `tools/call`,
  `notifications/tools/list_changed`; protocol "2025-06-18") (`app/assistant/bridge-client.ts:1-26,
  155-190`; `mcp-server.ts:1-60`; design `notes/voice-assistant-design.md:287-340`). Reconnect
  backoff 1-60 s; proactive (out-of-turn) calls gated by `proactive: true` + setting + 6/min rate
  limit (`mcp-server.ts:17`).

### 4.4 Terminal app: **g2mirror, not SSH** [V]
- No SSH in the terminal code (grep). Connections are `g2mirror://<token>@host[:port]` (ws, default
  8737) or `g2mirrors://` (wss, 443) (`app/apps/terminal/connections.ts:1-60`). Client
  `app/native/g2mirror-client.ts` talks JSON over WebSocket to a PC-side `g2mirror-server` that
  wraps CLI apps (PTY); messages: `init {version, auth_token, device, width, height}`, `list`,
  `launch {command}`, `connect {socket}`, `view`/`unview`, `input {data: base64}`, `history {before,
  limit}`, `disconnect`; server -> `init, error, sessions, bell, activity, title, launched, connect,
  snapshot, output (raw VT bytes), history_lines, exit, disconnected` (`:180-490`). Protocol doc is
  outside the repo (`../experiments/g2mirror/PROTOCOL.md`). The phone runs the xterm emulator and
  renders; the PC ships terminal bytes, not pixels. Repaints coalesced to ~30 fps
  (`terminal-app.worker.ts:1794`).

### 4.5 Other servers / mirroring [V]
- Only inbound listener in the phone app is the 8791 input port (grep for ServerSocket etc. hits
  only `FaceclawRemoteInput.kt`, `RemoteInputSession.kt`, iOS POSIX sockets; the EvenHub
  "asset server" is a WebView request interceptor, `KT/evenhub/EvenHubAssetServer.kt:1-40`).
- Phone-screen mirror of the glasses (white or green preview; touch mirror maps taps/holds/swipes
  to glasses input and `hitTest`) (`app/ui/dashboard-settings.ts:423-444`). GIF screen recorder
  (`KT/graphics/SharedGifScreenRecorder.kt`). No network screen mirroring / remote display
  protocol exists. **[V]**
- EvenHub dev-server loading over Wi-Fi/LTE (section 1.9) is the only "remote app code" path.

---

## 5. Throughput facts

Verified constants [V]:
- Screen 640x480 per lens, 4 bpp (16 gray levels) on the wire (`image.ts:7-8`; `SurfaceCompositor.kt:18-22`).
- BLE: request MTU 512 (`KT/g2protocol/ConnectionOptions.kt:8`), ring 247 (`:13`); write without
  response (`:7`); LE 2M PHY preferred (`FaceclawBleManager.kt:157, 201`); CONNECTION_PRIORITY_HIGH;
  firmware patch forces **7.5 ms connection interval** and enables LE 2M (`app/g2/firmware/cfw-patches.ts:88-100`).
- CFW transport framing (`KT/g2protocol/CfwTransport.kt:77-116`): record = 5 B header (flags, u16
  len, u16 CRC) + body; body is a **persistent zlib stream with sync flush** (falls back to raw if
  bigger than 65535) (`:28-48`); split into packets of `min(252, MTU-14)` payload bytes + 11 B
  (`aa 21 seq len 1 1 f0 ...` + CRC16) -> with MTU 512, max 263-byte writes carrying 252 B.
  Max logical message 65535 B (`:54`).
- Pipelining: `WINDOW_SIZE = 3` messages in flight (`ConnectionOptions.kt:57`); ACK timeout 500 ms,
  3 retries, go-back-N (`KT/g2protocol/CfwMessageWindow.kt:8-11`); measured "Flappy capture: p99
  ACK 63 ms, max 77 ms" (`:8`).
- Images go to the **left arm** with lens flag BOTH (`sendImagesToLeft = true`,
  `ConnectionOptions.kt:50`; `GlassesSessionSend.kt:360-376`) [V]; right-lens relay is firmware-side [I].
- Resource upload chunks <= 3600 B payload per message (`ScenePlanner.kt:34`, `TexturePlanner.kt:~43`);
  stock-path image fragment 3800 B (`ConnectionOptions.kt:34`). Bandwidth benchmark sizes 250-3800,
  windows 1-6, link modes HIGH/2M (`app/apps/developer/bandwidth-benchmark.ts:14-16`).
- Incremental updates: dirty rows in 32-row bands, bbox RLE (`ScenePlanner.kt:58-75`); multi-rect
  up to 6 rects/batch, split only above 900 B payload (`ConnectionOptions.kt:40-47`); text/icons as
  cached glyph (12 B record) / image (9 B record) draws (`glyph-wire.ts:28-31`; flappy
  `flappy-app.worker.ts:12-18` "a 9-byte record rather than pixels"); per-update caps 180 glyph runs,
  4600 glyphs, 60 image draws, 80 firmware-text runs (`TexturePlanner.kt:~27-43`).
- Latest-frame-wins: frames superseded before send are discarded (`KT/g2protocol/session/GlassesSessionCore.kt:279, 1026-1051`).
- Firmware resource cache 192 KiB / 512 ids / 64 KiB max per resource (`ResourceCacheState.kt:22-27`).
- Typical 576x288 UI frame = 166 KB as 8-bit indices, "a few KB" deflated (`SharedGifScreenRecorder.kt` header).

Observed/quoted performance (from code comments, not benchmarks) [V as quotes, numbers unmeasured here]:
- "The BLE pipeline runs ~250 ms input-to-pixels and a handful of fps" (`flappy-app.worker.ts:7`,
  `pinball-app.worker.ts:6`).
- Flappy "physics 60 Hz inside a ~12 fps render tick" (`flappy-app.worker.ts:9`) although
  `RENDER_TICK_MS = 30` (`:94`); pinball "120 Hz inside a ~9 fps render tick" (`pinball:10`) with
  `RENDER_TICK_MS = 50` (`:79`) -> the paint tick is faster than what the link delivers; effective
  on-glasses rate ~9-12 fps for moving sprites [I].
- Terminal coalesces to ~30 fps (`terminal-app.worker.ts:1794`); navigate refreshes a 260x260 map
  pane slowly (idle 5 s) and ships text as deltas (`navigate-app.worker.ts:4-6, 87-92`).
- Even's stock Navigate "ran into trouble with image-upload performance preventing a good map
  display size" (`notes/apps.txt:52-57`).
- MentraOS throttles displays to 1 per 300 ms (context, `notes/mentraos-compat-assessment.md:29`).
- Glasses-side animation: display-list expressions animate without BLE traffic; reference replay
  every 45 ms (~22 fps) (`DisplayListPlayer.kt:3-5, 38`) - firmware's own rate [I].
- No committed KB/s numbers from the bandwidth benchmark exist in repo, notes, or git log [V: grep];
  the phone UI can show live bytes/s and acked fps (`app/phone-ui/ble-bandwidth-meter.ts:16`,
  `dashboard-settings.ts:576`).
- Rough theoretical ceiling [I]: 7.5 ms interval, 2M PHY, ~252 B/packet; if a few packets per
  interval -> order of 100-200 KB/s raw, before zlib; practical rates depend on the unrecorded
  benchmark.

---

## 6. Implications for our app spec (inferred) [I]

1. The natural "app contract" already exists twice: native `WorkerAppMessage/WorkerAppReply`
   (JSON control + out-of-band pixels) and EvenHub containers (declarative text/list/image page).
   A network-transparent app protocol can mirror the worker protocol (input, focus, screen,
   tool-call in; frames, window requests, tools, state out).
2. Render on the device that owns BLE (our watch); ship **declarative content or small deltas**
   from phone/PC, not full frames: 4 bpp + zlib + cached glyph/icon draws is what makes BLE viable.
3. Use glasses-side display lists for motion (scroll/slide/highlight) so remote latency (~250 ms
   today even locally) does not show as jank.
4. Reuse existing surfaces: EvenHub-compatible page model (+ Faceclaw extended layout 576x452),
   MCP ToolSpec shape for app "verbs", token + Tailscale-only binding model from the 8791 port,
   g2mirror's "remote sends semantic data, device renders" pattern.
