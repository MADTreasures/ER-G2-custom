# Lizenz und Herkunft

- `firmware-image/src/main/resources/firmware/cfw_patches.json` ist eine unveränderte Kopie von
  `patches/cfw_patches.json` aus [g2flash](https://github.com/jimrandomh/g2flash), Commit `9079f99`
  (25.09.2026, **Faceclaw/35**, GPLv3). Es enthält nur Byte-Änderungen und den eingefügten Code der
  Custom-Firmware, **nicht** Evens Firmware.
- `cfw_bauen.py` stammt aus Faceclaw Edit (GPLv3) und ist eine unabhängige Neuimplementierung der
  Bau- und Prüfregeln. Ohne `--patches` nimmt es das Patch-Set der App.
- Evens Firmware (`g2_2.3.0.24.bin`) ist Eigentum von Even Realities. Sie wird von Evens Server
  geladen und darf nicht weitergegeben werden. Eine Custom-Firmware erlischt die Garantie.

Benutzung:

```bash
python3 cfw_bauen.py                    # Original laden, Faceclaw/35 bauen, alles prüfen
python3 cfw_bauen.py --stock DATEI      # vorhandenes Original verwenden
python3 cfw_bauen.py --check DATEI      # beliebiges Image prüfen (Prüfsummen, Speichergrenze)
```
