# 01 – Plattform und Grenzen

Zahlen, die jede App-Entscheidung prägen. Quellen: das Übergabe-Paket
([`../firmware-uebergabe/wissen/`](../firmware-uebergabe/wissen/)), Faceclaws Quelltext und die
Recherche in [`quellen/`](quellen/). „Gemessen“ heißt: von Faceclaw auf Hardware gemessen, nicht
von uns.

## 1. Brille (Even G2 mit Faceclaw/35)

### Anzeige

| Größe | Wert | Bedeutung für Apps |
|---|---|---|
| Bildspeicher je Glas | 640 × 480 Pixel | Die Uhr zeichnet in diese Fläche. |
| Sichtbarer Streifen | 640 × **288**, mittig (y = 96 … 383) | Die Optik zeigt nicht die ganze Höhe. Faceclaw und G2 Watch legen alles in diesen Streifen. |
| **App-Fläche** | **576 × 260** bei (32, 124) | Unter der 28 px hohen Kopfzeile der Uhr (Uhrzeit, App-Name, Akku). Das ist die Fläche einer App. |
| App-Fläche Vollbild | 576 × 288 bei (32, 96) | Ohne Kopfzeile (`statusBar: false`); so groß ist auch Evens Entwickler-Leinwand. |
| Farben | 16 Graustufen, grün | Stufe 0 = aus = durchsichtig. Helle Flächen blenden, große helle Flächen vermeiden. |
| Tiefe | Versatz je Glas −128 … 127 px | Faceclaw nutzt 2–4 px für Hervorhebungen, −2 für die Kopfzeile. Sparsam einsetzen. |
| Schrift | frei, weil die Uhr Text als Pixel zeichnet | Umlaute und Sonderzeichen gehen. Unter 16 px ist Text schlecht lesbar. |

### Übertragung Uhr → Brille (Bluetooth LE)

| Größe | Wert | Quelle |
|---|---|---|
| Durchsatz | ≈ 41 KiB/s (gemessen mit Firmware 2.2.9; für 2.3.0 und für die Pixel Watch ungeprüft) | wissen/02 §3.19 |
| Nachrichten | höchstens 3 unbestätigt unterwegs, Bestätigung p99 63 ms | ebd. |
| Eingabe bis Pixel | ≈ 250 ms, „eine Handvoll Bilder pro Sekunde“ (Faceclaws Spiele 9–12 fps) | ebd. |
| Ganzes Bild | 153.600 Byte (640 × 480 × 4 Bit) ≈ 3,7 s ohne Kompression | ebd. |
| Animation auf der Brille | 45-ms-Takt, **ohne** Bluetooth-Verkehr (Ausdrücke in Display-Listen) | wissen/02 §3.10 |
| Zwischenspeicher der Brille | 192 KiB, 512 Ressourcen, je höchstens 64 KiB | `ResourceCacheState.kt` |

Was daraus folgt:
- Die Uhr schickt nur **Änderungen**: Faceclaws Kern vergleicht jedes Bild mit dem vorigen und
  überträgt geänderte Streifen (RLE). Ein neuer Wert in einer Zeile kostet wenige hundert Byte, ein
  Seitenwechsel einige zehn KiB.
- **Höchstens 2–5 Aktualisierungen pro Sekunde** planen. Nie ein Video oder eine laufende Uhr mit
  Sekunden-Takt über die ganze Fläche.
- Neue Bilder ersetzen noch nicht gesendete ältere (nur das neueste zählt). Eine App muss also nicht
  drosseln, verschwendet aber Akku, wenn sie es nicht tut.

### Eingaben an der Brille

| Quelle | Ereignisse |
|---|---|
| Bügel links/rechts (Touchfläche) | Tippen, Doppeltippen, Wischen hoch/runter (Scroll), langes Drücken und Loslassen, Tippen-dann-Halten (öffnet das App-Menü, [02 §5](02_App-Modell.md#5-navigation-und-app-menü)); beide Bügel lange drücken schaltet den Lautlos-Modus |
| Ring R1 (über die Brille, wenn dort gekoppelt) | dieselben Gesten; Quelle „ring“ |
| Kopf heben | „head-up“ (IMU-Neigung) |
| Sensoren | Beschleunigung (IMU, „experimentell“, Takt als Firmware-Code 100–1000), Kompass (Magnetometer der Faceclaw-Firmware), Umgebungslicht (gehört auf der Faceclaw-Firmware der Helligkeitsregelung), Tragen erkannt |
| Mikrofon | **LC3**-kodiert: 16 kHz mono, 10-ms-Rahmen à 40 Byte (32 kbit/s); Pakete à 205 Byte = 5 Rahmen = 50 ms, vom linken Glas (Doppel vom rechten möglich, per Zähler aussortieren). Entschlüsseln mit liblc3 (Apache-2.0) zu PCM s16le. Ein Mikrofon für alle Apps. Siehe wissen/03 §7 |
| Ausgabe außer Bild | Summer (Piezo) mit Tonfolgen, Helligkeit |

Heute leitet G2 Watch nur Tippen (= Klick) und Doppeltippen (= zurück) an den Desktop weiter; alle
anderen Eingaben verwirft `GlassesConnection.onRingEvent`. Wie die Gesten technisch ankommen (Ereignisart,
Codes, Quelle), steht in [03 §5.1](03_Uhr-Apps.md#51-gesten-der-brille). IMU, Kompass, Mikrofon und Summer
sind im Faceclaw-Kern vorhanden (`GlassesSessionCore`), aber noch nicht zu den Apps verdrahtet (M5).

## 2. Uhr (Pixel Watch 5, 45 mm, LTE)

| Größe | Wert |
|---|---|
| System | Wear OS 7 = Android 17, API 37; die App hat `minSdk 33`, `targetSdk 37` |
| Bildschirm | rund, 456 × 456 px, 320 ppi |
| Netz | WLAN, LTE, Bluetooth (zur Brille; zum Handy nur, wenn gekoppelt) |
| Eingabe für die Brille | Touchpad als Maus (Finger ziehen = Zeiger, Doppeltippen = Klick), Krone = Zeigertempo, Zahnrad halten = Einstellungen |
| Rückmeldung | Vibration |
| Was fehlt | **kein WebView, keine JavaScript-Engine** im System; keine Kamera |
| Laufzeit | Vordergrund-Dienst (`connectedDevice`) hält die Verbindung; Akku 465 mAh – jede Rechenlast kostet Tragezeit |

## 3. Rechner (PC, Server, Handy)

Jedes Gerät, auf dem **Node.js ≥ 22** läuft: Windows, macOS, Linux, ein Raspberry Pi, ein Server.
Ein Android-Handy geht über Termux (Node aus dem Termux-Paket) nur für Rechner-Apps; das ist ungetestet,
und Android beendet Hintergrund-Prozesse gern. Der **EvenHub-Adapter braucht einen PC oder Server**, weil
Playwright/Chromium unter Termux nicht läuft. Der Rechner hat nie Bluetooth zur Brille. Er spricht nur
mit der Uhr, über WebSocket.

## 4. Netz zwischen Uhr und Rechner

| Lage | Weg | Hinweise |
|---|---|---|
| Zu Hause, gleiches WLAN | `ws://192.168.x.y:8790/g2` | Der Rechner meldet sich per mDNS (`_g2host._tcp`), die Uhr findet ihn ohne Tippen. Die Uhr muss dafür wirklich im WLAN sein, siehe unten. |
| Unterwegs (LTE) | `wss://…/g2` über einen verschlüsselten Tunnel oder VPN zum Rechner | Der Rechner ist aus dem Mobilnetz sonst nicht erreichbar (NAT). Möglich: Cloudflare Tunnel, ein eigener Server mit TLS, oder ein VPN wie Tailscale, falls es auf der Uhr läuft (ungeprüft). **Nie ohne TLS über das Internet.** |

**Netz auf der Uhr (Wear OS):** Solange die Uhr mit dem Handy gekoppelt ist, läuft ihr Verkehr
standardmäßig über das Handy (Bluetooth-Proxy); dann sind Geräte im Heim-WLAN nicht erreichbar. Die Uhr
muss für den Rechner ein Netz anfordern (`ConnectivityManager.requestNetwork` mit `TRANSPORT_WIFI`, für
Internet-Adressen auch Mobilfunk) und die Verbindung an dieses Netz binden (Socket-Factory des Netzes).
mDNS (`NsdManager`) geht nur im WLAN. Weil die App auf API 37 zielt, braucht Zugriff aufs lokale Netz
die Laufzeit-Berechtigung `ACCESS_LOCAL_NETWORK` (Gruppe „Geräte in der Nähe“; wer Bluetooth erlaubt hat,
wird meist nicht erneut gefragt). Unverschlüsseltes `ws://` muss in der Network-Security-Config erlaubt
werden; die App lässt es nur zu privaten Adressen zu.

Latenz zusätzlich zu den 250 ms zur Brille: WLAN wenige ms, LTE typischerweise 30–100 ms pro
Richtung (Erfahrungswerte, nicht gemessen). Eine Rechner-App muss also mit **0,3–0,5 s** vom Klick
bis zur sichtbaren Antwort rechnen. Deshalb navigiert die Uhr zwischen Seiten selbst (ohne
Rückfrage), wenn ein Knopf ein festes Ziel hat ([02 §5](02_App-Modell.md#5-navigation-und-app-menü)).

## 5. Grenzen der originalen Even-Hub-Plattform (nur für EvenHub-Apps)

Even-Hub-Apps sind für diese Grenzen geschrieben; der Adapter bildet sie nach
([05](05_EvenHub-Apps.md)):

- Leinwand 576 × 288, höchstens 12 Container je Seite (≤ 4 Bild, ≤ 8 Text/Liste), genau einer nimmt Eingaben an.
- Text ≤ 1000 Zeichen beim Anlegen, ≤ 2000 beim Aktualisieren; Liste ≤ 20 Einträge à 64 Zeichen.
- Bilder 20–288 × 20–144 px, 4 Bit, nacheinander mit ≥ 100 ms Abstand.
- Eine feste Schrift (20 px), keine Größen, keine Emojis.
