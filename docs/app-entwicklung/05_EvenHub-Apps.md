# 05 – EvenHub-Apps

Even Realities betreibt für die G2 eine eigene App-Plattform, **Even Hub**. Dieses Kapitel erklärt, wie
solche Apps gebaut sind, wie Faceclaw sie heute ausführt und wie `g2-host` sie für G2 Watch ausführen
soll. Belege: [`quellen/B-evenhub-offiziell.md`](quellen/B-evenhub-offiziell.md) (offizielle Doku),
[`quellen/A-evenhub-in-faceclaw.md`](quellen/A-evenhub-in-faceclaw.md) (Faceclaws Umsetzung).

## 1. Was eine Even-Hub-App ist

- Eine **Web-App** (HTML/JavaScript, meist mit Vite gebaut) mit dem SDK
  `@evenrealities/even_hub_sdk` (npm, MIT, Stand 0.0.16 vom 24.09.2026).
- Sie läuft **im WebView der Even-App auf dem Handy**. Die Brille zeigt nur „Container“ an, die die App
  beschreibt (Text, Liste, Bild) und meldet Eingaben zurück. Auf der Brille selbst läuft kein App-Code.
- Manifest `app.json`: `package_id`, `edition`, `name` (≤ 20 Zeichen), `version`, `min_sdk_version`,
  `min_app_version`, `entrypoint`, `permissions` (`network` mit Host-Liste, `location`,
  `g2-microphone`, `phone-microphone`, `album`, `camera`), `supported_languages`.
- Paket `.ehpk`, gebaut mit `evenhub pack app.json dist` (CLI `@evenrealities/evenhub-cli`): enthält
  `app.json` und `dist/`, Einträge mit zstd komprimiert und mit „EVEN REALITIES“ per XOR verwürfelt,
  am Ende eine SHA-512-Prüfsumme, keine Signatur.
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

## 2. Funktioniert Faceclaws Emulator noch?

**Ja, auf dem Handy.** Faceclaw (Android) führt Even-Hub-Apps aus. Der Teil wird laufend gepflegt: 47
Commits zwischen 11.08. und 27.09.2026. Einige Verbesserungen sind erst im Quelltext und kommen mit 0.8.1,
etwa „Absturz bei großen Apps behoben“ und ein Knopf für die Handy-Oberfläche einer App. Die README nennt
ihn „weitgehend kompatibel“. Unter iOS ist er nur teilweise nutzbar (Entwickler-Beta, Mikrofon und IMU fehlen).

So arbeitet er: Die App läuft in einem Android-WebView auf dem Handy. Faceclaw zeichnet ihre Container
selbst in ein 576×288-Bild und schickt es über seine eigene Anzeige an die Brille. Die Original-Firmware
sieht die Container nie. Abweichungen: Album und Kamera fehlen, Nutzer- und Brilleninfo sind fest
vorgegeben, und die Netzwerk-Freigabeliste wird nicht durchgesetzt.

Für uns heißt das:
- Unsere Brille (Faceclaw/35 von der Uhr aufgespielt) passt zu Faceclaw 0.8.x und dem kommenden 0.8.1,
  beide verlangen Revision 35.
- Aber: Die Brille nimmt **nur eine** Bluetooth-Verbindung an. Entweder Faceclaw auf dem Handy ist mit
  der Brille verbunden (dann gehen Even-Hub-Apps, und die Uhr kann über Faceclaws eigene Wear-OS-App als
  Fernbedienung dienen), oder G2 Watch ist verbunden (dann gehen Even-Hub-Apps nur über den Adapter
  unten).
- **Auf der Uhr selbst** kann der Emulator nicht laufen: Wear OS hat kein WebView. Ein mitgelieferter
  Browser (GeckoView) plus ein Nachbau von Faceclaws TypeScript-Gastgeber in Kotlin wäre praktisch eine
  Neuentwicklung mit großem Speicher- und Akkubedarf. Deshalb läuft der Adapter auf dem Rechner.

## 3. Der EvenHub-Adapter in `g2-host` (zu bauen in M4)

```
g2-host                                                                 Uhr
 ├─ Chromium (Playwright), je App ein eigener Kontext
 │    App unter http://<package>.localhost:8790/  + Brücken-Skript
 │        │ callHandler("evenAppMessage", …)          ▲ _listenEvenAppMessage(…)
 ├─ EvenHubSession (TypeScript)                        │
 │    Container-Modell · Liste/Fokus · Speicher · Berechtigungen
 │    Zeichner: Container → 576×288 Graustufen   ──── frame (gray4, nur geänderter Bereich) ──▶ image-Baustein
 │    Eingaben ◀──────────────────────────────────── event gesture / audio / imu ◀───────────── Bügel, Ring, Uhr
```

### 3.1 Laden

- Eine installierte App liegt als `~/.g2-host/apps/<package_id>/` mit `app.json` und `dist/`.
- Der Host liefert `dist/` unter `http://<package_id>.localhost:8790/` aus, nur für den eigenen Rechner.
  Chromium löst `*.localhost` lokal auf, so bekommt jede App ihren eigenen Ursprung und damit ihren
  eigenen Browser-Speicher.
- Playwright startet Chromium ohne sichtbares Fenster, mit einem **persistenten Kontext je App**
  (`~/.g2-host/data/<package_id>/browser/`). Die Hintergrund-Drosselung wird abgeschaltet
  (`--disable-background-timer-throttling`, `--disable-renderer-backgrounding`), damit Timer auch bei
  „unsichtbarer“ Seite laufen.
- Die Seite der Sitzung auf der Uhr: eine Vollbild-Seite `{ "id": "p_evenhub", "statusBar": false,
  "blocks": [ { "id": "leinwand", "type": "image", "src": null, "w": 576, "h": 288, "bleed": true } ] }`.
  Alles Weitere kommt als `frame` für `leinwand`.
- Das Brücken-Skript kommt vor jedem anderen Skript über `context.addInitScript`. Es definiert
  `window.flutter_inappwebview.callHandler` und leitet an eine Funktion weiter, die mit
  `page.exposeBinding` an den Host gebunden ist. Ereignisse gehen mit `page.evaluate` an
  `window._listenEvenAppMessage`.
- Netzwerk: Anfragen an Hosts, die nicht in der `network`-Freigabe stehen, blockiert der Adapter mit
  `context.route`. Das ist strenger als Faceclaw.

### 3.2 Methoden

| Methode | Verhalten im Adapter |
|---|---|
| `createStartUpPageContainer` | Seite anlegen, Ergebnis `0`; ein zweiter Aufruf liefert `1` (wie das Original); danach Ereignis „Vordergrund an“ |
| `rebuildPageContainer` | ganze Seite ersetzen, `true` |
| `textContainerUpgrade` | Text eines Containers ändern (nach `containerID`), Original-Verhalten „ab Position schreiben und abschneiden“ |
| `updateImageRawData` | PNG, BMP (1/4/8/24/32 Bit) oder rohe 4/8-Bit-Pixel in Containergröße; Ergebnis als Zahl wie beim Original (0 = `success`, 1 = `imageException`, 2 = `imageSizeInvalid`, 3 = `sendFailed`; das SDK macht daraus seinen Enum) |
| `shutDownPageContainer` | `exitMode 1` → Rückfrage auf der Brille „Beenden?“, sonst Ende nach 200 ms |
| `setLocalStorage` / `getLocalStorage` | Speicher der App im Host (`data/<id>/store.json`) |
| `getUserInfo` | feste Werte (`name: "G2 Watch"`) |
| `getGlassesInfo`, Ereignis `deviceStatusChanged` | Modell `g2`; **echter** Akkustand und „getragen“ von der Uhr, sobald die Uhr sie meldet |
| `audioControl` | ab M5; nur mit Mikrofon-Berechtigung: `cmd audio` an die Uhr, LC3 vom Uhr-Client im Host entschlüsseln, als `audioEvent` mit `audioPcm` = Zahlen-Array der PCM-Bytes (s16le) wie bei Faceclaw (SDK-Typ `Uint8Array`); bis M5 Ergebnis `false` |
| `imuControl` | ab M5: `cmd subscribe imu` (Takt 100–1000 wie beim Original); Werte als `sysEvent` Typ 8 mit `imuData`; bis M5 `false` |
| `getAppLocation`, `start/stopAppLocationUpdates` | später vom GPS der Uhr (Berechtigung `location`); bis dahin `null` |
| `pickImageFromAlbum`, `captureImageFromCamera` | nicht möglich, `null` |

### 3.3 Zeichnen

- Leinwand 576 × 288 (Vollbild-App-Fläche, `statusBar: false`), Graustufen 0–15.
- Reihenfolge: Bilder unter Listen und Text, sonst nach `zOrderIndex`. Text in 5 Helligkeiten (0–4 →
  Grau 0, 64, 128, 191, 255). Listen: gewählter Eintrag mit Rahmen, die anderen gedimmt.
- Schrift: Das Original nutzt eine feste 20-px-Schrift aus Evens Firmware. Die darf nicht mitgeliefert
  werden. Der Adapter nimmt eine freie Schrift mit ähnlichen Maßen (z. B. Noto Sans 20 px). Zeilenumbrüche
  können deshalb leicht abweichen.
- Zeichnen im Host mit `@napi-rs/canvas` (ohne System-Abhängigkeiten). Nach jeder Änderung vergleicht der
  Adapter mit dem letzten Bild und schickt nur das geänderte Rechteck als `frame` im Format `gray4` an
  einen einzigen `image`-Baustein der Seite (01 §1: Übertragung sparen).

### 3.4 Eingaben

Die Sitzung läuft mit `input: "gestures"` ([02 §7](02_App-Modell.md#7-eingabe-und-fokus)).

| Geste von Uhr/Bügel/Ring | an die App |
|---|---|
| `click` | Ist der Eingabe-Container eine Liste: `listEvent` Klick mit gewähltem Eintrag, sonst `sysEvent` Klick |
| `doubleClick` | `sysEvent` Doppelklick (die App entscheidet, meist „Beenden?“) |
| `scrollUp` / `scrollDown` | In einer Liste bewegt der Adapter die Auswahl selbst und zeichnet neu; am Rand bzw. ohne Liste `sysEvent` Scroll oben/unten |
| `longPress` / `longPressRelease` | Codes 9 / 10 |
| `shortThenLongPress` (Tippen-dann-Halten) | öffnet das App-Menü der Uhr ([02 §5](02_App-Modell.md#5-navigation-und-app-menü)). Der Adapter hält es mit `cmd menu` aktuell (die `menuObject`-Einträge der App-Seite); ein gewählter Eintrag kommt als Ereignis `menu` zurück und geht als `menuItemClickEvent` an die App |
| Zurück (Uhr: Wischen nach rechts, oder „Zurück“/„Schließen“ im App-Menü) | Die EvenHub-App hat aus Sicht der Uhr nur eine Seite, Zurück schließt sie also: `sysEvent` System-Ende 7, 200 ms später Ende der Sitzung |

Quelle: `right` → 1, `ring` und `watch` → 2, `left` → 3.

### 3.5 Tests

- Eine eigene kleine Test-App im Repo (`host/test/evenhub-fixture/`, mit dem offiziellen SDK gebaut), die
  jede Methode aufruft und jedes Ereignis anzeigt. Der Test prüft die Antworten und die gezeichneten Bilder.
  Mikrofon und IMU prüft erst M5; in M4 müssen `audioControl`/`imuControl` `false` liefern.
- Die Vorlage aus `evenhub init` (offizielle CLI) muss im Adapter starten und auf Klicks reagieren.
- Zum Abgleich der Darstellung kann der offizielle Simulator `@evenrealities/evenhub-simulator` dienen.
  Er ist laut Even kein Hardware-Emulator.

## 4. Lizenz

- Die SDK-Typen (`index.d.ts`) stehen unter MIT. Einen eigenen Adapter danach zu schreiben ist unproblematisch.
- Faceclaws Umsetzung (`app/apps/evenhub/*.ts`) steht unter GPL-3.0. Teile daraus zu übernehmen ist
  erlaubt, dann steht `g2-host` unter GPL-3.0. Das passt zum Repo, das Faceclaws Kern schon enthält.
  Übernommene Dateien bekommen einen Herkunftsvermerk wie `faceclaw-core/UPSTREAM.md`.

## 5. Woher Apps kommen dürfen

| Quelle | Weg im Adapter |
|---|---|
| **Eigene Apps** mit dem offiziellen SDK und der CLI (`evenhub init`, `vite build`) | Ordner mit `app.json` + `dist/` in der App-Verwaltung installieren |
| **Quelloffene Even-Hub-Apps** (z. B. auf GitHub, Lizenz beachten) | Git-Adresse angeben: der Host klont, installiert Pakete ohne Skripte, baut nach Bestätigung und installiert ([06 §3](06_App-Verwaltung.md#3-installieren)) |
| **`.ehpk`-Dateien, die du rechtmäßig hast** (z. B. eigene Builds aus `evenhub pack`) | Datei hochladen; der Host entpackt sie (Format §1) |
| **Entwicklungs-Server** (`vite dev`) | Adresse angeben, App läuft direkt von dort (wie Faceclaws „Load app from URL“) |

**Nicht vorgesehen: Herunterladen aus Evens Store.** Evens Store-Server ist nicht öffentlich. Faceclaws
Store-Client meldet sich mit dem Even-Konto an, unterschreibt jede Anfrage mit einem Schlüssel aus Evens
eigener App und gibt sich als die offizielle Android-App aus. Evens Nutzungs- und Entwicklerbedingungen
verbieten, die Schnittstelle zu entschlüsseln oder Inhalte automatisiert abzugreifen. Dieses Projekt baut
deshalb keinen solchen Zugang nach und übernimmt auch nicht Faceclaws Store-Code. Wer den Store nutzen
möchte, kann das mit Faceclaw auf dem Handy tun, auf eigene Verantwortung, und die Apps dort verwenden.
