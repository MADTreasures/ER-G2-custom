# Entwürfe aus dem Designer

Hier liegen Entwürfe aus dem Web-Designer ([`../designer/`](../designer/README.md)) im Format
`faceclaw-edit/design@1` ([Format](../docs/firmware-uebergabe/05_Entwurfsformat.md)).

- `beispiel-entwurf.json` – das Beispiel aus dem Übergabe-Paket (4 Bildschirme).
- Eigene Entwürfe: im Designer *Exportieren → JSON-Datei* und die Datei hier ablegen, oder das JSON
  in den Chat kopieren.

Wie ein Entwurf umgesetzt wird, entscheidet das Feld `runsOn` pro Bildschirm: `app` (die Uhr zeichnet
und schickt Bilder – ohne Flash-Risiko, der Normalfall), `firmware` (läuft auf der Brille selbst –
braucht eine eigene Custom-Firmware) oder `open`. Diese erste Version der Uhr-App liest noch keine
Entwürfe; sie schafft mit der Custom-Firmware die Grundlage, damit die Uhr beliebige Inhalte auf der
Brille zeigen kann.
