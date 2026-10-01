# Seiten-Vorschau fürs Brillenbild

Öffnet echte Web-Seiten im Fenster der Brille und zeigt daneben, was die Brille daraus macht. Damit
lassen sich die Regeln von `web-raster` ([05 §10.1](../../docs/app-entwicklung/05_EvenHub-Apps.md#101-seiten-ins-brillen-raster-wandeln-web-raster-gebaut))
an echten Seiten prüfen und nachjustieren, ohne Uhr und Brille.

**Ersatz-Engine:** Statt GeckoView (braucht die Uhr oder einen Emulator) zeichnet **Chromium** die Seite,
mit dem User-Agent von GeckoView auf der Uhr, 384 CSS-Pixel breit bei 1,5 Pixeln je CSS-Pixel
(576 × 260 Pixel, die App-Fläche der Brille). Das Layout liest dasselbe `collectLayout()` wie der
Browser der Uhr-App und der Gecko-Test (`web-raster/src/main/js/page-layout.js`); umgerechnet wird mit
dem echten `LayoutParser` und `web-raster`. Kleine Unterschiede zwischen Chromium und Gecko beim Zeichnen
bleiben möglich.

```sh
npm i -g playwright && npx playwright install chromium   # einmal
node tools/page-preview/capture.js /tmp/vorschau          # Seiten aufnehmen → /tmp/vorschau/raw/
PREVIEW_DIR=/tmp/vorschau ./gradlew :gecko-probe:testDebugUnitTest --tests '*PagePreviewTest*'
# → /tmp/vorschau/views/<seite>.png: links die Seite, rechts die Brille
# mit PREVIEW_STYLES=1 zusätzlich <seite>-stile.png: Umriss, Leuchtschrift mit Rand, Platte nebeneinander
```

Wie im Gecko-Test wird jede Stelle zweimal aufgenommen: einmal normal, einmal mit durchsichtiger Schrift
(`<stelle>-bare.png`, Animationen und Videos angehalten); der Unterschied ergibt die Buchstaben genau.

Die Seitenliste steht oben in `capture.js` (Adresse, Stellen zum Scrollen, dunkles Design). Cookie-Hinweise
werden mit der sparsamsten Wahl („Nur notwendige“, „Ablehnen“) geschlossen. Die Bilder fremder Seiten
gehören nicht ins Repo (Urheberrecht der Seiten).

## Prüfung des Browser-Skripts

`bridge-check.js` führt das Inhalts-Skript des Browsers der Uhr-App (`app/src/main/assets/webbridge/content.js`
mit Readability und `page-layout.js`) in Chromium aus, mit einem Ersatz für GeckoViews Verbindung zur Uhr-App,
auf zwei selbst geschriebenen Testseiten (ohne Netz). Geprüft wird, worauf sich die Engine verlässt: Lesemodus
an und aus, Eingabefelder mit Beschriftung, Tippen mit und ohne Enter, Scrollen von Fenster und inneren Boxen,
Layout-Bericht und die Stufen der doppelten Aufnahme.

```sh
node tools/page-preview/bridge-check.js   # „all ok“ oder die Prüfungen mit FAIL
```

Chromium hat keine „isolierte Welt“ wie Gecko-Erweiterungen; was davon abhängt, zeigt erst die echte Uhr.
