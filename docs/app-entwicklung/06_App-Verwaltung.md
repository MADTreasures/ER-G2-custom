# 06 – App-Verwaltung

Apps werden an zwei Stellen verwaltet:

| Was | Wo | Meilenstein |
|---|---|---|
| **Even-Hub-Apps** installieren, auf Uhr oder Handy legen, entfernen | Handy-App **„G2 Handy“** (§1); zur Not direkt auf der Uhr über eine Adresse (§2) | M4 (Uhr-Weg schon in M3) |
| **Rechner-Apps** installieren, starten, entfernen; Uhr koppeln | **Web-Seite von `g2-host`** auf dem Rechner (§3) | M5 |

Uhr-Apps stehen in keiner Verwaltung: Sie sind Teil der APK und kommen mit einem App-Update auf die Uhr.

## 1. „G2 Handy“ (Android, Handy)

Die Begleit-App aus [05 §6](05_EvenHub-Apps.md#6-engine-auf-dem-handy-g2-handy). Gleiche `applicationId` und
Signatur wie die Uhr-App (sonst keine Datenschicht). Oberfläche mit Jetpack Compose, deutsch, hell/dunkel.

| Seite | Inhalt |
|---|---|
| **Übersicht** | Uhr verbunden ja/nein (über die Datenschicht), Akku von Uhr und Brille, laufende Even-Hub-Apps mit Ort (Uhr/Handy) |
| **Apps** | Installierte Even-Hub-Apps als Karten: Name, Version, **Ort** (Uhr oder Handy, umschaltbar), Berechtigungen. Knöpfe „Auf der Brille starten“, „Protokoll“, „Entfernen“ |
| **Installieren** | Wege aus §1.1 |
| **App-Details** | `app.json`, Berechtigungen im Klartext („darf das Mikrofon der Brille benutzen“), Datenschutz-Adresse, falls `app.json` eine nennt (das offizielle Format hat kein festes Feld; Faceclaw liest mehrere Schreibweisen), Speicherplatz, Protokoll (Konsole der App) |
| **Einstellungen** | Standard-Ort für neue Apps (Uhr/Handy), Lizenzen (GeckoView/MPL, LGPL-Bibliotheken, GPL-Teile) |

### 1.1 Installieren

| Weg | Ablauf | Prüfungen |
|---|---|---|
| **Datei** (`.ehpk` oder ZIP mit `app.json` + `dist/`) | Dateiauswahl des Handys, entpacken ([05 §1](05_EvenHub-Apps.md#1-was-eine-even-hub-app-ist)) | `app.json` gültig, Pfade ohne `..`, SHA-512 am Ende bei `.ehpk`, Größe ≤ 50 MB |
| **Adresse** (HTTPS, z. B. ein Release-Paket auf GitHub) | herunterladen, dann wie Datei | wie oben |
| **Entwicklungs-Server** (z. B. `http://192.168.1.20:5173`) | keine Kopie; die App läuft direkt von dort | als „Entwicklung“ markiert; nur im Heim-WLAN |

Vor der Installation zeigt die App die Berechtigungen und fragt „Installieren?“. Danach:
- **Ort Uhr:** Die Dateien gehen über die Datenschicht (`ChannelClient`) auf die Uhr; die Uhr entpackt, prüft
  erneut und meldet „installiert“. Bis dahin zeigt die Karte „wird übertragen …“.
- **Ort Handy:** Die Dateien bleiben auf dem Handy; die Uhr bekommt nur den Eintrag für ihren Starter.
- Eine neue Version ersetzt die alte, ihr Speicher bleibt erhalten. Ort wechseln = Dateien übertragen
  bzw. löschen. Der Speicher aus `setLocalStorage` liegt immer auf der Uhr und bleibt; der Web-Speicher
  der Engine geht beim Ortswechsel nicht mit ([05 §4.1](05_EvenHub-Apps.md#41-methoden)).
- Große Pakete über Bluetooth dauern: 50 MB grob 4–17 Minuten. Besser im WLAN oder direkt auf der Uhr per
  Adresse installieren (§2).

**Kein Store:** „G2 Handy“ lädt keine Apps aus Evens Store ([05 §7](05_EvenHub-Apps.md#7-apps-installieren-und-woher-sie-kommen-dürfen)).
Eine eigene Sammlung (z. B. eine `katalog.json` in einem Git-Repo mit Adressen zu Release-Paketen) ist möglich
und wäre ein kleiner Zusatz zum Weg „Adresse“.

## 2. Direkt auf der Uhr

Für den Fall ohne Handy: Einstellungen → „Even-Hub-Apps“ → „Von Adresse installieren“ (Tastatur oder
Spracheingabe der Uhr), Download über WLAN/LTE, gleiche Prüfungen wie §1.1, danach Berechtigungen auf der
Brille bestätigen. Entfernen geht ebenfalls dort. Das kommt mit der EvenHub-Laufzeit in M3.

## 3. Web-Seite von `g2-host` (Rechner-Apps)

Wird vom Rechner-Host selbst ausgeliefert und ist fürs Handy gemacht.

**Aufruf und Anmeldung:**
- Adresse: `http://<rechner>:8790/`, zum Beispiel vom Handy im selben WLAN. Von unterwegs nur über denselben
  TLS-Tunnel oder dasselbe VPN wie die Uhr ([04 §4](04_Rechner-Apps_und_Protokoll.md#4-kopplung-und-sicherheit)).
- Beim ersten Start schreibt `g2-host` in die Konsole einen **Einrichtungs-Link** mit einmaligem Code. Dort
  legt man ein Passwort für die Verwaltung fest.
- Danach: Anmeldung mit Passwort. Das Sitzungs-Cookie ist `HttpOnly` und `SameSite=Strict` und läuft
  nach 30 Tagen ab. Jede Änderung (POST, DELETE) trägt ein CSRF-Token.

**Seiten:**

| Seite | Inhalt |
|---|---|
| **Übersicht** | Verbundene Uhr (Name, seit wann, Akku von Uhr und Brille aus der Nachricht `status`), laufende Apps mit „Beenden“, zuletzt aufgetretene Fehler |
| **Apps** | Alle installierten Rechner-Apps als Karten: Name, Version, Berechtigungen. Knöpfe „Auf der Brille starten“, „Protokoll“, „Entfernen“ |
| **Installieren** | ZIP oder Ordner mit `g2app.json` hochladen; oder Git-Adresse (HTTPS): klonen, `npm ci --ignore-scripts`, nach Bestätigung bauen (Build-Skripte führen beliebigen Code aus), installieren |
| **App-Details** | Manifest, Berechtigungen im Klartext, Speicherplatz, Protokoll der letzten 500 Zeilen |
| **Uhr koppeln** | 6-stelliger Code mit Ablaufzeit (5 Minuten), Liste der gekoppelten Uhren (Token-Anfang, 12 Zeichen) mit „Widerrufen“ |
| **Einstellungen** | Name des Rechners (für mDNS und die Uhr), Port, TLS-Zertifikat, Tunnel ja/nein, erlaubte Netze, Passwort ändern |

**Technik:**
- Ausgeliefert vom selben Node-Prozess wie das Protokoll (Port 8790). Schlichtes HTML mit wenig JavaScript,
  ohne Build-Schritt und ohne externe Server; Schriften kommen mit.
- Schnittstelle unter `/api/…` (JSON): `GET /api/status`, `GET /api/apps`, `POST /api/apps` (multipart),
  `POST /api/apps/:id/start`, `POST /api/apps/:id/stop`, `DELETE /api/apps/:id`, `GET /api/apps/:id/log`,
  `POST /api/pairing`, `DELETE /api/tokens/:prefix`.
- „Auf der Brille starten“ schickt der Uhr `app.launch` ([04 §5.3](04_Rechner-Apps_und_Protokoll.md#53-nachrichten)).
- Aussehen wie der G2 Baukasten (ruhiges Papier, Tintenblau), dunkles und helles Farbschema, ab 360 px Breite.

## 4. Tests

- „G2 Handy“: Unit-Tests für Entpacken und Prüfen (gültige/ungültige Pakete: `..`, fehlendes `app.json`, zu
  groß, falsche Prüfsumme), Übertragung mit einem Fake der Datenschicht, Compose-UI-Tests (Robolectric) für
  Installieren und Ort wechseln.
- `g2-host`: API-Tests gegen einen Host auf Zufalls-Port (Anmeldung, CSRF, Installation, ungültige Pakete) und
  ein Playwright-Test der Seiten in 390 × 844 und 1280 × 800.
