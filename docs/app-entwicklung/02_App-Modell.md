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
| **Rechner-Host** | `g2-host` auf einem Rechner: führt Rechner-Apps und EvenHub-Apps aus. |

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
| `runtime` | ja | `"watch"` (Uhr-App), `"remote"` (Rechner-App), `"evenhub"` (Even-Hub-App über den Adapter) |
| `entry` | bei `remote` | Einstiegsdatei (TypeScript/JavaScript-Modul) |
| `ui` | nein | Pfad zu einem Baukasten-Export (`g2-baukasten@1`); dessen Seiten kennt die App dann ohne weiteres |
| `input` | nein | `"pointer"` (Standard: Maus-Zeiger + Fokus) oder `"gestures"` (rohe Gesten, z. B. für Spiele und EvenHub-Apps), siehe §7 |
| `permissions` | nein | siehe §8 |
| `description` | nein | ein Satz für die App-Verwaltung |
| `icon` | nein | PNG, 48 × 48, Graustufen |

## 3. Lebenszyklus

```
(installiert) ──start──▶ läuft, sichtbar ◀──visible/hidden──▶ läuft, verdeckt ──stop──▶ (beendet)
```

- **start**: Der Träger wählt die App im Starter auf der Brille (oder in der App-Verwaltung). Die App
  bekommt das Ereignis `start` und muss **innerhalb von 2 s** eine Seite zeigen. Tut sie es nicht,
  zeigt der Host „App antwortet nicht“ und beendet sie nach 10 s.
- **visible / hidden**: Nur eine App ist auf der Brille sichtbar. Eine verdeckte App läuft weiter,
  ihre Anzeige-Befehle werden gespeichert (die jeweils neueste Seite) und beim Wiedereinblenden gezeigt.
  Uhr-Apps dürfen verdeckt nur Timer und kurze Arbeiten ausführen; Rechner-Apps unbegrenzt.
- **stop**: Der Träger schließt die App (Doppeltippen auf der Startseite der App, oder „Schließen“),
  die App ruft `close()`, oder der Host beendet sie. Die App bekommt `stop` und hat **1 s** zum Aufräumen.
- Verbindungsabbruch Uhr ↔ Rechner: Rechner-Apps laufen weiter; nach dem Wiederverbinden zeigt die
  Uhr die aktuelle Seite erneut ([04 §5.6](04_Rechner-Apps_und_Protokoll.md#56-verbindung-verloren-und-wieder-da)).

## 4. Oberfläche: Seiten und Bausteine

Das Format ist das des **G2 Baukastens** (`g2-baukasten@1`, siehe
[`designer/README.md`](../../designer/README.md)), ergänzt um den Baustein `image`. Eine App kann
ihre Seiten im Baukasten entwerfen, als JSON exportieren und als `ui.json` mitliefern.

### 4.1 Seite

```json
{ "id": "p_start", "name": "Start", "statusBar": true, "notes": "…",
  "blocks": [ { "id": "titel", "type": "heading", "text": "Guten Morgen", "align": "left", "size": "normal" } ] }
```

- `id`: `[A-Za-z0-9_.-]{1,40}`, eindeutig in der App. `name` steht in der Kopfzeile.
- `statusBar: true` → App-Fläche 576 × 260 unter der Kopfzeile; `false` → Vollbild 576 × 288.
- `notes` wird nicht angezeigt (Beschreibung aus dem Baukasten, was die Seite tun soll).
- Bausteine stehen untereinander in der gegebenen Reihenfolge. Es gibt keine freie Positionierung.

### 4.2 Bausteine

Alle Bausteine haben `id` (`[A-Za-z0-9_.-]{1,40}`, eindeutig auf der Seite) und `type`.

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
| `image` | `src`, `w`, `h`, `align` | Bild, auf 16 Stufen gerundet | nein |

Allgemeine Maße: Seitenrand links/rechts 16 px, Abstand zwischen Bausteinen 8 px. Graustufen:
Text 13, stark 15, gedimmt 9, schwach 6, Fläche 2, Rand 10, Linie 3 (von 0–15; mal 17 = 8-Bit-Grau).
Der Baukasten zeigt heute noch die ganze 640×480-Fläche mit größeren Maßen. Ihn auf die App-Fläche
umzustellen ist Meilenstein M0 ([07](07_Umsetzungsplan.md)).

**`image`:** `src` ist `"data:image/png;base64,…"` oder `"asset:<datei>"` (eine Datei im App-Paket).
PNG in Graustufen (8 oder 4 Bit) oder mit Farbe (wird in Helligkeit umgerechnet). `w` ≤ 544, `h` ≤ 260,
Datei ≤ 64 KiB. Große Bilder kosten Übertragungszeit ([01 §1](01_Plattform_und_Grenzen.md#übertragung-uhr--brille-bluetooth-le));
für Karten, Diagramme und EvenHub-Apps ist das der Weg, sonst Bausteine bevorzugen.

**Rollen:** Ist der Inhalt höher als die App-Fläche, scrollt die Seite senkrecht. Der Host hält den
fokussierten Baustein sichtbar und zeigt rechts einen 3 px schmalen Scroll-Balken.

## 5. Navigation

- Ein `button` mit `target` zeigt die Zielseite **sofort auf der Uhr** (keine Rückfrage beim Rechner)
  und schickt danach das Ereignis `navigate` an die App. Die App kann die Seite daraufhin ändern.
- Ein `button` ohne `target` schickt nur `click`.
- **Zurück** führt zur vorigen Seite aus dem Verlauf der Sitzung; auf der ersten Seite schließt es die
  App. Das garantiert der Host, eine App kann es nicht abschalten. Die App bekommt `back` (vor dem
  Seitenwechsel). Auslöser:
  - Doppeltippen an Bügel oder Ring (in beiden Eingabearten, außer bei EvenHub-Apps, §7),
  - der Pfeil „‹“ links in der Kopfzeile (mit dem Zeiger anklicken),
  - im Gestenmodus Wischen nach rechts auf der Uhr,
  - ein Knopf mit `target: "@back"`. Vollbild-Seiten (`statusBar: false`) haben keine Kopfzeile und
    brauchen in `pointer`-Apps so einen Knopf.
- `show(pageId)` durch die App legt die Seite auf den Verlauf; `replace(pageId)` ersetzt die aktuelle.

## 6. Ereignisse und Befehle

### 6.1 Ereignisse (Host → App)

Gleiche JSON-Form in beiden Laufzeiten (in Kotlin als `sealed class AppEvent`):

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
{ "kind": "sensor",   "sensor": "light", "lux": 340, "t": 1727600000123 }
{ "kind": "audio",    "format": "pcm_s16le_16k_mono", "seq": 17 }
```

- `toggle` und `check`: Der Host schaltet **sofort sichtbar** um und meldet es dann. Will die App es
  nicht zulassen, setzt sie den Wert mit `patch` zurück.
- `gesture` kommt nur bei `input: "gestures"` oder wenn die App `gestures` abonniert hat. Werte:
  `click`, `doubleClick`, `scrollUp`, `scrollDown`, `longPress`, `longPressRelease`, `headUp`,
  `swipeLeft`, `swipeRight` (nur Uhr). `source`: `watch`, `left`, `right`, `ring`.
- `audio`: Die PCM-Daten laufen als Binärdaten neben dem Ereignis ([04 §5.4](04_Rechner-Apps_und_Protokoll.md#54-binärrahmen)).
  Auf der Uhr bekommt die Kotlin-App ein `ShortArray`.
- Unbekannte `kind`-Werte ignoriert eine App. So kann die Plattform neue Ereignisse ergänzen.

### 6.2 Befehle (App → Host)

| Befehl | Parameter | Wirkung |
|---|---|---|
| `definePages` | `pages[]` oder ein Baukasten-Projekt | Seiten bekannt machen (ersetzt gleichnamige) |
| `show` | `page` (id) | Seite zeigen, auf den Verlauf legen |
| `replace` | `page` (id) | Seite zeigen, ohne Verlauf |
| `patch` | `page`, `changes`: `{ "<blockId>": { <Felder> } }` | Felder von Bausteinen ändern (nur die genannten) |
| `setBlocks` | `page`, `blocks[]` | Alle Bausteine einer Seite ersetzen (für dynamische Listen) |
| `toast` | `text`, `ms` (Standard 2000) | kurzer Hinweis unten auf der App-Fläche |
| `vibrate` | `pattern` (`"tick"`, `"double"`, `"long"`) | Uhr vibriert |
| `buzz` | `notes`: `[[freqHz, ms], …]` | Summer der Brille (Berechtigung `buzzer`) |
| `timer` | `tag`, `ms`, `repeat` | Ereignis `timer` nach `ms` (wiederholt, wenn `repeat`) |
| `cancelTimer` | `tag` | |
| `subscribe` / `unsubscribe` | `sensor` (`imu`/`compass`/`light`/`gestures`), `hz` | Sensor-Ereignisse an/aus (Berechtigung nötig) |
| `audio` | `on` (bool) | Mikrofon der Brille an/aus (Berechtigung `mic`) |
| `store` / `load` | `key`, `value` (JSON) | kleiner Speicher je App (≤ 256 KiB gesamt) |
| `close` | – | App beenden |

`patch`-Felder je Baustein: `heading`/`text`: `text`, `align`; `button`: `text`, `target`;
`list`: `items` (ganz) oder `item: { "index", "text"?, "done"? }`; `toggle`: `text`, `on`;
`value`: `text`, `value`; `progress`: `text`, `value`; `image`: `src`, `w`, `h`.

**Taktung:** Der Host zeichnet höchstens alle **200 ms** neu und fasst dazwischen eingehende
Befehle zusammen (der neueste Stand gewinnt). Eine App darf also viele kleine `patch` schicken. Sinnvoll
sind aber höchstens 5 pro Sekunde, sonst entsteht nur Verkehr und Akkulast.

## 7. Eingabe und Fokus

Zwei Eingabearten, im Manifest gewählt:

**`pointer` (Standard)** – wie der heutige Desktop:
- Uhr-Touchpad bewegt den Maus-Zeiger; Doppeltippen = Klick auf den Baustein unter dem Zeiger;
  ein Baustein unter dem Zeiger ist hervorgehoben (Rand heller).
- Bügel/Ring: Wischen hoch/runter bewegt den **Fokus** zum vorigen/nächsten fokussierbaren Baustein
  (die Seite scrollt mit); Tippen = Klick auf den fokussierten Baustein; Doppeltippen = Zurück.
- Zeiger und Fokus sind dasselbe Ziel: Bewegt sich der Zeiger über einen Baustein, bekommt er den Fokus.

**`gestures`** – für Spiele und EvenHub-Apps:
- Kein Zeiger. Das Uhr-Touchpad liefert Gesten: Wischen hoch/runter/links/rechts, Tippen, Doppeltippen,
  langes Drücken. Bügel/Ring wie oben, aber als `gesture`-Ereignisse an die App.
- Zurück (§5) bleibt: Wischen nach rechts auf der Uhr oder Doppeltippen, solange die App `back` nicht
  abonniert hat. EvenHub-Apps behandeln Doppeltippen selbst (Even-Regel); dort ist Zurück das
  App-Menü (Tippen-dann-Halten) oder Wischen nach rechts auf der Uhr.

| Eingabe | `pointer` | `gestures` |
|---|---|---|
| Uhr: Finger ziehen | Zeiger bewegen | Wischen (Richtung) |
| Uhr: Doppeltippen | Klick unter Zeiger | `doubleClick` |
| Uhr: Tippen | – (Zeiger-Ruhe) | `click` |
| Bügel/Ring: Tippen | Klick auf Fokus | `click` |
| Bügel/Ring: Doppeltippen | Zurück | `doubleClick` (EvenHub) / Zurück |
| Bügel/Ring: Wischen | Fokus vor/zurück | `scrollUp`/`scrollDown` |
| Bügel/Ring: lang drücken | – | `longPress` / `longPressRelease` |

## 8. Berechtigungen

| Name | Erlaubt |
|---|---|
| `network` | Internet (Uhr-Apps: HTTP-Abfragen; Rechner-Apps haben das immer, der Eintrag dient nur der Anzeige) |
| `mic` | Mikrofon der Brille |
| `imu`, `compass`, `light` | Sensor-Ereignisse |
| `buzzer` | Summer der Brille |
| `location` | Standort der Uhr (GPS) |
| `background` | Uhr-Apps: Timer auch verdeckt |

Die Berechtigungen stehen im Manifest. Beim ersten Start einer App fragt die Uhr einmal auf der Brille
(„Einkauf möchte: Mikrofon. Erlauben?“). Die Antwort gilt, bis die App-Version wechselt. Nicht erlaubte
Befehle beantwortet der Host mit einem Fehler, nicht mit Stille.

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
1. `start` → App: `definePages(ui.json)`, `show("p_liste")`.
2. Träger hakt „Brot“ ab → Host zeigt das Häkchen sofort → Ereignis `check {block:"items", index:1, done:true}`.
3. App: `store("liste", …)`, `patch("p_liste", { "offen": { "value": "2" } })`.
4. Klick auf „Erledigte löschen“ → `click {block:"leeren"}` → App: `patch("p_liste", { "items": { "items": [ … ohne Brot … ] }, "offen": { "value": "2" } })`.
