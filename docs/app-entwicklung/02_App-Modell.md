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
| **Starter** | Die Seite „Apps“ auf der Brille, vom App-Host selbst gezeichnet: alle Apps, laufende markiert. |
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
| `icon` | nein | PNG, 48 × 48, Graustufen |

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
  markiert; wählt der Träger eine davon, bekommt sie `visible` und erscheint mit ihrer aktuellen Seite.
  Anzeige-Befehle einer verdeckten App speichert der Host (der neueste Stand gilt).
  Uhr-Apps dürfen verdeckt nur Timer ausführen, und nur mit `background`; Rechner-Apps unbegrenzt.
- **stop**: „Schließen“ im App-Menü, Zurück auf der ersten Seite, `close()` durch die App oder Beenden
  durch den Host. Die App bekommt `stop` und hat **1 s** zum Aufräumen.
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

**Rollen:** Ist der Inhalt höher als die App-Fläche, scrollt die Seite senkrecht. Der Host hält den
fokussierten Baustein sichtbar und zeigt rechts einen 3 px schmalen Scroll-Balken.

## 5. Navigation und App-Menü

- Ein `button` mit Seiten-`target` zeigt die Zielseite **sofort auf der Uhr** (keine Rückfrage beim
  Rechner) und schickt danach `navigate` an die App. Die App kann die Seite daraufhin ändern.
- Ein `button` ohne `target` schickt nur `click`.
- Ein `button` mit `target: "@back"` löst Zurück aus (unten) und schickt nur `back`, kein `click`.
- **Zurück** führt zur vorigen Seite aus dem Verlauf der Sitzung; auf der ersten Seite schließt es die
  App (`stop`). Das garantiert der Host, eine App kann es nicht abschalten. Die App bekommt `back` vor
  dem Seitenwechsel. Auslöser:
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
{ "kind": "sensor",   "sensor": "location", "lat": 47.37, "lon": 8.54, "acc": 12, "t": 1727600000123 }
{ "kind": "audio",    "seq": 17, "pcm": "<16-kHz-PCM, siehe unten>" }
{ "kind": "menu",     "item": "sortieren" }
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
- `error`: nur bei Rechner-Apps; die Uhr hat einen Befehl abgelehnt (§6.2 „Fehler“).
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
| `menu` | `items`: `[{ "id", "text" }]`, ≤ 10, Text ≤ 32 Byte UTF-8 | eigene Einträge im App-Menü (§5); `[]` entfernt sie |
| `buzz` | `notes`: `[[freqHz, dutyProzent, ms], …]`, ≤ 48 Schritte | Summer der Brille (Berechtigung `buzzer`) |
| `timer` | `tag`, `ms`, `repeat` | Ereignis `timer` nach `ms` (wiederholt, wenn `repeat`) |
| `cancelTimer` | `tag` | |
| `subscribe` / `unsubscribe` | `sensor` (`imu`, `compass`, `location`, `gestures`), `rate` | Ereignisse an/aus (Berechtigung nötig). `rate` bei `imu`: Firmware-Takt 100–1000 (Einheit von Even nicht dokumentiert); bei `location`: Sekunden |
| `audio` | `on` (bool) | Mikrofon der Brille an/aus (Berechtigung `mic`) |
| `storage.get` / `storage.set` | `key`, `value` (JSON) | kleiner Speicher je App (≤ 256 KiB gesamt) |
| `close` | – | App beenden |

`patch`-Felder je Baustein: `heading`/`text`: `text`, `align`; `button`: `text`, `target`;
`list`: `items` (ganz) oder `item: { "index", "text"?, "done"? }`; `toggle`: `text`, `on`;
`value`: `text`, `value`; `progress`: `text`, `value`; `image`: `src`, `w`, `h`.

**Fehler:** Nennt ein Befehl eine unbekannte Seite oder einen unbekannten Baustein, hat die App keine
Berechtigung oder sind Werte ungültig, führt der Host ihn nicht aus und meldet einen Fehler
(Kotlin: Eintrag im Protokoll und `IllegalArgumentException` im Test-Fake; Rechner-Apps:
`cmd.error`, [04 §5.3](04_Rechner-Apps_und_Protokoll.md#53-nachrichten)). Nie still ignorieren.

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

## 8. Berechtigungen

| Name | Erlaubt |
|---|---|
| `network` | Internet (Uhr-Apps: HTTP-Abfragen; Rechner-Apps haben das immer, der Eintrag dient nur der Anzeige) |
| `mic` | Mikrofon der Brille |
| `imu`, `compass` | Sensor-Ereignisse |
| `location` | Standort der Uhr (GPS) als Sensor-Ereignis |
| `buzzer` | Summer der Brille |
| `background` | Uhr-Apps: Timer auch verdeckt |

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
