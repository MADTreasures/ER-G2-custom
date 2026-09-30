# 03 – Uhr-Apps (Kotlin, auf der Pixel Watch)

Uhr-Apps sind kleine Kotlin-Klassen, die fest in die G2-Watch-App eingebaut werden. Sie laufen auch
ohne Rechner und ohne Netz. Für alles Rechenintensive: [Rechner-Apps](04_Rechner-Apps_und_Protokoll.md).

**Stand (v0.4.0):** Der App-Host ist gebaut (Meilenstein M1) und mit simulierter Brille getestet, **nicht auf
Hardware erprobt**. Eingebaut sind die Beispiel-Apps Stoppuhr und Einkaufsliste. Dieses Kapitel beschreibt
erst, was eine App-Entwicklerin schreibt (§1–§4), dann die Plattform, wie M1 sie gebaut hat (§5–§8).

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

(Vereinfacht; die eingebaute Fassung in `apps/builtin/stopwatch/StopwatchApp.kt` zeigt laufend „1:05“ und
angehalten „1:05,4“ und heißt nach dem Anhalten „Weiter“.)

Eintragen in die Liste der eingebauten Apps (`apps/AppRegistry.kt`), als Lambdas, weil ein Konstruktor mit
Vorgabewerten (hier `clock`) als Funktionsreferenz kein `() -> G2App` ist:

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
[02 §4 und §6](02_App-Modell.md#4-oberfläche-seiten-und-bausteine). Das JSON übersetzt `AppJson` von Hand auf dem
JSON-Baum von kotlinx.serialization (`JsonElement`), weil mehrere Kotlin-Klassen auf `kind: "sensor"` abgebildet
werden und ankommende Seiten so nachsichtig ergänzt werden wie im Baukasten (`BaukastenProject.normalize`);
`AppEventSerializer` und `AppCommandSerializer` machen daraus Serializer für `Json.encodeToString` usw.

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

## 5. Plattform: der App-Host (gebaut in M1)

```
Touchpad / Bügel / Ring ──▶ InputRouter ──▶ AppHost (Thread „G2Watch-apps“) ──▶ G2App.onEvent
                                              │  Sitzungen, Verlauf, Fokus, Timer, Berechtigungen,
                                              │  Starter, App-Menü
                                              ▼  (neuester Stand)
                                         PageRenderer ──▶ GrayRaster (App-Fläche)
                                              ▼
                     DesktopController (Thread „G2Watch-desktop“) ──▶ CoreDisplay ──▶ Brille
```

Die Dateien unter `app/src/main/java/ch/madtreasures/g2watch/apps/`:

| Datei | Aufgabe |
|---|---|
| `Page.kt`, `BaukastenProject.kt` | Seitenmodell mit allen Bausteinen (auch `image`), `normalize` wie im Baukasten, aber `@back` bleibt stehen; fehlende oder doppelte Kennungen bekommen mit Warnung eine neue (`b_1`, `p_2`, …) |
| `AppEvent.kt`, `AppCommand.kt`, `AppJson.kt` | Ereignisse und Befehle, JSON-Codec (auch für das Protokoll in M5) |
| `G2App.kt`, `AppContext.kt`, `AppManifest.kt` | die Schnittstelle aus §2 (Manifest mit Prüfung von Kennung, Name, Version) |
| `host/AppHost.kt` | Sitzungen, `start` + `visible`, Verlauf (Zurück), Fokus, Timer, Zeitmessung (50/500 ms), Berechtigungsabfrage, App-Menü, Starter, Zeichnen höchstens alle 200 ms; läuft auf dem Thread „G2Watch-apps“ (`ThreadScheduler`, in Tests `FakeScheduler`) |
| `host/Session.kt`, `host/HostContext.kt`, `host/PageCommands.kt` | Zustand einer Sitzung, `AppContext` des Hosts, Prüfung jedes Befehls (unbekannte Seite oder Kennung, falsche Werte → Fehler statt Absturz) |
| `host/InternalApp.kt` | Anschlüsse für interne Sitzungen und EvenHub (§5.2) |
| `host/AppPlatform.kt`, `host/AndroidAppPlatform.kt` | was der Host von Android braucht (Assets, Speicher je App, Vibration, HTTPS, Bilder dekodieren); in Tests ein Fake |
| `host/AppHttp.kt` | `fetch`: nur `https://`, 10 s, 1 MB |
| `host/InputRouter.kt` | Gesten von Uhr und Brille nach §5.1 in Zeiger, Fokus oder `gesture`-Ereignisse |
| `render/PageLayout.kt`, `render/PageRenderer.kt`, `render/Shapes.kt` | Seite + Zustand (Fokus, Scroll) → Pixel der App-Fläche, Maße aus [02 §4.2](02_App-Modell.md#42-bausteine); `PageLayout` kennt auch, welcher Baustein unter dem Zeiger liegt |
| `launcher/Launcher.kt` | der Starter: vom Host gezeichnet (keine `G2App`), eingebaute Apps, ab M3 Even-Hub-Apps, ab M5 Rechner-Apps; laufende mit „läuft“ markiert |
| `AppRegistry.kt` | eingebaute Apps (§1) |
| `builtin/…` | Beispiel-Apps: Stoppuhr, Einkaufsliste (Seiten aus `assets/apps/ch.madtreasures.einkauf/ui.json`) |

Einbau in den bestehenden Desktop (`desktop/`):
- Kachel **„Apps“** (`AppId.APPS`) öffnet den Starter. Die Kachel „Zeiger“ ist dafür entfallen; Tempo und
  „Zeiger zentrieren“ stehen in den Einstellungen der Uhr. „Info“ bleibt.
- Solange eine App offen ist, zeichnet `DesktopRenderer` die Kopfzeile: „‹“ (zurück), den **Namen der
  Seite** (antippen öffnet das App-Menü), Uhrzeit und Akkus; die App-Fläche kommt aus `PageRenderer`.
- Zeiger: im Modus `pointer` bleibt er die eigene Fläche „pointer“, im Modus `gestures` wird er
  ausgeblendet (`GlassesDisplay.setSurfaceVisible`, neu). Bleibt der Zeiger 0,6 s am oberen oder unteren Rand
  (24 px) einer langen Seite, rollt sie weiter, danach alle 0,9 s.
- `TouchpadScreen` hat einen Gesten-Modus, den der AppHost nach `input` der App ein- und ausschaltet:
  Wischen ab 36 dp (Finger nach oben = `scrollDown`, wie eine Seite nach oben schieben; links/rechts =
  `swipeLeft`/`swipeRight`), Tippen, Doppeltippen (400 ms), langes Drücken (0,9 s) mit Loslassen.

### 5.1 Gesten der Brille

`GlassesConnection.onRingEvent(kind, eventType, source)` gibt alle Eingaben an den `InputRouter`
(`translate(kind, eventType, eventSource, ringTick, ringType)`), der diese Tabelle umsetzt (nach
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
Fenster von 100 Ticks (wissen/03 §2.11.3); der `InputRouter` macht es ebenso (Typ 8 und 10 gehen immer
durch, Typ 127 wird verworfen, rückt aber die Uhr vor). Das Loslassen nach `shortThenLongPress` gehört zu
dieser Geste und wird wie in Faceclaw verworfen. Kopf-Heben meldet die Firmware nur, solange eine
Faceclaw-Seite angezeigt wird.

### 5.2 EvenHub-Sitzungen im App-Host

Even-Hub-Apps sind keine `G2App`. Der App-Host bekommt dafür in M1 schon die Anschlüsse, die M3 benutzt:

| Anschluss | Zweck |
|---|---|
| `EvenHubRegistry` | Liste der installierten Even-Hub-Apps (Name, Version, Ort Uhr/Handy, Rechte) für den Starter; in M1 leer |
| Sitzungsart „intern“ | wie der Starter: vom Host verwaltet, eigener Lebenszyklus, Start-Zeitlimit einstellbar (EvenHub: 20 s mit Seite „Startet …“) |
| `setRaster(blockId, raster)` | intern: schreibt ein fertiges Graustufen-Raster in einen randlosen Bild-Baustein, ohne PNG und ohne 48-KiB-Grenze |
| Brillen-Status | Akku, Laden, „getragen“ der Brille als beobachtbarer Wert (`AppHost.glasses`, `GlassesStatus`). Die Uhr-App ruft nach dem Verbinden `enableWearDetectionAndRequestState()` auf, sobald die Sitzung bereit ist. |
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

- `FakeAppContext` zeichnet alle Befehle auf. Eine App-Prüfung sieht so aus:
  ```kotlin
  val ui = FakeAppContext()
  app.onEvent(AppEvent.Start, ui)
  app.onEvent(AppEvent.Click("p_main", "startstop"), ui)
  assertEquals("Stopp", ui.page("p_main").textOf("startstop"))
  ```
- `PageRendererTest` (Maße, Fokus, Scroll) und `AppsSnapshotTest`: jede Bausteinart, lange Seiten mit
  Scroll, Fokus, Starter, App-Menü, Berechtigungsfrage, randloses Bild; Bilder nach `docs/bilder/apps-*.png`
  mit `-PsnapshotDir` (wie `WatchSnapshotTest`).
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
