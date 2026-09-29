# 07 – Umsetzungsplan

Sechs Meilensteine, jeder für sich lieferbar und getestet. Die Reihenfolge ist so gewählt, dass nach jedem
Schritt etwas auf der Brille benutzbar ist. Jeder Meilenstein ist ein eigener Chat
([08](08_Prompt_fuer_neuen_Chat.md)).

Für alle gilt:
- Vor dem Start die Mappe 00–06 lesen. Bei Widersprüchen gilt dieser Plan vor 02–06, das App-Modell (02)
  vor den Einzelkapiteln. Der Chat klärt jeden Widerspruch und korrigiert die Doku im selben Schritt.
- Alles mit Tests ohne Hardware; CI grün; Lint ohne Fehler.
- Die Doku (README, diese Mappe, Bilder) im selben Schritt nachziehen. Nichts als „auf Hardware erprobt“
  bezeichnen, was nicht dort erprobt ist.
- Versionsnummer der Uhr-App erhöhen, wenn sich die Uhr-App ändert.

## M0 – Baukasten an die App-Fläche anpassen (klein)

**Ziel:** Was man im Baukasten entwirft, sieht auf der Brille genauso aus, und Apps können sich auf
Kennungen verlassen.

- Vorschau: der 640×480-Rahmen mit dem sichtbaren Streifen (y 96–383), darin Kopfzeile 28 px und
  App-Fläche 576 × 260, oder Vollbild 576 × 288 bei `statusBar: false`. Maße und Graustufen genau nach
  [02 §4.2](02_App-Modell.md#42-bausteine).
- Feld „Kennung“ für Seiten und Bausteine (`[A-Za-z0-9_.-]{1,40}`, eindeutig, Vorschlag aus dem Text);
  Knöpfe verweisen weiter über die Kennung.
- Neuer Baustein „Bild“ (`image`): PNG wählen, in Graustufen umrechnen, auf 544 × 260 begrenzen, ≤ 64 KiB.
- Knopf-Ziel „Zurück“ (`@back`).
- Format bleibt `g2-baukasten@1` (nur Ergänzungen); alte Projekte importieren weiter.
- Artifact unter derselben Adresse neu veröffentlichen, `designer/README.md` und `designs/beispiel.json` anpassen.

**Abnahme:** Bildschirmfotos Handy und PC, hell und dunkel; alte `beispiel.json` importiert ohne Fehler;
Kennungen doppelt → Hinweis statt Absturz.

## M1 – App-Host auf der Uhr, erste Uhr-Apps

**Ziel:** Auf der Brille gibt es „Apps“, darin Stoppuhr und Einkaufsliste, bedienbar mit Zeiger und Bügel.

- Alles aus [03 §5](03_Uhr-Apps.md#5-plattform-der-app-host-zu-bauen-in-m1): Modell + JSON, `AppHost`,
  `PageRenderer`, `LauncherApp`, `AppRegistry`, Gesten-Modus im `TouchpadScreen`, Weiterleitung aller
  Bügel-/Ring-Gesten.
- kotlinx.serialization im Modul `app` (Plugin und Bibliothek stehen schon im Versionskatalog).
- Beispiel-Apps `StopwatchApp` und `ShoppingListApp` (Seiten als Asset aus dem Baukasten).
- Berechtigungsabfrage auf der Brille (Seite mit „Erlauben“/„Ablehnen“).

**Abnahme:** `AppHostTest`, `PageRendererSnapshotTest` (jede Bausteinart, Scroll, Fokus, Zeiger),
Tests der Beispiel-Apps mit `FakeAppContext`; neue Bilder `docs/bilder/apps-*.png`; `FlashingBoundaryTest` grün;
README-Abschnitt „Apps“.

## M2 – Protokoll `g2-remote@1`, Rechner-Host, erste Rechner-Apps

**Ziel:** Eine TypeScript-App auf dem PC erscheint im Starter der Brille und reagiert auf Klicks.

- `protocol/vectors/`: Beispielnachrichten (gültig/ungültig) für jeden Typ aus [04 §5](04_Rechner-Apps_und_Protokoll.md#5-protokoll-g2-remote1).
- `host/`: Node ≥ 22, TypeScript, `ws`, `zod`, `bonjour-service`; Sitzungen in `worker_threads`;
  Kopplung mit Code und Token-Hash; Resume; Beispiel-Apps `echo`, `pc-status`, `notizen`; SDK-Modul
  `g2-host/sdk` mit `defineApp` und den Typen.
- Uhr: `RemoteHostClient` (OkHttp-WebSocket), Suche per `NsdManager` (mDNS), Seite „Rechner“ in den
  Einstellungen (suchen, Adresse eingeben, Code eingeben, Token speichern, trennen), Rechner-Apps im Starter.
- CI: eigener Job für `host/` (`npm ci`, `tsc --noEmit`, Tests).

**Abnahme:** Integrationstest simulierte Uhr ↔ Host mit `echo`; Kotlin-Client-Tests gegen die Vektoren und
`mockwebserver`; Anleitung im README „Rechner verbinden“ (WLAN, LTE über TLS-Tunnel).

## M3 – App-Verwaltung

**Ziel:** Apps per Handy-Browser installieren, starten, entfernen; Uhr koppeln.

- Alles aus [06](06_App-Verwaltung.md), inklusive `app.launch`.

**Abnahme:** API-Tests, Playwright-Test der Seiten (Handy- und PC-Breite), Bildschirmfotos in der Doku.

## M4 – EvenHub-Adapter

**Ziel:** Eine Even-Hub-App (eigene Test-App und die offizielle Vorlage) läuft über den Rechner auf der Brille.

- Alles aus [05 §3](05_EvenHub-Apps.md#3-der-evenhub-adapter-in-g2-host-zu-bauen-in-m4) und die
  Installationswege aus [05 §5](05_EvenHub-Apps.md#5-woher-apps-kommen-dürfen).
- Kein Zugriff auf Evens Store-Server.

**Abnahme:** Test-App deckt alle 16 Methoden und alle Ereignisarten ab; Bildvergleich des Zeichners mit
Referenzbildern; Blockieren nicht freigegebener Netz-Hosts getestet.

## M5 – Sensoren, Mikrofon, Summer

**Ziel:** Apps hören zu und spüren Bewegung.

- Uhr: IMU, Kompass, Umgebungslicht, Mikrofon (16 kHz PCM), Summer aus `GlassesSessionCore` an den
  AppHost anschließen; nur aktiv, solange eine sichtbare App sie abonniert hat ([03 §6](03_Uhr-Apps.md#6-sensoren-und-mikrofon-m5)).
- Protokoll: Audio-Binärrahmen, `subscribe`/`audio` weiterreichen.
- Beispiel-Rechner-App **„Diktat“**: Mikrofon → Spracherkennung auf dem PC (lokal, z. B. whisper.cpp,
  oder ein Cloud-Dienst mit eigenem Schlüssel) → Text auf der Brille, als Notiz speichern.
- Beispiel-Uhr-App **„Kompass“**.

**Abnahme:** Tests mit aufgezeichneten PCM-Daten und IMU-Folgen; Messung und Anzeige, wie viel Akku
Sensoren auf der Uhr kosten (Protokollzeile je Minute), damit man es auf Hardware prüfen kann.

## Danach (Ideen, nicht geplant)

- Stdio-Brücke für Apps in Python und anderen Sprachen.
- Mehrere Rechner gleichzeitig (z. B. PC zu Hause und Server im Netz).
- Eigene App-Sammlung als Git-Katalog in der App-Verwaltung.
- Display-Listen mit Animation (Revision-35-Ausdrücke) statt Pixeln für flüssigere Übergänge.
