# 02 – App-Modell

Dieses Kapitel gilt für **beide Laufzeiten** (Uhr und Rechner). Uhr-Apps benutzen es über
Kotlin-Klassen ([03](03_Uhr-Apps.md)), Rechner-Apps über TypeScript und das Protokoll
([04](04_Rechner-Apps_und_Protokoll.md)). Die JSON-Formen hier sind die gemeinsame Wahrheit: Kotlin-
und TypeScript-Typen werden daraus abgeleitet, nicht umgekehrt.

## 1. Begriffe

| Begriff | Bedeutung |
|---|---|
| **App** | Ein Programm mit Manifest, Oberfläche und Logik. |
| **Sitzung** | Eine laufende App, auf der Uhr oder auf einem Rechner. Pro App höchstens eine Sitzung. |
| **Seite** | Eine Bildschirmseite der App: Name, Kopfzeile ja/nein, Liste von Bausteinen. |
| **Baustein** | Ein Element einer Seite: Überschrift, Text, Knopf, Liste, Schalter, Wert, Fortschritt, Trennlinie, Bild. |
| **Ereignis** | Etwas, das der App passiert: Klick, Schalter umgelegt, Geste, Timer, Sensorwert … |
| **Befehl** | Etwas, das die App tut: Seite zeigen, Werte ändern, Hinweis zeigen, vibrieren … |
| **App-Host** | Der Teil der Uhr-App, der Seiten zeichnet, Eingaben verteilt und Sitzungen verwaltet. |
| **Rechner-Host** | `g2-host` auf einem Rechner: führt Rechner-Apps aus. |
| **EvenHub-Laufzeit** | Teil der Uhr-App, der Even-Hub-Apps ausführt (Engine auf der Uhr oder auf dem Handy, [05](05_EvenHub-Apps.md)). |
| **Starter** | Die Seite „Apps“ auf der Brille, vom App-Host selbst gezeichnet: alle Apps als Symbol mit Namen in einem Raster (4 × 2 je Bildschirm), laufende mit einem Punkt markiert. Seit v0.11.0 das Hauptmenü der Brille: Er steht nach dem Verbinden auf ihr, jede App kehrt zu ihm zurück, und Zurück bleibt auf ihm stehen. |
| **App-Menü** | Ein vom App-Host gezeichnetes Menü über jeder App: eigene Einträge der App (Befehl `menu`), dann „Apps“ (App bleibt im Hintergrund), „Zurück“, „Schließen“. |

## 2. Manifest `g2app.json`

Jede App hat ein Manifest. Uhr-Apps tragen es als Kotlin-Objekt ([03](03_Uhr-Apps.md)), Rechner-Apps
als Datei im App-Ordner.

```json
{
  "format": "g2app@1",
  "id": "ch.madtreasures.einkauf",
  "name": "Einkauf",
  "version": "1.0.0",
  "runtime": "remote",
  "entry": "index.ts",
  "ui": "ui.json",
  "input": "pointer",
  "permissions": ["network"],
  "description": "Einkaufsliste mit Häkchen, gespeichert auf dem PC."
}
```

| Feld | Pflicht | Bedeutung |
|---|---|---|
| `format` | ja | immer `"g2app@1"` |
| `id` | ja | eindeutig, kleingeschrieben, umgekehrte Domain: `[a-z][a-z0-9_]*(\.[a-z0-9_]+)+`, ≤ 64 Zeichen |
| `name` | ja | Anzeigename, ≤ 20 Zeichen (passt in Kopfzeile und Liste) |
| `version` | ja | `x.y.z` |
| `runtime` | ja | `"watch"` (Uhr-App), `"remote"` (Rechner-App); Even-Hub-Apps haben ihr eigenes `app.json` und brauchen kein `g2app.json` ([05](05_EvenHub-Apps.md)) |
| `entry` | bei `remote` | Einstiegsdatei (TypeScript/JavaScript-Modul) |
| `ui` | nein | Pfad zu einem Baukasten-Export (`g2-baukasten@1`). Der Host lädt ihn **vor** `start`; die Seiten sind dann schon bekannt. |
| `input` | nein | `"pointer"` (Standard: Maus-Zeiger + Fokus) oder `"gestures"` (rohe Gesten, z. B. für Spiele und EvenHub-Apps), siehe §7 |
| `permissions` | nein | siehe §8 |
| `description` | nein | ein Satz für die App-Verwaltung |
| `icon` | nein | Symbol im Starter: PNG, quadratisch, **64 × 64** empfohlen (16 bis 256 px, höchstens 64 KB), Graustufen. Schwarz und Transparenz sind auf dem Glas durchsichtig. Ruhige Strichzeichnung (2–3 px, Stufen 10–15), keine großen hellen Flächen (§9). Ohne Symbol zeigt der Starter die Anfangsbuchstaben in einem Rahmen. Uhr-Apps legen die Datei nur ins Paket, `apps/<app-id>/icon.png` ([09 §2](09_App-Pakete.md#2-das-paket)); im `AppManifest` steht es nicht. |

## 3. Lebenszyklus

```
(installiert) ──start──▶ läuft, sichtbar ◀──visible/hidden──▶ läuft, verdeckt ──stop──▶ (beendet)
```

- **start**: Der Träger wählt die App im Starter (oder in der App-Verwaltung). Die App bekommt `start`,
  gleich danach `visible`. Sie muss **innerhalb von 2 s** eine Seite zeigen. Hat das Manifest `ui` und
  zeigt die App nichts, zeigt der Host die Startseite des Baukasten-Projekts (`start`). Ohne `ui` und
  ohne Seite zeigt der Host „App antwortet nicht“ und beendet sie nach 10 s. Ausnahme: Even-Hub-Apps
  bekommen eine Seite „Startet …“ und 20 s ([05 §4.4](05_EvenHub-Apps.md#44-lebenszyklus-rechte-netz)).
- **visible / hidden**: Nur eine App ist auf der Brille sichtbar. Über das App-Menü → „Apps“ kommt der
  Träger zum Starter; die bisherige App bekommt `hidden` und läuft weiter. Im Starter sind laufende Apps
  mit einem Punkt rechts oben am Symbol markiert; wählt der Träger eine davon, bekommt sie `visible` und erscheint mit ihrer aktuellen Seite.
  Anzeige-Befehle einer verdeckten App speichert der Host (der neueste Stand gilt).
  Uhr-Apps dürfen verdeckt nur Timer ausführen, und nur mit `background`; Rechner-Apps unbegrenzt.
- **stop**: „Schließen“ im App-Menü, Zurück auf der ersten Seite, `close()` durch die App oder Beenden
  durch den Host. Die App bekommt `stop` und hat **1 s** zum Aufräumen; auf der Brille steht danach der
  Starter.
- Verbindungsabbruch Uhr ↔ Rechner: Rechner-Apps laufen weiter; nach dem Wiederverbinden zeigt die
  Uhr die aktuelle Seite erneut ([04 §5.6](04_Rechner-Apps_und_Protokoll.md#56-verbindung-verloren-und-wieder-da)).

## 4. Oberfläche: Seiten und Bausteine

Das Format ist das des **G2 Baukastens** (`g2-baukasten@1`, siehe
[`designer/README.md`](../../designer/README.md)), ergänzt um den Baustein `image` und das Knopf-Ziel
`@back` (beides baut M0 in den Baukasten ein). Eine App kann ihre Seiten im Baukasten entwerfen, als JSON
exportieren und als `ui.json` mitliefern.

### 4.1 Seite und Kennungen

```json
{ "id": "p_start", "name": "Start", "statusBar": true, "notes": "…",
  "blocks": [ { "id": "titel", "type": "heading", "text": "Guten Morgen", "align": "left", "size": "normal" } ] }
```

- **Kennungen** (`id` von Seiten und Bausteinen): `[A-Za-z0-9_.-]{1,40}`, **eindeutig im ganzen
  Projekt** – Seiten und Bausteine teilen einen Namensraum, genau wie im Baukasten (dessen `normalize`
  benennt Doppelte um). Apps sprechen Bausteine über diese Kennungen an; deshalb bekommen sie im
  Baukasten lesbare Namen (M0).
- `name` steht in der Kopfzeile hinter dem App-Namen („Einkauf · Liste“), wenn er sich von ihm unterscheidet.
- `statusBar: true` → App-Fläche 576 × 260 unter der Kopfzeile; `false` → Vollbild 576 × 288.
- `notes` wird nicht angezeigt (Beschreibung aus dem Baukasten, was die Seite tun soll).
- `input` (optional, seit v0.5.0): `"pointer"` oder `"gestures"` gilt für diese Seite statt der Eingabeart
  aus dem Manifest (§7). So nimmt etwa das Video der YouTube-App rohe Gesten (Tippen = Pause), während die
  Listen davor mit Zeiger und Fokus gehen. Fehlt es, gilt das Manifest. (Der Baukasten hat dafür noch kein
  Feld; ein vorhandenes `input` bleibt beim Import erhalten.)
- Bausteine stehen untereinander in der gegebenen Reihenfolge. Es gibt keine freie Positionierung.

### 4.2 Bausteine

Alle Bausteine haben `id` und `type`.

| `type` | Felder | Darstellung (Maße für die App-Fläche) | fokussierbar |
|---|---|---|---|
| `heading` | `text`, `align` (`left`/`center`), `size` (`normal`/`gross`) | 28 px halbfett (gross: 36 px), hellste Stufe | nein |
| `text` | `text` (mehrzeilig, `\n` erlaubt), `align` | 22 px, umbrechend | nein |
| `button` | `text`, `target` (Seiten-`id`, `"@back"` oder `null`), `action` (freier Text, nur Beschreibung) | Kapsel 40 px hoch, Rand; mit Ziel ein „›“ rechts | ja |
| `list` | `style` (`bullets`/`checks`/`numbers`), `items`: `[{ "text", "done" }]` | Zeilen 30 px; bei `checks` Kästchen, die man anhaken kann | bei `checks` jede Zeile |
| `toggle` | `text`, `on` | Zeile 36 px, Schalter rechts | ja |
| `value` | `text` (Bezeichnung), `value` (Text, z. B. `"80 %"`) | Zeile 36 px, Wert rechtsbündig, gedimmt | nein |
| `progress` | `text`, `value` (0–100) | Beschriftung 16 px + Balken 8 px | nein |
| `divider` | – | Linie 2 px, 6 px Abstand oben und unten | nein |
| `image` | `src`, `w`, `h`, `align`, `bleed` | Bild, auf 16 Stufen gerundet | nein |

Allgemeine Maße: Seitenrand links/rechts 16 px, Abstand zwischen Bausteinen 8 px, Innenabstand oben und
unten 8 px (ein randloses Bild als erster Baustein beginnt ganz oben).

**Graustufen:** 0–15. Der Kern der Uhr rundet 8-Bit-Grau mit `min(15, (v + 8) >> 4)` auf Stufen
(`BmpUtil.kt`); eine Stufe wird also mit Grauwert **Stufe × 16** gezeichnet (Stufe 15 = 255). Verwendet:
Text 13, stark 15, gedimmt 9, schwach 6, Fläche 2, Rand 10, Linie 3.

Der Baukasten zeigt heute noch die ganze 640×480-Fläche mit größeren Maßen. Ihn auf die App-Fläche
umzustellen ist Meilenstein M0 ([07](07_Umsetzungsplan.md)).

**`image`:**
- `src`: `"data:image/png;base64,…"` (≤ 48 KiB Text, passt in eine Protokoll-Nachricht), `"asset:<datei>"`
  (Datei im App-Paket; eine Rechner-App schickt sie als Binär-`frame`, [04 §5.4](04_Rechner-Apps_und_Protokoll.md#54-binärrahmen))
  oder `null` (schwarz, wird per `frame` gefüllt).
- PNG in Graustufen (8 oder 4 Bit) oder mit Farbe (wird in Helligkeit umgerechnet).
- Größe: normal `w` ≤ 544, `h` ≤ 260 (innerhalb der Seitenränder). Mit `bleed: true` auf einer
  Vollbild-Seite (`statusBar: false`) bis 576 × 288 ohne Ränder – so zeigen Karten, Diagramme und die
  EvenHub-Laufzeit ganze Bilder.
- Große Bilder kosten Übertragungszeit ([01 §1](01_Plattform_und_Grenzen.md#übertragung-uhr--brille-bluetooth-le));
  wo es geht, Bausteine bevorzugen.
- Ein Bild-Baustein kann auch ein **Video** (Befehl `video`) oder eine **Web-Seite** (Befehl `web`, seit
  Schnittstelle 2) zeigen; die Uhr schreibt die Bilder dann selbst hinein. Eine Web-Seite nimmt Klicks und
  Scrollen an (§6.1 `imageClick`, `imageScroll`).

**Rollen:** Ist der Inhalt höher als die App-Fläche, scrollt die Seite senkrecht. Der Host hält den
fokussierten Baustein sichtbar und zeigt rechts einen 3 px schmalen Scroll-Balken.

## 5. Navigation und App-Menü

- Ein `button` mit Seiten-`target` zeigt die Zielseite **sofort auf der Uhr** (keine Rückfrage beim
  Rechner) und schickt danach `navigate` an die App. Die App kann die Seite daraufhin ändern.
- Ein `button` ohne `target` schickt nur `click`.
- Ein `button` mit `target: "@back"` löst Zurück aus (unten) und schickt nur `back`, kein `click`.
- **Zurück** führt zur vorigen Seite aus dem Verlauf der Sitzung; auf der ersten Seite schließt es die
  App (`stop`). Das garantiert der Host, eine App kann es nicht abschalten. Die App bekommt `back` vor
  dem Seitenwechsel. Einzige Ausnahme: Zeigt die Seite eine **Web-Seite** (Befehl `web`) mit einer früheren
  Seite in deren eigenem Verlauf, geht Zurück erst dorthin, wie in jedem Browser (der Host macht das selbst,
  die App bekommt `web`, nicht `back`); hat sie keine mehr, gilt die Regel. Auslöser:
  - Doppeltippen an Bügel oder Ring (bei EvenHub-Apps nicht: dort gehört Doppeltippen der App),
  - der Pfeil „‹“ links in der Kopfzeile (mit dem Zeiger anklicken),
  - Wischen nach rechts auf der Uhr (im Modus `gestures`),
  - ein Knopf mit `target: "@back"`. Vollbild-Seiten (`statusBar: false`) haben keine Kopfzeile und
    brauchen in `pointer`-Apps so einen Knopf.
- **App-Menü** (§1): öffnet mit Tippen-dann-Halten an Bügel oder Ring oder mit einem Klick auf den
  App-Namen in der Kopfzeile. Einträge: zuerst die eigenen der App (Befehl `menu`, höchstens 10), dann
  „Apps“, „Zurück“, „Schließen“. Das Menü gehört dem Host; die App bekommt davon nur die Folgen
  (`menu` mit dem gewählten Eintrag, `hidden`, `back`, `stop`).
- `show(pageId)` durch die App legt die Seite auf den Verlauf; `replace(pageId)` ersetzt die aktuelle.

## 6. Ereignisse und Befehle

### 6.1 Ereignisse (Host → App)

Gleiche JSON-Form in beiden Laufzeiten:

```json
{ "kind": "start" }
{ "kind": "visible" }            { "kind": "hidden" }            { "kind": "stop" }
{ "kind": "click",    "page": "p_start", "block": "radio" }
{ "kind": "toggle",   "page": "p_set",   "block": "leise", "on": true }
{ "kind": "check",    "page": "p_liste", "block": "items", "index": 2, "done": true }
{ "kind": "navigate", "from": "p_start", "to": "p_liste", "block": "zur_liste" }
{ "kind": "back",     "page": "p_liste" }
{ "kind": "gesture",  "gesture": "scrollDown", "source": "right" }
{ "kind": "timer",    "tag": "tick" }
{ "kind": "sensor",   "sensor": "imu", "x": 0.02, "y": -0.98, "z": 0.11, "t": 1727600000123 }
{ "kind": "sensor",   "sensor": "compass", "heading": 213.5, "t": 1727600000123 }
{ "kind": "sensor",   "sensor": "location", "lat": 47.37, "lon": 8.54, "acc": 12, "t": 1727600000123, "speed": 1.4, "bearing": 92, "alt": 412 }
{ "kind": "audio",    "seq": 17, "pcm": "<16-kHz-PCM, siehe unten>" }
{ "kind": "menu",     "item": "sortieren" }
{ "kind": "text",     "tag": "suche", "text": "Katzen im Schnee" }
{ "kind": "video",    "block": "bild", "state": "playing", "position": 83000, "duration": 296000, "message": null }
{ "kind": "web",      "block": "seite", "state": "ready", "url": "https://srf.ch/", "title": "SRF", "progress": 100,
  "back": true, "forward": false, "reader": false, "readable": true, "field": null, "message": null }
{ "kind": "imageClick",  "page": "p_seite", "block": "seite", "x": 120, "y": 80 }
{ "kind": "imageScroll", "page": "p_seite", "block": "seite", "dy": 195 }
{ "kind": "key",      "key": "é", "text": "é", "shift": false, "ctrl": false, "alt": false, "meta": false, "repeat": false }
{ "kind": "phone",    "state": "message", "text": "{\"t\":\"state\",\"running\":true}", "message": null }
{ "kind": "download", "tag": "dl1", "state": "running", "bytes": 52428800, "total": 334266727, "message": null }
{ "kind": "media",    "players": [{ "app": "com.spotify.music", "appName": "Spotify", "state": "playing", "title": "W. Nuss vo Bümpliz",
  "artist": "Patent Ochsner", "album": "Gmües", "duration": 271000, "position": 61000, "positionAt": 1761660300000, "speed": 1.0,
  "artwork": "data:image/png;base64,…", "canNext": true, "canPrevious": true, "canSeek": true, "canSearch": false }],
  "volume": 7, "maxVolume": 15, "output": "Kopfhörer: Pixel Buds", "message": null }
{ "kind": "player",   "state": "paused", "url": "https://r.example/s", "title": null, "station": null, "message": "Eine andere App spielt" }
{ "kind": "error",    "command": "patch", "code": "unknown_block", "message": "…" }
```

- `toggle` und `check`: Der Host schaltet **sofort sichtbar** um und meldet es dann. Will die App es
  nicht zulassen, setzt sie den Wert mit `patch` zurück. Der Host speichert den neuen Wert in seinem
  Seitenstand.
- `gesture` kommt nur bei `input: "gestures"` oder wenn die App `gestures` abonniert hat. Werte:
  `click`, `doubleClick`, `scrollUp`, `scrollDown`, `longPress`, `longPressRelease`,
  `shortThenLongPress`, `press` (Berührung beginnt, für Spiele), `headUp`, `swipeLeft`, `swipeRight`
  (nur Uhr). `source`: `watch`, `left`, `right`, `ring`, `unknown` (Wischen am Bügel meldet die Brille
  ohne Seite, [03 §5.1](03_Uhr-Apps.md#51-gesten-der-brille)).
- `audio`: `pcm` sind entschlüsselte Abtastwerte, 16 kHz, mono, 16 Bit (Kotlin `ShortArray`, TypeScript
  `Int16Array`), je Ereignis 50 ms. Das Ereignis geht nie als JSON über die Leitung; die Uhr schickt die
  LC3-Pakete binär, der Rechner entschlüsselt ([04 §5.4](04_Rechner-Apps_und_Protokoll.md#54-binärrahmen)).
- `text` (seit v0.5.0): Antwort auf `askText` mit demselben `tag`; `text: null`, wenn die Trägerin abgebrochen
  hat oder eine neuere Frage die alte ersetzt hat.
- `video` (seit v0.5.0): Zustand des Videos in einem Bild-Baustein: `loading`, `playing`, `paused`,
  `buffering`, `ended`, `error` (dann steht in `message` der Grund, deutsch). Kommt bei jedem Wechsel und
  während des Abspielens etwa einmal pro Sekunde mit `position`; `duration` 0 = unbekannt (live).
- `web` (seit Schnittstelle 2): Zustand der Web-Seite in einem Bild-Baustein: `loading` (mit `progress`
  0–100), `ready`, `error` (Grund in `message`). Dazu Adresse, Titel, ob es eine frühere (`back`) oder spätere
  (`forward`) Seite gibt, ob der Lesemodus zeigt (`reader`) oder möglich ist (`readable`) und das Eingabefeld
  mit dem Cursor (`field`: `{ "label", "value", "password", "multiline" }` oder `null`); `message` ohne
  `error` ist ein Hinweis der Uhr (etwa ein Link, der eine andere App öffnen würde). Kommt bei jeder Änderung.
- `imageClick` / `imageScroll` (seit Schnittstelle 2): Klick an einer Stelle bzw. Scrollen um `dy` Pixel
  (positiv = weiter nach unten) über einem Bild-Baustein, der eine Web-Seite zeigt, in Pixeln des Bausteins.
  `imageClick`: Doppeltippen auf der Uhr mit dem Zeiger auf dem Baustein, oder Tippen am Bügel, während der
  Zeiger dort steht. `imageScroll`: Wischen am Bügel (¾ der Bausteinhöhe), wenn kein Fokus mehr weiterkommt
  oder der Zeiger auf dem Baustein steht; der Zeiger über den Rand geschoben, wenn die Seite selbst nicht
  mehr scrollt.
- `key` (seit Schnittstelle 3): eine Taste einer Bluetooth-Tastatur an der Uhr, nur nach `subscribe` mit
  `keys` (§7). `key` ist der Name, den eine Web-Seite als `KeyboardEvent.key` sieht: `Enter`, `Escape`,
  `Backspace`, `Delete`, `Tab`, `ArrowUp`, `ArrowDown`, `ArrowLeft`, `ArrowRight`, `PageUp`, `PageDown`,
  `Home`, `End`, `ContextMenu`, `F1` … `F12`, `MediaPlayPause` und weitere (Kotlin: `Keys`); sonst das Zeichen,
  das die Taste in der Belegung tippt (`"a"`, `"A"`, `"@"`, `"é"`; mit Strg weiterhin `"l"` für Strg+L). `text`
  ist, was die Taste in ein Feld schreibt, leer bei Tasten ohne Zeichen und bei Kürzeln mit Strg, Alt oder Meta.
  `alt` meint nur die linke Alt-Taste, AltGr steckt im Zeichen; `repeat` die Wiederholungen einer gehaltenen
  Taste. Eine Akzent-Taste (^ ´ ` ~ ¨) meldet sich nicht selbst, sondern mit dem nächsten Buchstaben (`é`).
  Unbekannte Namen ignoriert eine App.
- `phone` (seit Schnittstelle 4): die Verbindung zu einer App auf dem Handy, nur nach dem Befehl `phone` mit
  `connect` (§6.2): `connected` (sie steht), `message` (eine Nachricht der App auf dem Handy in `text`,
  unverändert), `disconnected` (zu Ende oder nicht zustande gekommen, auch ohne Berechtigung; der Grund deutsch
  in `message`). Jedes `connect` endet in `connected` oder `disconnected`; danach verbindet nur ein neues
  `connect` wieder.
- `sensor` mit `location` (Uhr-Apps seit v0.12.0): Standort vom GPS der Uhr, `acc` in Metern; seit Schnittstelle 5
  auch `speed` (m/s), `bearing` (Fahrtrichtung in Grad, 0 = Norden) und `alt` (Meter über Meer), jeweils nur, wenn
  die Uhr es weiß. Kommt nur, solange die App zu sehen ist ([03 §14](03_Uhr-Apps.md#14-gps-große-dateien-und-hintergrundarbeit-v0120-schnittstelle-5)).
- `download` (Uhr-Apps, seit Schnittstelle 5): wie weit der Download `tag` ist, den die App mit `files.download`
  begonnen hat: `running` etwa einmal pro Sekunde mit `bytes` von `total` (−1, solange unbekannt), dann `done`,
  `failed` (Grund deutsch in `message`) oder `cancelled` ([03 §14](03_Uhr-Apps.md#14-gps-große-dateien-und-hintergrundarbeit-v0120-schnittstelle-5)).
- `media` (seit Schnittstelle 6): die Musik-Player der Uhr (Spotify, YouTube Music …), nur nach dem Befehl
  `media` mit `follow` (§6.2), sofort und bei jeder Änderung (anderes Lied, Play/Pause, Lautstärke, Kopfhörer):
  `players` – der spielende oder zuletzt spielende zuerst – mit Android-Paketname, Name, Zustand (`playing`,
  `paused`, `buffering`, `stopped`, `error`), Titel, Interpret, Album, `duration` (0 = unbekannt), `position` zum
  Zeitpunkt `positionAt` (ms seit 1970; während er spielt, läuft sie mit `speed` weiter), dem Cover grau als
  PNG-`data:`-URL (höchstens 120 × 120) und was er kann (`canNext` …); dazu `volume` von `maxVolume` (Lautstärke
  der Uhr für Medien), `output` (wohin der Ton geht, `null`, wenn nirgends hin) und `message` (warum `players`
  leer ist, etwa ohne Zugriff, deutsch). Details [03 §15](03_Uhr-Apps.md#15-musik-der-uhr-und-eigener-ton-v0130-schnittstelle-6).
- `player` (seit Schnittstelle 6): der eigene Stream der App (Befehl `player`): `state` `loading`, `playing`,
  `paused` (von der App oder weil ein anderer Player den Ton übernahm; der Grund in `message`), `buffering`,
  `ended`, `error` (Grund deutsch in `message`), dazu, was der Sender gerade spielt (`title`) und seinen Namen
  (`station`), soweit er sie mitschickt.
- `error`: nur bei Rechner-Apps; die Uhr hat einen Befehl abgelehnt (§6.2 „Fehler“).
- Unbekannte `kind`-Werte ignoriert eine App. So kann die Plattform neue Ereignisse ergänzen.

### 6.2 Befehle (App → Host)

| Befehl | Parameter | Wirkung |
|---|---|---|
| `definePages` | `pages[]` oder ein Baukasten-Projekt | Seiten bekannt machen (ersetzt gleichnamige) |
| `show` | `page` (id) | Seite zeigen, auf den Verlauf legen |
| `replace` | `page` (id) | Seite zeigen, ohne Verlauf (steht im Verlauf darunter schon diese Seite, geht es zu ihr zurück: eine Seite folgt nie auf sich selbst) |
| `patch` | `page`, `changes`: `{ "<blockId>": { <Felder> } }` | Felder von Bausteinen ändern (nur die genannten) |
| `setBlocks` | `page`, `blocks[]` | Alle Bausteine einer Seite ersetzen (für dynamische Listen) |
| `toast` | `text`, `ms` (Standard 2000) | kurzer Hinweis unten auf der App-Fläche |
| `vibrate` | `pattern` (`"tick"`, `"double"`, `"long"`) | Uhr vibriert |
| `menu` | `items`: `[{ "id", "text" }]`, ≤ 10, Text ≤ 32 Byte UTF-8 | eigene Einträge im App-Menü (§5); `[]` entfernt sie |
| `buzz` | `notes`: `[[freqHz, dutyProzent, ms], …]`, ≤ 48 Schritte | Summer der Brille (Berechtigung `buzzer`) |
| `timer` | `tag`, `ms`, `repeat` | Ereignis `timer` nach `ms` (wiederholt, wenn `repeat`) |
| `cancelTimer` | `tag` | |
| `subscribe` / `unsubscribe` | `sensor` (`imu`, `compass`, `location`, `gestures`, `keys`), `rate` | Ereignisse an/aus (Berechtigung nötig). `rate` bei `imu`: Firmware-Takt 100–1000 (Einheit von Even nicht dokumentiert); bei `location`: Sekunden. `keys` (seit Schnittstelle 3, keine Berechtigung): Tasten einer Bluetooth-Tastatur als Ereignis `key` statt der Bedienung durch den Host (§7) |
| `audio` | `on` (bool) | Mikrofon der Brille an/aus (Berechtigung `mic`) |
| `storage.get` / `storage.set` | `key`, `value` (JSON) | kleiner Speicher je App (≤ 256 KiB gesamt) |
| `askText` | `tag`, `prompt` (≤ 100 Zeichen), `suggestions` (≤ 5, je ≤ 40 Zeichen) | Tastatur/Spracheingabe auf der Uhr, auf der Brille der Hinweis „Bitte auf der Uhr eingeben“; Antwort als Ereignis `text` (seit v0.5.0). Ist eine Bluetooth-Tastatur verbunden, steht die Frage auf der Brille und wird dort getippt (seit v0.9.0, für die App gleich) |
| `video` | `block` (ein `image`), `action`: `play` (`src`, `profile` `stable`/`balanced`/`fast`, `sound`, `start` in ms), `pause`, `resume`, `seek` (`position`), `profile` (`profile`), `stop` | Video in einen Bild-Baustein; `src` ist eine Video-Seite (YouTube …), eine Video-Datei/HLS-Adresse (`https://`, Berechtigung `network`) oder `test:muster`. Zustand als Ereignis `video`. Details [03 §10](03_Uhr-Apps.md#10-video-auf-der-brille-v050) (seit v0.5.0) |
| `web` | `block` (ein `image`), `action`: `open` (`url` `https://`/`http://`, `reader`), `back`, `forward`, `reload`, `scroll` (`dy`), `tap` (`x`, `y`), `type` (`text`, `enter`), `key` (`key` wie beim Ereignis `key`, `shift`, `ctrl`, `alt`; seit Schnittstelle 3), `insert` (`text`: an der Stelle des Cursors, ohne zu ersetzen; seit Schnittstelle 3), `reader` (`on`), `contrast` (`outline`/`halo`/`plate`), `stop` | Web-Seite in einem Bild-Baustein (Berechtigung `network`): die Uhr lädt sie, zeichnet sie in der Größe des Bausteins und schreibt das Brillenbild hinein; Zustand als Ereignis `web`. `open` in einem Baustein mit Seite lädt die neue Adresse als nächste Seite ihres Verlaufs. Verdeckt ruht die Seite, mit der App endet sie. Details [05 §10.2](05_EvenHub-Apps.md#102-die-browser-app-m7-gebaut-in-v080) (seit Schnittstelle 2, v0.8.0) |
| `phone` | `action`: `connect` (`partner`: Android-Paketname der App auf dem Handy), `send` (`text`, ≤ 64 KiB), `microphone` (`on`), `disconnect` | Verbindung zu einer App auf dem gekoppelten Handy, über die Handy-App „G2 Handy“ (Berechtigung `phone`, für das Mikrofon der Uhr zusätzlich `watch-mic`). Eine Verbindung je App, sie endet mit der App; ein zweites `connect` zum selben Partner tut nichts. Nachrichten nur, solange sie steht, höchstens 20 je Sekunde. Das Mikrofon der Uhr (16 kHz, mono) geht zum Partner, nur solange die App zu sehen ist; verdeckt geht es aus und bleibt aus. Ergebnis als Ereignis `phone`. Details [03 §13](03_Uhr-Apps.md#13-apps-auf-dem-handy-v0100-schnittstelle-4) (seit Schnittstelle 4, v0.10.0) |
| `media` | `action`: `follow`, `unfollow`, `play`, `pause`, `playPause`, `next`, `previous` (je mit `app`, sonst der spielende oder zuletzt spielende Player), `seek` (`position` in ms, `app`), `search` (`query` ≤ 200 Zeichen, `app`), `open` (`app`), `volume` (`steps`, ±1 bis ±15) | Die Musik-Player der Uhr steuern (Berechtigung `media`); `app` ist ein Android-Paketname wie `com.spotify.music`. `follow` meldet sie als Ereignis `media`, bis `unfollow` oder zum Ende der App. Ohne Zugriff der Uhr-App auf die Mediensteuerung gehen Play, Pause, Weiter und Zurück als Medientasten an den Player, der zuletzt spielte; Suchen und Springen brauchen den Zugriff. Klappt etwas nicht, zeigt die Uhr den Grund kurz auf der Brille. Details [03 §15](03_Uhr-Apps.md#15-musik-der-uhr-und-eigener-ton-v0130-schnittstelle-6) (seit Schnittstelle 6, v0.13.0) |
| `player` | `action`: `play` (`url` `https://` oder `http://`, `title`), `pause`, `resume`, `stop` | Eigener Ton der App auf der Uhr, etwa ein Radiosender (Berechtigung `network`): Kopfhörer der Uhr oder ihr Lautsprecher, die Brille hat keinen. Ein Stream je App; ein neues `play` ersetzt ihn, verdeckt spielt er weiter, mit der App endet er. Er nimmt anderen Playern den Ton und gibt ihn ab, wenn einer startet. Zustand als Ereignis `player` (nach `stop` keines mehr). Details [03 §15](03_Uhr-Apps.md#15-musik-der-uhr-und-eigener-ton-v0130-schnittstelle-6) (seit Schnittstelle 6, v0.13.0) |
| `close` | – | App beenden |

Nur Uhr-Apps (Kotlin, seit Schnittstelle 5): ein eigener Ordner für große Dateien mit Downloads
(`files.download`, `files.cancelDownload`), Hintergrundarbeit (`work`) und eigene Dateien des Pakets (`asset`),
[03 §14](03_Uhr-Apps.md#14-gps-große-dateien-und-hintergrundarbeit-v0120-schnittstelle-5). Rechner-Apps haben
dafür ihren Rechner.

`patch`-Felder je Baustein: `heading`/`text`: `text`, `align`; `button`: `text`, `target`;
`list`: `items` (ganz) oder `item: { "index", "text"?, "done"? }`; `toggle`: `text`, `on`;
`value`: `text`, `value`; `progress`: `text`, `value`; `image`: `src`, `w`, `h`.

**Fehler:** Nennt ein Befehl eine unbekannte Seite oder einen unbekannten Baustein, hat die App keine
Berechtigung oder sind Werte ungültig, führt der Host ihn nicht aus und meldet einen Fehler
(Kotlin: Eintrag im Protokoll und `IllegalArgumentException` im Test-Fake; Rechner-Apps:
`cmd.error`, [04 §5.3](04_Rechner-Apps_und_Protokoll.md#53-nachrichten)). Nie still ignorieren.

**Taktung:** Der Host zeichnet höchstens alle **200 ms** neu und fasst dazwischen eingehende
Befehle zusammen (der neueste Stand gewinnt). Eine App darf also viele kleine `patch` schicken. Sinnvoll
sind aber höchstens 5 pro Sekunde, sonst entsteht nur Verkehr und Akkulast. Ausnahme Video (Befehl `video`):
Ein neues Videobild zeichnet der Host schon nach höchstens **66 ms**, mit allen Befehlen bis dahin, außer das
App-Menü verdeckt es ([03 §10](03_Uhr-Apps.md#10-video-auf-der-brille-v050)).

## 7. Eingabe und Fokus

Zwei Eingabearten, im Manifest gewählt (und seit v0.5.0 je Seite mit `input` überschreibbar, §4.1):

**`pointer` (Standard)** – wie der Starter:
- Uhr-Touchpad bewegt den Maus-Zeiger; Doppeltippen = Klick auf den Baustein unter dem Zeiger;
  ein Baustein unter dem Zeiger ist hervorgehoben (Rand heller).
- Bügel/Ring: Wischen hoch/runter bewegt den **Fokus** zum vorigen/nächsten fokussierbaren Baustein
  (die Seite scrollt mit); Tippen = Klick auf den fokussierten Baustein; Doppeltippen = Zurück.
- Zeiger und Fokus sind dasselbe Ziel: Bewegt sich der Zeiger über einen Baustein, bekommt er den Fokus.

**`gestures`** – für Spiele und EvenHub-Apps:
- Kein Zeiger. Das Uhr-Touchpad liefert Gesten: Wischen hoch/runter/links/rechts, Tippen, Doppeltippen,
  langes Drücken. Bügel/Ring liefern ihre Gesten ebenfalls als `gesture`-Ereignisse.
- Zurück und App-Menü gelten wie in §5.

| Eingabe | `pointer` | `gestures` |
|---|---|---|
| Uhr: Finger ziehen | Zeiger bewegen (über den Rand hinaus: Seite scrollen) | Wischen: Finger nach oben = `scrollDown`, nach unten = `scrollUp`, links = `swipeLeft`, rechts = Zurück |
| Uhr: Doppeltippen | Klick unter Zeiger | `doubleClick` |
| Uhr: Tippen | – (Zeiger-Ruhe) | `click` |
| Uhr: lang drücken | – | `longPress` |
| Bügel/Ring: Tippen | Klick auf Fokus | `click` |
| Bügel/Ring: Doppeltippen | Zurück | Zurück; bei EvenHub-Apps `doubleClick` an die App |
| Bügel/Ring: Wischen | Fokus vor/zurück | `scrollUp`/`scrollDown` |
| Bügel/Ring: lang drücken / loslassen | – | `longPress` / `longPressRelease` |
| Bügel/Ring: Tippen-dann-Halten | App-Menü | App-Menü |

**Bluetooth-Tastatur und -Maus** (seit v0.9.0, [03 §5.4](03_Uhr-Apps.md#54-tastatur-und-maus)) bedienen jede App
ohne ihr Zutun, solange die Uhr das Touchpad zeigt:

| Eingabe | `pointer` | `gestures` |
|---|---|---|
| Maus: bewegen | Zeiger bewegen (über den Rand hinaus: Seite scrollen) | – |
| Maus: links | Klick unter Zeiger | `click` |
| Maus: rechts | App-Menü | App-Menü |
| Maus: Mitte, Zurück-Taste | Zurück | Zurück |
| Maus: Rad | Seite scrollen; über einer Web-Seite `imageScroll` | je Raste `scrollUp`/`scrollDown` |
| Pfeile, Tab, Umschalt+Tab | Fokus vor/zurück (im Raster des Starters ↑ ↓ eine Reihe hoch/runter) | → ↓ Bild↓ = `scrollDown`, ← ↑ Bild↑ = `scrollUp` |
| Enter | Klick auf Fokus; Zeiger auf einer Web-Seite: `imageClick` dort | `click` |
| Leertaste, Bild↓, Bild↑ | Seite scrollen (¾); über einer Web-Seite `imageScroll` | Leertaste = `click` |
| Play/Pause | – | `click` |
| Esc, Alt+←, Rücktaste | Zurück | Zurück |
| Menütaste, F10 | App-Menü | App-Menü |

Die Gesten von Tastatur und Maus kommen mit `source: "watch"`. Nach `subscribe` mit `keys` bekommt die App
stattdessen jede Taste als Ereignis `key`; nur Esc, Alt+← und die Zurück-Taste der Tastatur (zurück), die
Menütaste und F10 (App-Menü) behält der Host, ebenso alle Tasten, solange eine Seite des Hosts (App-Menü, Frage)
darüber liegt. Maus und Rad bleiben beim Host. Eine gehaltene Taste wiederholt sich (beim Ereignis `key` mit `repeat`); was
der Host selbst damit tut, wiederholt er nur bei Pfeilen, Tab, Leertaste und Bild↑/↓, zurück, App-Menü und
Klick gelten einmal je Druck.

## 8. Berechtigungen

| Name | Erlaubt |
|---|---|
| `network` | Internet (Uhr-Apps: HTTP-Abfragen und Downloads; Rechner-Apps haben das immer, der Eintrag dient nur der Anzeige) |
| `mic` | Mikrofon der Brille |
| `imu`, `compass` | Sensor-Ereignisse |
| `location` | Standort der Uhr (GPS) als Sensor-Ereignis (Uhr-Apps seit v0.12.0) |
| `buzzer` | Summer der Brille |
| `background` | Uhr-Apps: Timer auch verdeckt |
| `phone` | Verbindung zu einer App auf dem Handy (Befehl `phone`, seit Schnittstelle 4) |
| `watch-mic` | Mikrofon der Uhr, zur App auf dem Handy gestreamt (Befehl `phone` mit `microphone`, seit Schnittstelle 4) |
| `media` | Musik der Uhr steuern: ihre Player sehen, spielen, anhalten, weiterschalten, Lautstärke (Befehl `media`, seit Schnittstelle 6) |

Die Berechtigungen stehen im Manifest und in der App-Liste, die der Host der Uhr meldet. Beim ersten
Start einer App fragt die Uhr einmal auf der Brille („Einkauf möchte: Mikrofon. Erlauben?“). Die Antwort
gilt, bis die App-Version wechselt. Nicht erlaubte Befehle werden mit einem Fehler beantwortet (§6.2).

## 9. Gestaltungsregeln für die Brille

1. **Auf einen Blick lesbar:** höchstens 5–6 Zeilen Inhalt je Seite sichtbar, kurze Wörter, die
   wichtigste Information oben.
2. **Dunkel ist durchsichtig:** keine großen hellen Flächen; Hervorhebungen über Rand und Helligkeit, nicht über gefüllte Flächen.
3. **Ruhe:** Werte höchstens jede Sekunde ändern, Sekundenzähler nur, wenn nötig. Keine Blink-Effekte.
4. **Zurück geht immer** (§5). Wichtige Aktionen brauchen einen Knopf, keine versteckte Geste.
5. **Antwortzeit:** Jede Aktion zeigt in unter 0,5 s eine Reaktion. Dauert die Arbeit länger, erst einen
   Zwischenstand zeigen (z. B. `toast("Rechne …")` oder einen `progress`-Baustein).
6. **Keine Geheimnisse anzeigen**, die Umstehende nicht sehen sollen, ohne dass der Träger es angefordert hat.

## 10. Beispiel: Einkaufsliste

`ui.json` (aus dem Baukasten, Kennungen lesbar vergeben):

```json
{ "format": "g2-baukasten@1", "name": "Einkauf", "start": "p_liste",
  "pages": [
    { "id": "p_liste", "name": "Einkauf", "statusBar": true, "notes": "Liste abhaken",
      "blocks": [
        { "id": "offen", "type": "value", "text": "Offen", "value": "3" },
        { "id": "items", "type": "list", "style": "checks",
          "items": [ { "text": "Milch", "done": false }, { "text": "Brot", "done": false }, { "text": "Äpfel", "done": false } ] },
        { "id": "leeren", "type": "button", "text": "Erledigte löschen", "target": null }
      ] } ] }
```

Ablauf:
1. Host lädt `ui.json`, dann `start` und `visible` → App: `storage.get("liste")`, `patch` mit der
   gespeicherten Liste, `show("p_liste")`.
2. Träger hakt „Brot“ ab → Host zeigt das Häkchen sofort → Ereignis `check {block:"items", index:1, done:true}`.
3. App: `storage.set("liste", …)`, `patch("p_liste", { "offen": { "value": "2" } })`.
4. Klick auf „Erledigte löschen“ → `click {block:"leeren"}` → App: `patch("p_liste", { "items": { "items": [ … ohne Brot … ] }, "offen": { "value": "2" } })`.
