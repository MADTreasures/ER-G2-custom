# 04 – Rechner-Apps und das Protokoll `g2-remote@1`

Rechner-Apps laufen auf einem PC, Server oder Handy im Programm **`g2-host`**. Die Uhr verbindet sich
über WLAN oder LTE mit dem Rechner, zeigt die Seiten der App auf der Brille und schickt die Eingaben
zurück. Die App selbst sieht davon nichts: Sie benutzt dieselben Ereignisse und Befehle wie eine
Uhr-App ([02](02_App-Modell.md)).

**Stand:** `g2-host`, die TypeScript-Schnittstelle und der Rechner-Client der Uhr existieren noch
nicht (Meilenstein M2). §1–§2 beschreiben, was eine App-Entwicklerin schreibt, §3–§7 die Plattform.

## 1. Eine Rechner-App schreiben

Ordner einer App (Beispiele im Repo: `host/apps/<name>/`; installiert: `~/.g2-host/apps/<app-id>/`):

```
pc-status/
  g2app.json      Manifest (02 §2), runtime "remote", entry "index.ts"
  ui.json         Seiten aus dem Baukasten (optional)
  index.ts        die Logik
  assets/         Bilder für image-Bausteine (optional)
```

`g2app.json`:

```json
{ "format": "g2app@1", "id": "ch.madtreasures.pcstatus", "name": "PC-Status", "version": "1.0.0",
  "runtime": "remote", "entry": "index.ts", "ui": "ui.json",
  "description": "Auslastung und Speicher des PCs, alle 2 Sekunden." }
```

`ui.json` (im Baukasten entworfen, Kennungen lesbar vergeben):

```json
{ "format": "g2-baukasten@1", "name": "PC-Status", "start": "p_status",
  "pages": [ { "id": "p_status", "name": "PC-Status", "statusBar": true, "notes": "",
    "blocks": [
      { "id": "cpu",  "type": "value",    "text": "Prozessor", "value": "–" },
      { "id": "last", "type": "progress", "text": "Last",      "value": 0 },
      { "id": "ram",  "type": "value",    "text": "Speicher",  "value": "–" },
      { "id": "neu",  "type": "button",   "text": "Aktualisieren", "target": null }
    ] } ] }
```

`index.ts`:

```ts
import os from "node:os";
import { defineApp, type AppContext } from "g2-host/sdk";

export default defineApp({
  async onEvent(ev, ui: AppContext) {
    switch (ev.kind) {
      case "start":
        ui.show("p_status");          // pages from ui.json are already defined
        await update(ui);
        break;
      case "visible":
        ui.timer("tick", 2000, { repeat: true });
        break;
      case "hidden":
        ui.cancelTimer("tick");
        break;
      case "timer":
        await update(ui);
        break;
      case "click":
        if (ev.block === "neu") await update(ui);
        break;
    }
  },
});

async function update(ui: AppContext) {
  const load = Math.round((os.loadavg()[0] / os.cpus().length) * 100);
  const freeGb = (os.freemem() / 2 ** 30).toFixed(1);
  ui.patch("p_status", {
    cpu: { value: `${load} %` },
    last: { value: Math.min(100, load) },
    ram: { value: `${freeGb} GB frei` },
  });
}
```

`AppContext` in TypeScript entspricht der Kotlin-Schnittstelle ([03 §2](03_Uhr-Apps.md#2-die-schnittstelle)):
`definePages`, `show`, `replace`, `patch(pageId, changes)`, `setBlocks`, `toast`, `vibrate`, `menu`, `buzz`,
`timer(tag, ms, {repeat})`, `cancelTimer`, `subscribe`, `unsubscribe`, `audio(on)`,
`storage.get/set` (async, JSON-Datei je App), `log`, `close`. Netzwerk: das normale `fetch` von Node.
Befehle, die die Uhr ablehnt, kommen als Ereignis `{ kind: "error", command, code, message }` zurück.

Unterschiede zur Uhr:
- `onEvent` darf `async` sein. Der Host stellt die Ereignisse einer Sitzung in eine Warteschlange und
  ruft `onEvent` nie gleichzeitig auf. Wer lange rechnet, startet die Arbeit ohne `await` im Hintergrund
  und meldet sich mit `patch`, damit Klicks nicht warten.
- Keine 50-ms-Grenze, aber: Nach 2 s ohne erste Seite (und ohne `ui`) zeigt die Uhr „App antwortet nicht“.
- Timer und Speicher laufen auf dem Rechner, nicht auf der Uhr.
- Audio kommt als `{ kind: "audio", pcm: Int16Array, seq }` (16 kHz mono, 50 ms je Ereignis).

Weitere Sprachen: Jede App läuft in einem eigenen Worker-Thread von `g2-host`. Python oder andere
Sprachen können später über eine Stdio-Brücke angebunden werden (eine Zeile JSON je Ereignis bzw.
Befehl, gleiche Formen wie hier). Das ist nicht Teil von M2.

## 2. Beispiele, die M2 mitliefert

| App | Zeigt |
|---|---|
| `pc-status` | Werte, Timer nur wenn sichtbar |
| `echo` | Jede Geste und jeder Klick als Text; der Test für das Protokoll |
| `notizen` | Liste auf dem PC gespeichert, abhaken auf der Brille (wie 02 §10) |

Die Beispiele unter `host/apps/` installiert der Host beim Start automatisch als „eingebaut“ (vor M3 gibt
es noch keine App-Verwaltung).

## 3. Der Rechner-Host `g2-host`

| Punkt | Festlegung |
|---|---|
| Ort im Repo | `host/` (eigenes `package.json`, TypeScript, ES-Module) |
| Laufzeit | Node.js ≥ 22 |
| Start | `npm start` im Ordner `host/` |
| Abhängigkeiten | `ws` (WebSocket), `zod` (Prüfung aller Nachrichten), `bonjour-service` (mDNS), `esbuild` (Apps bauen), `lc3` per WebAssembly oder Node-Addon aus liblc3 (Mikrofon, ab M5); `playwright` nur für den EvenHub-Adapter |
| Apps laden | Beim Installieren und bei jeder Änderung bündelt `esbuild` den `entry` einer App zu einer Datei; `g2-host/sdk` wird dabei auf das SDK des Hosts umgeleitet (`alias`), damit Apps nichts selbst installieren müssen. Eigene npm-Pakete einer App: `package.json` im App-Ordner, `npm ci --ignore-scripts` vor dem Bündeln. |
| Daten | `~/.g2-host/`: `config.json`, `apps/<id>/`, `data/<id>/store.json`, `logs/` |
| Port | **8790**: HTTP (App-Verwaltung, [06](06_App-Verwaltung.md)) und WebSocket unter `/g2` |
| Sitzungen | jede in einem eigenen `worker_thread`; stürzt eine App ab, bekommt die Uhr `app.ended` mit Grund, der Host läuft weiter |
| Seitenstand | Der Host ist für Rechner-Apps die Wahrheit über den Seitenstand: Er führt die Seiten, alle `patch`es **und** die Wirkung der Ereignisse `toggle`, `check`, `navigate`, `back` mit. Daraus stellt er die Anzeige nach einem Neuverbinden wieder her (§5.6). |
| Uhren | eine aktive Uhr-Verbindung; eine neu angemeldete Uhr ersetzt die alte (Schließcode 4409) |

## 4. Kopplung und Sicherheit

**Kopplung** (einmal pro Uhr):
1. In der App-Verwaltung „Uhr koppeln“ → der Host zeigt einen **6-stelligen Code**. Er ist 5 Minuten
   gültig und wird nach 5 Fehlversuchen gesperrt.
2. Auf der Uhr unter Einstellungen → „Rechner“: Der Host erscheint über mDNS (`_g2host._tcp`, TXT
   `id`, `name`, `proto=g2-remote@1`, `tls=0|1`). Alternativ gibt man die Adresse von Hand ein.
3. Die Uhr öffnet eine Verbindung und sendet als **erste** Nachricht `pair` mit dem Code. Der Host antwortet
   mit `paired` und einem **Token** `g2r_` + 64 Hexzeichen und schließt die Verbindung (Code 1000).
4. Die Uhr speichert Adresse und Token (App-privater Speicher) und verbindet sich neu, jetzt mit `hello`.
   Der Host speichert nur den SHA-256 des Tokens. Tokens lassen sich in der App-Verwaltung widerrufen.

**Regeln:**
- Die erste Nachricht jeder Verbindung ist `pair` oder `hello`; alles andere schließt mit 4401. Ohne gültiges
  Token nimmt der Host nur `pair` an, und nur bei offenem Kopplungsfenster.
- Über das Internet **nur mit TLS** (`wss://`). Der Host spricht selbst TLS (Zertifikat in `config.json`)
  oder steht hinter einem Tunnel/Reverse-Proxy, der TLS macht.
- Unverschlüsselte Verbindungen nimmt der Host nur **direkt** aus privaten Netzen an (10/8, 172.16/12,
  192.168/16, 100.64/10, fd00::/8). Verbindungen von `127.0.0.1`/`::1` gelten als „über einen Tunnel“ und
  sind nur erlaubt, wenn `config.json` `"tunnel": true` sagt; dann muss der Tunnel TLS machen. So kann ein
  lokaler Tunnel die Regel nicht aushebeln.
- Die App-Verwaltung verlangt immer ihr Passwort, auch aus dem Heimnetz.
- Tokens erscheinen nie im Protokoll, nur ihre ersten 12 Zeichen (`g2r_` + 8 Hexzeichen).
- Eine App bekommt nie das Token und nie Daten anderer Apps.

## 5. Protokoll `g2-remote@1`

### 5.1 Transport

- WebSocket, Pfad `/g2`, Unterprotokoll `g2-remote.v1`. Die Uhr ist Client, der Rechner Server.
- Textrahmen: JSON, UTF-8, ein Objekt je Rahmen, höchstens **64 KiB**.
- Binärrahmen: Audio und Bildpixel (§5.4), höchstens **256 KiB**.
- Lebenszeichen: WebSocket-Ping alle 20 s von der Uhr; ohne Pong nach 10 s gilt die Verbindung als tot.
- Schließcodes: `1000` normal (z. B. nach `paired`), `4400` fehlerhafte Nachricht oder unbekannte
  Protokollversion, `4401` nicht angemeldet, `4409` von anderer Uhr ersetzt, `4500` Host fährt herunter.

### 5.2 Grundform

```json
{ "t": "<typ>", "id": 12, "re": 7, … }
```

- `t` ist der Typ. `id` setzt, wer eine Antwort erwartet; die Antwort trägt `re` = diese `id`.
- **Unbekannte Felder** werden ignoriert. **Unbekannte Typen** beantwortet die Gegenseite mit `error`
  `bad_message` (Host) bzw. `cmd.error`/Protokolleintrag (Uhr), die Verbindung bleibt offen.
  **Unbekannte Protokollversion** in `hello`: `error` und Schließen mit 4400.
- Sitzungen tragen eine Nummer `session` (1–65535), die der Host vergibt.

### 5.3 Nachrichten

**Uhr → Rechner**

| `t` | Felder | Antwort |
|---|---|---|
| `pair` | `code`, `watch: {app, model, id}` | `paired`, dann Schließen 1000; oder `error` (`pairing_closed`, `bad_code`) |
| `hello` | `proto: "g2-remote@1"`, `token`, `watch: {app, model, id}`, `screen: {w: 576, h: 260, fullH: 288, levels: 16}`, `input: ["pointer","gestures"]`, `sensors: [...]` | `welcome` oder Schließen 4401 |
| `apps.list` | – | `apps` |
| `app.start` | `app` (id) | `app.started` oder `error` |
| `app.stop` | `session` | `app.ended` |
| `event` | `session`, `event` (Ereignis aus [02 §6.1](02_App-Modell.md#61-ereignisse-host--app), ohne `audio`) | – |
| `cmd.error` | `session`, `c` (Befehlsname), `code` (`unknown_page`, `unknown_block`, `permission_denied`, `bad_value`), `message` | – (der Host gibt es der App als Ereignis `error`) |
| `frame.ack` | `session`, `seq` | – (Flusskontrolle für Bilder, §6) |
| `status` | `glasses: {battery, charging, wearing}`, `watch: {battery, charging}` | – (bei Änderung, höchstens alle 10 s) |
| `resume` | `sessions: [3, 5]` | je Sitzung eine `cmd`-Folge oder `app.ended` (§5.6) |

**Rechner → Uhr**

| `t` | Felder | Bedeutung |
|---|---|---|
| `paired` | `token` | Kopplung geglückt |
| `welcome` | `host: {id, name, version}` | angemeldet |
| `apps` | `items: [{id, name, version, runtime, input, permissions, description, icon?}]` | Liste für den Starter; `icon` als `data:`-URL. Kommt als Antwort auf `apps.list` **und** unaufgefordert, wenn Apps installiert oder entfernt werden. |
| `app.started` | `session`, `app`, `input`, `permissions` | Sitzung läuft; Befehle folgen. Die Uhr schaltet den Eingabemodus und fragt nach Berechtigungen (02 §8), bevor sie die erste Seite zeigt. |
| `app.launch` | `app` | Bitte der App-Verwaltung, die App zu öffnen; die Uhr antwortet mit `app.start` wie aus dem Starter |
| `cmd` | `session`, `cmd` | ein Befehl aus [02 §6.2](02_App-Modell.md#62-befehle-app--host), siehe unten |
| `frame` | `session`, `block`, `seq`, `x`, `y`, `w`, `h`, `format` (`gray4`/`png`) | Pixel für einen `image`-Baustein (Kennung projektweit eindeutig, darum ohne Seite); der Binärrahmen mit gleicher `session`+`seq` folgt direkt |
| `app.ended` | `session`, `reason` (`closed`/`crashed`/`timeout`/`host`), `message?` | Sitzung vorbei |
| `error` | `re?`, `code`, `message` | Fehler, siehe §5.7 |

**Befehle in `cmd`** (JSON-Formen; `timer`, `storage` und `fetch` bleiben auf dem Rechner und gehen
nicht über die Leitung):

```json
{ "c": "definePages", "pages": [ … ] }
{ "c": "show", "page": "p_status" }
{ "c": "replace", "page": "p_status" }
{ "c": "patch", "page": "p_status", "changes": { "cpu": { "value": "12 %" } } }
{ "c": "setBlocks", "page": "p_liste", "blocks": [ … ] }
{ "c": "toast", "text": "Gespeichert", "ms": 2000 }
{ "c": "vibrate", "pattern": "tick" }
{ "c": "menu", "items": [ { "id": "sortieren", "text": "Sortieren" } ] }
{ "c": "buzz", "notes": [[880, 50, 120], [0, 0, 60], [1320, 50, 120]] }
{ "c": "subscribe", "sensor": "imu", "rate": 100 }
{ "c": "unsubscribe", "sensor": "imu" }
{ "c": "audio", "on": true }
{ "c": "close" }
```

Die Uhr prüft jeden Befehl gegen die bekannten Seiten und Kennungen. Unbekannte Seite oder Kennung,
fehlende Berechtigung oder ungültige Werte: nicht ausführen, `cmd.error` senden.

### 5.4 Binärrahmen

```
Byte 0      Typ: 1 = Audio (Uhr → Rechner), 2 = Bildpixel (Rechner → Uhr)
Byte 1      Version: 1
Byte 2–3    session (u16, Big Endian)
Byte 4–7    seq (u32, Big Endian)
Byte 8…     Nutzdaten
```

- **Audio:** Byte 8 = Codec: `1` = LC3-Paket, wie es von der Brille kommt (205 Byte: 5 × 40 Byte LC3,
  16 kHz mono, 10-ms-Rahmen, dahinter Zähler/Kennbytes nach wissen/03 §7.2); `2` = PCM s16le 16 kHz mono.
  Die Uhr schickt Codec 1 unverändert (Doppel schon aussortiert); der Rechner entschlüsselt mit liblc3 und
  überbrückt fehlende Pakete wie Faceclaw (bis 8 Pakete mit Verlust-Verdeckung, darüber Neustart).
  `seq` zählt je Sitzung hoch.
- **Bildpixel:** `gray4` = zwei Pixel je Byte, oberes Halbbyte links, Zeile für Zeile, `w × h` Pixel;
  oder `png` = eine PNG-Datei. Die Pixel ersetzen den Bereich `x, y, w, h` im Bild des Bausteins. So
  kommen auch `asset:`-Bilder einer Rechner-App auf die Uhr (`x = y = 0`, ganze Größe).
- Ein `frame` für einen Baustein, der gerade nicht sichtbar ist, übernimmt die Uhr in den Seitenstand;
  für eine unbekannte Kennung sendet sie `cmd.error` `unknown_block` und bestätigt trotzdem mit `frame.ack`.

### 5.5 Ablauf

Kopplung:

```
Uhr                                   Rechner
 │── pair {code} ───────────────────────▶│  Code prüfen
 │◀──────────────── paired {token} ──────│
 │◀──────────────── Schließen 1000 ──────│
```

Anmeldung und eine Sitzung:

```
Uhr                                   Rechner
 │── hello {token, screen …} ───────────▶│
 │◀──────────────── welcome {host} ──────│
 │── status {glasses …} ────────────────▶│
 │── apps.list (id 1) ──────────────────▶│
 │◀─────── apps (re 1) [pc-status …] ────│
 │── app.start {app} (id 2) ────────────▶│  Worker starten, ui.json laden
 │◀── app.started {session 3, input …} ──│  (re 2)
 │◀── cmd definePages ───────────────────│  aus ui.json, dann Ereignisse start + visible
 │◀── cmd show / patch ──────────────────│
 │   Seite zeichnen, Brille aktualisiert  │
 │── event {session 3, click "neu"} ────▶│
 │◀── cmd patch {cpu: 12 %} ─────────────│
 │── event {session 3, back} ───────────▶│  (erste Seite: die Uhr schließt)
 │── app.stop {session 3} ──────────────▶│  Ereignis stop
 │◀── app.ended {session 3, closed} ─────│
```

Die Uhr zeigt eine Zielseite eines Knopfs sofort und schickt danach `event navigate` (02 §5); sie wartet
dafür nicht auf den Rechner.

### 5.6 Verbindung verloren und wieder da

- Die Uhr versucht es erneut nach 1, 2, 4, 8, … höchstens 30 s. Solange zeigt die Kopfzeile
  „Rechner getrennt“, und die letzte Seite bleibt stehen. Ereignisse in dieser Zeit werden verworfen,
  nicht gepuffert (sonst lösen alte Klicks später Aktionen aus).
- Der Host hält Sitzungen **5 Minuten** ohne Uhr am Leben, mit ihrem Seitenstand (§3).
- Nach `hello` sendet die Uhr `resume` mit den Nummern ihrer Sitzungen. Der Host schickt je Sitzung
  `cmd definePages` mit dem aktuellen Stand und `cmd replace` mit der Seite, die nach seinem Stand zuletzt
  gezeigt wurde. Besteht die Sitzung nicht mehr: `app.ended`. Sitzungen, die die Uhr nicht nennt, beendet
  der Host (`app.ended`, Grund `host`), sobald die 5 Minuten um sind.

### 5.7 Fehlercodes

Host → Uhr (`error`): `unauthorized`, `pairing_closed`, `bad_code`, `unknown_app`, `unknown_session`,
`bad_message`, `too_large`, `rate_limited`, `app_crashed`, `internal`.
Uhr → Host (`cmd.error`): `unknown_page`, `unknown_block`, `permission_denied`, `bad_value`.
Jeder Fehler hat eine deutsche `message`, die die Uhr als Hinweis zeigen kann.

### 5.8 Versionen

`proto` in `hello` nennt die Version. Erweiterungen, die nur Felder, Befehle oder Nachrichtentypen
hinzufügen, bleiben `g2-remote@1` (§5.2). Änderungen der Bedeutung erhöhen auf `@2`.

## 6. Leistungsregeln

| Regel | Wert |
|---|---|
| Befehle Rechner → Uhr | höchstens 20 je Sekunde; die Uhr fasst sie zusammen und zeichnet höchstens alle 200 ms |
| Bildpixel (`frame`) | höchstens 2 unbestätigt unterwegs (`frame.ack`), Bereich ≤ 576 × 288 |
| Bilder als `data:`-URL | ≤ 48 KiB Text, größer als Binär-`frame` |
| Seitendefinitionen | ≤ 64 KiB je Nachricht; größere Projekte seitenweise senden |
| Audio | nur, solange die App sichtbar ist und `audio` an hat; LC3 = 4 KB/s |

Die Bluetooth-Strecke zur Brille bleibt der Engpass (≈ 41 KiB/s, [01](01_Plattform_und_Grenzen.md)).
Der Rechner darf schnell senden, die Uhr gibt nur den neuesten Stand weiter.

## 7. Tests

- **Gemeinsame Beispielnachrichten** unter `protocol/vectors/` im Repo (eine JSON-Datei je
  Nachrichtentyp, gültig und ungültig, dazu Binärrahmen als Hex). Die Kotlin-Seite (Uhr) und die
  TypeScript-Seite (Host) prüfen beide gegen dieselben Dateien.
- Host: Unit-Tests für Kopplung (Code-Ablauf, Sperre, Token-Hash), Tunnel-Regel, Sitzungen, Absturz einer
  App, Seitenstand aus Ereignissen, Resume; ein Integrations-Test mit einer simulierten Uhr
  (Node-WebSocket-Client), die `echo` bedient.
- Uhr: `RemoteHostClient` gegen `okhttp3.mockwebserver`; Reconnect mit Backoff; verworfene Ereignisse
  bei getrennter Verbindung; `cmd.error` für unbekannte Kennungen; `frame.ack`-Flusskontrolle.
- Für die Uhr kommen OkHttp und mockwebserver neu in `gradle/libs.versions.toml`. Netzwerkanforderung,
  `ACCESS_LOCAL_NETWORK` und Network-Security-Config nach [01 §4](01_Plattform_und_Grenzen.md#4-netz-zwischen-uhr-und-rechner).
