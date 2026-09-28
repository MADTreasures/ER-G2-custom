# faceclaw-core – Herkunft

Gemeinsamer Kotlin-Kern von **Faceclaw** (Jim Babcock, GPL-3.0), **unverändert** übernommen.

| | |
|---|---|
| Quelle | https://github.com/jimrandomh/faceclaw |
| Stand | Release **0.8.0**, Commit `e6ec542f5602752537764581223c2161bfd9af17` (26.09.2026) |
| Übernommen | `native/kotlin/shared/src/commonMain` → `src/commonMain`, `native/kotlin/shared/src/androidMain` → `src/androidMain` |
| Tests | `tests/kotlin/src/commonTest` → `src/commonTest`, `tests/kotlin/src/androidHostTest` → `src/androidHostTest` |
| Weggelassen | `iosMain` (nur iOS), `FontTest.kt` (braucht eine 6 MB große Schrift aus Faceclaws `app/`) |
| Lokale Änderungen | keine |
| Werkzeug | `scripts/sync-faceclaw.sh /pfad/zu/faceclaw 0.8.0` |

## Firmware-Vertrag

Dieser Stand spricht das private Protokoll der Custom-Firmware in **Revision 35** („Faceclaw/35“),
gebaut aus Evens Firmware **2.3.0.24** plus dem Patch-Set von g2flash (Commit `9079f99`). Genau
dieses Image spielt die Uhr als „Custom-Firmware“ auf (`firmware-image`, `FirmwareCatalog`).

Faceclaw verlangt seit Release 0.8.0 **exakt** eine Revision (`app/g2/firmware-compat.ts`:
`REQUIRED_FACECLAW_FIRMWARE_VERSION = 35`). Revision 35 hat die Display-Semantik geändert
(Leeren/Kopieren des Kompositionspuffers steht jetzt in der Wurzel-Display-Liste), ein Kern für 34
zeichnet auf einer 35er-Firmware also nicht richtig – und umgekehrt. Deshalb gehören Kern-Stand und
Firmware-Revision immer zusammen.

Die Firmware-Abläufe, die die Uhr zum Aufspielen nutzt – `FlashPromptFlow` (Bestätigung auf der
Brille, Akku), `OtaFlashFlow` (Übertragung, Port von g2flash.py) und `FirmwareImage` (Prüfung) –
liegen in diesem Kern und sind dieselben, mit denen Faceclaws Handy-App ihre Firmware aufspielt.

## Aktualisieren

Den Kern nie von Hand ändern, sondern auf einen neuen Faceclaw-Stand heben:

```sh
scripts/sync-faceclaw.sh /pfad/zu/faceclaw <tag-oder-commit>
./gradlew :faceclaw-core:testAndroidHostTest :app:testDebugUnitTest
```

Meldet das Skript eine neue Revision, müssen im selben Schritt das Patch-Set
(`firmware-image/src/main/resources/firmware/cfw_patches.json` aus g2flash) und die Werte in
`FirmwareCatalog` (Revision, SHA-256, Größe) nachgezogen werden.
