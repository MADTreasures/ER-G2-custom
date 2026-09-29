# 04 – Rechner-Apps und das Protokoll `g2-remote@1`

Rechner-Apps laufen auf einem PC, Server oder Handy im Programm **`g2-host`**. Die Uhr verbindet sich
über WLAN oder LTE mit dem Rechner, zeigt die Seiten der App auf der Brille und schickt die Eingaben
zurück. Die App selbst sieht davon nichts: Sie benutzt dieselben Ereignisse und Befehle wie eine
Uhr-App ([02](02_App-Modell.md)).

**Stand:** `g2-host`, die TypeScript-Schnittstelle und der Rechner-Client der Uhr existieren noch
nicht (Meilenstein M2). §1–§2 beschreiben, was eine App-Entwicklerin schreibt, §3–§7 die Plattform.

## 1. Eine Rechner-App schreiben

Ordner einer App (im Repo: `host/apps/<name>/`, installiert: `~/.g2-host/apps/<app-id>/`):

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
`definePages`, `show`, `replace`, `patch(pageId, changes)`, `setBlocks`, `toast`, `vibrate`, `buzz`,
`timer(tag, ms, {repeat})`, `cancelTimer`, `subscribe`, `unsubscribe`, `audio(on)`, `store.get/set`
(async, JSON-Datei je App), `log`, `close`. Netzwerk: das normale `fetch` von Node.

Unterschiede zur Uhr:
- `onEvent` darf `async` sein. Der Host stellt die Ereignisse einer Sitzung in eine Warteschlange und
  ruft `onEvent` nie gleichzeitig auf. Wer lange rechnet, startet die Arbeit ohne `await` im Hintergrund
  und meldet sich mit `patch`, damit Klicks nicht warten.
- Keine 50-ms-Grenze, aber: Nach 2 s ohne erste Seite zeigt die Uhr „App antwortet nicht“.
- Timer und Speicher laufen auf dem Rechner, nicht auf der Uhr.
- Audio kommt als `{ kind: "audio", pcm: Int16Array, seq }` (16 kHz mono).

Weitere Sprachen: Jede App läuft in einem eigenen Worker-Thread von `g2-host`. Python oder andere
Sprachen können später über eine Stdio-Brücke angebunden werden (eine Zeile JSON je Ereignis bzw.
Befehl, gleiche Formen wie hier). Das ist nicht Teil von M2.

## 2. Beispiele, die M2 mitliefert

| App | Zeigt |
|---|---|
| `pc-status` | Werte, Timer nur wenn sichtbar |
| `echo` | Jede Geste und jeder Klick als Text; der Test für das Protokoll |
| `notizen` | Liste auf dem PC gespeichert, abhaken auf der Brille (wie 02 §10) |

## 3. Der Rechner-Host `g2-host`

| Punkt | Festlegung |
|---|---|
| Ort im Repo | `host/` (eigenes `package.json`, TypeScript, ES-Module) |
| Laufzeit | Node.js ≥ 22 |
| Start | `npm start` im Ordner `host/` oder `npx g2-host` |
| Abhängigkeiten | `ws` (WebSocket), `zod` (Prüfung aller Nachrichten), `bonjour-service` (mDNS), `tsx` oder `esbuild` (TypeScript-Apps laden); `playwright` nur für den EvenHub-Adapter |
| Daten | `~/.g2-host/`: `config.json`, `apps/<id>/`, `data/<id>/store.json`, `logs/` |
| Port | **8790**: HTTP (App-Verwaltung, [06](06_App-Verwaltung.md)) und WebSocket unter `/g2` |
| Apps | jede Sitzung in einem eigenen `worker_thread`; stürzt eine App ab, bekommt die Uhr `app.ended` mit Grund, der Host läuft weiter |
| Uhren | eine aktive Uhr-Verbindung; eine neu angemeldete Uhr ersetzt die alte (Schließcode 4409) |

## 4. Kopplung und Sicherheit

**Kopplung** (einmal pro Uhr):
1. In der App-Verwaltung „Uhr koppeln“ → der Host zeigt einen **6-stelligen Code**. Er ist 5 Minuten
   gültig und wird nach 5 Fehlversuchen gesperrt.
2. Auf der Uhr unter Einstellungen → „Rechner“: Der Host erscheint über mDNS (`_g2host._tcp`, TXT
   `id`, `name`, `proto=g2-remote@1`, `tls=0|1`). Alternativ gibt man die Adresse von Hand ein.
3. Die Uhr sendet `pair` mit dem Code. Der Host antwortet mit einem **Token** `g2r_` + 64 Hexzeichen.
4. Die Uhr speichert Adresse und Token (App-privater Speicher). Der Host speichert nur den SHA-256
   des Tokens. Tokens lassen sich in der App-Verwaltung einzeln widerrufen.

**Regeln:**
- Ohne gültiges Token nimmt der Host nur `pair` an, und nur bei offenem Kopplungsfenster.
- Über das Internet **nur mit TLS** (`wss://`). Der Host kann selbst TLS sprechen (Zertifikat in
  `config.json`) oder hinter einem Tunnel/Reverse-Proxy stehen. Ohne TLS nimmt er standardmäßig nur
  Verbindungen aus privaten Netzen an (10/8, 172.16/12, 192.168/16, 100.64/10, fd00::/8, localhost).
- Tokens erscheinen nie im Protokoll, nur ihre ersten 8 Zeichen.
- Eine App bekommt nie das Token und nie Daten anderer Apps.

## 5. Protokoll `g2-remote@1`

### 5.1 Transport

- WebSocket, Pfad `/g2`, Unterprotokoll `g2-remote.v1`. Die Uhr ist Client, der Rechner Server.
- Textrahmen: JSON, UTF-8, ein Objekt je Rahmen, höchstens **64 KiB**.
- Binärrahmen: Audio und Bildpixel (§5.4), höchstens **256 KiB**.
- Lebenszeichen: WebSocket-Ping alle 20 s von der Uhr; ohne Pong nach 10 s gilt die Verbindung als tot.
- Schließcodes: `4400` fehlerhafte Nachricht, `4401` nicht angemeldet, `4409` von anderer Uhr ersetzt,
  `4500` Host fährt herunter.

### 5.2 Grundform

```json
{ "t": "<typ>", "id": 12, "re": 7, … }
```

`t` ist der Typ. `id` setzt, wer eine Antwort erwartet; die Antwort trägt `re` = diese `id`. Unbekannte
Felder werden ignoriert, unbekannte Typen mit `error` (`bad_message`) beantwortet. Sitzungen tragen
eine Nummer `session` (1–65535), die der Host vergibt.

### 5.3 Nachrichten

**Uhr → Rechner**

| `t` | Felder | Antwort |
|---|---|---|
| `hello` | `proto: "g2-remote@1"`, `token`, `watch: {app, model, id}`, `screen: {w: 576, h: 260, fullH: 288, levels: 16}`, `input: ["pointer","gestures"]`, `sensors: [...]` | `welcome` oder Schließen 4401 |
| `pair` | `code`, `watch: {app, model, id}` | `paired` oder `error` |
| `apps.list` | – | `apps` |
| `app.start` | `app` (id) | `app.started` oder `error` |
| `app.stop` | `session` | `app.ended` |
| `event` | `session`, `event` (Ereignis aus [02 §6.1](02_App-Modell.md#61-ereignisse-host--app)) | – |
| `frame.ack` | `session`, `seq` | – (Flusskontrolle für Bilder, §6) |
| `resume` | `sessions: [{session, page}]` | je Sitzung `cmd`-Folge oder `app.ended` |

**Rechner → Uhr**

| `t` | Felder | Bedeutung |
|---|---|---|
| `welcome` | `host: {id, name, version}` | angemeldet |
| `paired` | `token` | Kopplung geglückt |
| `apps` | `items: [{id, name, version, runtime, description, icon?}]` | Liste für den Starter; `icon` als `data:`-URL |
| `app.started` | `session`, `app` | Sitzung läuft; Befehle folgen |
| `app.launch` | `app` | Bitte der App-Verwaltung, die App zu öffnen; die Uhr antwortet mit `app.start` wie aus dem Starter |
| `cmd` | `session`, `cmd` | ein Befehl aus [02 §6.2](02_App-Modell.md#62-befehle-app--host), siehe unten |
| `frame` | `session`, `page`, `block`, `seq`, `x`, `y`, `w`, `h`, `format` (`gray4`/`png`) | Pixel für einen `image`-Baustein; der Binärrahmen mit gleicher `session`+`seq` folgt direkt |
| `app.ended` | `session`, `reason` (`closed`/`crashed`/`timeout`/`host`), `message?` | Sitzung vorbei |
| `error` | `re?`, `code`, `message` | Fehler, siehe §5.7 |

**Befehle in `cmd`** (JSON-Formen; `timer`, `store`/`load` und `fetch` bleiben auf dem Rechner und
gehen nicht über die Leitung):

```json
{ "c": "definePages", "pages": [ … ] }
{ "c": "show", "page": "p_status" }
{ "c": "replace", "page": "p_status" }
{ "c": "patch", "page": "p_status", "changes": { "cpu": { "value": "12 %" } } }
{ "c": "setBlocks", "page": "p_liste", "blocks": [ … ] }
{ "c": "toast", "text": "Gespeichert", "ms": 2000 }
{ "c": "vibrate", "pattern": "tick" }
{ "c": "buzz", "notes": [[880, 120], [0, 60], [1320, 120]] }
{ "c": "subscribe", "sensor": "imu", "hz": 10 }
{ "c": "unsubscribe", "sensor": "imu" }
{ "c": "audio", "on": true }
{ "c": "close" }
```

### 5.4 Binärrahmen

```
Byte 0      Typ: 1 = Audio (Uhr → Rechner), 2 = Bildpixel (Rechner → Uhr)
Byte 1      Version: 1
Byte 2–3    session (u16, Big Endian)
Byte 4–7    seq (u32, Big Endian)
Byte 8…     Nutzdaten
```

- **Audio:** PCM s16le, 16 kHz, mono, Blöcke von 20–100 ms. `seq` zählt je Sitzung hoch; Lücken zeigen
  verlorene Blöcke an.
- **Bildpixel:** `gray4` = zwei Pixel je Byte, oberes Halbbyte links, Zeile für Zeile, `w × h` Pixel;
  oder `png` = eine PNG-Datei. Die Pixel ersetzen den Bereich `x, y, w, h` im Bild des Bausteins.

### 5.5 Ablauf

```
Uhr                                   Rechner
 │── hello {token, screen …} ───────────▶│
 │◀──────────────── welcome {host} ──────│
 │── apps.list (id 1) ──────────────────▶│
 │◀─────── apps (re 1) [pc-status …] ────│
 │── app.start {app} (id 2) ────────────▶│  Worker starten, Ereignis start
 │◀── app.started {session 3} (re 2) ────│
 │◀── cmd definePages / show ────────────│
 │   Seite zeichnen, Brille aktualisiert  │
 │── event {session 3, click "neu"} ────▶│
 │◀── cmd patch {cpu: 12 %} ─────────────│
 │── event {session 3, back} ───────────▶│  (erste Seite: Uhr schließt)
 │── app.stop {session 3} ──────────────▶│  Ereignis stop
 │◀── app.ended {session 3, closed} ─────│
```

Die Uhr zeigt eine Zielseite eines Knopfs sofort und schickt danach `event navigate` (02 §5), sie wartet
dafür nicht auf den Rechner.

### 5.6 Verbindung verloren und wieder da

- Die Uhr versucht es erneut nach 1, 2, 4, 8, … höchstens 30 s. Solange zeigt die Kopfzeile
  „Rechner getrennt“, und die letzte Seite bleibt stehen. Ereignisse in dieser Zeit werden verworfen,
  nicht gepuffert (sonst lösen alte Klicks später Aktionen aus).
- Der Host hält Sitzungen **5 Minuten** ohne Uhr am Leben. Er merkt sich je Sitzung die Seitendefinitionen,
  alle Änderungen und die aktuelle Seite.
- Nach `hello` sendet die Uhr `resume` mit ihren Sitzungen. Der Host schickt je Sitzung
  `cmd definePages` mit dem aktuellen Stand und `cmd replace` mit der aktuellen Seite, oder
  `app.ended`, wenn die Sitzung nicht mehr besteht.

### 5.7 Fehlercodes

`unauthorized`, `pairing_closed`, `bad_code`, `unknown_app`, `unknown_session`, `permission_denied`,
`bad_message`, `too_large`, `rate_limited`, `app_crashed`, `internal`. Jeder Fehler hat eine deutsche
`message`, die die Uhr als Hinweis zeigen kann.

### 5.8 Versionen

`proto` in `hello` nennt die Version. Erweiterungen, die nur Felder oder Befehle hinzufügen, bleiben
`g2-remote@1`: Die Gegenseite ignoriert Unbekanntes. Änderungen der Bedeutung erhöhen auf `@2`. Der Host
antwortet auf eine unbekannte Version mit `error` `bad_message` und schließt.

## 6. Leistungsregeln

| Regel | Wert |
|---|---|
| Befehle Rechner → Uhr | höchstens 20 je Sekunde; die Uhr fasst sie zusammen und zeichnet höchstens alle 200 ms |
| Bildpixel (`frame`) | höchstens 2 unbestätigt unterwegs (`frame.ack`), Bereich ≤ 576 × 288 |
| Seitendefinitionen | ≤ 64 KiB je Nachricht; größere Projekte seitenweise senden |
| Audio | nur, solange die App sichtbar ist und `audio` an hat |

Die Bluetooth-Strecke zur Brille bleibt der Engpass (≈ 41 KiB/s, [01](01_Plattform_und_Grenzen.md)).
Der Rechner darf schnell senden, die Uhr gibt nur den neuesten Stand weiter.

## 7. Tests

- **Gemeinsame Beispielnachrichten** unter `protocol/vectors/` im Repo (eine JSON-Datei je
  Nachrichtentyp, gültig und ungültig). Die Kotlin-Seite (Uhr) und die TypeScript-Seite (Host) prüfen
  beide gegen dieselben Dateien.
- Host: Unit-Tests für Kopplung (Code-Ablauf, Sperre, Token-Hash), Sitzungen, Absturz einer App,
  Resume; ein Integrations-Test mit einer simulierten Uhr (Node-WebSocket-Client), die `echo` bedient.
- Uhr: `RemoteHostClient` gegen `okhttp3.mockwebserver`; Reconnect mit Backoff; verworfene Ereignisse
  bei getrennter Verbindung; `frame.ack`-Flusskontrolle.
