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
3. Eigene Apps laufen **auf der Uhr** (Kotlin, fest eingebaut) oder, wenn sie viel rechnen, **auf einem
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

## Stand – was es schon gibt, was gebaut werden muss

| Teil | Stand | Wo |
|---|---|---|
| Firmware aufspielen, Verbindung, Maus-Desktop auf der Brille | **fertig** (v0.3.0, nicht auf Hardware erprobt) | `app/`, [FIRMWARE.md](../FIRMWARE.md) |
| G2 Baukasten (Seiten entwerfen) | **fertig**, braucht kleine Erweiterungen (M0) | `designer/` |
| App-Modell, Seitenformat, Ereignisse | **spezifiziert** hier | [02](02_App-Modell.md) |
| App-Host auf der Uhr, Uhr-Apps | **zu bauen** (M1) | [03](03_Uhr-Apps.md) |
| GeckoView auf der Uhr: Machbarkeitstest | **zu bauen und auf der Uhr zu messen** (M2) | [05 §5](05_EvenHub-Apps.md#5-engine-auf-der-uhr-geckoview) |
| EvenHub-Laufzeit auf der Uhr | **zu bauen** (M3) | [05](05_EvenHub-Apps.md) |
| Handy-App „G2 Handy“ (Ausweich-Engine, Apps installieren) | **zu bauen** (M4) | [05 §6–§7](05_EvenHub-Apps.md#6-engine-auf-dem-handy-g2-handy), [06](06_App-Verwaltung.md) |
| Rechner-Apps: `g2-host`, Protokoll `g2-remote@1`, Web-Verwaltung | **zu bauen** (M5) | [04](04_Rechner-Apps_und_Protokoll.md), [06](06_App-Verwaltung.md) |
| Mikrofon, IMU, Kompass, Standort zu den Apps | **zu bauen** (M6) | [07](07_Umsetzungsplan.md) |
| Web-Browser auf der Brille | **Idee** (M7) | [05 §10](05_EvenHub-Apps.md#10-und-ein-richtiger-browser-auf-der-brille) |

Die Reihenfolge und die Abnahmekriterien stehen im [Umsetzungsplan](07_Umsetzungsplan.md),
der Text für neue Chats in [08_Prompt_fuer_neuen_Chat.md](08_Prompt_fuer_neuen_Chat.md).

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
| [08_Prompt_fuer_neuen_Chat.md](08_Prompt_fuer_neuen_Chat.md) | Der Text zum Einfügen in einen neuen Chat |
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
