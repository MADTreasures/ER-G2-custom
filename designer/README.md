# G2 Baukasten

Eine einfache Notiz-App, um Bildschirme für die G2-Brille zu skizzieren. Eine Seite ist ein Stapel
aus Bausteinen, die sich von oben nach unten von selbst anordnen – ohne Pixel, Ebenen oder Schriften.
Die Vorschau zeigt die Seite so, wie die Brille sie zeigt: 640 × 480 Pixel, 16 Graustufen, grün.

## Bedienung (fürs Handy gemacht)

- **Seiten:** Die Übersicht zeigt alle Seiten als Karten. Tippen öffnet eine Seite, „Neue Seite“
  legt eine an. Der Stern markiert die Startseite.
- **Bausteine:** Im Editor mit „+ Baustein“ hinzufügen und direkt in der Karte bearbeiten. Pfeile
  verschieben, daneben Duplizieren und Löschen (mit „Rückgängig“). Tippen in die Vorschau springt
  zum passenden Baustein. Unten stehen Notiz, Statuszeile, Startseite und „Seite löschen“.
- **Testen:** Zeigt die Seite bildschirmfüllend. Knöpfe öffnen ihre Zielseite, Schalter und
  Häkchen reagieren, „Zurück“ geht zur vorherigen Seite, Wischen scrollt. Nichts davon wird gespeichert.
- **Für den Chat kopieren:** Übersicht → `⋯` → „Für den Chat kopieren“, dann im Chat einfügen.
  „Importieren“ im selben Menü übernimmt ein JSON aus dem Chat (ersetzt das Projekt nach Rückfrage).

In der Notiz jeder Seite steht, was sie tun soll. Daraus macht Claude später die Logik.

## Wo gespeichert wird

- **Als claude.ai-Artifact:** in der Datenbank der Seite (Dokument `projekte/haupt`), zusätzlich im
  Browser. Claude kann den Entwurf dort direkt lesen – sag im Chat: „Schau dir meinen Baukasten an“.
- **Lokal** (`index.html` im Browser geöffnet): nur im Browser auf diesem Gerät. Zum Übergeben
  „Für den Chat kopieren“ benutzen oder das JSON als Datei nach [`../designs/`](../designs/) legen.

## Format `g2-baukasten@1`

```json
{ "format": "g2-baukasten@1", "name": "Mein Brillen-UI", "start": "p_start", "updatedAt": "…",
  "pages": [ { "id": "p_start", "name": "Start", "statusBar": true, "notes": "…", "blocks": [ … ] } ] }
```

Jeder Baustein hat eine `id` und einen `type`:

| `type` | Baustein | Felder |
|---|---|---|
| `heading` | Überschrift | `text`, `align` (`left`/`center`), `size` (`normal`/`gross`) |
| `text` | Text | `text` (mehrzeilig), `align` (`left`/`center`) |
| `button` | Knopf | `text`, `target` (Seiten-`id` oder `null`), `action` (freier Text: „macht …“) |
| `list` | Liste | `style` (`bullets`/`checks`/`numbers`), `items`: Liste von `{ "text", "done" }` |
| `toggle` | Schalter | `text`, `on` |
| `value` | Wert | `text` (Bezeichnung), `value` (z. B. `"80 %"`) |
| `progress` | Fortschritt | `text` (Bezeichnung), `value` (0–100) |
| `divider` | Trennlinie | – |

Beim Import werden fehlende Felder ergänzt, unbekannte Bausteine weggelassen und Verweise auf
fehlende Seiten entfernt. Ein Beispiel liegt in [`../designs/beispiel.json`](../designs/beispiel.json).

## Entwicklung

`baukasten.html` ist die Quelle (so wird sie als Artifact veröffentlicht, ohne `<html>`-Gerüst);
`./build.sh` erzeugt daraus `index.html` zum direkten Öffnen im Browser.
