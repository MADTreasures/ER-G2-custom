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
verbunden. G2 Watch baut deshalb eine eigene Laufzeit (§3), statt Faceclaw daneben laufen zu lassen.

## 3. Wo die Apps bei uns laufen

| Ort | Engine | Wann |
|---|---|---|
| **1. Uhr** | **GeckoView** (Mozillas Browser-Engine, in die Uhr-App eingebaut) | Standard, sobald der Test auf der echten Uhr bestanden ist (M2) |
| **2. Handy** | System-WebView des Handys, in der Begleit-App **„G2 Handy“** | wenn eine App auf der Uhr nicht läuft (WebAssembly, WebGL, viel Canvas, zu viel Speicher) oder GeckoView den Test nicht besteht |
| (später) Uhr, „Lite“ | QuickJS + nachgebautes DOM (linkedom) | für sehr einfache Apps, schneller Start, wenig Speicher; nur wenn sich GeckoView als zu schwer erweist |

**Warum eine eigene Engine:** Wear OS hat **kein WebView** und man kann es nicht nachinstallieren.
Android sagt: „the android.webkit APIs aren't supported“ (Wear OS), und `WebViewFactory` wirft ohne die
Systemfunktion `android.software.webview` eine Ausnahme. Alle Browser für Wear-OS-Uhren bringen deshalb
ihre eigene Engine mit (Samsung Internet: Chromium; JusBrowse-Wrist, Mini Web Browser: Gecko). Eine
Chromium-Engine zum Einbauen gibt es nicht mehr (Crosswalk ist tot); GeckoView ist die gepflegte
Möglichkeit.

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

Neues Android-Library-Modul **`evenhub-runtime`** (Kotlin), benutzt von der Uhr-App und von „G2 Handy“:

| Teil | Aufgabe |
|---|---|
| `EvenHubPackage` | `.ehpk` und ZIP entpacken und prüfen (Pfade ohne `..`, SHA-512 am Ende, `app.json` gültig); Dateien unter `evenhub/<package_id>/` |
| `AssetServer` | kleiner HTTP-Server auf `127.0.0.1`, **ein Port je App** (eigener Ursprung → eigener Browser-Speicher); liefert `dist/` aus und fügt nichts ein |
| `WebEngine` | Schnittstelle: `load(url)`, `push(json)` (Ereignis an die App), `onCall(handler)`, `close()`; Umsetzungen `GeckoEngine` (Uhr), `SystemWebViewEngine` (Handy), `RemoteEngine` (Uhr: spricht mit dem Handy, §6) |
| `EvenHubSession` | nimmt SDK-Aufrufe entgegen, verwaltet Container und Rechte, schickt Ereignisse; Methoden §4.1 |
| `ContainerRenderer` | zeichnet die Container in 576 × 288 Graustufen (Regeln §4.2) |
| `EvenHubApp` | macht aus einer Sitzung eine App für den App-Host ([03](03_Uhr-Apps.md)): Vollbild-Seite mit einem randlosen `image`-Baustein, Eingabeart `gestures`, App-Menü mit den Menüeinträgen der App |

Faceclaws TypeScript-Umsetzung (`app/apps/evenhub/session.ts`, `containers.ts`, `compositor.ts`) ist die
Vorlage. Sie steht unter GPL-3.0; die Übertragung nach Kotlin steht dann ebenfalls unter GPL-3.0 (§8).

### 4.1 Methoden

| Methode | Verhalten |
|---|---|
| `createStartUpPageContainer` | Seite anlegen, Ergebnis `0`; ein zweiter Aufruf liefert `1` (wie das Original); danach Ereignis „Vordergrund an“ |
| `rebuildPageContainer` | ganze Seite ersetzen, `true` |
| `textContainerUpgrade` | Text eines Containers ändern (nach `containerID`), Original-Verhalten „ab Position schreiben und abschneiden“ |
| `updateImageRawData` | PNG, BMP (1/4/8/24/32 Bit) oder rohe 4/8-Bit-Pixel in Containergröße; Ergebnis als Zahl wie beim Original (0 = `success`, 1 = `imageException`, 2 = `imageSizeInvalid`, 3 = `sendFailed`) |
| `shutDownPageContainer` | `exitMode 1` → Rückfrage auf der Brille „Beenden?“, sonst Ende nach 200 ms |
| `setLocalStorage` / `getLocalStorage` | Speicher der App auf der Uhr (auch wenn die Engine auf dem Handy läuft) |
| `getUserInfo` | feste Werte (`name: "G2 Watch"`) |
| `getGlassesInfo`, Ereignis `deviceStatusChanged` | Modell `g2`, **echter** Akkustand und „getragen“ von der Brille |
| `audioControl` | ab M6 (Mikrofon, LC3 → PCM auf der Uhr); bis dahin `false` |
| `imuControl` | ab M6; Werte als `sysEvent` Typ 8 mit `imuData`; bis dahin `false` |
| `getAppLocation`, `start/stopAppLocationUpdates` | ab M6 vom GPS der Uhr (Berechtigung `location`); bis dahin `null` |
| `pickImageFromAlbum`, `captureImageFromCamera` | auf der Uhr nicht möglich, `null`; mit Engine auf dem Handy später denkbar |

### 4.2 Zeichnen

- Leinwand 576 × 288 (Vollbild-App-Fläche, `statusBar: false`), Graustufen 0–15.
- Reihenfolge: Bilder unter Listen und Text, sonst nach `zOrderIndex`. Text in 5 Helligkeiten (0–4 →
  Grau 0, 64, 128, 191, 255). Listen: gewählter Eintrag mit Rahmen, die anderen gedimmt.
- Schrift: Das Original nutzt eine feste 20-px-Schrift aus Evens Firmware. Die darf nicht mitgeliefert
  werden; die Uhr zeichnet mit einer freien Schrift mit ähnlichen Maßen (z. B. Noto Sans 20 px).
  Zeilenumbrüche können deshalb leicht abweichen.
- Das Ergebnis geht als Pixel an den randlosen `image`-Baustein der Seite; die Uhr schickt wie immer nur
  geänderte Streifen an die Brille ([01 §1](01_Plattform_und_Grenzen.md#übertragung-uhr--brille-bluetooth-le)).

### 4.3 Eingaben

Die Sitzung läuft mit `input: "gestures"` ([02 §7](02_App-Modell.md#7-eingabe-und-fokus)).

| Geste von Uhr/Bügel/Ring | an die App |
|---|---|
| `click` | Ist der Eingabe-Container eine Liste: `listEvent` Klick mit gewähltem Eintrag, sonst `sysEvent` Klick |
| `doubleClick` | `sysEvent` Doppelklick (die App entscheidet, meist „Beenden?“) |
| `scrollUp` / `scrollDown` | In einer Liste bewegt die Uhr die Auswahl selbst und zeichnet neu; am Rand bzw. ohne Liste `sysEvent` Scroll oben/unten |
| `longPress` / `longPressRelease` | Codes 9 / 10 |
| `shortThenLongPress` (Tippen-dann-Halten) | öffnet das App-Menü ([02 §5](02_App-Modell.md#5-navigation-und-app-menü)) mit den `menuObject`-Einträgen der App; ein gewählter Eintrag geht als `menuItemClickEvent` an die App |
| Zurück (Uhr: Wischen nach rechts, oder „Zurück“/„Schließen“ im App-Menü) | Die App hat für die Uhr nur eine Seite, Zurück schließt sie also: `sysEvent` System-Ende 7, 200 ms später Ende |

Quelle: `right` → 1, `ring` und `watch` → 2, `left` → 3.

## 5. Engine auf der Uhr: GeckoView

| Punkt | Festlegung |
|---|---|
| Bibliothek | `org.mozilla.geckoview:geckoview:<Version>` von `maven.mozilla.org` (Stand 156.0, MPL-2.0, minSdk 26) |
| Prozessorarchitektur | Die Pixel Watch 3 und 4 laufen mit **32-Bit-Apps** (`armeabi-v7a`); für die Watch 5 vorher mit `adb shell getprop ro.product.cpu.abilist` prüfen. Die APK braucht die passende Architektur (je ≈ 62–65 MB komprimiert). Ein Universal-APK mit beiden ist ≈ 130 MB größer. |
| Speicher | Uhr: 3 GB RAM (Snapdragon W5 Gen 2). GeckoView braucht geschätzt 150–300 MB; **messen**. |
| Laufzeit | ein `GeckoRuntime` mit `fissionEnabled(false)`, `extensionsProcessEnabled(false)`; eine `GeckoSession` je App, **ohne sichtbare Ansicht** (headless) |
| Wachhalten | Sitzung `setActive(true)` und `setPriorityHint(PRIORITY_HIGH)`, im Vordergrund-Dienst mit laufender Benachrichtigung; sonst bremst Gecko Timer inaktiver Seiten bis auf 15 Minuten. Zusätzlich wie bei Faceclaw ein Timer-Ersatz im Brücken-Skript, den die Uhr antreibt (`__g2Tick`). |
| Brücke | eingebaute WebExtension (`ensureBuiltIn("resource://android/assets/evenbridge/", …)`) mit Content-Script `run_at: document_start` für `http://127.0.0.1/*`. Das Skript definiert `window.flutter_inappwebview.callHandler` (über `wrappedJSObject`/`cloneInto`) und spricht über `browser.runtime.connectNative("evenhost")` mit Kotlin (`MessageDelegate`, `Port`). Skizze: `quellen/D` §2.7. |
| Laden | `http://127.0.0.1:<Port der App>/<entrypoint>` vom `AssetServer` |

### 5.1 Machbarkeitstest (M2, auf der echten Uhr)

Bevor die ganze Laufzeit gebaut wird, prüft eine Test-APK auf der Pixel Watch 5:

1. Architektur und Speicher der Uhr (`abilist`, freier Speicher) werden angezeigt.
2. GeckoView startet ohne sichtbare Ansicht und lädt drei Test-Apps: nur Text, mit Canvas-Bild, mit
   React/Vue. Jede ruft über die Brücke eine Methode auf und bekommt ein Ereignis zurück.
3. Gemessen und auf der Uhr angezeigt: Kaltstart bis zum ersten Aufruf, Speicher (PSS aller Prozesse),
   Timer-Genauigkeit bei an/aus geschaltetem Bildschirm, Akku pro 10 Minuten.

**Weiter mit GeckoView**, wenn: Kaltstart ≲ 5 s, Speicher ≲ 300 MB, kein Abbruch durch das System in
30 Minuten, Timer weichen < 20 % ab, Akku vertretbar (Richtwert: < 8 % pro Stunde bei laufender App).
Sonst: Handy als Standard (§6), GeckoView nur für ausgewählte Apps oder gar nicht.

## 6. Engine auf dem Handy: „G2 Handy“

- Eine kleine **Android-App fürs Handy** im selben Repo (Modul `phone/`). Sie muss **dieselbe
  `applicationId`** (`ch.madtreasures.g2watch`) und denselben Signaturschlüssel haben wie die Uhr-App,
  sonst gibt Google Play Services die Nachrichten nicht weiter.
- Sie führt Apps im System-WebView aus, wie Faceclaws Android-Gastgeber: eigener Ursprung je App über
  `shouldInterceptRequest`, Brücke über `addJavascriptInterface`, Brücken-Skript vor jedem anderen Skript
  (`WebViewCompat.addDocumentStartJavaScript`), WebView bleibt „sichtbar“ gemeldet, damit Chromium die
  Timer nicht einfriert.
- Die Uhr bleibt die Stelle, die zeichnet und die Brille bedient. Die SDK-Aufrufe gehen über die
  **Wear-OS-Datenschicht** (Google Play Services) zur Uhr: `MessageClient` für Nachrichten bis 100 KB
  (JSON, auch Bilder eines Containers), `ChannelClient` für Größeres. Die Datenschicht wählt selbst
  Bluetooth oder WLAN. Richtwerte: 50–200 KB/s über Bluetooth, einige MB/s über WLAN.
- Nachrichten (Pfad `/g2/evenhub`, JSON):

  | Richtung | `t` | Felder |
  |---|---|---|
  | Uhr → Handy | `start` | `app` (package_id), `version` |
  | Handy → Uhr | `ready` / `failed` | `app`, `message?` |
  | Handy → Uhr | `call` | `id`, `method`, `data` (SDK-Aufruf der App) |
  | Uhr → Handy | `result` | `id`, `ok`, `value` |
  | Uhr → Handy | `push` | `method`, `data` (Ereignis an die App) |
  | Uhr → Handy | `stop` | `app` |
  | Handy → Uhr | `log` | `level`, `text` (Konsole der App, fürs Protokoll) |

- Die App-Dateien liegen dort, wo die Engine läuft. Wer eine App installiert (§7), wählt „Uhr“ oder
  „Handy“; die Dateien werden dorthin übertragen. Für „Handy“ erscheint die App trotzdem im Starter der
  Uhr; beim Start fragt die Uhr das Handy.
- Ist das Handy nicht erreichbar, zeigt die Uhr „Handy nicht verbunden“ statt einer leeren Seite.

## 7. Apps installieren und woher sie kommen dürfen

Installiert wird in **„G2 Handy“** (Seite „Apps“) und von dort auf die Uhr übertragen
(`ChannelClient`), oder direkt auf der Uhr über eine Adresse (Download über WLAN/LTE):

| Quelle | Weg |
|---|---|
| **Eigene Apps** mit dem offiziellen SDK und der CLI (`evenhub init`, `vite build`, `evenhub pack`) | `.ehpk` oder ZIP mit `app.json` + `dist/` wählen |
| **Quelloffene Even-Hub-Apps** (z. B. auf GitHub, Lizenz beachten) | fertiges Release-Paket (`.ehpk`/ZIP) herunterladen; aus Quelltext bauen geht nur am PC mit Node |
| **`.ehpk`-Dateien, die du rechtmäßig hast** | Datei wählen |
| **Entwicklungs-Server** (`vite dev` im Heim-WLAN) | Adresse angeben; die App läuft direkt von dort (nur Handy-Engine oder Uhr im WLAN) |

Vor der Installation zeigt „G2 Handy“ die Berechtigungen und die Datenschutz-Adresse, falls `app.json`
eine nennt.

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

## 9. Tests

- Test-App im Repo (`evenhub-runtime/src/test/fixtures/`, mit dem offiziellen SDK gebaut), die jede Methode
  aufruft und jedes Ereignis anzeigt.
- `EvenHubSession` und `ContainerRenderer` ohne Engine testen: SDK-Aufrufe als JSON hineingeben, Antworten und
  gezeichnete Bilder (Referenzbilder) prüfen.
- `GeckoEngine` und `SystemWebViewEngine`: Instrumentierungstests auf Gerät/Emulator (Handy-Emulator geht in CI,
  die Uhr-Messung nur auf der echten Uhr, §5.1).
- `RemoteEngine`: Datenschicht mit einem Fake, Reihenfolge und Zeitüberschreitung der Aufrufe.

## 10. Und ein richtiger Browser auf der Brille?

Mit GeckoView auf der Uhr wird auch ein **Web-Browser für die Brille** möglich: GeckoView zeichnet eine Seite
in eine unsichtbare Fläche, die Uhr schickt das Bild (in Graustufen) an die Brille, und der Zeiger auf dem
Uhr-Touchpad klickt und scrollt. Das ist ein eigener Meilenstein nach der EvenHub-Laufzeit ([07](07_Umsetzungsplan.md)).
