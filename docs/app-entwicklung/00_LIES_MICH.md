# App-Entwicklung für G2 Watch – Spezifikation

Diese Mappe beschreibt, wie Apps für die Even Realities G2 mit Faceclaw-Firmware (Revision 35) und
der Uhr-App **G2 Watch** (Pixel Watch 5) programmiert werden. Sie ist für neue Chats geschrieben:
Wer sie liest, soll ohne weitere Fragen eine App bauen – oder die noch fehlenden Teile der Plattform.

Code, Bezeichner und Commit-Texte sind englisch, Erklärungen deutsch.

## Die Idee in fünf Sätzen

1. Die **Uhr** hält als einziges Gerät die Bluetooth-Verbindung zur Brille, zeichnet und nimmt
   die Eingaben entgegen: Touchpad-Maus auf der Uhr, Bügel und Ring an der Brille.
2. Eine **App** beschreibt ihre Oberfläche als **Seiten aus Bausteinen**, im gleichen Format wie der
   [G2 Baukasten](../../designer/README.md). Sie reagiert auf **Ereignisse** wie Klicks, Schalter,
   Gesten, Timer und Sensoren.
3. Eigene Apps laufen **auf der Uhr** (Kotlin, als App-Paket installiert, [09](09_App-Pakete.md)) oder, wenn sie viel rechnen, **auf einem
   Rechner** (PC, Server oder Handy; TypeScript), der über **WLAN oder LTE** mit der Uhr spricht.
4. Apps aus Evens **Even Hub** (Web-Apps mit Evens SDK) laufen **auf der Uhr** in einer eingebauten
   Browser-Engine (GeckoView, weil Wear OS keinen Browser-Kern hat). Geht eine App dort nicht, läuft sie
   **auf dem Handy** in der Begleit-App „G2 Handy“; gezeichnet wird trotzdem auf der Uhr.
5. Installiert werden Even-Hub-Apps über „G2 Handy“ oder direkt auf der Uhr über eine Adresse;
   Rechner-Apps über eine Web-Seite des Rechners.

```
            Brille (Faceclaw/35)
                  ▲  Bluetooth LE: Pixel, Text, Display-Listen   ▼ Bügel, Ring, IMU, Mikrofon
                  │
   ┌──────────────┴───────────────────────────────────────────────┐
   │ Uhr: G2 Watch                                                  │
   │  App-Host ── Seiten zeichnen, Fokus, Maus, Starter, App-Menü   │
   │   ├─ Uhr-Apps (Kotlin, im APK)                                 │
   │   ├─ EvenHub-Laufzeit ── GeckoView (Browser-Engine auf der Uhr)│
   │   │                  └── oder Engine auf dem Handy ────────────┼──┐ Wear-OS-Datenschicht
   │   └─ Rechner-Client (WebSocket) ───────────────────────────────┼─┐│ (Bluetooth/WLAN)
   └────────────────────────────────────────────────────────────────┘ ││
                                                   WLAN / LTE          ││
   ┌──────────────────────────────────────────┐  g2-remote@1          ││
   │ Rechner: g2-host (Node, PC/Server/Handy)  │◀─────────────────────┘│
   │  Rechner-Apps (TypeScript), Web-Verwaltung│                       │
   └──────────────────────────────────────────┘                        ▼
                                     ┌────────────────────────────────────────────┐
                                     │ Handy: G2 Handy (Android)                    │
                                     │  Even-Hub-Apps im WebView (Ausweichweg),     │
                                     │  Apps installieren und auf die Uhr bringen   │
                                     └────────────────────────────────────────────┘
```

## Wo läuft meine App? – Entscheidung

| Die App … | Laufzeit | Kapitel |
|---|---|---|
| zeigt Werte, Listen, Knöpfe; braucht höchstens kurze Web-Abfragen; soll ohne Rechner gehen | **Uhr-App** (Kotlin) | [03](03_Uhr-Apps.md) |
| rechnet viel (KI, Spracherkennung, Bilder), nutzt Programme/Dateien des PCs, hält große Daten | **Rechner-App** (TypeScript) | [04](04_Rechner-Apps_und_Protokoll.md) |
| gibt es schon als Even-Hub-App (Web-App mit `@evenrealities/even_hub_sdk`), oder soll eine sein | **EvenHub-App**: auf der Uhr, sonst auf dem Handy | [05](05_EvenHub-Apps.md) |

Faustregel für Uhr-Apps: Alles, was auf der Uhr länger als **50 ms** am Stück rechnet, mehr als
**20 MB** Speicher braucht oder dauernd im Netz hängt, gehört auf den Rechner. Die Uhr ist Anzeige und
Eingabe, kein Rechenknecht (Akku).

**Ausnahme Hintergrundarbeit (v0.12.0):** Länger rechnen darf eine Uhr-App mit `work` auf einem Thread der Uhr
([03 §14](03_Uhr-Apps.md#14-gps-große-dateien-und-hintergrundarbeit-v0120-schnittstelle-5)); der App-Thread
hält die 50 ms trotzdem. Große Daten (Karten, Medien) liegen im eigenen Ordner der App, nicht im Speicher.
So rechnet die Navigation [Navi](../../packages/navi/README.md) Routen und ihr Suchverzeichnis auf der Uhr, mit
höchstens 64 MB Speicher.

**Ausnahme Video (v0.5.0):** Die App YouTube läuft auf Wunsch ganz auf der Uhr, ohne Rechner. Das schwere
Arbeiten (Dekodieren, Verkleinern) machen ein Plattform-Teil mit eigenen Threads, der Hardware-Decoder und die
GPU der Uhr; die App selbst hält die 50-ms-Regel ([03 §10](03_Uhr-Apps.md#10-video-auf-der-brille-v050)).
Weitere Video-Apps können denselben Befehl `video` nutzen.

## Stand – was es schon gibt, was gebaut werden muss

| Teil | Stand | Wo |
|---|---|---|
| Firmware aufspielen, Verbindung, Maus-Zeiger auf der Brille | **fertig** (v0.3.0; die Kacheln des Desktops sind seit v0.11.0 weg, der Starter ist das Hauptmenü). **Custom-Firmware auf echter Uhr und Brille aufgespielt** (rechtes Glas erst im zweiten Anlauf, der Rückweg aus der Fehlermeldung hat funktioniert); das Übrige nicht auf Hardware erprobt. Seit v0.9.0 Bildtiefe Nah/Mittel/Fern und alles im Bereich von Evens Bild (x 32 … 607); beide Gläser im Code geprüft, kein Versatz gefunden | `app/`, [FIRMWARE.md](../FIRMWARE.md), [03 §5.3](03_Uhr-Apps.md#53-bildtiefe-und-bildrand) |
| G2 Baukasten (Seiten entwerfen) | **fertig**, braucht kleine Erweiterungen (M0) | `designer/` |
| App-Modell, Seitenformat, Ereignisse | **spezifiziert** hier | [02](02_App-Modell.md) |
| App-Host auf der Uhr, Uhr-Apps (Starter, App-Menü) | **fertig** (M1, v0.4.0 aus Pull Request #2, nicht auf Hardware erprobt). Aus Pull Request #1, der den Host ein zweites Mal baute, sind nur die M2-Teile übernommen (v0.7.0). Die Beispiele Stoppuhr und Einkaufsliste sind entfernt (v0.7.0). Seit v0.9.0 zeigt der Starter die Apps als Symbole im Raster (4 × 2), Pakete bringen ihr Symbol mit; seit v0.11.0 ist er das Hauptmenü der Brille; seit v0.14.0 lassen sich seine Apps verschieben wie am Handy (2 s halten, die App hängt am Zeiger, die anderen machen Platz; nicht auf Hardware erprobt) | [03](03_Uhr-Apps.md), `app/…/apps/` |
| **App-Pakete** (`.g2app`): eigene Apps getrennt von der Uhr-App installieren, Seite „Apps“ auf der Uhr | **fertig** (v0.6.0, nicht auf Hardware erprobt). Pakete: YouTube, Browser, Fluency, Navi, Musik; keine App mehr fest eingebaut. Schnittstelle Version 2 seit v0.8.0 (Web-Seiten), Version 3 seit v0.9.0 (Tastatur), Version 4 seit v0.10.0 (Apps auf dem Handy), Version 5 seit v0.12.0 (GPS, große Dateien, Hintergrundarbeit), Version 6 seit v0.13.0 (Musik der Uhr und eigener Ton) | [09](09_App-Pakete.md), `packages/`, `app-api/` |
| Texteingabe auf der Uhr, Video auf der Brille, App **YouTube** | **fertig** (v0.5.0, seit v0.7.0 als App-Paket; seit v0.9.0 feineres Raster, randlos 512 × 288, bis 15 Bilder pro Sekunde, simuliert; nicht auf Hardware erprobt; YouTube-Wiedergabe ungeprüft) | [03 §10](03_Uhr-Apps.md#10-video-auf-der-brille-v050), `app/…/apps/video/`, `packages/youtube/` |
| Bluetooth-Tastatur und -Maus für alle Apps: Zeiger, Klicks, Rad, Tasten, Text auf der Brille tippen, eigene Belegungen Schweiz/Deutschland, Koppeln aus der App; Schnittstelle 3 | **gebaut** (v0.9.0, nicht auf Hardware erprobt; ob Wear OS auf der Pixel Watch Tastaturen und Mäuse annimmt, ist offen) | [03 §5.4](03_Uhr-Apps.md#54-tastatur-und-maus), `app/…/input/` |
| Emoji in allen Texten auf der Brille (Schwarz-Weiß-Schrift Noto Emoji statt Kleckse) | **fertig** (v0.5.2, nicht auf Hardware erprobt) | [03 §11](03_Uhr-Apps.md#11-emoji-v052), `app/…/desktop/AndroidTextPainter.kt`, `EmojiText.kt` |
| GeckoView auf der Uhr: Machbarkeitstest | **Test-APK „Gecko-Test“ gebaut** (M2, seit v0.7.0 im Standard-Branch); **die Messwerte von der echten Uhr fehlen noch**. Seit v0.8.0 steckt GeckoView auch in der Uhr-App (Browser) | `tools/gecko-probe/`, [05 §5.2](05_EvenHub-Apps.md#52-die-test-apk-gecko-test), [Messwerte](quellen/E-m2-messwerte.md) |
| EvenHub-Laufzeit auf der Uhr | **zu bauen** (M3) | [05](05_EvenHub-Apps.md) |
| Apps auf dem Handy für Uhr-Apps und Pakete (Schnittstelle 4, `phone`), Mikrofon der Uhr zum Handy; erste Nutzerin: das Paket Fluency mit dem Übersetzer Fluency auf dem Handy | **gebaut** (v0.10.0, nicht auf Hardware erprobt): Wear-OS-Datenschicht, Handy-App „G2 Handy“ als Vermittler, gemeinsamer Schlüssel | [03 §13](03_Uhr-Apps.md#13-apps-auf-dem-handy-v0100-schnittstelle-4), `app/…/apps/phone/`, `phone-link/`, `phone/`, [`signing/`](../../signing/README.md), [Fluency](../../packages/fluency/PLATTFORM.md) |
| Handy-App „G2 Handy“ (Ausweich-Engine, Apps installieren) | **erster Teil gebaut** (v0.10.0): App, Schlüssel und Datenschicht, bisher nur als Vermittler (Zeile darüber). **Zu bauen** (M4): Ausweich-Engine, Apps installieren | [05 §6–§7](05_EvenHub-Apps.md#6-engine-auf-dem-handy-g2-handy), [06](06_App-Verwaltung.md), `phone/` |
| Rechner-Apps: `g2-host`, Protokoll `g2-remote@1`, Web-Verwaltung | **zu bauen** (M5) | [04](04_Rechner-Apps_und_Protokoll.md), [06](06_App-Verwaltung.md) |
| Standort der Uhr (GPS) zu den Uhr-Apps, dazu ein Ordner je App für große Dateien mit Downloads (WLAN, auf Wunsch LTE, Weiterladen) und Hintergrundarbeit; Schnittstelle 5 | **gebaut** (v0.12.0, nicht auf Hardware erprobt) | [03 §14](03_Uhr-Apps.md#14-gps-große-dateien-und-hintergrundarbeit-v0120-schnittstelle-5), `app/…/apps/location/`, `app/…/apps/files/` |
| App **Navi**: Navigation offline zu Fuß und mit dem Velo, Karten jedes Landes der Welt einzeln ladbar (OpenStreetMap über download.mapsforge.org), Suche, Start und Ziel, Abbiegehinweise im Stil von Evens Navigation, Demo-Fahrt | **gebaut** (Paket 1.0.0, nicht auf Hardware erprobt) | [`packages/navi`](../../packages/navi/README.md) |
| Musik der Uhr für die Uhr-Apps: die Player der Uhr (Spotify …) sehen und steuern, über Androids Mediensteuerung (einmal Zugriff per adb, ohne ihn gehen die Medientasten), dazu ein eigener Audio-Stream je App (Radio, auch `http://`); Schnittstelle 6 | **gebaut** (v0.13.0, nicht auf Hardware erprobt) | [03 §15](03_Uhr-Apps.md#15-musik-der-uhr-und-eigener-ton-v0130-schnittstelle-6), `app/…/apps/media/` |
| App **Musik**: Spotify von der Brille aus bedienen (Titel, Cover, Zeit, Play/Pause, Titel weiter, Lautstärke, Suchen), Radio mit den 44 Schweizer Sendern von DAB+ als Internet-Stream, Favoriten, weltweite Sendersuche über radio-browser.info | **gebaut** (Paket 1.0.0, nicht auf Hardware erprobt) | [`packages/musik`](../../packages/musik/README.md) |
| App **Fahrplan**: der Schweizer Fahrplan aufgebaut und bedient wie die SBB-App: Planen mit „Aktueller Standort“, Verbindungen als Karten mit Reisebalken (weiterwischen lädt spätere und frühere), Ihre Reise mit Umsteigezeit, Fußwegen und aufklappbaren Zwischenhalten, Abfahrten, Fahrt eines Zuges, Haltestellen in der Nähe, jede Minute neu; Haltestelle mit Tastatur direkt auf der Brille getippt (Treffer beim Tippen) oder auf der Uhr; ab 1.2.0 die Zugformation mit Sektoren, Klassen und Angeboten der Wagen (Train Formation Service, eigener Schlüssel); selbst gezeichnete Seiten im Modus `gestures`; Daten von transport.opendata.ch (opentransportdata.swiss). Kein Abo-QR-Code (geht nicht, siehe README), keine Belegung | **gebaut** (Paket 1.2.0, nicht auf Hardware erprobt) | [`packages/fahrplan`](../../packages/fahrplan/README.md) |
| Mikrofon der Brille, IMU, Kompass zu den Apps; Standort für Rechner- und EvenHub-Apps | **zu bauen** (M6); das Mikrofon der Uhr gibt es seit v0.10.0 für Apps auf dem Handy, den Standort für Uhr-Apps seit v0.12.0 | [07](07_Umsetzungsplan.md) |
| Web-Browser auf der Brille: Seiten ins Brillen-Raster wandeln, Text immer lesbar (automatisch negativ) | **fertig** als Modul `web-raster` (Tests mit Testseiten), mit dem Layout-Skript `page-layout.js` für Browser und Gecko-Test | `web-raster/`, [05 §10](05_EvenHub-Apps.md#10-und-ein-richtiger-browser-auf-der-brille) |
| Web-Browser auf der Brille: Browser-App selbst | **gebaut** (M7, v0.8.0, auf Wunsch vor den M2-Messwerten): Engine GeckoView in der Uhr-App (nur `armeabi-v7a`, APK +≈ 106 MB), Oberfläche als App-Paket. **Nicht auf Hardware erprobt**; ob Speicher, Akku, Kaltstart und Bildweg auf der Uhr reichen, zeigen erst die M2-Werte | `app/…/apps/web/`, `packages/browser/`, [05 §10.2](05_EvenHub-Apps.md#102-die-browser-app-m7-gebaut-in-v080), [07 M7](07_Umsetzungsplan.md#m7--web-browser-auf-der-brille-wenn-m2-geckoview-ja-ergibt) |

Die Reihenfolge und die Abnahmekriterien stehen im [Umsetzungsplan](07_Umsetzungsplan.md),
die Texte für neue Chats in [08_Prompt_fuer_neuen_Chat.md](08_Prompt_fuer_neuen_Chat.md).

## Inhalt

| Datei | Worum es geht |
|---|---|
| [01_Plattform_und_Grenzen.md](01_Plattform_und_Grenzen.md) | Brille, Uhr, Handy, Rechner, Netz: Zahlen und Grenzen, die jede App kennen muss |
| [02_App-Modell.md](02_App-Modell.md) | Manifest, Lebenszyklus, Seiten und Bausteine, Ereignisse, Eingabe, Berechtigungen, Gestaltungsregeln |
| [03_Uhr-Apps.md](03_Uhr-Apps.md) | Kotlin-Schnittstelle, App-Host auf der Uhr, Beispiel, Tests |
| [04_Rechner-Apps_und_Protokoll.md](04_Rechner-Apps_und_Protokoll.md) | `g2-host`, TypeScript-Schnittstelle, das Protokoll `g2-remote@1` Nachricht für Nachricht, Kopplung, Sicherheit |
| [05_EvenHub-Apps.md](05_EvenHub-Apps.md) | Even-Hub-Apps: Aufbau, EvenHub-Laufzeit, GeckoView auf der Uhr, Ausweichweg Handy, Installation, Lizenzen |
| [06_App-Verwaltung.md](06_App-Verwaltung.md) | Apps installieren: „G2 Handy“ für Even-Hub-Apps, Web-Seite von `g2-host` für Rechner-Apps |
| [07_Umsetzungsplan.md](07_Umsetzungsplan.md) | Meilensteine M0–M7 mit Abnahme |
| [08_Prompt_fuer_neuen_Chat.md](08_Prompt_fuer_neuen_Chat.md) | Texte zum Einfügen in neue Chats: App-Chat (eine App) und Hauptchat (die Uhr-App) |
| [09_App-Pakete.md](09_App-Pakete.md) | App-Pakete: eine App bauen, auf die Uhr legen, installieren; wer was macht |
| [quellen/](quellen/) | Recherche-Notizen (Faceclaw, offizielle Even-Hub-Doku, Browser auf der Uhr) mit Quellenangaben |

## Harte Regeln für alle Chats

- **Nie Evens Firmware** (`*.bin`) ins Repo oder in Pakete legen; das gilt auch für Schriften,
  die aus ihr gezogen werden.
- **Firmware-Pfad nicht anfassen**, außer der Auftrag verlangt es ausdrücklich. Apps haben keinen
  Zugriff auf den Update-Kanal; `FlashingBoundaryTest` wacht darüber.
- **Kein Zugriff auf Evens privaten Store-Server** (siehe [05 §7](05_EvenHub-Apps.md#7-apps-installieren-und-woher-sie-kommen-dürfen)).
- Die Uhr bleibt das einzige Gerät mit Bluetooth zur Brille. Handy und Rechner sprechen nur mit der Uhr.
- Jede neue Schnittstelle bekommt Tests ohne Hardware: simulierte Brille, Fakes, Snapshot-Bilder.
- Nichts ist auf echter Hardware erprobt, bis es jemand dort getestet hat; so steht es auch in der Doku.
