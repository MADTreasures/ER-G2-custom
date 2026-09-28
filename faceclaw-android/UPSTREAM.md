# faceclaw-android – Herkunft

Faceclaws Android-Anbindung an Bluetooth LE (GPL-3.0, Lizenztext in [`LICENSE`](LICENSE)), **unverändert** übernommen. Die Klassen sind
reines Android ohne NativeScript und laufen deshalb auch auf Wear OS.

| | |
|---|---|
| Quelle | https://github.com/jimrandomh/faceclaw, `App_Resources/Android/src/main/java/com/faceclaw/app/` |
| Stand | Release **0.8.0**, Commit `e6ec542f5602752537764581223c2161bfd9af17`, derselbe wie `faceclaw-core` |
| Übernommen | `FaceclawBleManager.kt` (GATT der Sitzung), `AndroidSessionLink.kt` (`SessionLink`), `AndroidStockLink.kt` (`StockLink` für Prüfung, Bestätigung und Aufspielen), `FaceclawDeviceInfoProbe.kt` (liest Firmware-Versionen) |
| Lokale Änderungen | keine |

`FaceclawBleManager` nutzt die `writeCharacteristic`-Variante ab API 33, daher braucht die App
mindestens Wear OS 4. Faceclaws eigene Hüllen um die Flash-Abläufe (`FaceclawFirmwareFlasher.kt`,
`FaceclawFlashPromptCommunicator.kt`) sind NativeScript-Brücken und werden **nicht** übernommen; die
Uhr-App ruft `FlashPromptFlow` und `OtaFlashFlow` direkt auf (`app/.../firmware/`).
