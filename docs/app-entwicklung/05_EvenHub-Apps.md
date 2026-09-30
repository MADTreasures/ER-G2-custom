# 05 – EvenHub-Apps auf der Uhr (und auf dem Handy)

Even Realities betreibt für die G2 eine eigene App-Plattform, **Even Hub**. Dieses Kapitel erklärt, wie
solche Apps gebaut sind und wie G2 Watch sie ausführt: **auf der Uhr** mit einer eingebauten
Browser-Engine, und wenn eine App dort nicht läuft, **auf dem Handy**. Einen PC braucht es dafür nicht.

Belege: [`quellen/B-evenhub-offiziell.md`](quellen/B-evenhub-offiziell.md) (offizielle Doku),
[`quellen/A-evenhub-in-faceclaw.md`](quellen/A-evenhub-in-faceclaw.md) (Faceclaws Umsetzung),
[`quellen/D-browser-auf-der-uhr.md`](quellen/D-browser-auf-der-uhr.md) (Browser-Engine auf der Uhr).

## 1. Was eine Even-Hub-App ist

- Eine **Web-App** (HTML/JavaScript, meist mit Vite gebaut) mit dem SDK
  `@evenrealities/even_hub_sdk` (npm, MIT, Stand 0.0.16 vom 24.09.2026).
- Im Original läuft sie **im WebView der Even-App auf dem Handy**. Die Brille zeigt nur „Container“ an,
  die die App beschreibt (Text, Liste, Bild), und meldet Eingaben zurück. Auf der Brille läuft kein App-Code.
  Das DOM der App ist nur ihre (optionale) Einstellungsseite auf dem Handy.
- Manifest `app.json`: `package_id`, `edition`, `name` (≤ 20 Zeichen), `version`, `min_sdk_version`,
  `min_app_version`, `entrypoint`, `permissions` (`network` mit Host-Liste, `location`,
  `g2-microphone`, `phone-microphone`, `album`, `camera`), `supported_languages`.
- Paket `.ehpk`, gebaut mit `evenhub pack app.json dist` (CLI `@evenrealities/evenhub-cli`): enthält
  `app.json` und `dist/`, Einträge mit zstd komprimiert und mit „EVEN REALITIES“ per XOR verwürfelt,
  am Ende eine SHA-512-Prüfsumme, keine Signatur. Einzelheiten: `quellen/A` §1.6.
- Grenzen der Anzeige: [01 §5](01_Plattform_und_Grenzen.md#5-grenzen-der-originalen-even-hub-plattform-nur-für-evenhub-apps).
- Alle Even-Hub-Apps im Store sind derzeit **kostenlos**.

Die Brücke zwischen App und Gastgeber (Faceclaw hat sie nachgebaut, die SDK-Typen bestätigen die Namen):

```
App → Gastgeber:  window.flutter_inappwebview.callHandler("evenAppMessage",
                    JSON.stringify({ type: "call_even_app_method", method, data }))  → Promise mit Ergebnis
Gastgeber → App:  window._listenEvenAppMessage({ type: "listen_even_app_data", method, data })
```

Die 16 Methoden (`EvenAppMethod` in `dist/index.d.ts`, gleich in 0.0.14 und 0.0.16):
`getUserInfo`, `getGlassesInfo`, `setLocalStorage`, `getLocalStorage`, `getAppLocation`,
`startAppLocationUpdates`, `stopAppLocationUpdates`, `pickImageFromAlbum`, `captureImageFromCamera`,
`createStartUpPageContainer`, `rebuildPageContainer`, `textContainerUpgrade`, `updateImageRawData`,
`shutDownPageContainer`, `audioControl`, `imuControl`.

Ereignisse an die App: `evenAppLaunchSource`, `deviceStatusChanged`, `evenHubEvent` (Typen `sysEvent`,
`listEvent`, `textEvent`, `audioEvent`, `menuItemClickEvent`), `appLocationChanged`. Ereigniscodes:
Klick 0, Scroll oben 1, Scroll unten 2, Doppelklick 3, Vordergrund an 4 / aus 5, unerwartetes Ende 6,
System-Ende 7, IMU 8, Langdruck 9, Langdruck los 10. Quelle: rechter Bügel 1, Ring 2, linker Bügel 3.

## 2. Faceclaws Emulator heute

**Er funktioniert, auf dem Handy.** Faceclaw (Android) führt Even-Hub-Apps aus. Der Teil wird laufend
gepflegt: 47 Commits zwischen 11.08. und 27.09.2026. Einige Verbesserungen sind erst im Quelltext und kommen
mit 0.8.1, etwa „Absturz bei großen Apps behoben“. Die README nennt ihn „weitgehend kompatibel“; unter iOS ist
er nur teilweise nutzbar. Die App läuft dort in einem Android-WebView; Faceclaw zeichnet ihre Container selbst
in ein 576×288-Bild und schickt es an die Brille.

Die Brille nimmt nur **eine** Bluetooth-Verbindung an: Entweder Faceclaw auf dem Handy oder G2 Watch ist
verbunden. G2 Watch baut deshalb eine eigene Laufzeit (§4), statt Faceclaw daneben laufen zu lassen.

## 3. Wo die Apps bei uns laufen

| Ort | Engine | Wann |
|---|---|---|
| **1. Uhr** | **GeckoView** (Mozillas Browser-Engine, in die Uhr-App eingebaut) | Standard, sobald der Test auf der echten Uhr bestanden ist (M2) |
| **2. Handy** | System-WebView des Handys, in der Begleit-App **„G2 Handy“** | wenn GeckoView den Test nicht besteht, oder wenn eine einzelne App auf der Uhr abstürzt, vom System beendet wird oder zu langsam ist |
| (später) Uhr, „Lite“ | QuickJS + nachgebautes DOM (linkedom) | für sehr einfache Apps (kein WebAssembly, kein Canvas); nur wenn sich GeckoView als zu schwer erweist |

GeckoView kann, was ein Firefox kann: DOM, Canvas, WebAssembly, WebSocket, Web-Speicher. Der Weg aufs Handy
ist deshalb die Ausnahme, nicht die Regel.

**Wer entscheidet:** Jede App hat einen **Ort** (Uhr/Handy), gewählt bei der Installation (Standard aus den
Einstellungen, [06 §1](06_App-Verwaltung.md#1-g2-handy-android-handy)) und jederzeit umschaltbar. Stürzt eine
App auf der Uhr zweimal ab oder wird sie vom System beendet (GeckoView meldet `onKill`/`onCrash`), fragt die Uhr
auf der Brille: „Diese App auf dem Handy ausführen?“.

**Warum eine eigene Engine:** Wear OS hat **kein WebView** und man kann es nicht nachinstallieren.
Android sagt: „the android.webkit APIs aren't supported“ (Wear OS), und `WebViewFactory` wirft ohne die
Systemfunktion `android.software.webview` eine Ausnahme. Alle Browser für Wear-OS-Uhren bringen deshalb
ihre eigene Engine mit (Samsung Internet: Chromium; JusBrowse-Wrist, Mini Web Browser: Gecko). Eine
Chromium-Engine zum Einbauen gibt es nicht mehr (Crosswalk ist tot); GeckoView ist die gepflegte Möglichkeit.

**Wer zeichnet:** In beiden Fällen die **Uhr**. Die Engine (auf der Uhr oder auf dem Handy) führt nur den
JavaScript-Code der App aus und reicht die SDK-Aufrufe an die EvenHub-Laufzeit der Uhr weiter. Die Uhr hält
das Container-Modell, zeichnet es in die App-Fläche und schickt es an die Brille. So ist die Anzeige in
beiden Fällen gleich, und vom Handy kommen nur kleine Nachrichten statt Bilder.

## 4. Aufbau

```
   Engine (austauschbar)                                     EvenHub-Laufzeit (Uhr, Kotlin)
 ┌──────────────────────────────┐   SDK-Aufrufe          ┌──────────────────────────────────────┐
 │ GeckoView auf der Uhr        │ ─────────────────────▶ │ Container-Modell · Zeichner 576×288   │
 │   oder                       │                        │ Liste/Auswahl · Speicher · Rechte     │──▶ App-Host (03) ──▶ Brille
 │ WebView in „G2 Handy“        │ ◀───────────────────── │ Eingaben → sysEvent/listEvent/…       │
 │ + Brücken-Skript             │   Ereignisse           └──────────────────────────────────────┘
 └──────────────────────────────┘
```

Drei Android-Library-Module, damit jede APK nur ihre Engine enthält:

| Modul | Enthält | Benutzt von |
|---|---|---|
| `evenhub-runtime` | `EvenHubPackage` (`.ehpk`/ZIP entpacken und prüfen), `EvenHubSession`, `ContainerRenderer`, `WebEngine`-Schnittstelle, Brücken-Skript, Nachrichten zum Handy (§6) | Uhr, Handy |
| `evenhub-gecko` | `GeckoEngine`, `AssetServer` (§5) | nur Uhr |
| `evenhub-webview` | `SystemWebViewEngine` (§6) | nur Handy |

| Teil | Aufgabe |
|---|---|
| `EvenHubPackage` | entpacken und prüfen (Pfade ohne `..`, SHA-512 am Ende, `app.json` gültig); Dateien unter `evenhub/<package_id>/<version>/` |
| `AssetServer` (Uhr) | kleiner HTTP-Server auf `127.0.0.1`, **ein fester Port je App** (bei der Installation vergeben und gespeichert); liefert `dist/` aus. Bei Entwicklungs-Apps leitet er an die Entwicklungs-Adresse weiter (über das gebundene WLAN, [01 §4](01_Plattform_und_Grenzen.md#4-netz-zwischen-uhr-und-rechner)), damit die Brücke auch dort greift. |
| `WebEngine` | `load(app)`, `push(json)` (Ereignis an die App), `onCall(handler)`, `onGone(reason)`, `close()`; Umsetzungen `GeckoEngine` (Uhr), `SystemWebViewEngine` (Handy), `RemoteEngine` (Uhr: spricht mit dem Handy, §6) |
| `EvenHubSession` | nimmt SDK-Aufrufe entgegen, verwaltet Container, Rechte und Lebenszyklus, schickt Ereignisse; §4.1–§4.4 |
| `ContainerRenderer` | zeichnet die Container in 576 × 288 Graustufen (§4.2) |

Faceclaws TypeScript-Umsetzung (`app/apps/evenhub/session.ts`, `containers.ts`, `compositor.ts`) ist die
Vorlage. Sie steht unter GPL-3.0; die Übertragung nach Kotlin steht dann ebenfalls unter GPL-3.0 (§8).

**Einbau in den App-Host der Uhr:** Eine EvenHub-Sitzung ist wie der Starter eine **host-interne** Sitzungsart,
keine `G2App` ([03 §5.2](03_Uhr-Apps.md#52-evenhub-sitzungen-im-app-host)): Sie erscheint im Starter aus dem
`EvenHubRegistry`, zeigt eine Vollbild-Seite mit einem randlosen Bild-Baustein, in den sie ihr Raster direkt
schreibt, läuft mit `input: "gestures"`, bekommt die Menüeinträge der App ins App-Menü und darf bis 20 s
starten (Seite „Startet …“).

### 4.1 Methoden

| Methode | Verhalten |
|---|---|
| `createStartUpPageContainer` | Seite anlegen, Ergebnis `0`; ein zweiter Aufruf liefert `1` (wie das Original); danach Ereignis „Vordergrund an“ |
| `rebuildPageContainer` | ganze Seite ersetzen, `true` |
| `textContainerUpgrade` | Text eines Containers ändern (nach `containerID`), Original-Verhalten „ab Position schreiben und abschneiden“ |
| `updateImageRawData` | PNG, BMP (1/4/8/24/32 Bit) oder rohe 4/8-Bit-Pixel in Containergröße; Ergebnis als Zahl wie beim Original (0 = `success`, 1 = `imageException`, 2 = `imageSizeInvalid`, 3 = `sendFailed`) |
| `shutDownPageContainer` | `exitMode 1` → Rückfrage auf der Brille „Beenden?“, sonst Ende nach 200 ms |
| `setLocalStorage` / `getLocalStorage` | Speicher der App **auf der Uhr** (auch wenn die Engine auf dem Handy läuft; er wandert beim Ortswechsel also nicht) |
| `getUserInfo` | feste Werte (`name: "G2 Watch"`) |
| `getGlassesInfo`, Ereignis `deviceStatusChanged` | Modell `g2`, **echter** Akkustand, Laden und „getragen“ der Brille aus dem Brillen-Status des App-Hosts |
| `audioControl` | ab M6 (Mikrofon, LC3 → PCM auf der Uhr); bis dahin `false` |
| `imuControl` | ab M6; Werte als `sysEvent` Typ 8 mit `imuData`; bis dahin `false` |
| `getAppLocation`, `start/stopAppLocationUpdates` | ab M6 vom GPS der Uhr; bis dahin `null` |
| `pickImageFromAlbum`, `captureImageFromCamera` | auf der Uhr nicht möglich, `null` |

**Web-Speicher** (`localStorage`, IndexedDB im Browser) ist etwas anderes als `setLocalStorage`: Er gehört
der Engine. Auf der Uhr trennt der feste Port je App und ein eigener GeckoView-`contextId` je App die Apps
voneinander; beim Entfernen löscht die Uhr ihn (`StorageController.clearDataForSessionContext`). Beim
Ortswechsel Uhr ↔ Handy geht er **nicht** mit.

### 4.2 Zeichnen

- Leinwand 576 × 288 (Vollbild-App-Fläche, `statusBar: false`), Graustufen 0–15.
- Reihenfolge: Bilder unter Listen und Text, sonst nach `zOrderIndex`. Text in 5 Helligkeiten (0–4 →
  Grau 0, 64, 128, 191, 255). Listen: gewählter Eintrag mit Rahmen, die anderen gedimmt.
- Schrift: Das Original nutzt eine feste 20-px-Schrift aus Evens Firmware. Die darf nicht mitgeliefert
  werden; die Uhr zeichnet mit einer freien Schrift mit ähnlichen Maßen (z. B. Noto Sans 20 px).
  Zeilenumbrüche können deshalb leicht abweichen.
- Das Ergebnis geht direkt als Raster in den randlosen Bild-Baustein; die Uhr schickt wie immer nur
  geänderte Streifen an die Brille ([01 §1](01_Plattform_und_Grenzen.md#übertragung-uhr--brille-bluetooth-le)).

### 4.3 Eingaben

Die Sitzung läuft mit `input: "gestures"` ([02 §7](02_App-Modell.md#7-eingabe-und-fokus)). Die Zuordnung folgt
der Original-Hardware (wissen/03 §2.3): Tippen kommt als `sysEvent`, Wischen auf einem Text-Container als `textEvent`.

| Geste von Uhr/Bügel/Ring | an die App |
|---|---|
| `click` | Eingabe-Container ist eine Liste: `listEvent` Klick mit gewähltem Eintrag; sonst `sysEvent` Klick |
| `doubleClick` | `sysEvent` Doppelklick (die App entscheidet, meist „Beenden?“) |
| `scrollUp` / `scrollDown` | Liste: die Uhr bewegt die Auswahl selbst und zeichnet neu, am Rand `listEvent` Scroll oben/unten; Text-Container: `textEvent` Scroll oben/unten; sonst `sysEvent` Scroll oben/unten |
| `longPress` / `longPressRelease` | Codes 9 / 10 |
| `shortThenLongPress` (Tippen-dann-Halten) | öffnet das App-Menü ([02 §5](02_App-Modell.md#5-navigation-und-app-menü)) mit den `menuObject`-Einträgen der App; ein gewählter Eintrag geht als `menuItemClickEvent` an die App |
| Zurück (Uhr: Wischen nach rechts, oder „Zurück“/„Schließen“ im App-Menü) | Die App hat für die Uhr nur eine Seite, Zurück schließt sie also: `sysEvent` System-Ende 7, 200 ms später Ende |

Quelle: `right` → 1, `ring` und `watch` → 2, `left` → 3.

### 4.4 Lebenszyklus, Rechte, Netz

| Ereignis im App-Host | an die App / Wirkung |
|---|---|
| Start | Seite „Startet …“; Engine lädt; `evenAppLaunchSource` (`glassesMenu`), dann `deviceStatusChanged`; Zeitlimit 20 s bis zur ersten Seite |
| `visible` / `hidden` | `sysEvent` Vordergrund an (4) / aus (5) |
| Engine beendet oder abgestürzt | Hinweis „App abgestürzt“ auf der Brille, Sitzung endet; beim zweiten Mal Frage nach Ortswechsel (§3) |
| Schließen | `sysEvent` System-Ende (7), nach 200 ms Engine schließen |

- **Gleichzeitig:** höchstens **2** EvenHub-Apps auf der Uhr (eine sichtbar, eine verdeckt, beide aktiv). Beim
  Start einer dritten schließt die Uhr die am längsten verdeckte (mit System-Ende 7). Die Grenze wird nach den
  Messwerten aus M2 festgelegt.
- **Rechte:** `g2-microphone` und `phone-microphone` → `mic` (das Mikrofon der Brille), `location` →
  `location`, `network` → `network` mit Host-Liste, `album`/`camera` → nicht unterstützt (die App wird trotzdem
  installiert). Die Bestätigung bei der Installation (Handy oder Uhr) **ist** die Antwort nach
  [02 §8](02_App-Modell.md#8-berechtigungen); beim Start wird nicht noch einmal gefragt.
- **Netz-Freigabe:** wie bei Evens App durchgesetzt: Auf der Uhr blockiert die eingebaute Erweiterung per
  `webRequest` alle Anfragen (auch WebSocket) an Hosts außerhalb der Liste. Auf dem Handy blockiert
  `shouldInterceptRequest` HTTP(S); WebSocket-Verbindungen lassen sich dort nicht abfangen (bekannte Lücke).

## 5. Engine auf der Uhr: GeckoView

| Punkt | Festlegung |
|---|---|
| Bibliothek | pro Architektur `org.mozilla.geckoview:geckoview-armeabi-v7a` bzw. `-arm64-v8a` (für den Emulator `-x86_64`), von `maven.mozilla.org` (MPL-2.0). Nicht `geckoview` (alle Architekturen, 242 MB). **Festgelegt: 157.0.20260924084938** (`geckoview` im Versionskatalog). Sie verlangt `compileSdk` **37.1** (`compileSdk = 37` + `compileSdkMinor = 1`, SDK-Paket `platforms;android-37.1`); `targetSdk` bleibt 37. |
| Repository | steht in `settings.gradle.kts`: `maven("https://maven.mozilla.org/maven2/")` mit Inhaltsfilter `includeGroup("org.mozilla.geckoview")` (M2) |
| Architektur | Pixel Watch 3 und 4 laufen mit 32-Bit-Apps (`armeabi-v7a`); für die Watch 5 vorher mit `adb shell getprop ro.product.cpu.abilist` prüfen und die APK mit `abiFilters` darauf beschränken (der Gecko-Test hat dafür je Architektur eine Variante) |
| Größe | `libxul.so` allein: 116 MB (armeabi-v7a, schon ohne Symbole). Gecko-Test-APK mit komprimierten Bibliotheken (`useLegacyPackaging = true`): 117 MB (armv7) bzw. 120 MB (arm64); unkomprimiert 190 MB. Installiert kommen die entpackten Bibliotheken dazu. Was auf der Uhr tatsächlich belegt ist, zeigt *Einstellungen → Apps* nach der Installation (M2). |
| Speicher | Uhr: 3 GB RAM. GeckoView braucht geschätzt 150–300 MB; **messen** (M2) |
| Prozesse | GeckoView startet eigene Dienst-Prozesse (`:socket`, `:gpu`, `:media`, Inhalts-Prozesse). `G2WatchApp.onCreate` darf seine Arbeit (Desktop, Verbindung) nur im Hauptprozess tun (Prozessname prüfen). Laufzeit mit `fissionEnabled(false)`, `extensionsProcessEnabled(false)`. |
| Sitzung | eine `GeckoSession` je App, **ohne sichtbare Ansicht**, `contextId` = `package_id`; `setActive(true)` und `setPriorityHint(PRIORITY_HIGH)`, im Vordergrund-Dienst mit laufender Benachrichtigung; sonst bremst Gecko Timer inaktiver Seiten bis auf 15 Minuten. Zusätzlich wie bei Faceclaw ein Timer-Ersatz im Brücken-Skript, den die Uhr antreibt (`__g2Tick`). |
| Brücke | eingebaute WebExtension (`ensureBuiltIn("resource://android/assets/evenbridge/", …)`) mit Content-Script `run_at: document_start` für `http://127.0.0.1/*`. Das Skript definiert `window.flutter_inappwebview.callHandler` (über `wrappedJSObject`/`cloneInto`) und spricht über `browser.runtime.connectNative("evenhost")` mit Kotlin (`MessageDelegate`, `Port`); `webRequest` für die Netz-Freigabe. Skizze: `quellen/D` §2.7. |
| Laden | `http://127.0.0.1:<Port der App>/<entrypoint>` vom `AssetServer` |
| Einstellungsseite der App | Auf der Uhr kann GeckoView die Seite sichtbar auf dem Uhr-Bildschirm zeigen (Knopf „Einstellungen der App“ in den Uhr-Einstellungen), klein, aber für einfache Formulare brauchbar |

### 5.1 Machbarkeitstest (M2, auf der echten Uhr)

Bevor die ganze Laufzeit gebaut wird, prüft eine Test-APK auf der Pixel Watch 5:

1. Architektur und Speicher der Uhr (`abilist`, freier Speicher) werden angezeigt.
2. GeckoView startet ohne sichtbare Ansicht und lädt drei Test-Apps: nur Text, mit Canvas-Bild, mit
   React/Vue und WebAssembly. Jede ruft über die Brücke eine Methode auf und bekommt ein Ereignis zurück.
3. Gemessen und auf der Uhr angezeigt: Kaltstart bis zum ersten Aufruf, Speicher (PSS aller Prozesse),
   Timer-Genauigkeit bei an/aus geschaltetem Bildschirm, Akku pro 10 Minuten.

**Weiter mit GeckoView**, wenn: Kaltstart ≲ 5 s, Speicher ≲ 300 MB, kein Abbruch durch das System in
30 Minuten, Timer weichen < 20 % ab, Akku vertretbar (Richtwert: < 8 % pro Stunde bei laufender App).
Sonst: Handy als Standard-Ort (§6), GeckoView nur für ausgewählte Apps oder gar nicht.

### 5.2 Die Test-APK „Gecko-Test“

Gebaut in M2 als eigenes Modul `tools/gecko-probe/` (Gradle-Projekt `:gecko-probe`, App-ID
`ch.madtreasures.g2watch.geckoprobe`), damit die Uhr-App nicht um GeckoView wächst, solange nichts
entschieden ist. **Nicht auf Hardware erprobt**: kompiliert, Lint sauber, Einheitstests für Server, Brücke,
Auswertung und Layout; ob GeckoView auf der Uhr startet, zeigt erst der erste Lauf dort.

| Variante | für |
|---|---|
| `armv7Release` | Uhr mit 32-Bit-Apps (`abilist` beginnt mit `armeabi-v7a`) |
| `arm64Release` | Uhr mit 64-Bit-Apps (`abilist` beginnt mit `arm64-v8a`) |
| `x86Debug` | nur Emulator |

Gemessen wird mit der **Release-Variante** (mit dem Debug-Schlüssel signiert, installiert sich direkt): Eine
debugbare App läuft langsamer und braucht mehr Speicher. Aufbau:

- `GeckoLab` – die Messungen im Hauptprozess: `GeckoRuntime` mit `fissionEnabled(false)`,
  `extensionsProcessEnabled(false)`, `isolatedProcessEnabled(false)` (damit die Speicher aller Prozesse
  lesbar sind), `displayDensityOverride(1.5)` (576 Pixel = 384 CSS-Pixel, Seiten ordnen sich wie auf einem
  kleinen Handy an). Je Test-App eine `GeckoSession` ohne sichtbare Ansicht, `setActive(true)`,
  `PRIORITY_HIGH`.
- `ProbeService` – Vordergrund-Dienst (`specialUse`) mit Wake-Lock, solange ein Test läuft: so wie später die
  Uhr-App die Brillenverbindung hält. Die Timer-Messung „Bildschirm aus“ gilt also für diesen Fall.
- Brücke als eingebaute Erweiterung (`assets/probe-bridge/`, `ensureBuiltIn`): Das Content-Script
  (`document_start`) setzt `window.flutter_inappwebview.callHandler` und ruft
  `window._listenEvenAppMessage` auf, wie Evens Handy-App; die Nachrichten laufen über
  `connectNative("g2probe")` zu `ProbeBridge` in Kotlin, als JSON-Text in `{ "json": "…" }`: GeckoView
  überträgt Port-Nachrichten als `GeckoBundle`, und das kann keine verschachtelten oder gemischten Arrays
  (das Seiten-Layout besteht daraus, Daten von Even-Hub-Apps können sie enthalten). Das gilt später genauso
  für die EvenHub-Brücke (M3).
- `AssetServer` – kleiner HTTP-Server auf `127.0.0.1` (nur GET/HEAD, kein `..`) für die Test-Apps aus
  `assets/probe-apps/`: `text` (Container anlegen, Ereignis-Echo), `canvas` (288 × 144 Canvas → 4 Bit →
  `updateImageRawData`), `vue-wasm` (Vue 3.5, MIT, und ein WebAssembly-Modul, das fib(30) rechnet),
  `timer` (100-ms-Intervall, meldet alle 5 s), `render` (Testseite mit Foto, hellen und dunklen Flächen).

Die Tests auf der Uhr, der Reihe nach:

| Knopf | Dauer | Misst |
|---|---|---|
| 1 · Schnelltest | ≈ 1 min | Engine-Start, Kaltstart bis zum ersten Aufruf der Text-App, Rundlauf der Brücke, die drei Test-Apps, Speicher (PSS aller Prozesse der App aus `/proc/<pid>/smaps_rollup`; `getProcessMemoryInfo` nur als Ersatz, weil Android es nur alle 5 min auffrischt), Prozessliste |
| 2 · Timer-Test | 4 min | 2 min mit Bildschirm an, Vibration, 2 min mit Bildschirm aus (Handgelenk senken); Abweichung vom 100-ms-Takt und längste Lücke |
| 3 · Dauertest | 30 min | Text- und Timer-App laufen; Abstürze (`onCrash`, `onKill`, Engine-Ende), Speicherspitze, Akku pro Stunde (Ladezähler, feiner als ganze Prozent) |
| 4 · Seite rendern | ≈ 10 s | der Bildweg des Browsers (M7): GeckoView zeichnet die Testseite in eine unsichtbare Fläche (`ImageReader` 576 × 260, `GeckoDisplay.capturePixels`), das Content-Script meldet Textzeilen, Bilder und Hintergründe, `web-raster` macht daraus das Brillenbild (§10) |
| 5 · Wikipedia rendern | ≈ 10 s | dasselbe mit `https://de.m.wikipedia.org/wiki/Brille` (braucht Internet) |

Die Uhr zeigt jeden Wert mit ✓ (Ziel erreicht), ~ (knapp) oder ✗ und darunter eine **Empfehlung** nach
§5.1. Ein laufender Test lässt sich oben mit „Abbrechen“ beenden. „Bericht“ (unterer Rand, am Ende der Liste)
schreibt alles nach
`/sdcard/Android/data/ch.madtreasures.g2watch.geckoprobe/files/g2-gecko-bericht.txt`; die Seiten-Tests legen
dort `render-seite.png` (was GeckoView gezeichnet hat) und `render-brille.png` (was die Brille zeigen würde)
ab. Holen mit `adb pull`. Eintragen in [quellen/E-m2-messwerte.md](quellen/E-m2-messwerte.md).

## 6. Engine auf dem Handy: „G2 Handy“

**Die App:**
- Eine kleine **Android-App fürs Handy** im selben Repo (Modul `phone/`). Sie muss **dieselbe
  `applicationId`** (`ch.madtreasures.g2watch`) und **denselben Signaturschlüssel** haben wie die Uhr-App,
  sonst gibt Google Play Services die Nachrichten nicht weiter. Dafür bekommen beide Module eine gemeinsame
  Signatur-Konfiguration (ein Debug-Schlüssel im Repo für Debug-Builds; für CI-Builds derselbe Schlüssel).
- Abhängigkeit `com.google.android.gms:play-services-wearable` (neu im Versionskatalog). Beide Seiten melden
  eine Fähigkeit an (`res/values/wear.xml`: `g2_watch` bzw. `g2_handy`), gefunden wird über `CapabilityClient`.
- Geweckt wird „G2 Handy“ durch einen `WearableListenerService`, wenn die Uhr eine App starten will. Solange
  eine App läuft, hält ein Vordergrund-Dienst (`connectedDevice`) die App am Leben.
- Apps laufen im System-WebView, wie Faceclaws Android-Gastgeber: Ursprung je App
  `https://<package_id>.evenhub.invalid/` über `shouldInterceptRequest` (kein `AssetServer` auf dem Handy),
  Brücke über `addJavascriptInterface`, Brücken-Skript vor jedem anderen Skript
  (`WebViewCompat.addDocumentStartJavaScript`), das WebView meldet sich immer „sichtbar“ und bleibt an einem
  Fenster hängen, damit Chromium die Timer nicht einfriert. Wie es ohne sichtbare Activity sicher weiterläuft
  (Overlay-Fenster oder unsichtbare Ansicht im Dienst), klärt M4 mit einem Test auf dem Handy.
- Die Einstellungsseite einer App, die auf dem Handy läuft, zeigt „G2 Handy“ auf Knopfdruck an.

**Datenweg:** Die Uhr bleibt die Stelle, die zeichnet und die Brille bedient. Nachrichten gehen über die
**Wear-OS-Datenschicht** (Google Play Services): `MessageClient` für Nachrichten bis 100 KB (JSON, auch Bilder
eines Containers), `ChannelClient` für Größeres (App-Pakete), `DataClient` für die App-Liste. Die Datenschicht
wählt selbst Bluetooth, WLAN oder den Umweg über Googles Server. Richtwerte: 50–200 KB/s über Bluetooth, beim
Übertragen großer Dateien grob 0,7 MB/s; Wechsel zwischen Bluetooth und WLAN kann eine Minute dauern.

**Nachrichten** (Pfad `/g2/evenhub`, JSON; jede Nachricht einer laufenden App trägt `session`):

| Richtung | `t` | Felder | Bedeutung |
|---|---|---|---|
| beide | `hello` | `proto: "g2-phone@1"`, `app` (Version der APK) | beim Verbinden; unbekannte Version → Hinweis „Uhr- und Handy-App aktualisieren“ |
| Uhr → Handy | `start` | `session`, `app`, `version` | App auf dem Handy starten; hat das Handy eine andere Version: `failed` mit `code: "version"` |
| Handy → Uhr | `ready` / `failed` | `session`, `code?`, `message?` | gestartet bzw. nicht |
| Handy → Uhr | `call` | `session`, `id`, `method`, `data` | SDK-Aufruf der App |
| Uhr → Handy | `result` | `session`, `id`, `ok`, `value` | Antwort |
| Uhr → Handy | `push` | `session`, `method`, `data` | Ereignis an die App |
| Uhr → Handy | `stop` | `session` | App beenden |
| Handy → Uhr | `log` | `session`, `level`, `text` | Konsole der App, fürs Protokoll |
| Handy → Uhr | `launch` | `app` | Knopf „Auf der Brille starten“ in „G2 Handy“ |
| Handy → Uhr | `install` | `app`, `version`, `size`, `sha256` | kündigt ein Paket an; danach `ChannelClient`-Strom auf `/g2/evenhub/install/<package_id>` |
| Uhr → Handy | `install.done` | `app`, `ok`, `message?` | Paket geprüft und installiert (oder nicht) |
| Handy → Uhr | `remove` / `move` | `app` / `app`, `to` (`watch`/`phone`) | entfernen / Ort wechseln |
| Uhr → Handy | `status` | `glasses: {battery, charging, wearing}`, `watch: {battery}` | für die Übersicht in „G2 Handy“, bei Änderung |

Die **App-Liste** (alle Even-Hub-Apps mit Version und Ort, auch die direkt auf der Uhr installierten) führt die
Uhr als `DataClient`-Eintrag `/g2/evenhub/apps` (dringend markiert); „G2 Handy“ liest ihn.

**Verbindung weg:** Jede `call`/`result`-Runde hat 10 s Zeitlimit (die App bekommt dann `null`). Die Uhr prüft
die Verbindung alle 10 s (`MessageClient.sendRequest`). Ist sie weg, zeigt die Kopfzeile „Handy getrennt“,
Eingaben werden verworfen (nicht gepuffert); nach 60 s ohne Verbindung endet die App mit Hinweis. Ist das
Handy beim Start nicht erreichbar, zeigt die Uhr „Handy nicht verbunden“ statt einer leeren Seite.

## 7. Apps installieren und woher sie kommen dürfen

Installiert wird in **„G2 Handy“** (Seite „Apps“, [06 §1](06_App-Verwaltung.md#1-g2-handy-android-handy)) oder
direkt auf der Uhr über eine Adresse ([06 §2](06_App-Verwaltung.md#2-direkt-auf-der-uhr)); der Weg über die Uhr
kommt schon mit M3, „G2 Handy“ mit M4.

| Quelle | Weg |
|---|---|
| **Eigene Apps** mit dem offiziellen SDK und der CLI (`evenhub init`, `vite build`, `evenhub pack`) | `.ehpk` oder ZIP mit `app.json` + `dist/` wählen |
| **Quelloffene Even-Hub-Apps** (z. B. auf GitHub, Lizenz beachten) | fertiges Release-Paket (`.ehpk`/ZIP) herunterladen; aus Quelltext bauen geht nur am PC mit Node |
| **`.ehpk`-Dateien, die du rechtmäßig hast** | Datei wählen |
| **Entwicklungs-Server** (`vite dev` im Heim-WLAN) | Adresse angeben; auf dem Handy lädt das WebView sie direkt, auf der Uhr leitet der `AssetServer` sie weiter |

Vor der Installation werden die Berechtigungen und die Datenschutz-Adresse angezeigt, falls `app.json` eine
nennt. Große Pakete besser über WLAN oder direkt auf der Uhr per Adresse installieren: 50 MB über Bluetooth
dauern 4–17 Minuten.

**Nicht vorgesehen: Herunterladen aus Evens Store.** Evens Store-Server ist nicht öffentlich. Faceclaws
Store-Client meldet sich mit dem Even-Konto an, unterschreibt jede Anfrage mit einem Schlüssel aus Evens
eigener App und gibt sich als die offizielle Android-App aus. Evens Nutzungs- und Entwicklerbedingungen
verbieten, die Schnittstelle zu entschlüsseln oder Inhalte automatisiert abzugreifen. Dieses Projekt baut
deshalb keinen solchen Zugang nach und übernimmt auch nicht Faceclaws Store-Code. Wer den Store nutzen
möchte, kann das mit Faceclaw auf dem Handy tun, auf eigene Verantwortung.

## 8. Lizenzen

- **GeckoView:** MPL-2.0 (Datei-Copyleft). Eigene Dateien bleiben unsere; mit jeder weitergegebenen APK
  muss ein Hinweis stehen, wo es den Quelltext von GeckoView gibt. Die mitgelieferten LGPL/FFmpeg-Bibliotheken
  (`liblgpllibs.so`, `libmozavcodec.so`, `libmozavutil.so`) bleiben unverändert und austauschbar; ihre
  Hinweise kommen in die Lizenz-Seite der App.
- **Faceclaws EvenHub-Code** (GPL-3.0) als Vorlage: die übertragenen Kotlin-Dateien stehen unter GPL-3.0,
  mit Herkunftsvermerk wie `faceclaw-core/UPSTREAM.md`. Das passt zum Repo, das Faceclaws Kern schon enthält.
- **SDK-Typen** (`index.d.ts`): MIT.
- **Gecko-Test (M2):** enthält GeckoView (MPL-2.0; Quelltext: https://hg.mozilla.org/mozilla-central und
  https://github.com/mozilla-firefox/firefox) und Vue 3.5 (MIT, Lizenztext neben `vue.global.prod.js` in
  `tools/gecko-probe/src/main/assets/probe-apps/vue-wasm/`). Die Test-APK ist nur zum Messen, nicht zum
  Weitergeben gedacht.

## 9. Tests

- Test-App im Repo (`evenhub-runtime/src/test/fixtures/`, mit dem offiziellen SDK gebaut), die jede Methode
  aufruft und jedes Ereignis anzeigt.
- `EvenHubSession` und `ContainerRenderer` ohne Engine testen: SDK-Aufrufe als JSON hineingeben, Antworten und
  gezeichnete Bilder (Referenzbilder) prüfen. Das geht in CI wie heute.
- `GeckoEngine` und `SystemWebViewEngine`: Instrumentierungstests auf dem Android-Emulator (x86_64-Variante von
  GeckoView); dafür braucht die CI einen neuen Job mit Emulator. Die Uhr-Messung geht nur auf der echten Uhr (§5.1).
- `RemoteEngine` und die Nachrichten aus §6: mit einem Fake der Datenschicht, Reihenfolge, Zeitlimits,
  Verbindungsabbruch.

## 10. Und ein richtiger Browser auf der Brille?

Mit GeckoView auf der Uhr wird auch ein **Web-Browser für die Brille** möglich: GeckoView zeichnet eine Seite
in eine unsichtbare Fläche, die Uhr schickt das Bild (in Graustufen) an die Brille, und der Zeiger auf dem
Uhr-Touchpad klickt und scrollt. Das ist ein eigener Meilenstein nach der EvenHub-Laufzeit ([07](07_Umsetzungsplan.md)).

### 10.1 Seiten ins Brillen-Raster wandeln (`web-raster`, gebaut)

Eine Web-Seite ist fürs Papier gemacht: dunkle Schrift auf hellem Grund. Auf der Brille leuchtet Hell und
Schwarz ist durchsichtig – eins zu eins übernommen wäre die Seite eine leuchtende Fläche mit Löchern als
Schrift. Das Modul `web-raster` (reines Kotlin, ohne Android) macht daraus ein Bild, wie es die Even-Apps
„Photos“ und „G2 Agent Cam“ zeigen: Inhalte leuchten grün, der Grund bleibt durchsichtig, und **Text ist
immer lesbar**.

Eingabe (`PageCapture`): die Pixel der Seite (ARGB, wie `Bitmap.getPixels`) und, wenn vorhanden, was das DOM
weiß: Textzeilen mit Farbe (`TextRun`), Bilder (`<img>`, `<video>`, `<canvas>`, Hintergrundbilder) und Flächen
mit Hintergrundfarbe (`Surface`). Im Gecko-Test liefert das Content-Script diese Angaben (`collectLayout`,
CSS-Pixel → `LayoutParser`). Ausgabe: 576 Pixel breit, 16 Stufen, dazu ein Bericht.

| Regel | Umsetzung (`GlassesRasterizer`, Werte in `RasterOptions`) |
|---|---|
| Grund wird durchsichtig | Hintergrund je Stelle schätzen (häufigste Helligkeit in 24 × 24 Pixeln, oder die Farbe der kleinsten DOM-Fläche darunter) und abziehen: weiße und dunkle Seiten verlieren ihren Grund gleichermaßen; kleine Unterschiede (< 28 von 255, Schatten, Kartenränder) bleiben dunkel |
| Bilder bleiben positiv | Bilder behalten Hell und Dunkel, je Bild auf 2–98 % gestreckt, mal 0,85, mit Floyd–Steinberg auf die 16 Stufen gebracht |
| Text immer voll lesbar | jede Textzeile wird aus ihren Pixeln neu gezeichnet: Glyphen in voller Helligkeit (Stufe 15), auch blaue Links und graue Bildunterschriften |
| Text auf unruhigem Grund → **negativ** | wäre der Grund um eine Zeile auf der Brille hell oder unruhig (Text auf einem Foto: Mittel > 70 oder Streuung > 40 von 255), bekommt die Zeile eine helle Platte (Stufe 12) mit dunkel ausgesparten Buchstaben |
| **Überladenes Fenster → negativ** | nehmen Bilder (mit ihrer ganzen Fläche, auch dunkle) und leuchtende Flächen mehr als 35 % des Fensters ein, wird **aller** Text negativ gesetzt, und helle Bilder werden auf ein Mittel von 80 gedämpft, damit die Platten sich abheben |

Die Bilder in `docs/bilder/raster-*.png` zeigen die vier Fälle (erzeugt von `RasterSnapshotTest` aus
Java2D-Testseiten, nicht von einem Browser):

| Heller Artikel | Dunkle Seite | Text auf Foto | Überladen |
|---|---|---|---|
| ![hell](../bilder/raster-hell.png) | ![dunkel](../bilder/raster-dunkel.png) | ![Text auf Bild](../bilder/raster-text-auf-bild.png) | ![überladen](../bilder/raster-ueberladen.png) |

Die Grenzwerte sind Annahmen und werden nachjustiert, sobald echte Seiten auf der echten Brille zu sehen sind
(Gecko-Test „Seite rendern“ liefert dafür `render-brille.png`). Was M7 noch fehlt, steht in
[07 M7](07_Umsetzungsplan.md#m7--web-browser-auf-der-brille-wenn-m2-geckoview-ja-ergibt).
