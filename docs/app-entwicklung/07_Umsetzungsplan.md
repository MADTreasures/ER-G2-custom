# 07 – Umsetzungsplan

Acht Meilensteine, jeder für sich lieferbar und getestet. Die Reihenfolge folgt dem, was dir am wichtigsten
ist: erst eigene Apps und Even-Hub-Apps auf Uhr und Brille, dann Rechner-Apps, dann Sensoren. Jeder
Meilenstein ist ein eigener Chat ([08](08_Prompt_fuer_neuen_Chat.md)).

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
- Feld „Kennung“ für Seiten und Bausteine (`[A-Za-z0-9_.-]{1,40}`, Vorschlag aus dem Text). Kennungen bleiben
  projektweit eindeutig, wie es `normalize` heute schon erzwingt; der Baukasten zeigt Doppelte an, statt sie
  still umzubenennen. Knöpfe verweisen weiter über die Kennung.
- Neuer Baustein „Bild“ (`image`): PNG wählen, in Graustufen umrechnen, auf 544 × 260 begrenzen, ≤ 48 KiB;
  „randlos“ auf Vollbild-Seiten bis 576 × 288.
- Knopf-Ziel „Zurück“ (`@back`); `normalize` lässt `@back` stehen (heute setzt es jedes Ziel, das keine
  Seite ist, auf `null`).
- Format bleibt `g2-baukasten@1` (nur Ergänzungen); alte Projekte importieren weiter.
- Artifact unter derselben Adresse neu veröffentlichen, `designer/README.md` und `designs/beispiel.json` anpassen.

**Abnahme:** Bildschirmfotos Handy und PC, hell und dunkel; alte `beispiel.json` importiert ohne Fehler;
Kennungen doppelt → Hinweis statt Absturz.

## M1 – App-Host auf der Uhr, erste Uhr-Apps

**Ziel:** Auf der Brille gibt es „Apps“ (den Starter), bedienbar mit Zeiger und Bügel.

**Stand:** erledigt in v0.4.0 (nicht auf Hardware erprobt), aus Pull Request #2. Pull Request #1 baute M1 ein
zweites Mal; von ihm sind in v0.7.0 nur Webseiten-Raster und GeckoView-Test (M2) übernommen, sein Host nicht. Die Beispiele Stoppuhr und Einkaufsliste sind seit v0.7.0 entfernt; YouTube ist ein App-Paket (M1b). Was von 03 abweicht oder
dazukam, steht dort in §9. Außer der Reihe kam in v0.5.0 für die App YouTube dazu: Texteingabe auf der Uhr,
Eingabeart je Seite und die Video-Wiedergabe auf der Uhr (03 §10) – statt M5, weil alles auf der Uhr laufen
soll. In v0.5.2 kamen Emoji in allen Texten dazu (03 §11).

- Alles aus [03 §5](03_Uhr-Apps.md#5-plattform-der-app-host-zu-bauen-in-m1): Modell + JSON, `AppHost`,
  `PageRenderer`, Starter (`launcher/Launcher.kt`), App-Menü, `InputRouter` nach 03 §5.1, `AppRegistry`,
  Gesten-Modus im `TouchpadScreen`, Weiterleitung aller Bügel-/Ring-Gesten, und schon die Anschlüsse für
  EvenHub-Sitzungen nach 03 §5.2 (noch ohne Inhalt).
- kotlinx.serialization im Modul `app` (Plugin und Bibliothek stehen schon im Versionskatalog).
- Beispiel-Apps `StopwatchApp` und `ShoppingListApp` (v0.6.0 App-Pakete, seit v0.7.0 entfernt).
  Sie stehen nicht in `AppRegistry`: Der Starter zeigt nur installierte Apps, ohne
  Apps den Hinweis „Noch keine Apps“.
- Berechtigungsabfrage auf der Brille (Seite mit „Erlauben“/„Ablehnen“).

**Abnahme:** `AppHostTest`, `InputRouterTest`, `PageRendererSnapshotTest` (jede Bausteinart, Scroll, Fokus,
Zeiger, randloses Bild), Tests der Beispiel-Apps mit `FakeAppContext`; neue Bilder `docs/bilder/apps-*.png`;
`FlashingBoundaryTest` grün; README-Abschnitt „Apps“.

## M1b – App-Pakete: Apps getrennt installieren

**Ziel:** Die Uhr-App wird einmal installiert; jede eigene App kommt als Datei (`.g2app`) dazu und steht nach
dem Installieren auf der Uhr im Starter ([09](09_App-Pakete.md)).

**Stand:** erledigt in v0.6.0 (nicht auf Hardware erprobt; das Laden des DEX-Codes auf der Uhr ist ohne Uhr
nicht testbar).

- Modul `app-api/` mit der Schnittstelle und `G2AppApi.VERSION`; Paket-Bau in der `build.gradle.kts` im
  Hauptordner (`g2appManifest`, D8, Zip), ein Ordner je App in `packages/`.
- Auf der Uhr: `PackageArchive` (prüfen), `PackageStore` (installieren, aktualisieren, entfernen),
  `DexPackageLoader`, `AppPackages` (Ordner für neue Apps, Listen), Seite „Apps“ in den Einstellungen; der
  App-Host listet installierte Apps im Starter, lädt ihre Seiten aus dem Paket und beendet sie beim
  Entfernen oder Aktualisieren.
- CI-Artefakt `g2-apps` mit allen Paketen. In v0.7.0 wurde YouTube ein Paket, `builtInApps` ist leer; die
  Beispiel-Pakete Stoppuhr und Einkauf sind entfernt.

**Abnahme:** `PackageArchiveTest`, `PackageStoreTest`, `InstalledPackagesTest` (die echt gebauten Pakete
im echten App-Host), `AppsScreenTest`, `PackageManifestTest`, erweiterter `AppsBoundaryTest`; Bild
`docs/bilder/uhr-apps.png`; README-Abschnitt „Eigene Apps installieren“.

## M2 – GeckoView auf der Uhr: Machbarkeitstest

**Ziel:** Messen, ob die Browser-Engine auf der Pixel Watch 5 gut genug läuft. Das geht nur auf der echten
Uhr; der Chat baut die Test-APK, **du** installierst sie und liest die Werte ab.

- Eigene kleine Test-App (`tools/gecko-probe/` oder ein Build-Flavor), damit die normale Uhr-App nicht um
  ≈ 85–90 MB (Download) bzw. 150–190 MB (installiert) wächst, solange nichts entschieden ist. Nur die
  Architektur der Uhr einbauen (`geckoview-armeabi-v7a` bzw. `-arm64-v8a`, [05 §5](05_EvenHub-Apps.md#5-engine-auf-der-uhr-geckoview)).
- Umfang und Grenzwerte aus [05 §5.1](05_EvenHub-Apps.md#51-machbarkeitstest-m2-auf-der-echten-uhr):
  Architektur (`abilist`), Kaltstart, Speicher, Timer bei Bildschirm an/aus, Akku, drei Test-Apps über die
  Brücke. Die Werte erscheinen auf der Uhr und im Protokoll, zum Abschreiben oder als Datei.
- Ergebnis als Tabelle in `quellen/` und eine Entscheidung „GeckoView ja / nur für manche Apps / nein“.

**Abnahme:** APK für die richtige Architektur, Anleitung zum Installieren, Messwerte eingetragen,
Entscheidung dokumentiert. Ohne echte Messwerte ist M2 nicht fertig.

**Stand: Test-APK gebaut, Messwerte fehlen.** Modul `tools/gecko-probe/` („Gecko-Test“, nur `armeabi-v7a`,
Release voreingestellt), Beschreibung in [05 §5.2](05_EvenHub-Apps.md#52-die-test-apk-gecko-test),
Anleitung im README, Vorlage für die Werte in [quellen/E-m2-messwerte.md](quellen/E-m2-messwerte.md).
Zusätzlich zum Plan misst sie den Bildweg des Browsers (M7): „Seite rendern“ zeichnet eine Seite in eine
unsichtbare Fläche und wandelt sie mit `web-raster` ins Brillenbild. Die APK braucht zum Kompilieren die
Plattform 37.1 (GeckoView 157).

## M3 – EvenHub-Laufzeit auf der Uhr

**Ziel:** Eine Even-Hub-App (eigene Test-App und die offizielle Vorlage aus `evenhub init`) läuft auf der Uhr
und erscheint auf der Brille. (Bei „GeckoView nein“ aus M2: M3 baut nur Modul und Zeichner, die Engine kommt
mit M4 vom Handy.)

- Module `evenhub-runtime` und `evenhub-gecko` nach [05 §4](05_EvenHub-Apps.md#4-aufbau): Paket, `AssetServer`,
  `WebEngine`, `EvenHubSession`, `ContainerRenderer`; `GeckoEngine` nach [05 §5](05_EvenHub-Apps.md#5-engine-auf-der-uhr-geckoview)
  (Repository `maven.mozilla.org`, `abiFilters`, Hauptprozess-Prüfung in `G2WatchApp`); Einbau als interne
  Sitzung nach [03 §5.2](03_Uhr-Apps.md#52-evenhub-sitzungen-im-app-host); Lebenszyklus, Rechte und Netz-Freigabe
  nach [05 §4.4](05_EvenHub-Apps.md#44-lebenszyklus-rechte-netz).
- Installieren direkt auf der Uhr über eine Adresse ([06 §2](06_App-Verwaltung.md#2-direkt-auf-der-uhr)).
- Lizenzseite in den Einstellungen (MPL, LGPL-Bibliotheken, GPL-Teile).

**Abnahme:** Test-App deckt alle Methoden ab; Mikrofon, IMU und Standort liefern bis M6 `false`/`null`. Ereignisse:
`sysEvent` (Klick, Doppelklick, Scroll, Langdruck, Vordergrund an/aus, System-Ende), `listEvent`, `textEvent`
(Scroll auf einem Text-Container), `menuItemClickEvent`, `evenAppLaunchSource`, `deviceStatusChanged`; nicht
`audioEvent`, IMU und `appLocationChanged` (M6). Bildvergleich des Zeichners mit Referenzbildern; Netz-Freigabe
geprüft; ein Durchlauf auf der echten Uhr (von dir) mit Beschreibung oder Foto der Brille.
Im Zweig „GeckoView nein“: dieselben Tests ohne Engine (SDK-Aufrufe als JSON), der echte Durchlauf folgt mit M4.

## M4 – Handy-App „G2 Handy“

**Ziel:** Even-Hub-Apps vom Handy installieren; Apps, die auf der Uhr nicht laufen, laufen auf dem Handy.

- Modul `phone/` (Android-App, gleiche `applicationId`), Modul `evenhub-webview` mit `SystemWebViewEngine`,
  `RemoteEngine` auf der Uhr, Nachrichten über die Datenschicht nach [05 §6](05_EvenHub-Apps.md#6-engine-auf-dem-handy-g2-handy).
- Gemeinsame Signatur-Konfiguration für Uhr- und Handy-App (Debug-Schlüssel im Repo, auch für CI-Builds),
  `play-services-wearable` im Versionskatalog, Fähigkeiten in `wear.xml`, `WearableListenerService` und
  Vordergrund-Dienst auf dem Handy; ein Test klärt, wie das WebView ohne sichtbare Activity weiterläuft.
- App-Verwaltung nach [06 §1](06_App-Verwaltung.md#1-g2-handy-android-handy).
- CI baut beide APKs.

**Abnahme:** Unit- und UI-Tests nach 06 §4; `RemoteEngine` mit Fake-Datenschicht; Handy-Engine im
Android-Emulator mit der Test-App aus M3; Anleitung „Handy-App installieren“ im README.

## M5 – Rechner-Apps: Protokoll `g2-remote@1`, `g2-host`, Web-Seite

**Ziel:** Eine TypeScript-App auf dem PC erscheint im Starter der Brille und reagiert auf Klicks; Apps lassen
sich über die Web-Seite installieren.

- `protocol/vectors/`: Beispielnachrichten (gültig/ungültig) für jeden Typ aus [04 §5](04_Rechner-Apps_und_Protokoll.md#5-protokoll-g2-remote1).
- `host/`: Node ≥ 22, TypeScript, `ws`, `zod`, `bonjour-service`, `esbuild`; Sitzungen in `worker_threads`;
  Kopplung mit Code und Token-Hash; Seitenstand und Resume; Beispiel-Apps `echo`, `pc-status`, `notizen`;
  SDK-Modul `g2-host/sdk` mit `defineApp` und den Typen; Web-Seite nach [06 §3](06_App-Verwaltung.md#3-web-seite-von-g2-host-rechner-apps).
- Uhr: `RemoteHostClient` (OkHttp-WebSocket, neu im Versionskatalog), Netz anfordern und binden,
  `ACCESS_LOCAL_NETWORK`, Network-Security-Config für `ws://` zu privaten Adressen
  ([01 §4](01_Plattform_und_Grenzen.md#4-netz-zwischen-uhr-und-rechner)), Suche per `NsdManager`, Seite
  „Rechner“ in den Einstellungen, Rechner-Apps im Starter, `status`-Meldungen.
- CI: eigener Job für `host/` (`npm ci`, `tsc --noEmit`, Tests).

**Abnahme:** Integrationstest simulierte Uhr ↔ Host mit `echo`; Kotlin-Client-Tests gegen die Vektoren und
`mockwebserver`; API- und Seitentests der Web-Seite; Anleitung „Rechner verbinden“ (WLAN, LTE über TLS-Tunnel).

## M6 – Sensoren, Mikrofon, Summer, Standort

**Ziel:** Apps hören zu und spüren Bewegung.

- Uhr: IMU, Kompass, Mikrofon (LC3), Summer aus `GlassesSessionCore` und den Standort der Uhr an den
  AppHost anschließen; nur aktiv, solange eine sichtbare App sie abonniert hat ([03 §6](03_Uhr-Apps.md#6-sensoren-mikrofon-summer-m6)).
  Umgebungslicht erst, wenn geklärt ist, wie es ohne Eingriff in die Helligkeitsregelung geht.
- Uhr-Apps und EvenHub-Laufzeit: liblc3 per JNI (armeabi-v7a und arm64) für PCM; in der EvenHub-Laufzeit
  `audioControl`, `imuControl` und den Standort freischalten.
- Protokoll: Audio-Binärrahmen (LC3 von der Uhr), LC3-Entschlüsselung im Host, `subscribe`/`audio` weiterreichen.
- Beispiel-Rechner-App **„Diktat“**: Mikrofon → Spracherkennung auf dem PC (lokal, z. B. whisper.cpp,
  oder ein Cloud-Dienst mit eigenem Schlüssel) → Text auf der Brille, als Notiz speichern.
- Beispiel-Uhr-App **„Kompass“**.

**Abnahme:** Tests mit aufgezeichneten LC3-Paketen und IMU-Folgen; eine Protokollzeile je Minute mit dem
Akkuverbrauch der Sensoren, damit man es auf Hardware prüfen kann.

## M7 – Web-Browser auf der Brille (wenn M2 „GeckoView ja“ ergibt)

**Ziel:** Normale Web-Seiten auf der Brille ansehen und mit dem Uhr-Zeiger bedienen ([05 §10](05_EvenHub-Apps.md#10-und-ein-richtiger-browser-auf-der-brille)).

- GeckoView zeichnet in eine unsichtbare Fläche (`GeckoDisplay` mit `ImageReader`); die Uhr wandelt das Bild in
  Graustufen, schickt nur geänderte Bereiche; Zeiger-Klicks und Scrollen als Touch-Ereignisse an GeckoView;
  Adresseingabe über Tastatur/Sprache der Uhr; Lesezeichen.
- Lesemodus (Reader View) als Standard, weil er auf 576 × 260 besser lesbar ist.

**Abnahme:** Snapshot-Tests des Bildwegs; Messung von Speicher und Akku auf der echten Uhr.

**Schon gebaut (Vorarbeit):** das Modul `web-raster` – Seite → Brillen-Raster mit durchsichtigem Grund,
positiven Fotos, Logos als Grafik, Text in voller Helligkeit und automatisch negativem Text (Umriss) auf unruhigem Grund oder in
überladenen Fenstern ([05 §10.1](05_EvenHub-Apps.md#101-seiten-ins-brillen-raster-wandeln-web-raster-gebaut)),
mit Tests und Bildern `docs/bilder/raster-*.png`. Der Gecko-Test (M2) nutzt es schon auf der Uhr. Für M7 fehlt
noch: die Browser-App im App-Host (interne Sitzung nach 03 §5.2, Bild über `setRaster` in einen randlosen
Bild-Baustein), Scrollen der Seite unter dem Fenster, Zeiger → Touch-Ereignisse, Adresse und Lesezeichen,
nur geänderte Bereiche senden, Lesemodus, und die Grenzwerte des Rasters an echten Seiten auf der echten
Brille nachjustieren.

## Danach (Ideen, nicht geplant)

- „Lite“-Engine auf der Uhr (QuickJS + linkedom) für sehr einfache Even-Hub-Apps.
- Stdio-Brücke für Rechner-Apps in Python und anderen Sprachen.
- Mehrere Rechner gleichzeitig (z. B. PC zu Hause und Server im Netz).
- Eigene App-Sammlung (Katalog) in „G2 Handy“.
- Display-Listen mit Animation (Revision-35-Ausdrücke) statt Pixeln für flüssigere Übergänge.
