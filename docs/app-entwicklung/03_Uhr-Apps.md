# 03 – Uhr-Apps (Kotlin, auf der Pixel Watch)

Uhr-Apps sind kleine Kotlin-Klassen, die fest in die G2-Watch-App eingebaut werden. Sie laufen auch
ohne Rechner und ohne Netz. Für alles Rechenintensive: [Rechner-Apps](04_Rechner-Apps_und_Protokoll.md).

**Stand:** Der App-Host ist gebaut (M1, v0.4.0, nicht auf Hardware erprobt). Dieses Kapitel beschreibt
erst, was eine App-Entwicklerin schreibt (§1–§4), dann die Plattform (§5–§8). Was bei der Umsetzung dazukam
oder anders wurde als ursprünglich geplant, steht in §9.

## 1. Eine Uhr-App schreiben

```kotlin
package ch.madtreasures.g2watch.apps.builtin.stopwatch

import ch.madtreasures.g2watch.apps.*

/** Start/stop stopwatch; ticks only while visible to keep the link quiet. */
class StopwatchApp(private val clock: () -> Long = System::currentTimeMillis) : G2App {

    override val manifest = AppManifest(
        id = "ch.madtreasures.stoppuhr",
        name = "Stoppuhr",
        version = "1.0.0",
    )

    private var startedAt: Long? = null
    private var elapsed = 0L

    override fun onEvent(event: AppEvent, ui: AppContext) {
        when (event) {
            AppEvent.Start -> {
                ui.definePages(listOf(page()))
                ui.show("p_main")
            }
            is AppEvent.Click -> when (event.block) {
                "startstop" -> toggle(ui)
                "reset" -> { startedAt = null; elapsed = 0; ui.cancelTimer("tick"); render(ui) }
            }
            is AppEvent.Timer -> render(ui)
            AppEvent.Hidden -> ui.cancelTimer("tick")
            AppEvent.Visible -> if (startedAt != null) ui.timer("tick", 500, repeat = true)
            else -> Unit
        }
    }

    private fun toggle(ui: AppContext) {
        val since = startedAt
        if (since == null) {
            startedAt = clock()
            ui.timer("tick", 500, repeat = true)
        } else {
            elapsed += clock() - since
            startedAt = null
            ui.cancelTimer("tick")
        }
        render(ui)
    }

    private fun render(ui: AppContext) {
        val total = elapsed + (startedAt?.let { clock() - it } ?: 0)
        ui.patch("p_main") {
            text("zeit", "%d:%02d,%d".format(total / 60_000, total / 1000 % 60, total / 100 % 10))
            text("startstop", if (startedAt == null) "Start" else "Stopp")
        }
    }

    private fun page() = Page(
        id = "p_main", name = "Stoppuhr",
        blocks = listOf(
            Block.Heading(id = "zeit", text = "0:00,0", size = HeadingSize.GROSS, align = Align.CENTER),
            Block.Button(id = "startstop", text = "Start"),
            Block.Button(id = "reset", text = "Zurücksetzen"),
        ),
    )
}
```

Eintragen in die Liste der eingebauten Apps (`apps/AppRegistry.kt`), in der Reihenfolge des Starters:

```kotlin
val builtInApps: List<() -> G2App> = listOf({ StopwatchApp() }, { ShoppingListApp() })
```

Seiten aus dem Baukasten statt aus Code: den Export nach `app/src/main/assets/apps/<app-id>/ui.json`
legen und im Manifest `ui = "apps/<app-id>/ui.json"` setzen. Der Host lädt die Datei vor `Start`; die App
ruft dann nur noch `show(...)` oder `patch(...)` auf.

## 2. Die Schnittstelle

Alle Typen, die eine App braucht, liegen direkt im Paket `ch.madtreasures.g2watch.apps`
(`G2App`, `AppContext`, `AppManifest`, `AppEvent`, `Page`, `Block`, `PatchBuilder`, …). Die
Unterpakete `host/`, `render/`, `launcher/` sind intern. Die Typen entsprechen den JSON-Formen in
[02 §4 und §6](02_App-Modell.md#4-oberfläche-seiten-und-bausteine). Für JSON gibt es eigene Serializer
(kotlinx.serialization, `JsonContentPolymorphicSerializer`, der nach `kind` und bei Sensoren nach `sensor`
unterscheidet), weil mehrere Kotlin-Klassen auf `kind: "sensor"` abgebildet werden.

```kotlin
interface G2App {
    val manifest: AppManifest
    /** Called on the app thread, never concurrently. Must return within 50 ms. */
    fun onEvent(event: AppEvent, ui: AppContext)
}

data class AppManifest(
    val id: String,
    val name: String,
    val version: String,
    val input: InputMode = InputMode.POINTER,
    val permissions: Set<Permission> = emptySet(),
    val ui: String? = null,                                // asset path of a Baukasten export
    val description: String = "",
)

interface AppContext {
    fun definePages(pages: List<Page>)
    fun definePages(project: BaukastenProject)
    fun show(pageId: String)
    fun replace(pageId: String)
    fun patch(pageId: String, changes: PatchBuilder.() -> Unit)
    fun setBlocks(pageId: String, blocks: List<Block>)
    fun toast(text: String, ms: Int = 2000)
    fun vibrate(pattern: Vibration = Vibration.TICK)
    fun menu(items: List<MenuItem>)                        // MenuItem(id, text); own entries in the app menu, ≤ 10
    fun buzz(notes: List<BuzzNote>)                        // BuzzNote(freqHz, dutyPercent, ms), ≤ 48; Permission.BUZZER
    fun timer(tag: String, ms: Long, repeat: Boolean = false)
    fun cancelTimer(tag: String)
    fun subscribe(sensor: Sensor, rate: Int = 0)           // needs the sensor's permission; 0 = default rate
    fun unsubscribe(sensor: Sensor)
    fun audio(on: Boolean)                                 // needs Permission.MIC
    fun fetch(request: HttpRequest, onResult: (HttpResult) -> Unit)  // needs Permission.NETWORK
    val storage: AppStorage                                // get(key): JsonElement?, set(key, JsonElement); ≤ 256 KiB per app
    fun log(message: String)                               // goes to Settings → Protokoll
    fun close()
}

sealed interface AppEvent {
    data object Start : AppEvent
    data object Visible : AppEvent
    data object Hidden : AppEvent
    data object Stop : AppEvent
    data class Click(val page: String, val block: String) : AppEvent
    data class Toggle(val page: String, val block: String, val on: Boolean) : AppEvent
    data class Check(val page: String, val block: String, val index: Int, val done: Boolean) : AppEvent
    data class Navigate(val from: String, val to: String, val block: String) : AppEvent
    data class Back(val page: String) : AppEvent
    data class Menu(val item: String) : AppEvent
    data class Gesture(val gesture: GestureKind, val source: InputSource) : AppEvent
    data class Timer(val tag: String) : AppEvent
    data class Imu(val x: Float, val y: Float, val z: Float, val t: Long) : AppEvent        // kind "sensor", sensor "imu"
    data class Compass(val heading: Float, val t: Long) : AppEvent                         // kind "sensor", sensor "compass"
    data class Location(val lat: Double, val lon: Double, val acc: Float, val t: Long) : AppEvent
    class Audio(val pcm: ShortArray, val seq: Int) : AppEvent                              // 16 kHz mono, 50 ms
}
```

Ungültige Befehle (unbekannte Seite oder Kennung, fehlende Berechtigung, falsche Werte) führt der Host
nicht aus und schreibt sie ins Protokoll; `FakeAppContext` wirft in Tests eine `IllegalArgumentException`.

`fetch`: läuft auf einem Hintergrund-Thread, Ergebnis kommt auf dem App-Thread zurück. Grenzen:
10 s Zeitlimit, 1 MB Antwort, nur `https://`. Über LTE geht das auch ohne Handy.

## 3. Regeln für Uhr-Apps

Diese Regeln gelten für Apps, nicht für Plattform-Teile wie die EvenHub-Laufzeit (die hat eigene Dienste,
Threads und GeckoView-Prozesse, [05](05_EvenHub-Apps.md)).

- **50 ms** pro `onEvent`: Der Host misst mit. Über 50 ms gibt es einen Eintrag im Protokoll, über
  500 ms wird die App mit „reagiert zu langsam“ beendet. Längere Arbeit gehört auf den Rechner.
- Keine eigenen Threads, keine Android-Dienste, kein direkter Zugriff auf `GlassesConnection`,
  Bluetooth oder den Firmware-Pfad. Alles geht über `AppContext`.
- Speicher: keine großen Bitmaps halten; Bilder als `image`-Baustein (PNG) übergeben.
- Verdeckt (`Hidden`) laufen nur Timer weiter, und nur mit `Permission.BACKGROUND`; ohne sie hält
  der Host die Timer an und setzt sie bei `Visible` fort.
- Jede App hat Unit-Tests mit `FakeAppContext` (§7).

## 4. Was eine Uhr-App nicht kann

- Keinen Code nachladen: Uhr-Apps sind Teil der APK. Wer Apps ohne neue APK installieren will, macht
  eine Rechner-App.
- Keine Web-Oberfläche: Web-Apps (Even-Hub-Apps) laufen in der EvenHub-Laufzeit mit eigener Engine
  ([05](05_EvenHub-Apps.md)), nicht als Uhr-App.

---

## 5. Plattform: der App-Host (zu bauen in M1)

```
Touchpad / Bügel / Ring ──▶ InputRouter ──▶ AppHost (Thread „G2Watch-apps“) ──▶ G2App.onEvent
                                              │  Sitzungen, Verlauf, Fokus, Timer, Berechtigungen,
                                              │  Starter, App-Menü
                                              ▼  (neuester Stand)
                                         PageRenderer ──▶ GrayRaster (App-Fläche)
                                              ▼
                     DesktopController (Thread „G2Watch-desktop“) ──▶ CoreDisplay ──▶ Brille
```

Neue Dateien unter `app/src/main/java/ch/madtreasures/g2watch/apps/`:

| Datei | Aufgabe |
|---|---|
| `Page.kt`, `Block.kt`, `BaukastenProject.kt` | Seitenmodell, JSON, `normalize` wie im Baukasten (Stand nach M0: projektweit eindeutige Kennungen, `@back` bleibt erhalten) |
| `AppEvent.kt`, `AppCommand.kt`, `AppJson.kt` | Ereignisse und Befehle, JSON-Codec (auch für das Protokoll in M5) |
| `G2App.kt`, `AppContext.kt`, `AppManifest.kt` | die Schnittstelle aus §2 |
| `host/AppHost.kt` | Sitzungen starten/stoppen, `start` + `visible`, Verlauf (Zurück), Fokus, Timer, Zeitmessung (50/500 ms), Berechtigungsabfrage, App-Menü |
| `host/AppThread.kt` | ein Thread für alle Apps (`Scheduler`-Schnittstelle wie `ThreadScheduler`) |
| `host/InputRouter.kt` | Gesten von Uhr und Brille nach §5.1 in Zeiger, Fokus oder `gesture`-Ereignisse |
| `render/PageRenderer.kt` | Seite + Zustand (Fokus, Scroll, Zeiger) → Pixel der App-Fläche, Maße aus [02 §4.2](02_App-Modell.md#42-bausteine) |
| `render/Hit.kt` | welcher Baustein unter dem Zeiger liegt |
| `launcher/Launcher.kt` | der Starter: vom Host gezeichnet (keine `G2App`), eingebaute Apps, ab M3 Even-Hub-Apps, ab M5 Rechner-Apps; laufende markiert |
| `AppRegistry.kt` | eingebaute Apps (§1) |
| `builtin/…` | Beispiel-Apps: Stoppuhr, Einkaufsliste |

Einbau in den bestehenden Desktop (`desktop/`):
- Neue Kachel **„Apps“** (`AppId.APPS`) öffnet den Starter. Die Kachelreihe hat 6 Plätze (3 × 2):
  „Zeiger“ und „Info“ wandern in die Einstellungen der Uhr oder in den Starter, damit „Apps“ Platz hat.
- Solange eine App offen ist, zeichnet `DesktopRenderer` die Kopfzeile (mit „‹“ und App-Name) und
  übernimmt für die App-Fläche das Raster aus `PageRenderer`.
- Zeiger: im Modus `pointer` bleibt er die eigene Fläche „pointer“, im Modus `gestures` wird er
  ausgeblendet. `setSurfaceVisible` gibt es bisher nur an `GlassesSessionCore`; die Schnittstelle
  `GlassesDisplay` (mit `CoreDisplay` und den Test-Fakes) bekommt dafür eine neue Methode.
- `TouchpadScreen` bekommt einen Gesten-Modus (Wischen in 4 Richtungen, Tippen, Doppeltippen, langes
  Drücken), den der AppHost ein- und ausschaltet.

### 5.1 Gesten der Brille

`GlassesConnection.onRingEvent(kind, eventType, source)` bekommt heute alle Eingaben, lässt aber alles
außer `kind == "sys-event"` fallen. Der `InputRouter` übernimmt stattdessen diese Tabelle (nach
wissen/03 §2.11.2, der Übersetzung in Faceclaw):

| `kind` | Code | Geste | `source` |
|---|---|---|---|
| `sys-event` | 0 | `click` | 1 → `right`, 2 → `ring`, 3 → `left` |
| `sys-event` | 3 | `doubleClick` | wie oben |
| `sys-event` | 1 / 2 | `scrollUp` / `scrollDown` | nur wenn angegeben, sonst `unknown` |
| `sys-event` | 9 / 10 | `longPress` / `longPressRelease` | wie oben |
| `sys-event` | 11 | `shortThenLongPress` (→ App-Menü) | wie oben |
| `sys-event` | 14 | `press` (Berührung beginnt) | Quelle 0 oder 2 → `ring`, sonst `unknown` |
| `text-click` | 1 / 2 | `scrollUp` / `scrollDown` | `unknown` (Wischen am Bügel kommt ohne Quelle) |
| `display-wake` | 12 | `headUp` | – |

Ring-Ereignisse kommen doppelt vor (über die Brille und direkt). Faceclaw entfernt Doppelte in einem
Fenster von 100 Ticks (wissen/03 §2.11.3); der `InputRouter` macht es ebenso. Kopf-Heben meldet die
Firmware nur, solange eine Faceclaw-Seite angezeigt wird.

### 5.2 EvenHub-Sitzungen im App-Host

Even-Hub-Apps sind keine `G2App`. Der App-Host bekommt dafür in M1 schon die Anschlüsse, die M3 benutzt:

| Anschluss | Zweck |
|---|---|
| `EvenHubRegistry` | Liste der installierten Even-Hub-Apps (Name, Version, Ort Uhr/Handy, Rechte) für den Starter; in M1 leer |
| Sitzungsart „intern“ | wie der Starter: vom Host verwaltet, eigener Lebenszyklus, Start-Zeitlimit einstellbar (EvenHub: 20 s mit Seite „Startet …“) |
| `setRaster(blockId, raster)` | intern: schreibt ein fertiges Graustufen-Raster in einen randlosen Bild-Baustein, ohne PNG und ohne 48-KiB-Grenze |
| Brillen-Status | Akku, Laden, „getragen“ der Brille als beobachtbarer Wert. Akku und Laden stehen heute schon in `GlassesState`; „getragen“ braucht `enableWearDetectionAndRequestState()`, das die Uhr-App bisher nicht aufruft. |
| App-Menü-Einträge | eine interne Sitzung kann eigene Einträge setzen und bekommt die Auswahl zurück |

## 6. Sensoren, Mikrofon, Summer (M6)

Alles liegt in `GlassesSessionCore`. Der AppHost schaltet es nur ein, solange eine sichtbare App es
abonniert hat, und beim Verdecken oder Beenden wieder aus (Akku).

- **IMU:** `setImuReportEnabled(true, pace)` + `addImuListener`; `pace` ist ein Firmware-Code 100–1000.
- **Kompass:** `setCompassEnabled(owner, true)` + `addCompassListener`.
- **Mikrofon:** `startG2AudioCapture(listener)` liefert **LC3-Pakete** zu 205 Byte (5 × 40 Byte LC3 + Zähler),
  vom linken Glas, mit möglichen Doppeln vom rechten → nach Zähler aussortieren. Es gelingt nur, wenn die
  Sitzung bereit ist (`fixedLayoutCreated`), und endet still bei Pause, Laden oder Neuverbindung; der
  AppHost startet es danach neu, solange die App `audio(true)` hat.
  - Für **Rechner-Apps** gehen die Pakete unverändert über das Protokoll; der Rechner entschlüsselt.
  - Für **Uhr-Apps** entschlüsselt die Uhr mit liblc3 (Apache-2.0) über JNI zu 16-kHz-PCM, wie Faceclaw
    (`libfaceclaw_lc3.so`, wissen/03 §7.4).
- **Summer:** `playBuzzerSequence(bytes)` mit `[5][4][n]` und je Schritt `[freqLo, freqHi, duty, msLo, msHi]`,
  höchstens 48 Schritte.
- **Umgebungslicht:** Auf der Faceclaw-Firmware gehört der Lichtsensor der Helligkeitsregelung
  (`setAmbientLightPolling` wirkt dort nicht). Erst anbieten, wenn geklärt ist, wie Werte ohne Eingriff in
  die Regelung lesbar sind; bis dahin kein `light`.
- **Standort:** GPS der Uhr (`FusedLocationProviderClient` oder `LocationManager`), nur mit
  Berechtigung `location` und der Android-Standortberechtigung.

## 7. Tests

- `FakeAppContext` (Testquellen, Paket `ch.madtreasures.g2watch.apps`) führt die Seiten mit demselben
  `PageState` wie der Host und zeichnet alles andere auf (Timer, Hinweise, Menü, Anfragen, Protokoll).
  `FakeAppContext.forApp(app)` lädt vorher die Baukasten-Seiten aus `src/main/assets`, wie der Host.
  Eine App-Prüfung sieht so aus:
  ```kotlin
  val ui = FakeAppContext.forApp(app)
  app.onEvent(AppEvent.Start, ui)
  app.onEvent(AppEvent.Click("p_main", "startstop"), ui)
  assertEquals("Stopp", ui.page("p_main").textOf("startstop"))   // auch valueOf(…), itemsOf(…)
  ```
- `PageRendererSnapshotTest`: jede Bausteinart, lange Seiten mit Scroll, Fokus, Zeiger, randloses Bild;
  Bilder nach `docs/bilder/apps-*.png` mit `-PsnapshotDir` (wie `WatchSnapshotTest`).
- `AppHostTest` mit `FakeScheduler`: `start` gefolgt von `visible`, Zurück auf der ersten Seite schließt,
  500-ms-Grenze beendet, Timer ruhen im Hintergrund ohne `BACKGROUND`, Berechtigung verweigert → Fehler statt
  Stille, App-Menü „Apps“ verdeckt die App und der Starter holt sie zurück.
- `InputRouterTest`: jede Zeile der Tabelle §5.1, doppelte Ring-Ereignisse.
- `FlashingBoundaryTest` bleibt grün: Apps berühren den Firmware-Pfad nicht.

## 8. Warum Kotlin auf der Uhr und kein Skript-Interpreter?

Ein eingebetteter JavaScript- oder Lua-Interpreter würde Apps ohne neue APK erlauben, kostet aber
Speicher, Akku und eine zweite Sicherheitsgrenze auf der Uhr. Die Rechner-Laufzeit deckt dynamische
Apps bereits ab. Wenn später ein Bedarf entsteht, kann ein Interpreter als weitere Laufzeit hinter
dieselbe `G2App`-Schnittstelle gelegt werden.

## 9. Umsetzung in v0.4.0 (M1) – was dazukam oder abweicht

| Punkt | So ist es gebaut |
|---|---|
| Dateien | wie in §5; dazu `host/PageState.kt` (Seitenstand, von Host und `FakeAppContext` geteilt), `host/HostPorts.kt` (Plattform-Anschlüsse, Brillen-Status, interne Sitzungen, `EvenHubRegistry`), `host/AndroidHostPorts.kt`, `host/FileAppStorage.kt`, `render/PageLayout.kt`, `render/Shapes.kt` (kantengeglättete Formen), `render/GrayImages.kt`, `desktop/AppView.kt` |
| JSON | `AppJson` mit eigenen Serializern (`AppEventSerializer`, `AppCommandSerializer`) statt `JsonContentPolymorphicSerializer`: Ereignisse werden nach `kind` und bei Sensoren nach `sensor` unterschieden. Streng: kaputte Ereignisse/Befehle sind ein Fehler (`bad_value`), nur unbekannte `kind` werden ignoriert. `definePages` nimmt `pages` oder ein ganzes Baukasten-Projekt (`project`). |
| Desktop | Die Kachel **„Apps“** ersetzt „Zeiger“ (Tempo und Zentrieren gibt es in den Einstellungen der Uhr und an der Krone). „Info“ bleibt, weil die sechs Plätze reichen. |
| Kopfzeile | „‹“, App-Name fett, dahinter „· Seitenname“, wenn er vom App-Namen abweicht; Uhrzeit und Akkus wie auf dem Desktop. Klick auf „‹“ = Zurück, auf den Namen = App-Menü. |
| Maße | wie 02 §4.2, dazu 8 px Innenabstand oben und unten; ein randloses Bild als erster Baustein beginnt ganz oben. Toggle-Schalter 46 × 26, Häkchen-Kästchen 22 × 22, Fokus als heller Rahmen (Knöpfe 3 px, Zeilen 2 px). |
| Zeiger und Scrollen | Was unter dem Zeiger liegt, bekommt den Fokus; über leerer Fläche bleibt der Fokus. Den Zeiger über den oberen/unteren Rand des sichtbaren Streifens hinaus zu schieben scrollt die Seite. |
| Fokus per Bügel | Wischen springt zum nächsten fokussierbaren Baustein, wenn er höchstens ¾ Seite entfernt ist; sonst scrollt die Seite um ¾ Höhe (lange Texte lesen). Eine neue Seite fokussiert ihren ersten sichtbaren Knopf. |
| Gesten-Modus der Uhr | Finger nach oben = `scrollDown` (das Nächste, wie am Handy), nach unten = `scrollUp`, links/rechts = `swipeLeft`/`swipeRight` (rechts = Zurück). Tippen wird erst nach 400 ms ohne zweites Tippen zum `click`. Auf der Uhr steht „Gesten“. Das App-Menü wird auch im Gesten-Modus mit Wischen und Tippen bedient. |
| Abonnierte Gesten | Eine `pointer`-App mit `subscribe(GESTURES)` bekommt nur die Gesten, die der Host nicht selbst braucht (lang drücken/loslassen, `press`, `headUp`, `swipeLeft`). |
| Zurück | Die App bekommt `back` vor dem Seitenwechsel; was sie währenddessen am Verlauf ändert, wird verworfen – Zurück verlässt die Seite immer. |
| Timer | wiederholte Timer mindestens 50 ms; verdeckt ohne `background` pausiert (Restzeit bleibt). |
| 2-s-Regel | ohne Seite nach 2 s: Baukasten-Startseite, sonst Seite „App antwortet nicht“ mit „Schließen“; Ende nach 10 s. Interne Sitzungen: eigenes Zeitlimit (Even Hub: 20 s mit „Startet …“), danach 8 s bis zum Ende. |
| M6-Befehle | `subscribe` (außer `gestures`), `audio`, `buzz` prüfen Berechtigung und Werte und merken sich das Abo; bis M6 steht einmal „… ist noch nicht angeschlossen (kommt mit M6)“ im Protokoll. |
| Speicher | eine JSON-Datei je App unter `files/apps/<id>/store.json`, höchstens 256 KiB; mehr wird abgelehnt. |
| Bilder | `data:image/…;base64,…` und `asset:<datei>` (aus `assets/apps/<app-id>/`), dekodiert mit Android, in Helligkeit umgerechnet (Transparenz = schwarz) und auf `w × h` skaliert. |
| Interne Sitzungen (§5.2) | `InternalSession` + `InternalContext` (= `AppContext` + `setRaster`, `glasses`), `onGlassesStatus`, `startTimeoutMs`, `startingText`, `ownsDoubleClick` (Doppeltippen geht an die App). Der Starter ist selbst so eine Sitzung (`launcher/Launcher.kt`). `EvenHubRegistry.NONE` bis M3. |
| Brillen-Status | `AppHost.glassesStatus` (verbunden, Akku, Laden, getragen); die Uhr schaltet beim Verbinden die Trageerkennung ein (`enableWearDetectionAndRequestState`). |
| Beobachtbar | `AppHost.inputMode` schaltet das Touchpad; `AppHost.state` (sichtbare App, Seite, Fokus, Scroll, laufende Apps) für die Uhr-Oberfläche und Tests. |
