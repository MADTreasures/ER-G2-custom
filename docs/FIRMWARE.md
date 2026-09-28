# Firmware auf der Uhr – Ablauf, Schranken, Tests

Wie die Uhr-App Evens Original-Firmware oder die Custom-Firmware Faceclaw/35 auf die G2 bringt. Die
Grundlagen (Image-Aufbau, Protokoll, Sicherheitsregeln) stehen im Übergabe-Paket:
[`firmware-uebergabe/02_Firmware_flashen.md`](firmware-uebergabe/02_Firmware_flashen.md) und
[`firmware-uebergabe/wissen/06-firmware.md`](firmware-uebergabe/wissen/06-firmware.md). Hier steht, was
davon die App wo umsetzt – und wo sie bewusst anders entschieden hat.

## 1. Bausteine

| Datei | Aufgabe |
|---|---|
| `firmware-image/…/FirmwareCatalog.kt` | Allow-List (zwei SHA-256), Versionen, Größen, `prepare()` = Original prüfen → ggf. Patch-Set anwenden → vollständig prüfen |
| `firmware-image/…/PatchSet.kt` | g2flash-Patch-Set anwenden: SHA-256 der Basis, jede Stelle mit erwarteten Bytes, SHA-256 des Ergebnisses |
| `firmware-image/…/EvenOtaImage.kt` | EVENOTA-Container prüfen: Struktur, CRC-32C (MSB-first) in Inhaltsverzeichnis **und** Kopf, Vorspann des Hauptprogramms (Ladeadresse `0x438000`, Länge, zlib-CRC), **Speichergrenze `0x7F0000`** |
| `app/…/firmware/StockImageStore.kt` | Original-Image: Cache (`noBackupFilesDir/firmware`) → Import (`Android/data/…/files/firmware/*.bin`) → Download von Evens CDN; nur geprüfte Bytes werden gespeichert |
| `app/…/firmware/FirmwareJob.kt` | Der Ablauf (Abschnitt 2) um Faceclaws `DeviceInfoProbeFlow`, `FlashPromptFlow`, `OtaFlashFlow` |
| `app/…/firmware/GuardedStockLink.kt` | Wächter vor dem Update-Kanal: nur scharf geschaltet, nur mit MTU ≥ 243 |
| `app/…/firmware/WatchStockLink.kt` | Faceclaws GATT-Manager als `StockLink`, mit Zugriff auf die ausgehandelte MTU |
| `app/…/firmware/WatchFirmwareInstaller.kt` | `FirmwareInstaller` der Uhr: ein Auftrag zur Zeit auf eigenem Thread, Testlauf-Pflicht pro Brille |
| `app/…/firmware/FirmwareService.kt` | Vordergrund-Dienst (`connectedDevice`) + Wake-Lock während des ganzen Auftrags |
| `app/…/glasses/GlassesConnection.kt` | `releaseForFirmware()`: trennt Prüfung/Sitzung und meldet sich erst, wenn die Verbindungen zu sind |
| `app/…/ui/SettingsScreen.kt` | Knöpfe, Bestätigung (2 s halten), Fortschritt, „Risiken & Rückweg“ |

Die Faceclaw-Abläufe liegen unverändert in `faceclaw-core` (Faceclaw 0.8.0). Laut Faceclaws
Changelog ist das Aufspielen dort „end to end getestet“; `OtaFlashFlow` ist Byte für Byte ein Port
von `g2flash.py`.

## 2. Ablauf eines Auftrags

```
install(target) ── nur aus dem 2-s-Halten ─┐        testRun(target) ── Knopf „Testlauf“ ─┐
                                           ▼                                              ▼
 1  Uhr:   beide Bügel? Akku Uhr ≥ 50 % oder Laden?
 2  Image: Original (Cache/Import/CDN, SHA-256) → Custom bauen (Patch-Set, SHA-256) → prüfen → Allow-List
 3  App-Verbindung zur Brille trennen (releaseForFirmware), 2 s warten
 4  DeviceInfoProbeFlow: Versionen + Feld 100 lesen; neuere Original-Firmware als 2.3.0.24 → Stopp
 5  FlashPromptFlow: beide Bügel koppeln, Lautlos-Modus?, Frage auf der Brille, Akku L/R ≥ 50 %
        │ Testlauf: ohne Frage ───────────────────────────────────────────────────────────┐
 6  Allow-List erneut, Link scharf, OtaFlashFlow: links (30 s Fenster) → Neustart → rechts (120 s)  │
 7  15 s warten, bis zu 6× jedes Glas einzeln fragen: Faceclaw/35 bzw. Original 2.3.0.24?          │
                                                                                                   ▼
                                    Testlauf: je Glas Update-Kanal verbinden, koppeln, MTU ≥ 243, trennen
```

`OtaFlashFlow` selbst (unverändert von Faceclaw): pro Glas verbinden, beide Benachrichtigungen,
Anmeldung (sid `0x80`, Magic `0x60–0x7F`), 2,5 s, `BEGIN`, je Komponente `FILE_CHECK` → 4-KB-Blöcke
(Markierung + Daten mit derselben seq) → `END`. Ein Block wird nur nach ausdrücklicher Ablehnung
wiederholt (max. 3), eine Komponente nach fehlgeschlagenem `END` oder Zeitüberschreitung ab
`FILE_CHECK` (max. 3). Scheitert das linke Glas, wird das rechte nicht angefasst.

## 3. Wo die App strenger ist als die Vorlagen

| Thema | Faceclaw (Handy) | g2flash (PC) | Uhr-App |
|---|---|---|---|
| Reihenfolge | Frage → Image bauen → flashen | – | **Image zuerst**, dann Brille (ein Download-Fehler stört die Brille nicht) |
| Allow-List | nur beim Bauen | keine | beim Bauen **und** direkt vor dem ersten Byte |
| MTU | angefragt, nie geprüft | – | **≥ 243 Pflicht**, sonst wird schon `BEGIN` verweigert |
| Akku Brille | < 30 % verweigert, unbekannt erlaubt | keine | < 50 % **oder unbekannt** verweigert (Evens eigene Vorgabe: > 50 %) |
| Akku Uhr | – | – | ≥ 50 % oder am Laden |
| Neuere Original-Firmware | „Trotzdem“-Knopf | – | Stopp (ungetesteter Downgrade) |
| Testlauf | – | `--stop-before flash` (sendet schon `BEGIN`+`FILE_CHECK`) | schreibt **nichts** in den Update-Kanal; Pflicht vor dem ersten echten Aufspielen je Brille |
| Nach dem Flashen | keine Kontrolle | keine | **jedes Glas einzeln** fragen (Faceclaws Probe meldet nur ein Glas); nicht bestätigt = rot |
| 2 s Halten | – | – | mit der Uhrzeit gemessen, unabhängig von „Animationen aus“ |
| App wird beendet | – | – | Markierung vor dem ersten Byte; beim nächsten Start Warnung „Zustand unklar“ |
| Wach bleiben | – | – | Vordergrund-Dienst + Wake-Lock + Bildschirm an |
| Revision | exakt 35 | – | exakt 35 (`FirmwareRequirement`) |

Bewusst **nicht** übernommen aus dem Referenz-Code des Übergabe-Pakets: dessen eigener Flasher
(`OtaFlasher`) mit Neuverbinden und Neustart des Glases ab Komponente 0. Er ist strenger, lief aber
nie auf Hardware; Faceclaws Flasher ist im Einsatz. Aus dem Referenz-Code stammen dagegen die
Image-Prüfung und das Patch-Set (`firmware-image`), die hier bitgenau gegen das echte Image getestet sind.

## 4. Was die Meldungen über die Brille sagen

| Meldung endet mit | Bedeutung |
|---|---|
| „Nichts wurde an der Brille verändert.“ | Kein Byte ist in den Update-Kanal gegangen (der Wächter zählt mit) |
| „Das linke Glas hat die neue Firmware, das rechte nicht. …“ | Links fertig, rechts abgebrochen: gemischter Stand, erneut aufspielen |
| „Welche Firmware die Brille jetzt startet, ist unklar. …“ | Abbruch während der Übertragung; laut Recherche startet die Brille meist mit der bisherigen Firmware |
| „Beide Gläser sind übertragen, aber die Kontrolle danach fand … nicht auf beiden (links: …, rechts: …)“ | Übertragung beendet, aber nicht beide Gläser zeigen die neue Firmware; neu verbinden, sonst erneut aufspielen |
| „Die App wurde während der Übertragung beendet. …“ | Android hat die App mitten in der Übertragung beendet; Zustand wie oben „unklar“ |

Die App wiederholt nie selbst. Jeder Schritt steht mit Uhrzeit im **Protokoll** (Einstellungen →
Protokoll), Zeilen mit „Firmware:“ stammen vom Auftrag.

## 5. Revision wechseln (z. B. auf eine künftige Faceclaw/36)

Kern und Firmware gehören zusammen. In einem Schritt:

1. `scripts/sync-faceclaw.sh /pfad/zu/faceclaw <tag>` – meldet die benötigte Revision.
2. `patches/cfw_patches.json` aus dem passenden g2flash-Commit nach
   `firmware-image/src/main/resources/firmware/` kopieren.
3. In `FirmwareCatalog`: `CUSTOM_REVISION`, `CUSTOM_SHA256`, `CUSTOM_SIZE`, `PATCH_SET_ORIGIN`.
4. `python3 tools/cfw_bauen.py` (baut und prüft gegen das echte Original).
5. `G2_STOCK_IMAGE=… ./gradlew :firmware-image:test :app:testDebugUnitTest` – `RealImageTest`
   braucht die neuen Größen/CRCs des Hauptprogramms (die Ausgabe von `cfw_bauen.py` nennt sie).

Für eine **eigene** Firmware (eigene Patches) zusätzlich eine eigene Kennung statt `Faceclaw/` und
die Regeln aus [`firmware-uebergabe/01_Custom-Firmware_erstellen.md`](firmware-uebergabe/01_Custom-Firmware_erstellen.md) §5.

## 6. Offene Punkte für den ersten Hardware-Versuch

- Bluetooth der Pixel Watch: welche MTU sie aushandelt und wie schnell sie überträgt, ist unbekannt –
  der Testlauf zeigt die MTU beider Gläser.
- Ob Wear OS beim Koppeln einen Dialog zeigt, ist ungeprüft; die App wartet bis zu 90 s.
- Ob die Kopplung mit der Uhr die Kopplung der Even-App auf dem Handy verdrängt, ist unbekannt – die
  Even-App muss danach eventuell neu koppeln.
- Ob die Custom-Firmware die Anmeldung auf dem Update-Kanal genauso bestätigt wie die Original-Firmware,
  ist nur aus dem Quelltext abgeleitet: Vor dem ersten Zurück auf Original den Testlauf auch einmal mit
  Custom-Firmware auf der Brille machen.
- Faceclaws Flasher verbindet nach einer Zeitüberschreitung nicht neu, sondern wiederholt die
  Komponente auf derselben Verbindung. Bricht die Verbindung wirklich ab, scheitert das Glas nach drei
  Versuchen – dann einfach neu starten.
- Empfehlung aus der Recherche: nach dem ersten Custom-Aufspielen einmal zurück auf Original und
  wieder auf Custom, damit der Rückweg für diese Brille erprobt ist.
