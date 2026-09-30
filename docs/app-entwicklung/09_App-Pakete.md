# 09 – App-Pakete: Apps getrennt von der Uhr-App installieren

Eigene Apps sind **App-Pakete**: je eine Datei `<app-id>-<version>.g2app`. Die Uhr-App wird einmal
installiert (README, „App auf die Uhr bringen“). Danach kommen Apps als Datei auf die Uhr, werden in der
Uhr-App unter **Einstellungen → Apps installieren** installiert und stehen sofort im Starter der Brille.
Die Uhr-App muss dafür weder neu gebaut noch neu installiert werden.

Stand v0.6.0: fertig und mit Tests abgesichert, **nicht auf echter Uhr erprobt**. Nur das Laden des
DEX-Codes auf der Uhr (`DexClassLoader`) lässt sich ohne Uhr nicht testen.

## 1. Wer macht was

| Wer | Macht | Liefert |
|---|---|---|
| **Hauptchat** | die Uhr-App selbst (Plattform): App-Host, Schnittstelle `app-api/`, Installation, Firmware | eine neue Version der Uhr-App |
| **App-Chat** (einer je App) | eine App in `packages/<name>/` | die Datei `<app-id>-<version>.g2app` und einen Pull Request |
| **du** | Uhr-App einmal installieren; Paket-Dateien auf die Uhr legen und dort installieren | – |

Braucht eine App etwas, das die Schnittstelle nicht kann (ein neuer Befehl, ein neues Ereignis), baut der
App-Chat das **nicht** selbst in die Uhr-App. Er beschreibt es, und der Hauptchat erweitert die Uhr-App
(neue Schnittstellen-Version, §5). So bleibt die Uhr-App an einer Stelle, und App-Chats kommen sich nicht in
die Quere.

## 2. Das Paket

Eine ZIP-Datei mit der Endung `.g2app`:

| Datei | Inhalt |
|---|---|
| `g2app.json` | Beschreibung, beim Bauen aus dem Manifest der App erzeugt |
| `classes.dex` (`classes2.dex` …) | der Code der App, für Android übersetzt; ohne Kotlin, kotlinx.serialization und die Schnittstelle, die hat die Uhr-App schon |
| `assets/…` | Dateien der App, gleich angeordnet wie in einer APK: `assets/apps/<app-id>/ui.json` (Seiten aus dem Baukasten), Bilder |

```json
{
  "format": "g2app-paket@1",
  "api": 1,
  "main": "ch.madtreasures.einkauf.ShoppingListApp",
  "id": "ch.madtreasures.einkauf",
  "name": "Einkauf",
  "version": "1.0.0",
  "input": "pointer",
  "permissions": [],
  "ui": "apps/ch.madtreasures.einkauf/ui.json",
  "description": "Einkaufsliste zum Abhaken, gespeichert auf der Uhr."
}
```

`api` ist die Schnittstellen-Version, gegen die gebaut wurde (§5), `main` die Klasse, die `G2App`
implementiert. Die übrigen Felder sind das `AppManifest` der App ([02 §2](02_App-Modell.md), [03 §1](03_Uhr-Apps.md)).

Grenzen: Datei höchstens 20 MB, entpackt höchstens 50 MB, höchstens 2000 Dateien, Pfade ohne `..`.

## 3. Ein Paket bauen

```
packages/<name>/
  build.gradle.kts                        ein Kommentar; nur für zusätzliche Bibliotheken mehr
  src/main/kotlin/…                       die App: genau eine Klasse, die G2App implementiert
  src/main/assets/apps/<app-id>/ui.json   Seiten aus dem Baukasten (wenn die App welche hat), Bilder
  src/test/kotlin/…                       Tests mit FakeAppContext (§6)
```

Jeder Ordner in `packages/` mit einer `build.gradle.kts` ist automatisch ein Paket; die gemeinsame
Bau-Logik steht in der `build.gradle.kts` im Hauptordner. Vorlagen: [`packages/stoppuhr`](../../packages/stoppuhr)
(nur Code) und [`packages/einkauf`](../../packages/einkauf) (Code und Seiten aus dem Baukasten).

```sh
./gradlew :packages:einkauf:g2app   # → packages/einkauf/build/g2app/ch.madtreasures.einkauf-1.0.0.g2app
./gradlew g2appPackages             # alle Pakete → build/g2app/
```

In Android Studio: rechts **Gradle** → *G2Watch → packages → einkauf → Tasks → g2app → g2app*
doppelklicken. Jeder Push baut außerdem alle Pakete auf GitHub (Actions → *Build* → Artefakt `g2-apps`).

Beim Bauen sucht der Schritt `g2appManifest` die eine Klasse, die `G2App` implementiert, legt sie an,
liest ihr Manifest und schreibt daraus `g2app.json`. Fehlt eine Seiten-Datei, die das Manifest nennt,
bricht er ab. Dann übersetzt D8 (die Version aus AGP 9.4) den Code nach DEX, und alles zusammen wird gezippt.

**Regeln für den Code:**

- Nur die Schnittstelle `ch.madtreasures.g2watch.apps` (`G2App`, `AppContext`, Ereignisse, Seiten …),
  die Kotlin-Standardbibliothek und kotlinx.serialization. Android-Klassen gibt es im Paket nicht: Es
  ist reines Kotlin, alles auf der Brille geht über `AppContext`. `AppsBoundaryTest` prüft die Imports.
- Eine weitere Bibliothek kommt als `implementation(...)` in die `build.gradle.kts` des Pakets und wird
  mit ins Paket gepackt. Keine Bibliothek, die die Uhr-App selbst enthält (etwa NewPipe, Media3): Deren
  Version aus der Uhr-App hätte Vorrang.
- Ein Konstruktor ohne Argumente (Kotlin: alle Parameter mit Vorgabewert).
- `when (event)` immer mit `else -> Unit`: Neue Schnittstellen-Versionen bringen neue Ereignisse.
- Die `id` bleibt für immer gleich: An ihr hängen der Speicher der App und die Berechtigungen.
- Bei jeder Änderung `version` erhöhen. Eine neue Version fragt die Berechtigungen neu ab.
- Eine `id`, die eine fest eingebaute App schon hat (zurzeit nur YouTube), nimmt die Uhr nicht an.

## 4. Auf die Uhr bringen und installieren

![Seite „Apps“ auf der Uhr](../bilder/uhr-apps.png)

1. Die Uhr ist mit Android Studio verbunden (Debugging über WLAN, wie beim Installieren der Uhr-App).
2. Die Datei in den Ordner für neue Apps legen:
   - **Android Studio:** *View → Tool Windows → Device Explorer*, die Uhr wählen, zu
     `sdcard/Android/data/ch.madtreasures.g2watch/files/apps/` gehen, Rechtsklick → *Upload* → die
     `.g2app`-Datei wählen.
   - **oder auf der Kommandozeile:**
     `adb push ch.madtreasures.einkauf-1.0.0.g2app /sdcard/Android/data/ch.madtreasures.g2watch/files/apps/`

   Den Ordner legt die Uhr-App ab Version 0.6.0 beim ersten Start selbst an.
3. Auf der Uhr: Zahnrad halten → **Einstellungen → Apps installieren**. Unter „Neu auf der Uhr“ steht die
   App mit Version; antippen installiert sie. Die Datei verschwindet aus dem Ordner, die App steht im
   Starter der Brille. Eine Datei, die die Uhr nicht annimmt, bleibt liegen und zeigt den Grund.

**Aktualisieren:** eine Datei mit höherer Version genauso installieren. Läuft die alte Version gerade,
endet sie („wurde aktualisiert“). Der Speicher der App bleibt.

**Entfernen:** unter „Installiert“ die App zweimal antippen. Läuft sie, endet sie („wurde entfernt“). Ihr
Speicher bleibt, falls sie wieder installiert wird.

**Was die Uhr vor dem Installieren prüft:** ZIP lesbar, Größen und Pfade, `g2app.json` gültig, Format
`g2app-paket@1`, Schnittstellen-Version nicht neuer als die der Uhr-App, keine `id` einer eingebauten App,
`classes.dex` ist DEX, die Seiten-Datei aus dem Manifest liegt bei. Entpackt wird in einen neuen Ordner,
der erst am Ende den alten ersetzt; eine fehlgeschlagene Aktualisierung lässt die alte Version stehen.

Installiert liegt jede App in `files/app-packages/<app-id>/` der Uhr-App, nicht von außen zugänglich.
Die DEX-Dateien sind schreibgeschützt, wie es Android ab Version 14 für geladenen Code verlangt.

## 5. Schnittstellen-Version

`G2AppApi.VERSION` in [`app-api/`](../../app-api) ist zurzeit **1**. Jedes Paket merkt sich, gegen welche
Version es gebaut ist. Die Uhr-App führt Pakete mit dieser oder einer älteren Version aus.

Deshalb **wächst die Schnittstelle nur**: neue Befehle, Ereignisse, Bausteine oder Felder mit Vorgabewert
kommen dazu, nichts wird umbenannt oder entfernt. Mit jeder Erweiterung steigt `VERSION` um eins. Ein Paket
für eine neuere Version lehnt die Uhr ab („braucht eine neuere Uhr-App“). Dann zuerst die Uhr-App
aktualisieren.

Technisch läuft ein Paket im Prozess der Uhr-App, mit einem eigenen Class-Loader (`DexClassLoader`),
dessen Eltern-Loader der der Uhr-App ist: Das Paket sieht Kotlin, kotlinx.serialization und die
Schnittstelle der Uhr-App, aber nichts von anderen Paketen. Ruft ein Paket etwas auf, das es in dieser
Uhr-App nicht gibt, endet nur diese App („ist abgestürzt“), der App-Host läuft weiter.

## 6. Tests

- Die Tests eines Pakets liegen in `packages/<name>/src/test/kotlin/` und laufen mit den Tests der
  Uhr-App (`./gradlew :app:testDebugUnitTest`). Dort gibt es `FakeAppContext` und, wo nötig, den echten
  `AppHost` mit Fakes ([03 §7](03_Uhr-Apps.md)).
- `InstalledPackagesTest` nimmt die Dateien, die `./gradlew :packages:<name>:g2app` wirklich baut,
  installiert sie in einen echten App-Host und startet sie. Nur den DEX-Schritt ersetzt die JVM: Sie führt
  dieselben Klassen aus dem Test-Klassenpfad aus.
- `PackageArchiveTest`, `PackageStoreTest` (Prüfen, Installieren, Aktualisieren, Entfernen, Ordner für
  neue Apps), `AppsScreenTest` (die Seite „Apps“ auf der Uhr), `PackageManifestTest` in `app-api`.

## 7. Grenzen

- Ein Paket läuft mit allen Rechten der Uhr-App (gleicher Prozess). Deshalb nur eigene Pakete
  installieren. Eine Signaturprüfung gibt es nicht; für den privaten Gebrauch reicht die Regel „nur eigene
  Dateien“.
- Pakete gibt es nur für Uhr-Apps. Even-Hub-Apps (05) und Rechner-Apps (04) werden anders installiert (06).
- Eine eingebaute App wird zum Paket, indem ihr Ordner von `app/src/main/java/…/apps/builtin/<name>/` nach
  `packages/<name>/src/main/kotlin/` wandert (eigenes Kotlin-Paket, z. B. `ch.madtreasures.youtube`), ihr
  Eintrag aus `builtInApps` verschwindet und ihre Tests nach `packages/<name>/src/test/kotlin/` ziehen. Das
  geht, solange sie nur die Schnittstelle benutzt; bei YouTube ist das so, die Video-Wiedergabe bleibt Teil
  der Uhr-App.
