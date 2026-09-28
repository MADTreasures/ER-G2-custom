# Eigene Firmware für die Even Realities G2 – Recherche

**Stand:** 25.09.2026
**Art:** reine Recherche. Es wurde nichts geflasht und kein Befehl an eine Brille gesendet. Dieses Dokument enthält bewusst **keine Anleitung zum Aufspielen**. Die Hardware-Stufen des Stufenplans (Abschnitt 12) beginnen erst nach deiner ausdrücklichen Zustimmung.

**Ausgangspunkt:** die Wear-OS-App „G2 Direct“ (Pixel Watch 5). Sie steuert die G2 direkt per BLE [APP]. Auf der Standard-Firmware stößt sie an folgende Grenzen:
- kein frei beweglicher Zeiger
- höchstens 8 Text- und 4 Bildcontainer
- langsame Bild-Updates
- etwa 7 bestätigte Updates pro Sekunde

## Inhalt

1. [Kurzfassung](#1-kurzfassung)
2. [Legende und Methode](#2-legende-und-methode)
3. [Vorteile, Nachteile, Aufwand (Frage 7)](#3-vorteile-nachteile-aufwand-frage-7)
4. [Hardware (Frage 1)](#4-hardware-frage-1)
5. [Firmware-Aufbau (Frage 2)](#5-firmware-aufbau-frage-2)
6. [Update-Weg, Signatur, Secure Boot (Frage 3)](#6-update-weg-signatur-secure-boot-frage-3)
7. [Stand von openCFW und der übrigen Community-Firmware (Frage 4)](#7-stand-von-opencfw-und-der-übrigen-community-firmware-frage-4)
8. [Wiederherstellung (Frage 5)](#8-wiederherstellung-frage-5)
9. [Ungenutzte Möglichkeiten der Standard-Firmware (Frage 6)](#9-ungenutzte-möglichkeiten-der-standard-firmware-frage-6)
10. [Risikobewertung](#10-risikobewertung)
11. [Empfehlung](#11-empfehlung)
12. [Stufenplan](#12-stufenplan)
13. [Zuerst in der Uhr-App ausprobieren](#13-zuerst-in-der-uhr-app-ausprobieren)
14. [Entscheidungen, die bei dir liegen](#14-entscheidungen-die-bei-dir-liegen)
15. [Offene Punkte](#15-offene-punkte)
16. [Quellen](#16-quellen)

---

## 1. Kurzfassung

- **Ja, eigene Firmware geht, und die Community macht es bereits.**
  - Die G2 prüft Firmware-Pakete beim Aufspielen nur mit CRC-Prüfsummen. Eine Signaturprüfung wurde in keiner Quelle gefunden.
  - Gepatchte Firmware läuft nachweislich auf echten Brillen:
    - g2flash/Faceclaw spätestens seit Juli 2026
    - SybilSight CFW 2.2.6.11
    - ein Thai-Schrift-Patch
  - Auch Downgrades sind möglich *(belegt)*.
- **„Eigene Firmware“ heißt in der Praxis: Patches auf der Original-Firmware.**
  - Alle funktionierenden Projekte hängen eigenen C-Code an die Hauptanwendung an und biegen wenige Sprünge um. Bei g2flash sind das 69 764 B Code plus 37 Stellen mit zusammen 142 B.
  - Ein Neubau aus Quellcode (openCFW) ist auch nach Monaten Arbeit auf Hardware nicht lauffähig nachgewiesen.
  - Die Firmware von Audio-Chip, BLE-Controller und Touch-Controller ist proprietär.
- **Deine Wunschliste ist in der Community-Firmware g2flash großteils schon umgesetzt:**
  - Bildspeicher 640 × 480 mit 16 Graustufen
  - Display-Listen mit Rechteck-Kopien (also Sprites)
  - Schriften und Bilder als Ressourcen im RAM
  - animierbare Koordinaten
  - privater Nachrichtenkanal bis 64 KiB
  - 2M PHY und 7,5-ms-Verbindungsintervall: etwa 41 KiB/s statt etwa 9 KB/s
  - Langdruck-Gesten, Kompass, Umgebungslicht, Helligkeitssteuerung
- **Das Risiko ist real und nicht vollständig beherrschbar.**
  - Ein Bügel nimmt keine Updates mehr an, wenn eine Firmware beim Start abstürzt, über das MRAM-Ende schreibt oder den Bootloader beschädigt.
  - Für einen solchen Bügel gibt es **keinen nachgewiesenen Rettungsweg**, weder per BLE noch über Etui oder USB. Übrig bleiben ein SWD-Debugger (Gehäuse öffnen, Anschlusspunkte unbekannt) oder der Herstellerservice.
  - Ein Fall ist dokumentiert: Bei openCFW meldete sich ein Bügel nach dem Flashen nicht mehr (Bericht vom 23.08.2026). Das Projekt führte das später auf ein angestoßenes Etui zurück. Eine Wiederherstellung ist nicht dokumentiert.
- **Garantie und Nutzungsbedingungen:**
  - Die Garantie schließt Schäden durch nicht autorisierte Software aus.
  - Die Nutzungsbedingungen untersagen Reverse Engineering *(belegt)*.
- **Auch ohne Firmware-Änderung ist noch Luft:**
  - Möglich sind Bildkompression (RLE/LZ4, seit Firmware 2.2.6.10), schlankere Zeiger-Updates (1 statt 2–3 BLE-Pakete), eine Pipeline-Messung, die Klärung der Z-Reihenfolge, ein Bild-Zeiger aus Kacheln und Kopfneigung per IMU.
  - Mit der Standard-Firmware **nicht** möglich: Container ohne Neuaufbau verschieben, 2M PHY, ein Verbindungsintervall unter 15 ms.
- **Empfehlung: eigene Firmware jetzt nein, später ja.**
  - Später dann als Nutzung der bestehenden g2flash-Firmware, nicht als Eigenbau (Abschnitt 11).
  - Zuerst die Möglichkeiten der Standard-Firmware in der Uhr-App ausschöpfen und messen. Parallel ohne Brille vorbereiten.
  - Auf die Brille erst nach deiner Entscheidung zu den Punkten in Abschnitt 14.
  - Eigene Patches nur auf einem Gerät, dessen Verlust du verschmerzen kannst.

---

## 2. Legende und Methode

| Kennzeichnung | Bedeutung |
|---|---|
| **belegt** | steht ausdrücklich in einer Primärquelle (Hersteller, Datenblatt, FCC) oder ist im Code bzw. in den Protokollen eines Projekts dokumentiert, das es auf echter Hardware einsetzt |
| **wahrscheinlich** | mehrere unabhängige Indizien oder eine glaubwürdige Sekundärquelle, aber nicht direkt gezeigt |
| **Vermutung** | eigene Schlussfolgerung, Einzelaussage oder nicht nachprüfbar |

- **Quellenangaben:** Kürzel plus Datei und Zeile, z. B. [G2F `g2flash.py`:65–70]. Das Verzeichnis mit Links und den geprüften Commit-Ständen steht in [Abschnitt 16](#16-quellen).
- **Prüfung:** Die tragenden Aussagen wurden am Original nachgelesen, nicht nur aus Zusammenfassungen übernommen.
  - Community-Projekte beschreiben ihre eigenen Ergebnisse. „Belegt“ heißt dort „vom Projekt dokumentiert“, nicht „unabhängig nachgeprüft“.
- **Nicht erreichbar waren:** Reddit, Discord und die FCC-Originalseiten (Ersatz: der Spiegel fccid.co).
- **Kein Teardown:** Es gibt keinen öffentlichen Teardown der G2 und keine öffentlichen FCC-Innenfotos. Alle Chip-Angaben stammen aus der Analyse der Firmware.
- **Firmware-Versionen im Text:** 2.2.6.10 (Juli 2026), 2.2.9.22, 2.2.10.10 und 2.3.0.24 (aktuell, Mitte September 2026).
  - Welche Version auf deiner Brille läuft, ist in `ERKENNTNISSE.md` noch nicht notiert.

---

## 3. Vorteile, Nachteile, Aufwand (Frage 7)

### 3.1 Was eine eigene Firmware bringen würde

„Custom-Firmware“ meint hier den Stand von g2flash im September 2026. Die Aufwandsangaben beziehen sich auf deine Uhr-App.

| Ziel | Standard-Firmware (inkl. ungenutzter Möglichkeiten) | Mit Custom-Firmware | Nutzen für Uhr + Zeiger | Aufwand | Machbarkeit |
|---|---|---|---|---|---|
| **Frei beweglicher Zeiger, Sprites** | Zeichen in 4 transparenten Textebenen, Raster 5 × ~6,75 px. Oder eine Bildkachel: pixelgenau, aber vermutlich ≥ ~105 ms je Update, und die Z-Reihenfolge ist ungeklärt. | Sprite als Ressource plus Rechteck-Kopie in einer Display-Liste, pixelgenau. Koordinaten lassen sich sogar auf der Brille animieren. | **sehr hoch** (Kernziel) | mittel: Die Uhr-App muss das CFW-Protokoll sprechen (Vermutung: einige Wochen) | belegt (Faceclaw nutzt es). Die neueste Stufe mit Display-Listen ist noch unveröffentlicht („0.7.3 – Future“). |
| **Direkter Bildspeicher, eigenes Zeichenprotokoll** | nein. Nur Container-Seiten (CREATE/REBUILD), Bilder höchstens 288 × 144. | Bildspeicher 640 × 480 × 4 bit (153 600 B) plus Kompositionspuffer. Zeichenbefehle: Rechteck-Kopie, abgerundete Rechtecke, Text, Bild, Farb-LUT, verschachtelte Listen. Privater Kanal SID 0xF0, Nachrichten bis 65 535 B mit Einzelbestätigung. | **sehr hoch** | wie oben | belegt |
| **Update-Rate und Latenz** | Verbindungsintervall 15–30 ms, nach 60 s Ruhe langsamer. Kein 2M PHY. Text-Bestätigung Ø 141 ms. Seriell ~7 Updates/s, mit Pipelining vermutlich 12–20/s. Ein unkomprimiertes Bild 288 × 144 braucht bis ~2,4 s. | 2M PHY + 7,5 ms: ~41 KiB/s gemessen (FW 2.2.9). „30 fps Vollbild-Animation“ (Flappy-Demo). Bestätigung p99 63 ms. | **hoch** (flüssiger Zeiger) | gering: in g2flash enthalten; die Uhr muss 2M PHY anfordern | belegt für 2.2.9, für 2.3.0 laut Projekt „needs hardware validation“. Mehrverbrauch geschätzt ~5 % Akku pro Tag. |
| **Keine Container-Grenzen** | 12 Container (8 Text/Liste, 4 Bild), Text ≤ 1000/2000 Zeichen | Display-Listen bis 4096 Operationen, Schachtelungstiefe 8. Ressourcen-Cache 192 KiB (512 IDs, je ≤ 64 KiB). | hoch | – | belegt |
| **Eigene Schriften und Symbole** | eine fest eingebaute Schrift. Pfeile, Dreiecke, ☞, Rahmen- und Blockzeichen laut Simulator-Liste vorhanden; auf Hardware z. B. `╋` bestätigt. | Schriften als Ressourcen zur Laufzeit hochladbar (96 Glyphen je Schrift). Eine Schrift direkt in die Firmware zu patchen kostet knappes MRAM: Beim Thai-Patch waren es 133 KB, danach blieben 2,4 KB Reserve. | mittel | gering (Ressource) bis hoch (Firmware-Patch) | belegt |
| **Echte Graustufen-Grafik** | 16 Graustufen gibt es schon, aber nur in höchstens 4 Bildcontainern à 288 × 144 | 16 Stufen vollflächig, Farb-LUT, Blending, Dithering | gering–mittel (das Panel kann ohnehin nur 16 Stufen Grün) | – | belegt |
| **Größere Anzeigefläche** | 576 × 288 je Auge | Bildspeicher 640 × 480. Sichtbar laut Hersteller 640 × 350. Die zusätzlichen 64 px Breite sind laut Faceclaw optisch nutzbar, die zusätzliche Höhe hängt vom Sitz der Brille ab. | gering–mittel | – | belegt / wahrscheinlich |
| **Verhalten ohne Even-App (Energie, Weckgesten)** | Die Seite endet ~10 s nach dem letzten Heartbeat. Langdruck öffnet einen Beenden-Dialog. Meldungen beim An-/Absetzen kommen wahrscheinlich nur im Onboarding. | Gemeldet werden: Tipp und Langdruck auch bei ausgeschaltetem Bild, die Head-up-Weckgeste sowie An- und Absetzen. Das Weckwort lässt sich übernehmen, zurück geht es per Doppeltipp. | mittel–hoch (Uhr als alleinige Steuerung) | gering (vorhanden) | belegt (Aussage des Projekts) |
| **Sensoren** | Touch: Tipp, Doppeltipp, Wischen. IMU-Beschleunigung x/y/z (nur mit aktiver Seite). Mikrofon: LC3, 16 kHz, mono. | Zusätzlich Ring-Rohereignisse, Langdruck, Kompass-Kurs, Umgebungslicht, Tragestatus, Summer. Mikrofon-Rohdaten der 4 Mikrofone sind noch nicht hardware-validiert. | mittel (Kopf als Zeiger nur grob) | gering–mittel | belegt, teils ungetestet |
| **Akku-Management** | Akkustand lesbar | keine Eingriffe. Die Helligkeit ist selbst regelbar, das spart indirekt. | gering | Eigenes Lade- und Energiemanagement wäre aufwendig und riskant und wird **nicht empfohlen**. | – |
| **Unabhängigkeit vom Hersteller** | Updates können das Protokoll ändern. Beispiel 2.2.9: neuer Verbindungs-Watchdog, geänderte Bild-Bestätigungen. | wird **nicht** erreicht: Jede neue Stock-Version braucht einen Rebase aller Adressen (2.3.0: 23 Dateien, +4744/−542 Zeilen). Ein offizielles Update entfernt die CFW. CFW-Revisionen sind untereinander inkompatibel. | negativ | laufend | belegt |

Belege zur Tabelle:
- Zeiger- und Zeichenfunktionen: [G2F `README.md`:44–75]
- privater Kanal: [G2F `README.md`:136–142]
- 2M PHY: [G2F `README.md`:97–101], [G2F `patches/patch_compress.py`:80]
- Ressourcen-Cache: [G2F `README.md`:56–65]
- Display-Listen-Grenzen: [G2F `patches/display_list.c`:6–7]
- Flappy-Demo und Akku-Schätzung: [FC `CHANGELOG`: Abschnitt 0.7.0]
- Bestätigungszeit: [FC `CfwMessageWindow.kt`:8]
- Optik: [FC `notes/apps.txt`:66–76]
- Rebase: [G2F Commit `c1a4b55`]
- Kompatibilität: [G2F `README.md`:156–177]
- Stock-Werte: Abschnitt 9

### 3.2 Was man dafür verliert

| Verlust / Nachteil | Einschätzung | Sicherheit |
|---|---|---|
| **Garantie** | Ausgeschlossen sind Schäden durch „software … not supplied or authorized by Even Realities“ [EVEN-GARANTIE]. Die Community-Projekte schreiben pauschal „voids your warranty“ [G2F `README.md`:18]. Ein durch CFW beschädigter Bügel wäre praktisch nicht gedeckt. | belegt |
| **Nutzungsbedingungen** | untersagen „reverse engineer, decompile, disassemble“ [EVEN-AGB]. Die Rechtsfolgen sind nicht Teil dieser Recherche. | belegt |
| **Offizielle Updates** | Ein Update über die Even-App entfernt die CFW vollständig. Wer die CFW behalten will, muss Updates auslassen, bis ein Rebase vorliegt [G2F `README.md`:170–177]. | belegt |
| **Even-App, EvenHub-Apps** | Die CFW soll kompatibel bleiben, aber „this isn't tested“. EvenHub-Apps sind laut Projekt „especially likely to be broken“ [G2F `README.md`:156–162]. | belegt (Aussage des Projekts) |
| **KI, Übersetzung, Benachrichtigungen** | bleiben bei Patch-Firmware grundsätzlich erhalten, weil der Original-Code weiterläuft. Patch-Fehler können sie aber treffen: SybilSight CFW 2.2.6.11 stürzte bei „Hey Even“ ab [OCFW `g2/components/apollo_main/evenai_thumb/README.md`:3–31]. | belegt |
| **Stabilität** | Jeder Patch greift in fremden Binärcode ein. Fehler zeigen sich teils erst in seltenen Pfaden. | wahrscheinlich |
| **Akkulaufzeit** | Das 7,5-ms-Intervall kostet geschätzt ~5 % pro Tag mehr [FC `CHANGELOG`: 0.7.0]. | Vermutung (Schätzung des Projekts aus Datenblättern) |
| **Pflege** | Die Uhr-App müsste eine bestimmte CFW-Revision voraussetzen. Faceclaw verlangt genau Revision 33 [FC `app/g2/firmware-compat.ts`:29]. | belegt |

### 3.3 Was das für deinen Anwendungsfall heißt

- **Mit der Standard-Firmware bestenfalls:**
  - entweder ein Zeiger im 5-px-Raster mit vermutlich 12–20 Bewegungen/s, sofern Pipelining hält,
  - oder ein pixelgenauer Bild-Zeiger mit vermutlich höchstens ~9 Bewegungen/s.
  - Über Bildern ist der Zeiger je nach Z-Reihenfolge unsichtbar.
- **Mit der g2flash-Custom-Firmware:**
  - pixelgenauer Zeiger mit bis zu ~30 Bildern/s
  - Glättung auf der Brille möglich
  - beliebig viele Elemente
  - Die Uhr bleibt Rechner, die Brille reines Display, also genau dein Ziel.
- **Eine eigene Firmware über g2flash hinaus** bringt für diesen Anwendungsfall kaum zusätzlichen Nutzen. Mehr kämen vor allem Pflegeaufwand und Risiko hinzu.

---

## 4. Hardware (Frage 1)

| Baugruppe | Befund | Quelle | Sicherheit |
|---|---|---|---|
| **Prozessor (je Bügel)** | Ambiq **Apollo510B**, Arm Cortex-M55, 4 MiB internes MRAM | [OCFW `g2/manifests/g2-2.2.6.10.json`:239], [G2F `g2flash.py`:71–73], [ED] („Apollo 510“) | wahrscheinlich (Firmware-Analyse und Fachpresse, kein Foto) |
| **BLE-Controller** | EM Microelectronic **EM9305**: So heißt die Firmware-Komponente `ble_em9305.bin`; ARC-Kern, QP/C, Bluetooth 5.4. Laut Datenblatt hat der Apollo510B ein integriertes „Bluetooth Low Energy 5.4 Controller Subsystem“ mit eigenem 512-kB-Flash. Ob der EM9305 als eigener Chip oder im Gehäuse integriert verbaut ist, ist offen. | [OCFW `g2/docs/memory-map.md`:1792, 1861], [AMBIQ-510B], [EVEN-BLOG1] (BT 5.4) | wahrscheinlich |
| **Audio-/Sprach-Chip** | NationalChip **GX8002(B)** mit NPU und Schlüsselwort-Modell (vermutlich für „Hey Even“), angebunden per UART und I²S | [OCFW `g2/docs/functional-capability-ledger.md`:329–332] | wahrscheinlich |
| **Touch-Controller** | Infineon **PSoC 4000T** (CY8C4046FNI, Cortex-M0+) | [OCFW `g2/docs/research/g2-touch-identity-recovery.md`:14–15] | wahrscheinlich |
| **IMU / Kompass** | Treiber für TDK **ICM-45608**. Die Datensätze enthalten Beschleunigung, Gyroskop und Magnetometer. Hersteller: IMU „by adding a geomagnetic sensor (compass)“. | [OCFW `g2/docs/research/g2-imu-icm45608-recovery.md`:11, 43–44], [EVEN-BLOG2] | wahrscheinlich; Kompass belegt |
| **Umgebungslicht** | TI **OPT3007** laut openCFW (eigene Registerinitialisierung). g2flash nennt dagegen „TI OPT3001 on the master temple“. | [OCFW `functional-capability-ledger.md`:300–301], [G2F `README.md`:116] | wahrscheinlich (Typ widersprüchlich) |
| **Externer Flash** | Macronix **MX25U25643G**, 32 MiB, MSPI mit 96 MHz. Darauf littlefs, FlashDB und die Schriften. | [OCFW `functional-capability-ledger.md`:286–287], [THAI `docs/research.md`:16–18] | wahrscheinlich |
| **Display** | Offiziell: **Micro-LED, grün, 640 × 350**, 60 Hz, 1200 nits, 27,5°. Intern: Panel mit **640 × 480** Pixeln à 4 bit, zwei Paneltypen (JBD4010 oder „A6N-G“) per MSPI. Die EvenHub-Fläche 576 × 288 wird mit Versatz hineinkopiert. | [EVEN-SPEC], [OCFW `g2/docs/research/g2-uled-jbd4010-recovery.md`:101], [OCFW `g2/docs/source-coverage.md`:7555] | belegt (offiziell) / wahrscheinlich (intern) |
| **Mikrofone** | 4 Stück, je Bügel ein vorderes und ein hinteres | [EVEN-BLOG2], [G2F `README.md`:78–80] | belegt |
| **Energie** | Brille 192 mAh, Etui 2000 mAh. Zwei Board-Varianten: Nordic nPM1300 oder TI BQ25180 + BQ27427. | [EVEN-SPEC], [OCFW `g2/docs/research/g2-s200-board-config-recovery.md`:40] | belegt / wahrscheinlich |
| **Weiteres** | Piezo-Summer (PWM), Pogo-Kontakte mit Magnet zum Laden | [G2F `README.md`:110], [EVEN-BLOG2] | belegt |
| **Ladeetui** | STM32G0 (Firmware „B200“, Version 1.2.57), USB über WCH CH340, Umschalter YHM2510 zu den Pogo-Kontakten links/rechts, zwei Flash-Bänke | [WF `README.md`:14–40], [OCFW `g2/docs/research/g2-box-stm32g0-platform-recovery.md`:28] | wahrscheinlich |
| **Funk / Zulassung** | FCC-ID **2BFKR-G2**, erteilt am 23.11.2025, 2402–2480 MHz, 724 µW, zwei Antennen (L/R). Ring: 2BFKR-R1. | [FCC] | belegt (über Spiegel) |
| **Ring R1** | Nordic nRF52840. Updates signiert (Nordic Secure DFU), anders als bei der Brille. | [OCFW `README.md`:11], [WF `README.md`:45] | wahrscheinlich |

**Aufgabenteilung links/rechts**

- **Eigenständige Bügel:** Jeder Bügel hat einen eigenen Prozessor, eine eigene Linse und **dieselbe Firmware**. Beim Start liest er einen Pin fünfmal und erkennt so seine Seite. *(belegt)*
  - Quellen: [OCFW `memory-map.md`:1750], [OCFW `g2/components/apollo_main/core_overlay/EVIDENCE.md`:55–64]
- **BLE-Geräte:** Beide Bügel sind eigene BLE-Geräte mit eigener Verbindung zum Handy bzw. zur Uhr. *(belegt)* [APP `docs/PROTOKOLL.md` §1]
- **Verbindung zwischen den Bügeln:** eine **0,1-mm-Flexleitung (FPC) durch den Rahmen**: „links both sides directly“, und „What used to require three wireless channels now requires two“. Darüber läuft eine UART mit dem TinyFrame-Protokoll.
  - Sicherheit: FPC belegt, UART/TinyFrame wahrscheinlich
  - Quellen: [EVEN-BLOG1], [OCFW `g2/docs/research/g2-uart-sync-recovery.md`:69–74]
- **Master-Rolle:** Eine Seite startet das Synchronisationsmodul als „Master“. Die Indizien sprechen für links, eindeutig belegt ist das nicht. *(Vermutung)* [OCFW `apollo-decomp-02.c`:3071–3078]
- **Kommandokanal und Mikrofon:**
  - Für Clients ist der **rechte Bügel der Kommandokanal**. EvenHub-Befehle gehen nur nach rechts, Bestätigungen kommen von rechts.
  - Das Mikrofon-Audio (LC3) kommt links.
  - *(belegt)* Quellen: [APP], [KIT `ble/audio.ts`:11–13]
- **Ring:** Der Ring verbindet sich mit dem Bügel der dominanten Hand. *(wahrscheinlich)* [OCFW `g2/docs/research/g2-app-ble-central-recovery.md`:70]

---

## 5. Firmware-Aufbau (Frage 2)

- **Betriebssystem:** FreeRTOS 10.5.1 (Cortex-M55-Port ohne TrustZone) mit CMSIS-RTOS2-Schicht *(wahrscheinlich)* [OCFW `g2/docs/upstream-inventory.md`:25–26].
- **Grafik:**
  - **LVGL 9.3** (Hersteller-Fork) mit Ambiqs GPU-Bibliothek NemaGFX, dazu FreeType 2.9.1 *(wahrscheinlich)* [OCFW `upstream-inventory.md`:29–32].
  - Offiziell: „a single LVGL font baked into firmware“ *(belegt)* [EVEN-HUB].
- **Bluetooth:**
  - Host-Stack Arm/Packetcraft **Cordio** (aus AmbiqSuite) auf dem Apollo.
  - Controller EM9305 mit eigener Firmware (QP/C, Synopsys-MetaWare-Compiler).
  - *(wahrscheinlich)* [OCFW `upstream-inventory.md`:1222], [OCFW `memory-map.md`:1793, 1825]
- **Weitere Bausteine:** littlefs, FlashDB (Einstellungen), nanopb (Protobuf), TinyFrame (Bügel-Verbindung), LZ4 (nur Entpacken), EasyLogger und CmBacktrace. Compiler: IAR (Build-Pfad `D:\01_workspace\s200_ap510b_iar_git\`). *(wahrscheinlich)* [OCFW `upstream-inventory.md`], [OCFW `g2/docs/research/tinyframe-wire-format-recovery-audit.md`:22]
- **Fehlerbehandlung:** Ein HardFault schreibt `/log/hardfault.txt` und wartet dann in einer Endlosschleife, bis der externe Watchdog den Bügel neu startet. *(belegt, Dekompilat)* [OCFW `evenai_thumb/README.md`:24–27]

**Speicheraufteilung des MRAM (Apollo510B, 4 MiB)**

| Bereich | Inhalt | per OTA änderbar? |
|---|---|---|
| `0x00400000–0x0040FFFF` | Ambiq Secure Bootloader (SBL), geschützt | nein |
| `0x00410000–0x00437FFF` | Even-Bootloader (`ota/s200_bootloader.bin`, ~149 KB) | ja, Teil jedes Vollpakets |
| `0x00438000` bis ~`0x007CDA60` (bei 2.3.0.24) | Hauptanwendung (`ota/s200_firmware_ota.bin`, ~3,5–3,8 MB) | ja |
| bis `0x007FE000` | frei bzw. Kopplungs- und Einstellungsdaten | nein, nicht Teil des Pakets |
| `0x007FE000` (letzte 8 KB) | OTA-Flag | – |

Quellen: [OCFW `memory-map.md`:27, 308–313, 1199], [G2F `g2flash.py`:64–76], [WF `README.md`:123–147]. *(belegt)*

- **Nur ein Slot:**
  - Es gibt weder eine A/B-Aufteilung noch ein automatisches Zurückfallen auf eine ältere Version: „no last-known-good application slot or boot-attempt rollback has been found“ [WF `README.md`:134–136], [OCFW `memory-map.md`:1747–1748]. *(belegt)*
  - Die Anwendung wird zuerst vollständig als Datei zwischengespeichert. Erst nach bestandener CRC setzt sie das Update-Flag und startet neu. Der Even-Bootloader schreibt das Image dann ins MRAM [WF `README.md`:166–169]. *(belegt)*
- **Firmware der Nebenchips:** Touch-, Audio- und Etui-Chip haben eigene Firmware im selben Paket. Sie wird nur bei einem Versionswechsel neu geschrieben. *(wahrscheinlich)*
  - Belege:
    - Touch: `isTouchNeedUpgrade` [OCFW `g2/docs/research/g2-touch-i2c-protocol-recovery.md`:126]
    - Audio-Chip: „same-version skip“ [OCFW `g2/docs/progress.md`:8052]
    - Etui: Versionsvergleich [OCFW `g2/docs/research/g2-box-stm32g0-platform-recovery.md`:176]

---

## 6. Update-Weg, Signatur, Secure Boot (Frage 3)

### 6.1 Wie die Even-App aktualisiert

g2flash bildet den Ablauf der offiziellen App nach, laut eigener Aussage „validated byte-for-byte against a real flash capture“ [G2F `README.md`:339–341]. *(belegt)*

1. **Verbindung und Anmeldung:** Die App verbindet sich mit jedem Bügel einzeln und meldet sich über den Steuerkanal an (Dienst `…5450`, Charakteristiken `…5401`/`…5402`). Ohne Anmeldung trennt Firmware ≥ 2.2.9 die Verbindung nach ~30 s [G2F `g2flash.py`:27–30].
2. **Übertragung:** Sie läuft über den Firmware-Datendienst `…e1001` (Schreiben `…e0001`, Benachrichtigung `…e0002`). Die Rahmen sind dieselben `AA 21`-Rahmen mit CRC-16 [G2F `g2flash.py`:17–26, 52–58].
3. **Ablauf je Komponente:**
   - Zuerst BEGIN, dann für jede Komponente FILE_CHECK (128-B-Kopf), dann 4-KB-Blöcke (je 18 Pakete, jeder Block einzeln bestätigt), dann END mit CRC32C-Prüfung.
   - Statuscodes 0–10, u. a. 7 = CHECK_FAIL und 8 = UPDATING [G2F `g2flash.py`:104–109].
4. **Abschluss:** Nach dem Abschluss starten **beide** Bügel neu und der Bootloader installiert [FC `OtaFlashFlow.kt`:15–16].

- **Offizielle Voraussetzungen:** Die Brille lädt, der Akku ist über 50 %, die Even-App bleibt im Vordergrund [EVEN-UPDATE]. *(belegt)*
- **Dauer:** ~460 ms pro 4-KB-Block bei 15-ms-Intervall. Ein kompletter Bügel braucht ~17 min, mit dem g2flash-Schnellpatch ~5 min [G2F-ISSUE3]. *(wahrscheinlich)*

### 6.2 Paketformat EVENOTA

- **Aufbau:** Magic `EVENOTA\0`, 64-B-Kopf, Inhaltsverzeichnis mit 16 B je Eintrag, je Komponente ein 128-B-Unterkopf mit Größe, CRC und Name *(belegt)*.
  - Quellen: [FC `native/kotlin/shared/src/commonMain/kotlin/com/faceclaw/app/g2protocol/FirmwareImage.kt`:8–11], [OCFW `g2/tools/open_cfw.py`:36–41]
- **Komponenten:** seit 2.2.6 sechs Stück, 2.2.4 hatte fünf.

| Komponente | Ziel | Größe in 2.3.0.24 |
|---|---|---|
| `firmware/codec.bin` | GX8002 | 326 092 B |
| `firmware/ble_em9305.bin` | EM9305 | 211 948 B |
| `firmware/touch.bin` | PSoC 4000T | 34 720 B |
| `firmware/box.bin` | Etui (STM32) | 55 784 B |
| `ota/s200_bootloader.bin` | Even-Bootloader | 149 755 B |
| `ota/s200_firmware_ota.bin` | Hauptanwendung | 3 758 720 B |

Quelle: [THAI `docs/rebases/2.3.0.24.json`:25–62] *(belegt)*.

- **Teilpakete:** Ein Paket, das nur die Hauptanwendung enthält, wird ebenfalls angenommen [G2F-ISSUE3]. *(wahrscheinlich, ein Bericht)*

### 6.3 Signatur, Verschlüsselung, Secure Boot

- **Prüfungen:**
  - CRC-16 je BLE-Paket, CRC32C je Komponente, CRC-32 in der 32-B-Präambel der Hauptanwendung.
  - Beim Etui-Kanal nur additive Summen.
  - Quellen: [G2F `g2flash.py`:25–26, 279], [OCFW `functional-capability-ledger.md`:201–205] *(belegt)*
  - Fehlerhafte CRCs lehnt die Brille mit Status 7 ab. Nach eigenen Änderungen müssen die Prüfsummen deshalb neu berechnet werden [G2F `README.md`:315–318].
- **Signatur:** In keiner Quelle gefunden.
  - Das Feld `file_sign` in den Cloud-Metadaten ist leer (`null`) [THAI `docs/rebases/2.2.9.22.json`:14].
  - openCFW nennt seine eigenen Pakete „unsigned packages“ [OCFW `g2/docs/progress.md`:4268].
  - Der Inhalt ist Klartext, also disassemblierbar.
  - Einstufung: *belegt (nicht gefunden)*. *Wahrscheinlich* prüft die Brille keine Signatur, sonst würden gepatchte Images nicht starten.
- **Secure Boot:**
  - Der Ambiq-SBL kann laut Datenblatt Authentifizierung, Entschlüsselung und Integritätsprüfung durchsetzen, und zwar „upon installation and boot/reset“. Die Boot-ROM startet den Secure-Boot-Ablauf aber nur „if enabled“ [AMBIQ-DS §5.5].
  - Bei der G2 wird die Anwendung offensichtlich nicht authentifiziert.
  - Ob andere Teile aktiv sind (Lebenszyklus-Zustand, SWD-Sperre), ist unbekannt.
  - Den SBL selbst zu ersetzen würde Ambiqs Secure-Boot-Schlüssel erfordern [OCFW `functional-capability-ledger.md`:198].
  - Einstufung: Datenblatt *belegt*, Übertragung auf die G2 *wahrscheinlich*.
- **Downgrade:** möglich *(belegt)*.
  - openCFW hat beide Bügel per BLE auf 2.2.6.10 zurückgesetzt [OCFW `g2/docs/hardware-validation-2026-08-23.md`:26].
  - Faceclaw installiert von 2.2.10 und 2.3 aus ältere Basen [FC `CHANGELOG`: 0.7.0, 0.7.1].
- **Eigene Images einspielen:** **Ja**, spätestens seit Juli 2026 von mehreren Projekten praktiziert *(belegt)*.
  - Bedingungen: Längen- und CRC-Felder korrekt setzen und die MRAM-Obergrenze einhalten [G2F `g2flash.py`:64–76].
- **Reaktion von Even Realities:**
  - Es wurde keine öffentliche Stellungnahme und keine Sperre gefunden.
  - Neuere Versionen haben aber Werkzeuge gebrochen: 2.2.9 mit einem Verbindungs-Watchdog und geänderten Bild-Bestätigungen [G2F Commits `7c6d3c1`, `784846b`].
  - 2.3.0 verspricht „improved … firmware-update reliability“ [THAI `docs/rebases/2.3.0.24.md`:28–29].
- **Ausblick (Vermutung):** Eine künftige Version könnte eine Signaturprüfung einführen, etwa im Even-Bootloader oder über den SBL. Ob dann Downgrades noch gehen, ist offen.

---

## 7. Stand von openCFW und der übrigen Community-Firmware (Frage 4)

| Projekt | Ansatz | Was wirklich funktioniert | Erfahrungen auf Hardware |
|---|---|---|---|
| **openCFW** (kalanihelekunihi) | Byte-genaue Rekonstruktion des Originals, dann schrittweiser Ersatz durch Quellcode. Für den Ring R1: Neuimplementierung. | Der Referenz-Build ist byte-identisch zum Original (per Hash geprüft). Das „source“-Profil ersetzt 609 Funktionen (~119 KB) durch kompilierten Code. Das „transparent“-Profil „is not known to run“. `source_complete=false`, `release_authorized=false`, „recorded hardware operation list is empty“. | Laut Bericht vom 23.08.2026 ging ein source-Build per BLE auf den **rechten Bügel**: alle 6 Komponenten, 1099 Blöcke, fehlerfrei bestätigt. **Danach meldete sich der Bügel nicht mehr** (kein Advertising, keine UART-Antwort), Rettungsversuche per SBL-HELLO scheiterten. Später wurde das einem angestoßenen Etui zugeschrieben, ohne Beleg und ohne dokumentierte Wiederherstellung. Seitdem sind Hardware-Tests „deliberately deferred“. |
| **g2flash + Faceclaw** (jimrandomh) | Stock-Image plus 37 kleine Patches plus angehängter C-Code (positionsunabhängig, mit clang kompiliert) | Android-App im Alltagsgebrauch (Releases 0.7.x), Installation und Deinstallation der CFW direkt aus der App. iOS-Beta: „Custom firmware flashing: Tested end to end and working“. Rebases 2.2.4 → 2.2.6 → 2.2.9 → 2.3.0. | Keine Brick-Meldungen in den Issues. Bekannte Hardware-Fehler (behoben): dunkles Display nach Helligkeits-Revision 31, Even-AI-Absturz. Die neuesten Funktionen (Display-Listen, Animation, Tiefe) stehen unter „0.7.3 – Future“. |
| **SybilSight CFW** (AM-Guru / kalanihelekunihi) | auf Basis von g2flash, Web-Flasher | 2.2.6.11 wurde ausgeliefert, mit einem HardFault bei „Hey Even“: ein falsch gesetztes Thumb-Bit führte zum Watchdog-Neustart. | Der öffentliche Flasher hat die CFW-Unterstützung am 21.08.2026 entfernt („Remove custom firmware support“), **ohne Begründung**. |
| **even-g2-thai** (rayriffy) | Thai-Schrift als Patch (5,3 KB Code + 133 KB Schrift), Flashen nur über das Etui | Emulationstests mit Unicorn. Die aktuelle Version ist „physisch ungetestet“. | **Zwei Bügel** wurden mit fehlerhaften Builds bespielt und stürzten beim Öffnen des Dashboards ab (zur Laufzeit, nicht beim Start). Die Fehler wurden danach in der Emulation nachgestellt. |
| **Glassly** (tetramo-labs/g2flash) | Fork von g2flash | – | – |

Quellen zur Tabelle:
- openCFW: [OCFW `g2/README.md`:61–72], [OCFW `hardware-validation-2026-08-23.md`:5–13, 105–147], [OCFW `hardware-validation-policy.md`], [OCFW `g2/docs/transparent-source.md`:215]
- g2flash und Faceclaw: [G2F `README.md`], [FC `CHANGELOG`]
- SybilSight: [SYB-COMMIT], [OCFW `evenai_thumb/README.md`]
- even-g2-thai: [THAI `docs/rebases/2.2.9.22.md`:81–103], [THAI `docs/rebases/2.3.0.24.md`:4–5]

**Berichte über lahmgelegte („gebrickte“) Brillen**

- **Dokumentiert, aber ungeklärt:** der rechte openCFW-Bügel.
  - Die Unterlagen widersprechen sich: Das Protokoll sagt „completed all six components“, die spätere Korrektur spricht von einer Unterbrechung „midway“.
  - Nirgends steht, dass der Bügel wieder läuft oder wie er gerettet wurde. [OCFW `hardware-validation-2026-08-23.md`:5–7, 112–123, 147]
- **Vorübergehend:** Bei g2-kit wurde ein Brillenpaar „non-terminally bricked“, weil ein unbekanntes `dev_config`-Feld geschrieben wurde. Behoben durch Neustart und erneutes Koppeln. [KIT `ble/docs/gotchas.md`:136–140]
- **Laufzeitabstürze, per OTA behebbar:** Thai (Dashboard), SybilSight 2.2.6.11 (Even AI), g2flash-Revision 31 (Display dunkel).
- **Nicht gefunden:** Bricks durch offizielle Updates, Bricks mit g2flash/Faceclaw.
  - Reddit und Discord waren nicht einsehbar, die Dunkelziffer ist also unbekannt.

---

## 8. Wiederherstellung (Frage 5)

| Zustand des Bügels | Rückweg | Quelle | Sicherheit |
|---|---|---|---|
| **Firmware läuft** (auch eine CFW mit Fehlern, solange das BLE-OTA noch funktioniert) | Stock-Image per BLE neu aufspielen: g2flash, Faceclaw „Uninstall firmware“ oder WebFlasher. Alternativ ein offizielles Update über die Even-App, falls eine neuere Version angeboten wird. | [G2F `README.md`:170–177] | belegt |
| **Firmware läuft, aber der BLE-Weg klappt nicht** | USB-C am Etui: Der WebFlasher überträgt die Hauptanwendung über die Pogo-Kontakte („Running-temple recovery through the Case“). Das geht nur, solange die Anwendung im Bügel antwortet („application-alive reinstall path“). | [WF `README.md`:100–121], [THAI `docs/flashing.md`] | wahrscheinlich (vom Projekt auf Hardware erprobt) |
| **Übertragung bricht ab** | harmlos: Der Bügel startet mit der alten Firmware. Die Übertragung wird komplett neu begonnen. | [G2F Commit `7c6d3c1`], [WF `README.md`:166–169] | belegt |
| **Anwendung startet nicht** (Absturz beim Start, MRAM-Überlauf, zerstörter Bootloader) | **Kein nachgewiesener Weg** über BLE, Etui oder USB: „there is no proven retail pogo, BLE, or stock-case path for reflashing an application-dead temple“ | [WF `README.md`:458–491] | belegt |
| Kandidat: **Ambiq-SBL-Update per UART** | Der SBL kann nach einem ungültigen Image oder per Override-Pin ein kabelgebundenes UART-Update annehmen, aber nur, wenn INFO0/INFOC entsprechend provisioniert sind. Ob Even das getan hat, ist unbekannt. Prüfen lässt es sich nur mit einem Debugger-Auszug (SWD). Das Etui kennt kein SBL-HELLO. | [AMBIQ-A4PROV §9], [AMBIQ-QSG], [WF `README.md`:458–491], [OCFW `hardware-validation-2026-08-23.md`:125–180] | Mechanismus belegt, für die G2 offen |
| Kandidat: **SWD/JTAG** | Der Apollo510 hat SWD. Die Anschlusspunkte der G2 sind unbekannt, der Zugang erfordert das Öffnen des Bügels. Ob SWD gesperrt ist, ist unbekannt. | – | Vermutung |
| **Recovery-Modus, Werksreset** | Offiziell gibt es nur den Neustart: 5× schnell auf beide Touchflächen tippen oder den Etui-Strom 3× innerhalb von 7 s trennen. Einen Recovery-Modus oder Werksreset für die G2 dokumentiert Even nicht. | [EVEN-NEUSTART] | belegt |
| **Sicherung vor dem Flashen** | Das installierte MRAM lässt sich nicht auslesen. Ersatz ist das Originalpaket vom Even-CDN (Hash prüfen). Kopplungs-, Kalibrier- und Schlüsseldaten werden nicht angetastet, lassen sich aber auch nicht sichern. | [WF `README.md`:438–456], [OCFW `memory-map.md`:1750–1751] | belegt |

**Besonders heikel: der Even-Bootloader**

- Anders als die Hauptanwendung wird die Bootloader-Komponente nach dem Empfang **direkt ins MRAM kopiert**. Die Erfolgsmeldung kann schon vor dem Kopieren kommen. Der WebFlasher warnt: „Power loss or copy failure can therefore destroy the only Even bootloader despite a successful-looking reply.“ [WF `README.md`:169–173]
- g2flash überträgt bei jedem Flash **alle** Komponenten, also auch den Bootloader [G2F `g2flash.py`:808–811]. Das Risiko ist klein, weil das Zeitfenster kurz ist, aber endgültig.
- Ein Paket nur mit der Hauptanwendung würde dieses Risiko vermeiden [G2F-ISSUE3].

**Fazit:** Der Rückweg ist einfach, **solange die Anwendung startet**. Ein Fehler im Startpfad, ein MRAM-Überlauf oder ein zerstörter Bootloader ist nach heutigem Wissen ohne Debugger oder Service endgültig.

---

## 9. Ungenutzte Möglichkeiten der Standard-Firmware (Frage 6)

### 9.1 Was es nicht gibt

Diese Punkte sind belegt, d. h. sie wurden in Protobuf-Definitionen, Dekompilat und Treibern nicht gefunden:

- **Container verschieben:** Es gibt keinen Befehl, der Container verschiebt oder ihre Eigenschaften ohne REBUILD ändert.
  - Ein Text-Update enthält nur ID, Name, Offset, Länge und Inhalt [KIT `ble/gen/EvenHub_pb.ts`:722–753].
  - Der Stock-Dispatcher (Dekompilat von 2.2.6.10) kennt nur die Befehle 0, 3, 5, 7, 9, 12, 15, 18 und 19 [OCFW `g2/research/corpus/apollo-main/ghidra/decomp/bundles/apollo-decomp-07.c`:4678–5463].
- **Keine Teil-Updates von Bildern:** kein Rechteck, kein Offset.
- **Keine Listenauswahl setzen:** Es gibt kein Feld dafür. Die Markierung bewegt sich nur per Wischen am Bügel oder am Ring [NOTES `docs/display.md`:355–370].
- **Kein Bildspeicherzugriff:** kein direkter Zugriff, keine Sprites. Offiziell ausgeschlossen: „no arbitrary pixel drawing“ [EVEN-HUB].
- **Kein 2M PHY:** Das Controller-Feature-Bit ist beim Start aus [G2F `patches/patch_compress.py`:82–83].
- **Verbindungsintervall:** Das schnelle Profil liegt bei 15–30 ms, nach 60 s ohne Verkehr kommt der „slow mode“ [G2F `patches/patch_compress.py`:80–87], [OCFW `EVIDENCE.md`:3824–3825].
- **Bild-Pipelining:** Ab Firmware 2.2.9 gehen Bestätigungen verloren, wenn **Bild**-Updates parallel laufen (Fenster ≥ 2). Die Firmware merkt sich nur eine offene Bestätigung [G2F Commit `784846b`].

### 9.2 Was es gibt

| Möglichkeit | Stand | Sicherheit |
|---|---|---|
| **Bildkompression** | `CompressMode` 1 = RLE (Paare [Anzahl, Wert]), 2 = LZ4-Block über die ganze BMP-Datei. Verfügbar ab Firmware 2.2.6.10. Die Firmware entpackt in einen Puffer der Größe Breite × Höhe. Unbekannte Modi werden still als Rohdaten behandelt, auf der Linse erscheint dann Datenmüll. [G2F `demos/video-bench.ts`:22–31], [G2F `demos/lz4.ts`:4–19], [OCFW `EVIDENCE.md`:673–689, 757–764] | belegt (Dekompilat; die offizielle App nutzt LZ4 seit SDK 0.0.12) |
| **IMU** | Befehl 19, Feld 20 = {1: an/aus, 2: Takt 100–1000}. Daten kommen als Ereignis Typ 8 mit x/y/z (float32, auf g normiert). Nur bei aktiver EvenHub-Seite. Nur Beschleunigung, also Nicken und Neigen, keine Drehung. [MOS `G2.kt`:99, 512–525, 3835] | wahrscheinlich („confirmed via on-device brute-force“) |
| **Z-Reihenfolge** | **Umstritten.** even-g2-notes: „later containers draw on top“, Bilder also über Text [NOTES `docs/display.md`:15], [NOTES `docs/ui-patterns.md`:37]. Faceclaw: „image containers sit below lists and text … matching stock“ [FC `app/apps/evenhub/compositor.ts`:141–143]. SDK ≥ 0.0.12 kennt `zOrderIndex` [NOTES `docs/display.md`:33–39], die Feldnummer im Funkprotokoll ist unbekannt. | widersprüchlich |
| **Listencontainer** | 1–20 Einträge à höchstens 64 Zeichen. Die Firmware zeichnet den Auswahlrahmen selbst und scrollt ihn nativ, aber nur per Geste am Bügel oder Ring. [NOTES `docs/display.md`:355–370] | belegt |
| **Mikrofon** | Befehl 15. LC3-Pakete (205 B ≈ 50 ms, 16 kHz) kommen am linken Bügel an (`…6402`). [KIT `ble/audio.ts`:11–14] | wahrscheinlich |
| **Weitere Ereignisse** | 14 (Seiten-Timeout), 17 (Menü-Start), 11 (private Ereignisse); ab SDK 0.0.14 Langdruck als 9/10 [KIT `ble/gen/EvenHub_pb.ts`:1015–1022, 1122–1139], [FC `app/apps/evenhub/session.ts`:77–79] | wahrscheinlich |
| **Glyphen** | Pfeile ← ↑ → ↓ ↖ ↗ ↘ ↙, Dreiecke ▲ ▶ ▼ ◀ ◤ ◥ ◢ ◣, ◎ ● ○, ☞ ☜ sowie Rahmen- und Blockzeichen. Die Liste stammt aus dem Simulator („Real hardware may differ“). [NOTES `docs/display.md`:114–116, 163–242] | wahrscheinlich |
| **Kacheln** | Sind die Bilddaten kleiner als der Container, wiederholt die Firmware sie [NOTES `docs/display.md`:401]. | wahrscheinlich |

### 9.3 Messwerte zur Einordnung

| Vorgang | Wert | Quelle |
|---|---|---|
| Text-Update direkt (deine App) | Ø 141 ms bis zur Bestätigung | [APP `docs/ERKENNTNISSE.md` §3] |
| Text-Update über das SDK | ~83 ms | [NOTES `docs/performance.md`:16] |
| Bild über das SDK (mit LZ4) | ~104 ms + 3,9 ms/KB. Ein Bild 288 × 144 braucht ≈ 185 ms, also ≤ 9,5 Bilder/s. | [NOTES `docs/performance.md`:14, 90–99] |
| Bild direkt, unkomprimiert | ~400–500 ms je 4-KB-Fragment, 288 × 144 ≈ 2,4 s (gemessen am 14.04.2026) | [KIT `ble/messages.ts`:345–347] |
| REBUILD | ~165 ms, flackert | [NOTES `docs/performance.md`:15] |
| OTA-Block 4 KB | ~460 ms bei 15-ms-Intervall, also ≈ 9 KB/s Nutzdurchsatz | [G2F-ISSUE3] |
| CFW-Kanal (zum Vergleich) | ~41 KiB/s, Bestätigung p99 63 ms | [G2F `patches/patch_compress.py`:80], [FC `CfwMessageWindow.kt`:8] |

Die konkreten Versuche in der Reihenfolge, in der ich sie empfehle, stehen in [Abschnitt 13](#13-zuerst-in-der-uhr-app-ausprobieren).

---

## 10. Risikobewertung

Zwei Fälle werden unterschieden:
- **A:** eine getestete Community-Firmware (g2flash/Faceclaw) installieren und nutzen
- **B:** eigene Firmware-Patches entwickeln

| # | Ereignis | Wahrscheinlichkeit A / B | Folge | Wiederherstellbar? | Gegenmaßnahme |
|---|---|---|---|---|---|
| 1 | Übertragung bricht ab (Funk, Akku, App im Hintergrund) | mittel / mittel | keine: Neustart mit alter Firmware | **ja**, erneut übertragen | laden, Akku > 50 %, App im Vordergrund |
| 2 | Nur ein Bügel umgestellt (Abbruch zwischen links und rechts) | gering / gering | gemischter Stand, CFW-Funktionen fehlen | **ja**, fertig flashen oder zurück zu Stock | – |
| 3 | CFW stürzt im Betrieb ab (seltener Codepfad) | gering–mittel / **hoch** | Watchdog-Neustarts, einzelne Funktionen gestört | **ja**, per BLE, solange die Anwendung startet | Patches nur nach eigenen Nachrichten aktiv; Stock-Pfade nicht umbiegen |
| 4 | Absturz schon beim Start, bevor BLE/OTA läuft | gering / **mittel** | Bügel „tot“ | **nein** (nur SWD oder Service, beides unbewiesen) | nichts am Startpfad ändern; Unicorn-Tests; Zweitgerät; zuerst nur ein Bügel |
| 5 | Image zu groß, MRAM-Überlauf | sehr gering (g2flash prüft) / gering–mittel (mit eigenen Werkzeugen) | „permanent, BLE-unrecoverable bootloop (SWD-only recovery)“ [G2F `g2flash.py`:222–230] | **nein** | Obergrenze `0x7F0000` hart prüfen; bewährte Werkzeuge nutzen |
| 6 | Stromausfall, während der Even-Bootloader ersetzt wird | sehr gering / sehr gering (betrifft auch offizielle Updates) | Bootloader zerstört | **nein** | voll laden; Pakete nur mit Hauptanwendung erwägen |
| 7 | Hardware falsch angesteuert (Panel, Laden, Mikrofon) | gering / mittel | z. B. dauerhaft dunkles Display (belegt bei Revision 31, per Update behoben). Dauerschäden sind nicht berichtet. | meist ja | keine Eingriffe in Treiber oder Register |
| 8 | Unbekannte Stock-Befehle ausprobieren | – / mittel | vorübergehend unbrauchbar („non-terminally bricked“) | ja (Neustart, neu koppeln) | nur dokumentierte Befehle senden |
| 9 | Even schließt die Lücke (Signaturpflicht) oder ändert das Protokoll | Vermutung: mittel innerhalb von 1–2 Jahren | CFW nicht mehr installierbar, eventuell kein Downgrade mehr | – | Stock-Images archivieren; Updates erst nach Prüfung installieren |
| 10 | Garantiefall nach einem CFW-Schaden | – | Reparatur auf eigene Kosten | – | Zweitgerät |

**Gesamturteil**

- **Fall A** (getestete CFW installieren und wieder deinstallieren): Das Risiko eines dauerhaften Schadens ist **gering, aber nicht null**. Der Rückweg ist einfach, solange die Anwendung startet.
- **Fall B** (eigene Patches): Das Risiko ist **deutlich höher**.
  - Jeder Fehler im Startpfad kann einen Bügel dauerhaft lahmlegen, und für diesen Fall ist kein Rettungsweg belegt.
  - Deshalb nur auf einem Gerät, dessen Verlust du verschmerzen kannst.

---

## 11. Empfehlung

**Eigene Firmware: jetzt nein. Später ja, und dann als Nutzung (bei Bedarf kleine Erweiterung) der bestehenden g2flash-Firmware, nicht als Eigenbau.**

Gründe:

1. **g2flash deckt deinen Kernwunsch bereits ab:** freier, pixelgenauer Zeiger, freie Zeichenfläche, schnelle Updates. Ein Eigenbau hieße monatelange Arbeit bei gleichen Risiken, dazu die Pflege über jede Stock-Version.
2. **Ein Neubau aus Quellcode (der openCFW-Weg) ist auf absehbare Zeit unrealistisch.**
   - Proprietär sind: die Firmware von EM9305, GX8002 (samt Sprachmodell) und Touch-Controller sowie die Panel-Kalibrierung.
   - Selbst openCFW hat noch keinen lauffähigen Eigenbau nachgewiesen.
3. **Die Standard-Firmware hat noch ungenutzte Möglichkeiten.** Sie verbessern die Uhr-App ohne Risiko. Gleichzeitig zeigen die Messungen, ob der Unterschied zur CFW den Aufwand überhaupt lohnt.
4. **Ohne belegten Rettungsweg für einen „toten“ Bügel** gehört jede Entwicklung an der Firmware selbst auf ein Zweitgerät.

**„Später“ heißt:** wenn alle drei Bedingungen erfüllt sind:
- (a) Stufe 1 zeigt, dass der Stock-Zeiger nicht reicht.
- (b) Du hast die Punkte in [Abschnitt 14](#14-entscheidungen-die-bei-dir-liegen) entschieden.
- (c) g2flash unterstützt die Firmware-Version deiner Brille als Basis (derzeit 2.3.0.24).

---

## 12. Stufenplan

| Stufe | Inhalt | Risiko | Freigabe durch dich nötig? |
|---|---|---|---|
| **0 – Lesen und Analysieren** | dieses Dokument | keins | erledigt |
| **1 – Stock-Möglichkeiten in der Uhr-App** | Versuche aus Abschnitt 13; nur dokumentierte Befehle, die keine Einstellungen verändern | sehr gering | für neue Befehle (IMU, Kompression) ja |
| **2 – Offline vorbereiten, ohne Brille** | g2flash lokal bauen und verstehen, CFW-Protokoll im Simulator nachbilden, Unicorn-Emulation | keins | nein |
| **3 – Hardware nur lesend** | Firmware-Version notieren, Stock-Paket archivieren, Kompatibilität prüfen | sehr gering | ja |
| **4 – Getestete Community-CFW aufspielen** | unveränderte Release-Version, danach Rückweg einmal üben | gering–mittel | **ja, ausdrücklich** (inkl. Garantie) |
| **5 – Uhr-App gegen die CFW entwickeln** | Zeiger als Sprite, messen | wie Stufe 4 | ja |
| **6 – Eigene kleine Patches** | nur angehängter Code, nur auf einem Zweitgerät | mittel–hoch | **ja, ausdrücklich** |
| **7 – nicht empfohlen** | Startpfad, Bootloader, BLE-Controller, Nebenchips, Neubau | hoch | – |

**Stufe 1 – Möglichkeiten der Standard-Firmware in der Uhr-App.**
- Die Versuche aus [Abschnitt 13](#13-zuerst-in-der-uhr-app-ausprobieren).
- Ergebnis: Messwerte zu Bewegungen/s, ms je Update und Z-Reihenfolge.
- **Entscheidungskriterium:** Reicht ein Zeiger mit ≥ 15 Bewegungen/s im 5-px-Raster oder ein pixelgenauer Bild-Zeiger mit ≥ 8/s? Dann ist keine CFW nötig.

**Stufe 2 – Offline vorbereiten, ohne Brille.**
- **g2flash lokal bauen**, nur um Paket und Patches zu verstehen. `build_cfw.sh` lädt die Stock-Datei vom Even-CDN und prüft die Hashes. Kein Gerät, kein Flashen.
- **CFW-Protokoll im Simulator nachbilden:** SID 0xF0, Nachrichten 26/27/28, Ressourcen, Lease. Nachgebildet wird es im Simulator `FakeGlasses` der Uhr-App. Das Zeiger-Konzept (Sprite + Rechteck-Kopie + Präsentation) und der Messaufbau werden dort fertig.
- **Lizenz klären:** g2flash und Faceclaw stehen unter GPL-3.0. Wer Code von dort übernimmt, übernimmt auch die GPL-Pflichten. Ein Protokoll nach Dokumentation selbst umzusetzen, ist davon getrennt zu betrachten (keine Rechtsberatung).
- **Emulation:**
  - Eigene Patch-Funktionen lassen sich wie bei even-g2-thai mit **Unicorn** testen: Thumb-Code der Stock-Firmware plus Patch läuft auf dem PC [THAI `tests/test_thai_device_path.py`].
  - Eine Vollsystem-Emulation der G2 (Apollo510 + Panel + EM9305) gibt es nicht *(Vermutung)*. Sie würde einen Hardwaretest ohnehin nicht ersetzen.

**Stufe 3 – Hardware nur lesend.**
- Firmware-Version und Hardware-Revision beider Bügel notieren. Die Uhr-App fragt die Geräteinfo bereits ab.
- Das passende Stock-Paket vom Even-CDN laden und mit Hash archivieren. Das ist die Datei für den Rückweg.
- Prüfen, ob g2flash/Faceclaw genau diese Version als Basis unterstützen.
- **Kein OTA-Befehl senden.** Auch `g2flash --stop-before flash` ist kein reiner Lesetest: Es sendet bereits BEGIN und FILE_CHECK [G2F `g2flash.py`:775, 680–683].

**Stufe 4 – Getestete Community-CFW aufspielen.**
- Nur eine unveränderte, per Hash geprüfte Release-Version, keine eigenen Änderungen.
- Vorbedingungen mindestens wie beim offiziellen Update: Akku > 50 %, stabile Umgebung.
- **Direkt danach den Rückweg einmal üben:** Stock wieder aufspielen, danach die CFW erneut. Erst dann ist gezeigt, dass der Rückweg bei *diesem* Gerät funktioniert.
- Werkzeug, Reihenfolge und genaue Durchführung legen wir erst in dieser Stufe gemeinsam fest. Dieses Dokument ist bewusst keine Anleitung.

**Stufe 5 – Uhr-App gegen die CFW entwickeln.**
- Kein zusätzliches Firmware-Risiko gegenüber Stufe 4.
- Die Uhr verbindet sich direkt (Handy-Bluetooth aus). Sie fordert 2M PHY und hohe Priorität an, mit denselben Android-Standard-APIs wie Faceclaw (`setPreferredPhy`, `requestConnectionPriority`) [FC `FaceclawBleManager.kt`:196–202]. Dann spricht sie den privaten Kanal.
- Zeiger als Sprite umsetzen. Messen: Bewegungen/s, Latenz, Akku.

**Stufe 6 – Eigene kleine Patches, nur auf einem Zweitgerät.**
- Nur im angehängten C-Code, nicht in Stock-Funktionen.
- Nur nach dem Empfang eigener Nachrichten aktiv; keine Start-, Treiber- oder Bootloader-Pfade.
- Vorher Unicorn-Tests. Zuerst ein Bügel. Die Größe bleibt unter der MRAM-Obergrenze.
- Noch besser: die Änderung als Beitrag zu g2flash einreichen, dann wird sie gemeinsam gepflegt.

**Stufe 7 – nicht empfohlen:** Eingriffe in Startpfad, Bootloader, BLE-Controller oder die Firmware von Touch-Controller, Audio-Chip und Etui, sowie ein Neubau aus Quellcode.

---

## 13. Zuerst in der Uhr-App ausprobieren

Sortiert nach Nutzen im Verhältnis zu Aufwand und Risiko. Alles läuft mit der Standard-Firmware.

| Nr. | Maßnahme | Erwarteter Gewinn | Aufwand | Risiko | Sicherheit |
|---|---|---|---|---|---|
| 1 | **Pipeline-Test 1/2/4/8** (schon eingebaut, Menü „Parallel“). Bewegungen/s, Ø-Bestätigungszeit und verlorene Bestätigungen messen. | von ~7/s auf vielleicht 12–20/s | nur testen | gering | Vermutung, Wert offen |
| 2 | **Schlankere Zeiger-Zeilen.** Heute füllt `CursorLayers.renderLines` jede Zeile mit bis zu 111 NBSP (≈ 222 B). Das ergibt ~260 B je Update, also 2 BLE-Pakete, beim großen Fadenkreuz 3. Stattdessen mit U+3000 (20 px, 3 B) plus höchstens 3 NBSP einrücken: ~90 B, 1 Paket. Die konstante Länge hält man über unsichtbare Füllzeichen am Zeilenende. | weniger Funkzeit und weniger Layoutarbeit auf der Brille, vermutlich 10–30 ms je Update | klein | gering. Zu prüfen: Bleibt ein führendes U+3000 erhalten? Siehe auch den „stray tick“ unten. | Rechnung belegt [APP `CursorLayers.kt`:110–121], Gewinn Vermutung |
| 3 | **Verbindungsparameter sichtbar machen.** Das tatsächliche Intervall über das versteckte Callback `onConnectionUpdated` loggen, die PHY über `readPhy`. | klärt, ob 15 oder 30 ms anliegen und wann der Slow-Mode greift | klein | keins | – |
| 4 | **Z-Reihenfolge klären.** Den Zeiger über den Graukeil fahren, dann Reihenfolge und IDs der Container tauschen. Prüfen, ob schwarze Bildpixel Text verdecken. | entscheidet, ob der Zeiger über Bildern sichtbar sein kann | Minuten | keins | – |
| 5 | **Bildkompression:** erst `CompressMode` 1 (RLE, ~20 Zeilen Code), dann 2 (LZ4-Block). Vorher per Geräteinfo prüfen, dass die Firmware ≥ 2.2.6.10 ist. Jeweils die kleinere Variante senden. | große Bilder vermutlich 10–20× schneller (1 statt 6 Fragmente) | klein–mittel | gering | Format belegt, Gewinn Vermutung |
| 6 | **Bild-Zeiger-Prototyp.** 2 × 2 Bildcontainer à 288 × 144 decken 576 × 288 ab. Den Zeiger in die betroffene Kachel zeichnen und komprimiert als ein Fragment senden. Gegen den Text-Zeiger messen. | pixelgenau, beliebige Form, macht Textcontainer frei. Aber vermutlich ≤ ~9 Updates/s. | mittel | gering. Bild-Updates nie parallel senden, kein Text-Update zwischen Bildfragmenten [GW `mentraos/g2/constants.py`:38–43]. | Vermutung |
| 7 | **Kopfneigung per IMU.** Befehl 19, Feld 20 = {1: 1, 2: 100}. Kommt nichts, Feld 22 probieren. Ausschalten mit {1: 0}. | zweiter Eingabekanal (Nicken/Neigen) | klein–mittel | gering | wahrscheinlich |
| 8 | **Unbenutzte Ereignisse mitloggen:** Befehl 14 (Seiten-Timeout), 17, 11, Langdruck 9/10 | robustere Sitzung | klein | keins | wahrscheinlich |
| 9 | **Feldnummer von `zOrderIndex` ermitteln.** Passiv per Android-HCI-Snoop-Log der offiziellen App mit einer EvenHub-App ab SDK 0.0.12 mitschneiden. | Zeiger-Ebenen dauerhaft über Bildern | mittel | keins (nur mitschneiden) | Vermutung |

**Hinweis zum „stray tick“:** MentraOS berichtet, dass die Firmware bei Containern, die nur Leerraum enthalten, an der Inhaltsposition eine zeigerähnliche Marke zeichnet [MOS `G2.kt`:2794–2797]. Deine leeren Zeiger-Ebenen sollte man darauf prüfen.

**Auf der Standard-Firmware nicht empfohlen:**
- Bilddaten über den linken Bügel senden: Der Nutzen ist fraglich, denn die Bestätigungen kommen ohnehin über rechts. Laut deinem Protokoll berichten andere Treiber außerdem von Blockaden oder Neustarts [APP `docs/PROTOKOLL.md` §1].
- Helligkeit setzen: Das ändert eine Nutzereinstellung.
- Kompass über den Navigationsdienst: Das verdrängt die Seite.
- `dev_config`-Felder ausprobieren: Ein vorübergehender Brick ist belegt.
- Bild-Pipelining mit Fenster ≥ 2 auf Firmware ≥ 2.2.9.

---

## 14. Entscheidungen, die bei dir liegen

1. **Garantie und Nutzungsbedingungen:** Bist du für die Stufen 4–6 bereit, die Garantie für CFW-bedingte Schäden zu verlieren und gegen das Reverse-Engineering-Verbot der Nutzungsbedingungen zu handeln? Eine rechtliche Bewertung ist nicht Teil dieser Recherche.
2. **Zweitgerät:** Gibt es eine zweite G2? Oder bist du bereit, im schlimmsten Fall einen Bügel zu verlieren?
   - Ohne Zweitgerät empfehle ich höchstens die Stufen 4 und 5, nicht Stufe 6.
3. **Weg:** Die g2flash-CFW mitnutzen oder ein eigenes Patch-Set?
   - g2flash: schnell und erprobt, aber GPL-3.0, abhängig vom Rhythmus des Maintainers, und die Revisionen sind untereinander inkompatibel.
   - Eigenes Patch-Set: volle Kontrolle, aber viel mehr Aufwand und Risiko.
4. **Offizielle Funktionen:** Wie wichtig sind dir Even-App, EvenHub-Apps, Even AI, Übersetzung und zeitnahe offizielle Updates?
5. **Werkzeug zum Flashen (für später):** Android-Handy mit Faceclaw oder Rechner mit g2flash? Die Uhr selbst ist dafür nicht vorgesehen.

---

## 15. Offene Punkte

- **Chips:** Die Angaben stammen nur aus der Firmware-Analyse, es gibt kein Foto und keinen Teardown. OPT3001 vs. OPT3007 ist widersprüchlich.
- **Master-Rolle:** Welche Seite das Synchronisationsmodul als Master startet, ist offen.
- **Sicherheit künftiger Versionen:**
  - Kommt eine Signaturprüfung?
  - Ist SWD bei Serien-G2 offen und zugänglich?
  - Wie ist INFO0/INFOC provisioniert?
- **Z-Reihenfolge:** Wie liegen Bild und Text übereinander? Welche Feldnummer hat `zOrderIndex`?
- **Update-Zeiten:**
  - Wie lange braucht ein direktes Bild-Update auf aktueller Firmware?
  - Verliert Text-Pipelining auf Firmware ≥ 2.2.9 ebenfalls Bestätigungen?
- **openCFW-Vorfall vom 23.08.2026:** Die Aufklärung fehlt.
- **Deine Brille:** Welche Firmware-Version läuft darauf?
- **SybilSight:** Warum hat das Projekt die CFW-Unterstützung entfernt?

---

## 16. Quellen

### Repositories

Alle Repositories wurden am 25.09.2026 gelesen. Zeilenangaben beziehen sich auf die genannten Commits.

| Kürzel | Quelle | Commit (Datum) | genutzt für |
|---|---|---|---|
| APP | [MADTreasures/ER-G2-Test-2-Claude-5.5-](https://github.com/MADTreasures/ER-G2-Test-2-Claude-5.5-/tree/2299361f320fea11ee23f6cbcd46bc6a3a0d1c04), Branch `claude/zen-newton-15o9rd` | `2299361` (24.09.2026) | Ausgangslage, Protokoll, Messwerte, Zeiger-Code |
| OCFW | [kalanihelekunihi/evenRealities-openCFW](https://github.com/kalanihelekunihi/evenRealities-openCFW/tree/cfd10634a0f0f15d0277d2d009d0c53bf500390e) | `cfd1063` (16.09.2026) | Hardware, Speicheraufteilung, Aufbau, OTA, Hardware-Vorfall |
| G2F | [jimrandomh/g2flash](https://github.com/jimrandomh/g2flash/tree/62703ab3169134ff08673b2983b867462ef97409) | `62703ab` (25.09.2026) | OTA-Protokoll, CFW-Funktionen, Risiken, BLE-Parameter |
| FC | [jimrandomh/faceclaw](https://github.com/jimrandomh/faceclaw/tree/90d60cee10198fe95c2f441f28ec7d4231f09a15) | `90d60ce` (25.09.2026) | CFW im Alltag, Changelog, Wear-OS-Fernbedienung, Z-Reihenfolge |
| WF | [AM-Guru/evenRealities-webflasher](https://github.com/AM-Guru/evenRealities-webflasher/tree/21914f6b3b05762ea5bd2b028311a3efbdd5b0e5) | `21914f6` (15.09.2026) | Update-Modell, Wiederherstellung, Etui, Bootloader-Risiko |
| THAI | [rayriffy/even-g2-thai](https://github.com/rayriffy/even-g2-thai/tree/e012a593cf3be52311c5ef5f081e858eb5df75fc) | `e012a59` (18.09.2026) | Komponenten, Flash über Etui, Abstürze, Emulation |
| KIT | [Commute773/g2-kit-unofficial](https://github.com/Commute773/g2-kit-unofficial/tree/33da3a7ec2b905ca7148acad69f105a0986b3fb7) | `33da3a7` (16.04.2026) | Protobuf-Definitionen, Bildlatenz, Audio, dev_config-Warnung |
| NOTES | [nickustinov/even-g2-notes](https://github.com/nickustinov/even-g2-notes/tree/227c866718fbb68907ef723592c8a4d90630e823) | `227c866` (31.07.2026) | Messwerte, Limits, Glyphen, Z-Reihenfolge |
| MOS | [Mentra-Community/MentraOS](https://github.com/Mentra-Community/MentraOS/tree/62aae4e4a23247b25acd38c039a91eac88fb8f0b), `mobile/modules/bluetooth-sdk/android/src/main/java/com/mentra/bluetoothsdk/sgcs/G2.kt` | `62aae4e` (24.09.2026) | IMU-Befehl, Text ohne Bestätigung, „stray tick“ |
| GW | [gpsnmeajp/men-g2-ble-gateway](https://github.com/gpsnmeajp/men-g2-ble-gateway/tree/ea00f2b56754368946d7cb53ff4f8c43b17745c8) | `ea00f2b` (14.06.2026) | Instabilität von Text nach Bild |
| ATOM | [gpsnmeajp/men-g2-atoms3-hello](https://github.com/gpsnmeajp/men-g2-atoms3-hello/tree/e5b58f6d3d690c2f5a66fd3e53bb972504ebcd0f) | `e5b58f6` (25.05.2026) | Ansteuerung ohne Smartphone (Hintergrund) |
| ISOXI | [i-soxi/even-g2-protocol](https://github.com/i-soxi/even-g2-protocol/tree/b227335f5fbecb7d4ede3748c36e098ef88850fa) | `b227335` (02.01.2026) | Dienst-IDs (Hintergrund) |

### Commits und Issues

| Kürzel | Link | Inhalt |
|---|---|---|
| G2F Commit `7c6d3c1` | [github.com/jimrandomh/g2flash/commit/7c6d3c1](https://github.com/jimrandomh/g2flash/commit/7c6d3c1) | Watchdog in 2.2.9; ein abgebrochener Flash startet mit der alten Firmware |
| G2F Commit `784846b` | [github.com/jimrandomh/g2flash/commit/784846b](https://github.com/jimrandomh/g2flash/commit/784846b) | 2.2.9: nur eine offene Bild-Bestätigung |
| G2F Commit `c1a4b55` | [github.com/jimrandomh/g2flash/commit/c1a4b55](https://github.com/jimrandomh/g2flash/commit/c1a4b55) | Rebase auf 2.3.0 |
| G2F-ISSUE3 | [github.com/jimrandomh/g2flash/issues/3](https://github.com/jimrandomh/g2flash/issues/3) (12.09.2026) | paralleles Flashen, Paket nur mit Hauptanwendung, Blockzeiten |
| SYB-COMMIT | [AM-Guru/SybilSight-webflasher `ec1d3af`](https://github.com/AM-Guru/SybilSight-webflasher/commit/ec1d3af169ca85767d0e472c65b6f927aa7f814d) (21.08.2026) | „Remove custom firmware support“ |

### Herstellerangaben (Even Realities)

| Kürzel | Link | Inhalt |
|---|---|---|
| EVEN-SPEC | [Specs (Support-Artikel 13499229138959)](https://support.evenrealities.com/hc/en-us/articles/13499229138959), aktualisiert 05.09.2026 | Micro-LED, grün, 640 × 350, 60 Hz, 1200 nits, BLE 5.4, 192 mAh |
| EVEN-BLOG1 | [How we rebuilt G2 from the inside out](https://www.evenrealities.com/en-CA/blog/how-we-rebuilt-g2-from-the-inside-out) (12.12.2025) | neuer Prozessor, BT 5.4/PAwR, FPC zwischen den Bügeln |
| EVEN-BLOG2 | [How we shaped Even G2 from the outside in](https://www.evenrealities.com/blogs/even-insider/how-we-shaped-even-g2-from-the-outside-in) (09.01.2026) | Pogo-Kontakte, 4 Mikrofone, Kompass |
| EVEN-HUB | [Even Hub Docs](https://hub.evenrealities.com/docs/get-started/overview) | App läuft auf dem Handy; kein direkter BLE-Zugriff, kein freies Zeichnen, 576 × 288 |
| EVEN-UPDATE | [Update Even G2 Firmware (13499311356943)](https://support.evenrealities.com/hc/en-us/articles/13499311356943), aktualisiert 04.09.2026 | Laden, Akku > 50 %, App im Vordergrund |
| EVEN-NEUSTART | [G2 Glasses Display Is Frozen (17141832541839)](https://support.evenrealities.com/hc/en-us/articles/17141832541839) | Neustart per Touch oder Etui |
| EVEN-GARANTIE | [Warranty Policy (13558587234575)](https://support.evenrealities.com/hc/en-us/articles/13558587234575), aktualisiert 23.09.2026 | 1 Jahr (EU/EFTA 2 Jahre); Ausschlüsse |
| EVEN-AGB | [Terms of Service (14290554040335)](https://support.evenrealities.com/hc/en-us/articles/14290554040335), aktualisiert 23.09.2026 | Verbot von Reverse Engineering |

### Weitere Quellen

| Kürzel | Link | Inhalt |
|---|---|---|
| FCC | [fccid.co/fcc/2BFKR-G2](https://fccid.co/fcc/2BFKR-G2) (Spiegel der FCC-Daten) | Grant 23.11.2025, 2402–2480 MHz, 724 µW |
| ED | [Electronic Design, 11.09.2026](https://www.electronicdesign.com/technologies/embedded/video/55404374/electronic-design-spot-helps-knock-down-edge-ai-noise) | „Even G2 … use the Apollo 510“ |
| AMBIQ-DS | [Apollo510 SoC Datasheet](https://contentportal.ambiq.com/documents/20123/2877485/Apollo510-SoC-Datasheet.pdf), §5.5 | Aufgaben des SBL, „if enabled“ |
| AMBIQ-510B | [Apollo510B SoC Datasheet](https://contentportal.ambiq.com/documents/20123/4530417/Apollo510B-SoC-Datasheet.pdf) | integriertes BLE-5.4-Controller-Subsystem mit 512-kB-Flash |
| AMBIQ-QSG | [Apollo510 EVB Quick Start Guide v2.1](https://contentportal.ambiq.com/documents/20123/4133620/Apollo510-EVB-Quick-Start-Guide-v2.1.pdf) | Auslieferung „non-secure“, UART-Wired-Update per INFO0 |
| AMBIQ-A4PROV | [Apollo4 OEM Provisioning Guide](https://contentportal.ambiq.com/documents/20123/387772/Apollo4-Family-OEM-Provisioning-Update-and-Tools-Users-Guide.pdf), §9 (andere Chipfamilie) | Wired Update nur bei Provisionierung, Bootfehler oder Override-Pin |
| AMBIQ-A3SEC | [Apollo3 Security App Note](https://contentportal.ambiq.com/documents/20123/387797/Apollo3-Blue-Family-Typical-Security-Guidelines-App-Note.pdf) (ältere Familie) | SWD-Sperre und Wired Update |
