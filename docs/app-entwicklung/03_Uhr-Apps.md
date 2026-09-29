# 03 – Uhr-Apps (Kotlin, auf der Pixel Watch)

Uhr-Apps sind kleine Kotlin-Klassen, die fest in die G2-Watch-App eingebaut werden. Sie laufen auch
ohne Rechner und ohne Netz. Für alles Rechenintensive: [Rechner-Apps](04_Rechner-Apps_und_Protokoll.md).

**Stand:** Der App-Host auf der Uhr existiert noch nicht (Meilenstein M1). Dieses Kapitel beschreibt
erst, was eine App-Entwicklerin schreibt (§1–§4), dann, was M1 an der Plattform bauen muss (§5–§8).

## 1. Eine Uhr-App schreiben

```kotlin
package ch.madtreasures.g2watch.apps.builtin.stopwatch

import ch.madtreasures.g2watch.apps.*

/** Start/stop stopwatch; shows tenths only while visible to keep the link quiet. */
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

Eintragen in die Liste der eingebauten Apps (`apps/AppRegistry.kt`):

```kotlin
val builtInApps: List<() -> G2App> = listOf(::StopwatchApp, ::ShoppingListApp)
```

Seiten aus dem Baukasten statt aus Code: `ui.json` nach `app/src/main/assets/apps/<app-id>/ui.json`
legen und mit `ui.definePages(BaukastenProject.fromAsset(context, "apps/<app-id>/ui.json"))` laden.

## 2. Die Schnittstelle

Paket `ch.madtreasures.g2watch.apps`. Die Typen entsprechen 1 : 1 den JSON-Formen in
[02 §4 und §6](02_App-Modell.md#4-oberfläche-seiten-und-bausteine) (kotlinx.serialization,
`@SerialName` = JSON-Namen).

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
    fun buzz(notes: List<Pair<Int, Int>>)                  // needs Permission.BUZZER
    fun timer(tag: String, ms: Long, repeat: Boolean = false)
    fun cancelTimer(tag: String)
    fun subscribe(sensor: Sensor, hz: Int = 10)            // needs the sensor's permission
    fun unsubscribe(sensor: Sensor)
    fun audio(on: Boolean)                                 // needs Permission.MIC
    fun fetch(request: HttpRequest, onResult: (HttpResult) -> Unit)  // needs Permission.NETWORK
    val storage: AppStorage                                // JSON values, ≤ 256 KiB per app
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
    data class Gesture(val gesture: GestureKind, val source: InputSource) : AppEvent
    data class Timer(val tag: String) : AppEvent
    data class Imu(val x: Float, val y: Float, val z: Float, val t: Long) : AppEvent
    data class Compass(val heading: Float, val t: Long) : AppEvent
    data class Light(val lux: Int, val t: Long) : AppEvent
    class Audio(val pcm: ShortArray, val seq: Int) : AppEvent   // 16 kHz mono
}
```

`fetch`: läuft auf einem Hintergrund-Thread, Ergebnis kommt auf dem App-Thread zurück. Grenzen:
10 s Zeitlimit, 1 MB Antwort, nur `https://`. Über LTE geht das auch ohne Handy.

## 3. Regeln für Uhr-Apps

- **50 ms** pro `onEvent`: Der Host misst mit. Über 50 ms gibt es einen Eintrag im Protokoll, über
  500 ms wird die App mit „reagiert zu langsam“ beendet. Längere Arbeit gehört auf den Rechner.
- Keine eigenen Threads, keine Android-Dienste, kein direkter Zugriff auf `GlassesConnection`,
  Bluetooth oder den Firmware-Pfad. Alles geht über `AppContext`.
- Speicher: keine großen Bitmaps halten; Bilder als `image`-Baustein (PNG) übergeben.
- Verdeckt (`Hidden`) laufen nur Timer weiter, und nur mit `Permission.BACKGROUND`; ohne sie hält
  der Host die Timer an.
- Jede App hat Unit-Tests mit `FakeAppContext` (§7).

## 4. Was eine Uhr-App nicht kann

- Keinen Code nachladen: Uhr-Apps sind Teil der APK. Wer Apps ohne neue APK installieren will, macht
  eine Rechner-App.
- Kein WebView, also keine EvenHub-Apps auf der Uhr ([05](05_EvenHub-Apps.md)).

---

## 5. Plattform: der App-Host (zu bauen in M1)

```
Touchpad / Bügel / Ring ──▶ InputRouter ──▶ AppHost (Thread „G2Watch-apps“) ──▶ G2App.onEvent
                                              │  Sitzungen, Verlauf, Fokus, Timer, Berechtigungen
                                              ▼  (neuester Stand)
                                         PageRenderer ──▶ GrayRaster (App-Fläche)
                                              ▼
                     DesktopController (Thread „G2Watch-desktop“) ──▶ CoreDisplay ──▶ Brille
```

Neue Dateien unter `app/src/main/java/ch/madtreasures/g2watch/apps/`:

| Datei | Aufgabe |
|---|---|
| `model/Page.kt`, `model/Block.kt`, `model/BaukastenProject.kt` | Seitenmodell, JSON (kotlinx.serialization), `normalize` wie im Baukasten |
| `model/AppEvent.kt`, `model/AppCommand.kt` | Ereignisse und Befehle, JSON-Codec (auch für das Protokoll in M2) |
| `G2App.kt`, `AppContext.kt`, `AppManifest.kt` | die Schnittstelle aus §2 |
| `host/AppHost.kt` | Sitzungen starten/stoppen, Verlauf (Zurück), Fokus, Timer, Zeitmessung (50/500 ms), Berechtigungsabfrage |
| `host/AppThread.kt` | ein Thread für alle Apps (`Scheduler`-Schnittstelle wie in `ThreadScheduler`) |
| `render/PageRenderer.kt` | Seite + Zustand (Fokus, Scroll, Zeiger) → Pixel der App-Fläche, Maße aus [02 §4.2](02_App-Modell.md#42-bausteine) |
| `render/Hit.kt` | welcher Baustein unter dem Zeiger liegt |
| `launcher/LauncherApp.kt` | die Seite „Apps“: Liste der eingebauten und der Rechner-Apps, selbst eine `G2App` |
| `AppRegistry.kt` | eingebaute Apps (§1) |
| `builtin/…` | Beispiel-Apps: Stoppuhr, Einkaufsliste |

Einbau in den bestehenden Desktop (`desktop/`):
- Neue Kachel **„Apps“** (`AppId.APPS`) öffnet den Starter. Die Kachelreihe hat 6 Plätze (3 × 2):
  „Zeiger“ und „Info“ wandern in die Einstellungen der Uhr oder in den Starter, damit „Apps“ Platz hat.
- Solange eine App offen ist, zeichnet `DesktopRenderer` die Kopfzeile (mit „‹“ und App-Name) und
  übernimmt für die App-Fläche das Raster aus `PageRenderer`. Der Zeiger bleibt im Modus `pointer` die
  eigene Fläche „pointer“, im Modus `gestures` wird er ausgeblendet (`setSurfaceVisible`).
- `TouchpadScreen` bekommt einen Gesten-Modus (Wischen in 4 Richtungen, Tippen, Doppeltippen, langes
  Drücken), den der AppHost ein- und ausschaltet.
- `GlassesConnection.onRingEvent` leitet alle Gesten an den `InputRouter` weiter (heute nur Klick und
  Doppelklick an den Desktop).

## 6. Sensoren und Mikrofon (M5)

`GlassesSessionCore` hat alles Nötige: `setImuReportEnabled` + `addImuListener`, `setCompassEnabled` +
`addCompassListener`, `setAmbientLightPolling` + Listener, `startG2AudioCapture` (16 kHz PCM),
`playBuzzerSequence`. Der AppHost schaltet sie nur ein, solange eine sichtbare App sie abonniert hat,
und schaltet sie beim Verdecken oder Beenden wieder aus (Akku).

## 7. Tests

- `FakeAppContext` zeichnet alle Befehle auf. Eine App-Prüfung sieht so aus:
  ```kotlin
  val ui = FakeAppContext()
  app.onEvent(AppEvent.Start, ui)
  app.onEvent(AppEvent.Click("p_main", "startstop"), ui)
  assertEquals("Stopp", ui.page("p_main").textOf("startstop"))
  ```
- `PageRendererSnapshotTest`: jede Bausteinart, lange Seiten mit Scroll, Fokus, Zeiger; Bilder nach
  `docs/bilder/apps-*.png` mit `-PsnapshotDir` (wie `WatchSnapshotTest`).
- `AppHostTest` mit `FakeScheduler`: Zurück auf der ersten Seite schließt, 500-ms-Grenze beendet,
  Timer stoppen im Hintergrund ohne `BACKGROUND`, Berechtigung verweigert → Fehler statt Stille.
- `FlashingBoundaryTest` bleibt grün: Apps berühren den Firmware-Pfad nicht.

## 8. Warum Kotlin auf der Uhr und kein Skript-Interpreter?

Ein eingebetteter JavaScript- oder Lua-Interpreter würde Apps ohne neue APK erlauben, kostet aber
Speicher, Akku und eine zweite Sicherheitsgrenze auf der Uhr. Die Rechner-Laufzeit deckt dynamische
Apps bereits ab. Wenn später ein Bedarf entsteht, kann ein Interpreter als weitere Laufzeit hinter
dieselbe `G2App`-Schnittstelle gelegt werden.
