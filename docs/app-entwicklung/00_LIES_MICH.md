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
3. Dieselbe App-Schnittstelle gibt es in zwei **Laufzeiten**:
   - **auf der Uhr**, in Kotlin, für kleine Apps, die immer gehen sollen;
   - **auf einem Rechner** (PC, Server oder Handy), in TypeScript, der über **WLAN oder LTE**
     mit der Uhr spricht – für alles, was Rechenleistung, Internet-Dienste oder viel Speicher braucht.
4. Apps aus Evens **Even Hub** (Web-Apps mit Evens SDK) laufen über einen **Adapter auf dem
   Rechner**: Ein echter Browser führt sie aus, der Adapter übersetzt ihre Anzeige und Ereignisse.
5. Eine **Web-Seite auf dem Rechner** („App-Verwaltung“) installiert, startet und entfernt Apps
   und koppelt die Uhr.

```
            Brille (Faceclaw/35)
                  ▲  Bluetooth LE: Pixel, Text, Display-Listen   ▼ Bügel, Ring, IMU, Mikrofon
                  │
          ┌───────┴────────────────────────────────────────┐
          │ Uhr: G2 Watch                                    │
          │  App-Host ── Seiten zeichnen, Fokus, Maus        │
          │   ├─ Uhr-Apps (Kotlin, im APK)                   │
          │   └─ Rechner-Client (WebSocket) ─────────────────┼──┐  WLAN / LTE
          └──────────────────────────────────────────────────┘  │  g2-remote@1
                                                                 ▼
          ┌──────────────────────────────────────────────────────────┐
          │ Rechner: g2-host (Node/TypeScript, PC · Server · Handy)   │
          │  ├─ Rechner-Apps (TypeScript)                              │
          │  ├─ EvenHub-Adapter (Chromium + SDK-Brücke)                │
          │  └─ App-Verwaltung (Web-Seite, Handy-tauglich)             │
          └──────────────────────────────────────────────────────────┘
```

## Wo läuft meine App? – Entscheidung

| Die App … | Laufzeit | Kapitel |
|---|---|---|
| zeigt Werte, Listen, Knöpfe; braucht höchstens kurze Web-Abfragen; soll ohne Rechner gehen | **Uhr-App** (Kotlin) | [03](03_Uhr-Apps.md) |
| rechnet viel (KI, Spracherkennung, Bilder), nutzt Programme/Dateien des PCs, hält große Daten | **Rechner-App** (TypeScript) | [04](04_Rechner-Apps_und_Protokoll.md) |
| gibt es schon als Even-Hub-App (Web-App mit `@evenrealities/even_hub_sdk`) | **EvenHub-App** über den Adapter | [05](05_EvenHub-Apps.md) |

Faustregel: Alles, was auf der Uhr länger als **50 ms** rechnet, mehr als **20 MB** Speicher
braucht oder dauernd im Netz hängt, gehört auf den Rechner. Die Uhr ist Anzeige und Eingabe, kein
Rechenknecht (Akku).

## Stand – was es schon gibt, was gebaut werden muss

| Teil | Stand | Wo |
|---|---|---|
| Firmware aufspielen, Verbindung, Maus-Desktop auf der Brille | **fertig** (v0.3.0, nicht auf Hardware erprobt) | `app/`, [FIRMWARE.md](../FIRMWARE.md) |
| G2 Baukasten (Seiten entwerfen) | **fertig**, braucht kleine Erweiterungen (M0) | `designer/` |
| App-Modell, Seitenformat, Ereignisse | **spezifiziert** hier | [02](02_App-Modell.md) |
| App-Host auf der Uhr, Uhr-Apps | **zu bauen** (M1) | [03](03_Uhr-Apps.md) |
| Protokoll `g2-remote@1`, Rechner-Host `g2-host`, Rechner-Apps | **zu bauen** (M2) | [04](04_Rechner-Apps_und_Protokoll.md) |
| App-Verwaltung (Web) | **zu bauen** (M3) | [06](06_App-Verwaltung.md) |
| EvenHub-Adapter | **zu bauen** (M4) | [05](05_EvenHub-Apps.md) |
| Mikrofon, IMU, Kompass zu den Apps | **zu bauen** (M5) | [07](07_Umsetzungsplan.md) |

Die Reihenfolge und die Abnahmekriterien stehen im [Umsetzungsplan](07_Umsetzungsplan.md), fertige
Aufträge für neue Chats in [08_Prompt_fuer_neuen_Chat.md](08_Prompt_fuer_neuen_Chat.md).

## Inhalt

| Datei | Worum es geht |
|---|---|
| [01_Plattform_und_Grenzen.md](01_Plattform_und_Grenzen.md) | Brille, Uhr, Rechner, Netz: Zahlen und Grenzen, die jede App kennen muss |
| [02_App-Modell.md](02_App-Modell.md) | Manifest, Lebenszyklus, Seiten und Bausteine, Ereignisse, Eingabe, Berechtigungen, Gestaltungsregeln |
| [03_Uhr-Apps.md](03_Uhr-Apps.md) | Kotlin-Schnittstelle, App-Host auf der Uhr, Beispiel, Tests |
| [04_Rechner-Apps_und_Protokoll.md](04_Rechner-Apps_und_Protokoll.md) | `g2-host`, TypeScript-Schnittstelle, das Protokoll `g2-remote@1` Nachricht für Nachricht, Kopplung, Sicherheit |
| [05_EvenHub-Apps.md](05_EvenHub-Apps.md) | Even-Hub-Apps: wie sie gebaut sind, wie der Adapter sie ausführt, woher sie kommen dürfen |
| [06_App-Verwaltung.md](06_App-Verwaltung.md) | Die Web-Seite zum Installieren und Starten |
| [07_Umsetzungsplan.md](07_Umsetzungsplan.md) | Meilensteine M0–M5 mit Abnahme |
| [08_Prompt_fuer_neuen_Chat.md](08_Prompt_fuer_neuen_Chat.md) | Texte zum Einfügen in einen neuen Chat |
| [quellen/](quellen/) | Recherche-Notizen (Faceclaw, offizielle Even-Hub-Doku) mit Datei- und Quellenangaben |

## Harte Regeln für alle Chats

- **Nie Evens Firmware** (`*.bin`) ins Repo oder in Pakete legen; das gilt auch für Schriften,
  die aus ihr gezogen werden.
- **Firmware-Pfad nicht anfassen**, außer der Auftrag verlangt es ausdrücklich. Apps haben keinen
  Zugriff auf den Update-Kanal; `FlashingBoundaryTest` wacht darüber.
- **Kein Zugriff auf Evens privaten Store-Server** (siehe [05 §5](05_EvenHub-Apps.md#5-woher-apps-kommen-dürfen)).
- Die Uhr bleibt das einzige Gerät mit Bluetooth zur Brille. Rechner sprechen nur mit der Uhr.
- Jede neue Schnittstelle bekommt Tests ohne Hardware: simulierte Brille, Fake-Host, Snapshot-Bilder.
- Nichts ist auf echter Hardware erprobt, bis es jemand dort getestet hat; so steht es auch in der Doku.
