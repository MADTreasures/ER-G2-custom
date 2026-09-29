# 06 – App-Verwaltung (Web-Seite von `g2-host`)

Die App-Verwaltung ist die Web-Anwendung zum **Installieren, Starten und Entfernen** von Apps und zum
**Koppeln der Uhr**. Sie wird vom Rechner-Host selbst ausgeliefert und ist fürs Handy gemacht.
Uhr-Apps stehen nicht darin: Sie sind Teil der APK und kommen mit einem App-Update auf die Uhr.

**Stand:** zu bauen in M3.

## 1. Aufruf und Anmeldung

- Adresse: `http://<rechner>:8790/`, zum Beispiel vom Handy im selben WLAN. Von unterwegs geht es nur
  über denselben TLS-Tunnel oder dasselbe VPN wie die Uhr ([04 §4](04_Rechner-Apps_und_Protokoll.md#4-kopplung-und-sicherheit)).
- Beim ersten Start schreibt `g2-host` in die Konsole einen **Einrichtungs-Link** mit einmaligem Code. Dort
  legt man ein Passwort für die Verwaltung fest.
- Danach: Anmeldung mit Passwort. Das Sitzungs-Cookie ist `HttpOnly` und `SameSite=Strict` und läuft
  nach 30 Tagen ab. Jede Änderung (POST, DELETE) trägt ein CSRF-Token.

## 2. Seiten

| Seite | Inhalt |
|---|---|
| **Übersicht** | Verbundene Uhr (Name, seit wann, Akku von Uhr und Brille aus der Nachricht `status`), laufende Apps mit „Beenden“, zuletzt aufgetretene Fehler |
| **Apps** | Alle installierten Rechner- und EvenHub-Apps als Karten: Name, Version, Laufzeit, Berechtigungen. Knöpfe „Auf der Brille starten“, „Protokoll“, „Entfernen“ |
| **Installieren** | Vier Wege, siehe §3 |
| **App-Details** | Manifest, Berechtigungen im Klartext („darf das Mikrofon der Brille benutzen“), Speicherplatz, Protokoll der letzten 500 Zeilen, bei EvenHub-Apps eine Datenschutz-Adresse, falls `app.json` eine nennt (das offizielle Format hat kein festes Feld; Faceclaw liest mehrere Schreibweisen) |
| **Uhr koppeln** | 6-stelliger Code mit Ablaufzeit (5 Minuten), Liste der gekoppelten Uhren (Token-Anfang, 12 Zeichen) mit „Widerrufen“ |
| **Einstellungen** | Name des Rechners (für mDNS und die Uhr), Port, TLS-Zertifikat, erlaubte Netze, Passwort ändern |

## 3. Installieren

| Weg | Ablauf | Prüfungen |
|---|---|---|
| **ZIP oder Ordner** mit `g2app.json` (Rechner-App) bzw. `app.json` + `dist/` (EvenHub-App) | hochladen, entpacken nach `apps/<id>/` | Manifest gültig, Pfade ohne `..`, Größe ≤ 50 MB |
| **`.ehpk`-Datei** | hochladen, entpacken ([05 §1](05_EvenHub-Apps.md#1-was-eine-even-hub-app-ist)) | wie oben, SHA-512 am Ende prüfen |
| **Git-Adresse** (HTTPS) | klonen in einen Arbeitsordner, `npm ci --ignore-scripts`, dann `npm run build`, Ergebnis installieren | Anzeige der Befehle vorher; Bauen erst nach Bestätigung, weil Build-Skripte beliebigen Code ausführen |
| **Entwicklungs-Adresse** (z. B. `http://192.168.1.20:5173`) | keine Kopie; die App läuft direkt von dort | nur für EvenHub-Apps, als „Entwicklung“ markiert |

Vor jeder Installation zeigt die Seite die Berechtigungen und fragt „Installieren?“. Eine neue Version
einer vorhandenen App ersetzt die alte. Ihr Speicher bleibt erhalten.

**Kein Store:** Die Seite lädt keine Apps aus Evens Store ([05 §5](05_EvenHub-Apps.md#5-woher-apps-kommen-dürfen)).
Eine spätere eigene Sammlung (z. B. ein Git-Repo mit einer `katalog.json` voller Git-Adressen) ist
möglich und wäre ein kleiner Zusatz zum Weg „Git-Adresse“.

## 4. Technik

- Ausgeliefert vom selben Node-Prozess wie das Protokoll (Port 8790). Die Seiten sind schlichtes HTML
  mit wenig JavaScript, ohne Build-Schritt und ohne externe Server. Schriften kommen mit, damit die
  Seite auch ohne Internet geht.
- Schnittstelle unter `/api/…` (JSON), von der Seite selbst benutzt: `GET /api/status`, `GET /api/apps`,
  `POST /api/apps` (Installation, multipart), `POST /api/apps/:id/start`, `POST /api/apps/:id/stop`,
  `DELETE /api/apps/:id`, `GET /api/apps/:id/log`, `POST /api/pairing`, `DELETE /api/tokens/:prefix`.
- „Auf der Brille starten“ schickt der Uhr `app.launch` ([04 §5.3](04_Rechner-Apps_und_Protokoll.md#53-nachrichten)),
  und die Uhr startet die App wie aus dem Starter.
- Aussehen: wie der G2 Baukasten (ruhiges Papier, Tintenblau, die Brillen-Vorschau als einziges dunkles
  Element), dunkles und helles Farbschema, bedienbar ab 360 px Breite.

## 5. Tests

- API-Tests mit `supertest` oder `fetch` gegen einen Host auf Zufalls-Port: Anmeldung, CSRF, Installation
  jedes Wegs mit Beispiel-Dateien, ungültige Pakete (Pfad mit `..`, fehlendes Manifest, zu groß).
- Ein Playwright-Test der Seiten in 390 × 844 und 1280 × 800: Installieren, Starten (mit simulierter Uhr),
  Entfernen, keine horizontale Scroll-Leiste.
