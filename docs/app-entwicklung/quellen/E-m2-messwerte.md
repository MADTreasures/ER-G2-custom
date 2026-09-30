# E – Messwerte M2: GeckoView auf der Pixel Watch 5

**Stand: noch keine Messung.** Die Test-APK „Gecko-Test“ (`tools/gecko-probe/`, [05 §5.2](../05_EvenHub-Apps.md#52-die-test-apk-gecko-test))
ist gebaut und ohne Hardware getestet (Einheitstests, Kompilieren, Lint). Die Werte unten kann nur die echte
Uhr liefern. Wer misst, trägt sie hier ein (oder schickt den Bericht `g2-gecko-bericht.txt` in den Chat) und
setzt die Entscheidung unten; erst dann ist M2 fertig.

## Uhr

| Wert | Messung |
|---|---|
| Modell, API (Zeile „Gerät“) | |
| `ro.product.cpu.abilist` (Zeile „ABI“) | |
| APK-Variante (release, armeabi-v7a) | |
| RAM gesamt / frei | |
| System-WebView vorhanden | |
| GeckoView-Version | 157.0.20260924084938 |

## Kriterien aus 05 §5.1

| Kriterium | Ziel | Messung | ✓ / ~ / ✗ |
|---|---|---|---|
| Kaltstart bis erster Aufruf (Schnelltest, erster Lauf nach dem App-Start) | ≤ 5 s | | |
| Speicher, PSS aller Prozesse (Spitze) | ≤ 300 MB | | |
| Drei Test-Apps (Text, Canvas, Vue + WebAssembly) über die Brücke | alle ✓ | | |
| Timer 100 ms, Bildschirm an | < 20 % Abweichung | | |
| Timer 100 ms, Bildschirm aus | < 20 % Abweichung | | |
| Akku im Dauertest | < 8 %/h | | |
| Dauertest 30 min ohne Abbruch | kein Abbruch | | |

## Weitere Werte

| Wert | Messung |
|---|---|
| Engine gestartet (GeckoRuntime.create) | |
| Brücke Uhr → App → Uhr (Rundlauf) | |
| Canvas-App: gezeichnet / gepackt | |
| Vue + WASM: WebAssembly / fertig | |
| Prozesse, die GeckoView startet | |
| Seite rendern (Testseite): laden / aufnehmen / Layout / Raster, Textzeilen, negativ | |
| Seite rendern (Wikipedia): laden / aufnehmen / Layout / Raster, Textzeilen, negativ | |
| Wie sieht `render-brille.png` aus? Text lesbar? Negativ, wo es sein soll? | |

## Entscheidung

- [ ] **GeckoView ja** – alle Kriterien erfüllt → M3 mit GeckoView auf der Uhr, M7 (Browser) möglich
- [ ] **GeckoView nur für manche Apps** – Grenzwerte knapp verfehlt → Handy als Standard, GeckoView für ausgewählte Apps
- [ ] **GeckoView nein** – Abstürze, Abbrüche oder zwei verfehlte Kriterien → Handy als Standard-Ort (05 §6), kein Browser auf der Uhr

Die Test-APK schlägt die Entscheidung selbst vor (letzte Zeile „Empfehlung“); sie folgt der Regel in
`ProbeResults.decision()`: ein Absturz, zwei ausgefallene Test-Apps oder ein Kaltstart über 10 s heißt „nein“,
zwei verfehlte Kriterien ebenso; alle erfüllt heißt „ja“; alles dazwischen „nur für manche Apps“.
