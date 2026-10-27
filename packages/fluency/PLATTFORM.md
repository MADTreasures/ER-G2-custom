# Fluency auf der Brille – wie Uhr, Handy und Paket zusammenhängen

Für den **Hauptchat** (Uhr-App G2 Watch) und für den Chat, der **Fluency** auf dem Handy erweitert (Repo
`MADTreasures/playground`). Stand: App-Paket Fluency 1.1.0, Schnittstelle 4, Uhr-App und „G2 Handy“ 0.10.0,
Fluency 1.9.0. Alle vier Teile sind gebaut und ohne Hardware getestet (simulierte Datenschicht, Fakes,
Robolectric); **nichts davon ist auf Hardware erprobt** (§10).

## 1. Worum es geht

```
                         Untertitel                      Nachrichten (JSON)
  Brille  ◀─────────────────────────  Uhr: G2 Watch  ◀───────────────────────▶  Handy: Fluency
                                       + Paket „Fluency“                          Spracherkennung,
                                       Mikrofon der Uhr ──── Ton (16 kHz) ─────▶  Übersetzung (offline)
```

Fluency rechnet auf dem Handy (Parakeet/Whisper, Hy-MT2 auf der NPU); für die Uhr wäre das viel zu schwer.
Das Paket zeigt die Übersetzung als rollenden Untertitel auf der Brille und steuert Fluency. Der Ton kommt
aus einer von zwei Quellen:

- **Uhr-Mikrofon:** G2 Watch nimmt auf und streamt den Ton zu Fluency (Gegenüber, Durchsagen, Fernseher).
- **Handy-Mikrofon:** Fluency nimmt selbst auf, wie in seinem Live-Modus (Personen oder Medien beim Handy).

## 2. Die Teile – Kurzfassung

| Teil | Stand | Wo |
|---|---|---|
| **A** Verbindung eines Pakets zu einer App auf dem Handy | gebaut in G2 Watch 0.10.0 (Schnittstelle 4, §5–§6) | `app-api`, `app/…/apps/phone/`, `phone-link/`, `phone/` („G2 Handy“) |
| **B** Mikrofon der Uhr als Tonquelle für das Handy | gebaut in G2 Watch 0.10.0 (§6.3) | `app/…/apps/phone/WatchMicrophone.kt` |
| **C** Gegenstück in Fluency (Nachrichten, Ton von der Uhr, Ergebnisse zurück) | gebaut in Fluency 1.9.0 (§8) | Repo `playground` |
| **D** Anschluss im Paket | gebaut im Paket 1.1.0 (§9) | `WatchPhoneLink` in `PhoneLink.kt` |

Was es zum Ausprobieren braucht: G2 Watch ab 0.10.0 auf der Uhr, „G2 Handy“ 0.10.0 und Fluency 1.9.0 auf dem
gekoppelten Handy, beide G2-Apps mit dem Schlüssel aus `signing/` gebaut (`signing/README.md`), in Fluency
**Optionen → „Brille (G2 Watch)“** an. Der **Probelauf** des Pakets (erfundenes Gespräch, gleiche Untertitel)
geht weiter ohne Handy.

## 3. Warum es Schnittstelle 4 braucht (mit 3 ging es nicht)

- **`fetch`** erreicht nur `https://`-Adressen im Internet (10 s, 1 MB). Fluency läuft offline auf dem Handy;
  ein Umweg über einen Server im Netz wäre langsam und schlecht für die Privatsphäre.
- **`web`** (GeckoView) könnte eine Seite zeigen, die Fluency im WLAN anbietet, aber `CapturePlan` macht neue
  Bilder höchstens alle 2 s und nur bis 1 min nach der letzten Eingabe – für Untertitel unbrauchbar. Ton von
  der Uhr gäbe es so auch nicht, und es ginge nur im selben WLAN.
- **`audio(on)`** ist das Mikrofon der Brille, kommt erst mit M6, und das Paket hätte keinen Weg, die Daten
  zum Handy zu bringen. Das Mikrofon der Uhr kennt die Schnittstelle gar nicht.
- **Wear-OS-Datenschicht:** Google Play Services gibt Nachrichten nur zwischen Apps mit **derselben App-ID
  und demselben Signaturschlüssel** weiter ([Data Layer, Android Developers](https://developer.android.com/training/wearables/data/overview)).
  G2 Watch ist `ch.madtreasures.g2watch`, Fluency `ch.madtreasures.fluency`: Direkt geht es nicht.

## 4. Wege für A – Vergleich und Empfehlung

| Weg | Wie | Gut | Schwierig | Größe |
|---|---|---|---|---|
| **1 Datenschicht + „G2 Handy“ als Vermittler** (Empfehlung) | Uhr ⇄ `MessageClient`/`ChannelClient` ⇄ „G2 Handy“ (App-ID `ch.madtreasures.g2watch` auf dem Handy, wie für M4 geplant, 05 §6) ⇄ Android-IPC ⇄ Fluency | Googles vorgesehener Weg; wählt selbst Bluetooth oder WLAN, geht auch unterwegs; der Kern von M4 (Handy-Modul, gemeinsamer Schlüssel, Datenschicht) wird ohnehin gebraucht | eine zweite App auf dem Handy; Uhr- und Handy-App mit demselben Schlüssel; zwei Sprünge auf dem Handy (Android-intern, schnell) | groß: ≈ 1.500–2.000 Zeilen mit Tests |
| 2 Bluetooth LE direkt | Fluency als GATT-Server (oder L2CAP-Kanal), G2 Watch verbindet sich wie mit der Brille | keine zweite Handy-App, keine Play Services | drei BLE-Verbindungen zugleich an der Uhr (zwei Gläser und das Handy): ob das den ≈ 41 KiB/s der Brille schadet, zeigt nur die Hardware; eigener Übertragungscode auf beiden Seiten | mittel: ≈ 1.000–1.200 |
| 3 WLAN | WebSocket von der Uhr zu Fluency, gefunden per mDNS (wie der Rechner-Client in M5, 01 §4) | bekannte Technik, viel Bandbreite | nur im selben WLAN oder am Hotspot des Handys; WLAN kostet Akku auf der Uhr | mittel, überschneidet sich mit M5 |
| 4 Bluetooth klassisch (RFCOMM) | Socket direkt zwischen Uhr und Handy | einfach | auf Wear-OS-Uhren unsicher ([Fehlschläge auf einer Galaxy Watch](https://forum.developer.samsung.com/t/galaxy-watch3-send-data-to-android-app-through-bluetooth-rfcomm-fail/10273)) | nicht empfohlen |

**Gewählt und gebaut: Weg 1.** Er ist der einzige, den Google für Uhr ⇄ Handy vorsieht, und er braucht keine
Annahme über die Hardware, die erst getestet werden müsste. Die Handy-App „G2 Handy“ vermittelt zunächst nur
(dieses Dokument) und bekommt die Even-Hub-Engine von M4 später dazu.

## 5. Schnittstelle 4 für Pakete (gebaut, G2 Watch 0.10.0)

Die Schnittstelle wächst nur (09 §5): neue Klassen, eine neue Methode, ein neues Ereignis, zwei neue
Berechtigungen; `G2AppApi.VERSION` = 4. Im Code: `app-api/…/apps/Phone.kt`, `AppContext.phone`,
`AppEvent.Phone`, `AppCommand.Phone`, `Permission.PHONE` und `WATCH_MIC`.

```kotlin
// AppContext
/** Talks to app [PhoneAction.Connect.partner] on the phone the watch is paired with. Since interface version 4. */
fun phone(action: PhoneAction) {}

sealed interface PhoneAction {
    /** Connects to the app with this Android package name on the phone; the outcome comes as AppEvent.Phone. Needs Permission.PHONE. */
    data class Connect(val partner: String) : PhoneAction
    /** One message (UTF-8 text, here JSON) to the partner, at most 64 KiB; only while connected; order is kept. */
    data class Send(val text: String) : PhoneAction
    /** Streams the watch's microphone to the partner (16 kHz mono PCM), or stops. Needs Permission.WATCH_MIC. */
    data class Microphone(val on: Boolean) : PhoneAction
    data object Disconnect : PhoneAction
}

// AppEvent, only after Connect
/** [state] CONNECTED, MESSAGE (with [text]) or DISCONNECTED (with a German [message] why). */
data class Phone(val state: PhoneState, val text: String? = null, val message: String? = null) : AppEvent

enum class PhoneState(val json: String) { CONNECTED("connected"), MESSAGE("message"), DISCONNECTED("disconnected") }

// Permission
PHONE("phone", "Verbindung zum Handy"),
WATCH_MIC("watch-mic", "Mikrofon der Uhr"),
```

**Regeln im Host:**

- Eine Verbindung je Sitzung; sie endet mit der App (`Disconnect` implizit). Ein zweites `Connect` mit
  demselben Partner während einer bestehenden Verbindung tut nichts; eines mit einem anderen Partner ersetzt
  sie.
- Verdeckt bleibt die Verbindung, aber **das Mikrofon stoppt** (Akku, Privatsphäre) und läuft beim nächsten
  `visible` nicht von selbst weiter; das Paket schaltet es wieder ein (§9). `Microphone(true)` geht nur,
  solange die App zu sehen ist.
- Jedes `Connect` hat ein Ergebnis, das die App hört: `Phone(CONNECTED)` oder `Phone(DISCONNECTED, message)`,
  auch wenn der Träger die Berechtigung nicht gab („Verbindung zum Handy nicht erlaubt“). Gründe, die so
  kommen: „Kein Handy mit G2 Handy erreichbar“, „Das Handy antwortet nicht“ (15 s), „Fluency ist auf dem
  Handy nicht installiert oder nimmt keine Verbindung an“, „Die App auf dem Handy antwortet nicht“ (10 s),
  „Keine Verbindung zum Handy“ (zwei Lebenszeichen ohne Antwort), oder was die Partner-App selbst sagt.
  Wiederverbinden versucht der Host nicht von selbst; das Paket ruft `Connect` erneut (auf Tippen).
- Hinweise auf der Brille statt Ereignisse: Mikrofon der Uhr ohne Android-Erlaubnis („Mikrofon der Uhr:
  bitte auf der Uhr erlauben“, die Uhr fragt dann auf ihrem Bildschirm), ohne Berechtigung der App
  („Mikrofon der Uhr nicht erlaubt“), Ton-Kanal ging nicht auf.
- Nachrichten höchstens 64 KiB und 20 je Sekunde von der App ans Handy; größere Mengen zusammenfassen (der
  Partner tut das schon, §7).
- JSON-Formen für Rechner-Apps (M5), wie die übrigen Befehle: `{ "c": "phone", "action": "connect", "partner": "ch.madtreasures.fluency" }`,
  `{ "c": "phone", "action": "send", "text": "…" }`, `{ "c": "phone", "action": "microphone", "on": true }`,
  `{ "c": "phone", "action": "disconnect" }`; Ereignis `{ "kind": "phone", "state": "message", "text": "…" }`.
- Tests ohne Hardware: `FakePhoneEngine` und `FakePhoneLine` in den Host-Fakes (`AppHostPhoneTest`);
  `FakeAppContext` zeichnet `phone`-Befehle auf und lässt Tests antworten (`phoneEvent`).

## 6. Übertragung über Weg 1 (gebaut)

### 6.1 Uhr ⇄ „G2 Handy“ (Wear-OS-Datenschicht)

- Fähigkeiten `g2_watch` (Uhr) und `g2_handy` (Handy) in `res/values/wear.xml`, gefunden über `CapabilityClient`
  (wie 05 §6).
- `MessageClient`, Pfade `/g2/link/<partner>/<typ>`:

  | Typ | Richtung | Inhalt |
  |---|---|---|
  | `open` | Uhr → Handy | `{ "app": "<paket-id>", "version": "1.1.0" }`; „G2 Handy“ bindet den Dienst des Partners (§6.2) |
  | `opened` / `failed` | Handy → Uhr | Verbindung steht, oder `{ "message": "…" }` (Partner fehlt, lehnt ab, Bindung scheitert) |
  | `msg` | beide | eine Nachricht der App (UTF-8), unverändert durchgereicht |
  | `close` | beide | Verbindung zu Ende, `{ "message": "…" }` oder leer |
  | `ping` | Uhr → Handy, als `sendRequest` alle 10 s | Antwort `alive` oder `gone` („G2 Handy“ kennt die Verbindung nicht mehr, etwa nach einem Neustart) |

  Zwei Lebenszeichen ohne Antwort → `DISCONNECTED`. Eine `msg` für eine Verbindung, die „G2 Handy“ nicht
  kennt, beantwortet es mit `close`. Pfade und Inhalte stehen an einer Stelle, im Modul `phone-link`
  (`PhoneLinkWire`), das beide Apps benutzen.
- `ChannelClient`, Pfad `/g2/link/<partner>/audio`: der Ton der Uhr als Strom, **PCM s16le, 16 kHz, mono**, in
  Blöcken zu 20 ms (640 Byte), roh hintereinander. 32 KB/s; über Bluetooth gibt die Datenschicht grob
  50–200 KB/s (01 §3). Wird es eng (das Funkmodul teilt sich die Uhr mit der Brille), kommt später Opus
  (`MediaCodec`, ≈ 3 KB/s) als Option dazu; der Pfad bekommt dann ein `?codec=opus`.

### 6.2 „G2 Handy“ ⇄ Partner-App (Android-IPC)

- Der Partner exportiert einen Dienst mit der Intent-Aktion **`ch.madtreasures.g2watch.action.LINK`**;
  „G2 Handy“ bindet ihn mit `setPackage(partner)` und `BIND_AUTO_CREATE`. Damit G2 Handy die Partner sieht
  (Android 11+), braucht sein Manifest `<queries><intent><action android:name="ch.madtreasures.g2watch.action.LINK" /></intent></queries>`.
- `Messenger`, jede Nachricht mit `replyTo` = dem Messenger von G2 Handy. So umgesetzt in Fluency
  (`GlassesService`):

  | `what` | Wert | Richtung | Bundle |
  |---|---|---|---|
  | `OPEN` | 1 | G2 Handy → Partner | `app` (Paket-ID auf der Uhr), `version` (seine Version) |
  | `OPENED` | 2 | Partner → G2 Handy | – |
  | `MSG` | 3 | beide | `text`: eine Nachricht der App, unverändert |
  | `CLOSE` | 4 | beide | `message` (optional): warum. Lehnt der Partner ein `OPEN` ab, antwortet er mit `CLOSE` statt `OPENED` → auf der Uhr `failed` |
  | `AUDIO_START` | 5 | G2 Handy → Partner | `pfd`: Leseende einer Pipe (`ParcelFileDescriptor`) mit dem PCM-Strom, `rate` = 16000 |
  | `AUDIO_STOP` | 6 | G2 Handy → Partner | – |

- Der Partner prüft den Aufrufer an `Message.sendingUid` (nicht `Binder.getCallingUid()`: Ein `Messenger`
  bearbeitet die Nachricht später auf seinem Looper, dort ist das die eigene UID): Paket
  `ch.madtreasures.g2watch`. Fluency merkt sich dessen Zertifikat (SHA-256) beim ersten Verbinden und nimmt
  danach nur noch dieses an; einen gemeinsamen Schlüssel braucht es dafür nicht. Spätere Nachrichten müssen von
  derselben UID kommen wie das `OPEN`.
- „G2 Handy“ hält einen Vordergrund-Dienst (`connectedDevice`), solange sein Schalter **„Für die Uhr bereit“**
  an ist (Standard; auch nach einem Neustart des Handys), und zeigt auf seiner Seite, ob die Uhr erreichbar ist
  und welche Partner verbunden sind. Dauernd, weil Android 12+ einen Vordergrund-Dienst nicht aus dem
  Hintergrund starten lässt; ohne ihn könnte Android G2 Handy mitten im Gespräch beenden.
- „G2 Handy“ hat die App-ID der Uhr-App (`ch.madtreasures.g2watch`, sonst stellt die Datenschicht nichts zu)
  und deshalb im Projekt ein eigenes Gradle-Modul `phone/`, das nur mit `-Pg2.phone=true` gebaut wird: So
  landet es nie aus Versehen auf der Uhr.

### 6.3 Mikrofon der Uhr (Teil B)

- `AudioRecord` mit `MediaRecorder.AudioSource.VOICE_RECOGNITION` (ohne automatische Pegelregelung, wie
  Fluency auf dem Handy), 16 kHz mono, 16 Bit.
- Laufzeit-Berechtigung `RECORD_AUDIO` auf der Uhr: Die Uhr fragt auf ihrem Bildschirm, sobald ein Paket das
  Mikrofon zum ersten Mal einschaltet, und startet es nach dem Ja. Der Vordergrund-Dienst der Uhr-App
  (`GlassesService`) hat dann zusätzlich den Typ `microphone`.
- Nur solange die App sichtbar ist (§5); bei Verbindungsverlust stoppen. Endet der Ton-Kanal, öffnet das
  nächste `Microphone(true)` einen neuen.
- Später (M6) kann `Microphone` eine Quelle bekommen (`WATCH`/`GLASSES`); das Brillen-Mikrofon liefert LC3, das
  dann auf der Uhr zu PCM wird.

### 6.4 Wo was liegt (Weg 1, A + B)

| Teil | Dateien | Tests (ohne Hardware) |
|---|---|---|
| Schnittstelle 4, JSON-Formen | `app-api/…/apps/Phone.kt` und die bestehenden Dateien | `PackageManifestTest`, `AppJsonTest` |
| Host: Anschluss, Berechtigungen, Lebenszyklus | `AppHost.kt`, `apps/phone/PhoneEngine.kt` | `AppHostPhoneTest` |
| Uhr: Datenschicht, Lebenszeichen, Mikrofon | `apps/phone/PhoneLinks.kt`, `WearTransport.kt`, `WatchMicrophone.kt` | `PhoneLinksTest` (simulierte Datenschicht und Zeit) |
| Pfade und Inhalte | `phone-link/…/PhoneLinkWire.kt` | `PhoneLinkWireTest` |
| „G2 Handy“ | `phone/…/Relay.kt`, `MessengerPartners.kt`, `LinkListenerService.kt`, `RelayService.kt`, `MainActivity.kt` | `RelayTest`, `MessengerPartnersTest` (Robolectric, falscher Partner-Dienst) |
| Gemeinsamer Schlüssel | `signing/g2-debug.keystore`, `signing/README.md` | – |

## 7. App-Protokoll `fluency-g2@1` (Paket ⇄ Fluency)

Eine Nachricht = ein JSON-Objekt, Typ in `t`. Beide Seiten **ignorieren unbekannte Typen und Felder**; eine
geänderte Bedeutung bekommt einen neuen Namen (`fluency-g2@2`). Sprachen sind Fluencys Codes (`de`, `de-CH`,
`en`, `zh-Hant` …), `from` darf `auto` sein. Umgesetzt im Paket: `Protocol.kt`, geprüft in `ProtocolTest`.

**Paket → Fluency**

| `t` | Felder | Bedeutung |
|---|---|---|
| `hello` | `proto: "fluency-g2@1"`, `app` (Version des Pakets) | erste Nachricht nach dem Verbinden |
| `start` | `n`, `source` (`watch`/`phone`), `from`, `to`, `speak`, `partials` | zuhören und übersetzen; bei `watch` kommt der Ton über die Uhr |
| `config` | wie `start` | geänderte Einstellungen, während Fluency zuhört (Fluency startet neu, was nötig ist) |
| `stop` | `n` | aufhören; was noch gesprochen wird, wird fertig übersetzt |

`n` zählt die Anfragen des Pakets (`start`, `config`, `stop`) hoch. `speak`: Fluency liest jede fertige
Übersetzung auf dem Handy vor. `partials: false`: nur fertige Sätze schicken.

**Fluency → Paket**

| `t` | Felder | Bedeutung |
|---|---|---|
| `welcome` | `proto`, `app` (Version von Fluency), `languages` (optional: Codes, die Fluency gerade übersetzen kann) | Antwort auf `hello`; anderes `proto` → das Paket benutzt Fluency nicht |
| `state` | `running`, `speech` (optional), `note` (optional, deutsch), `re` (optional) | Zustand. `running` gilt ab `start`, auch während die Modelle laden (`note: "Lädt die Modelle …"`). `re` = `n` der Anfrage, die dieser Zustand beantwortet; ohne `re` hat Fluency sich von selbst geändert (Fehler, am Handy gestoppt) |
| `utt` | `id`, `src`, `text`, `final`, `from` (optional), `gone` (optional) | eine Äußerung, wie sie wächst: erkannter Text `src`, Übersetzung `text`; `final` einmal am Ende; `gone: true`, wenn Fluency sie verwirft (Geräusch); `from` = erkannte Sprache, wenn bekannt |
| `error` | `code`, `message` (deutsch) | `no_model` (kein Modell für das Sprachpaar), `mic` (Handy-Mikrofon belegt oder verweigert), `audio` (kein Ton von der Uhr), `busy`, `bad_request` |

Beispiele:

```json
{"t":"hello","proto":"fluency-g2@1","app":"1.1.0"}
{"t":"start","n":1,"source":"watch","from":"auto","to":"de-CH","speak":false,"partials":true}
{"t":"welcome","proto":"fluency-g2@1","app":"1.9.0","languages":["de","de-CH","en","fr","it","es","pt"]}
{"t":"state","running":true,"note":"Lädt die Modelle …","re":1}
{"t":"utt","id":17,"src":"Where is the","text":"Wo ist der","final":false}
{"t":"utt","id":17,"src":"Where is the station?","text":"Wo ist der Bahnhof?","final":true,"from":"en"}
{"t":"utt","id":18,"gone":true}
{"t":"error","code":"no_model","message":"Kein Modell für Englisch → Japanisch"}
{"t":"stop","n":2}
```

**Takt:** Fluency schickt Teilergebnisse einer Äußerung höchstens alle 250 ms (sein Pipeline-Zustand ändert
sich mit jedem Token); fertige Sätze, `gone`, `state` und `error` sofort. Das Paket zeichnet Teilergebnisse
höchstens alle 400 ms (02 §9: ruhig).

## 8. Teil C: in Fluency gebaut (1.9.0)

Im Repo `MADTreasures/playground`, Ordner `fluency/app/src/main/java/ch/madtreasures/fluency/glasses/`; noch
nicht auf Hardware erprobt.

- **`GlassesService`**: der Partner-Dienst nach §6.2 (exportiert, Aktion `…LINK`, Prüfung des Aufrufers wie
  oben). Er läuft als Vordergrund-Dienst (`connectedDevice`, mit Mikrofon-Erlaubnis auch `microphone`),
  solange in Fluency der Schalter **Optionen → „Brille (G2 Watch)“** an ist. Weil Android 14+ das
  Handy-Mikrofon im Hintergrund nur so erlaubt, startet ihn der Schalter und jedes Öffnen von Fluency.
  Ist der Schalter aus, lehnt der Dienst jedes `OPEN` ab („In Fluency ist „Brille (G2 Watch)“ aus“).
- **`WatchAudio`**: liest den PCM-Strom der Uhr aus dem `ParcelFileDescriptor` und gibt Float-Blöcke zu
  512 Samples weiter, wie `MicrophoneSource`; Lücken werden nicht aufgefüllt. Endet ein Strom
  (`AUDIO_STOP`, Pipe zu), hört dieselbe Pipeline beim nächsten `AUDIO_START` weiter.
- **`GlassesBridge`**: `start`/`config`/`stop` steuern eine `LivePipeline` wie der Live-Modus, mit denselben
  Modellen und Einstellungen (`PipelineFactory`, auch vom Live- und Gesprächsmodus benutzt). Quelle `watch` →
  `WatchAudio`, `phone` → `MicrophoneSource`. `PipelineState` geht als `utt` (unfertige höchstens alle
  250 ms, fertige sofort) und `state`; Utterance-Ids wie in der Pipeline. Ein `config`, das nur `speak` oder
  `partials` ändert, lässt die Pipeline laufen; andere Quelle oder Sprache startet eine neue (die Sätze der
  alten gehen als `gone`). Nach `stop` wird der angefangene Satz fertig übersetzt; ein `start` gleich danach
  wartet nicht darauf. Kommt 5 s lang kein Ton von der Uhr: einmal `error` `audio`.
- **Vorlesen** wie im Live-Modus: Der Eingang ist stumm, solange vorgelesen wird – auch der Ton von der Uhr.
- **Größe:** ≈ 1.050 Zeilen Code (davon ≈ 100 in bestehenden Dateien) und ≈ 950 Zeilen Tests, mehr als die
  geschätzten 600–900: Prüfung des Aufrufers, Vordergrund-Dienst, Schalter und Tests für die Abläufe
  (Stopp mitten im Satz, Neustart beim Laden) kamen dazu.
- **Tests** (ohne Uhr, Brille und G2 Handy): `G2ProtocolTest`, `WatchAudioTest`, `GlassesBridgeTest`
  (simulierte Pipeline, virtuelle Zeit), `GlassesServiceTest` (Robolectric: wer hereinkommt, „hello“ →
  „welcome“ über den Messenger, Ausschalten trennt).
- **Später denkbar:** Ton anderer Apps auf dem Handy (ein Video) über `AudioPlaybackCapture` (Android 10+,
  Erlaubnis zur Bildschirmaufnahme bei jedem Start, Apps können es verbieten) als Quelle `phone-media`.

## 9. Teil D: im Paket gebaut (1.1.0)

- `WatchPhoneLink(ui)` setzt `PhoneLink` mit `ui.phone(...)` um (`open` → `Connect`, `send` → `Send`,
  `microphone` → `Microphone`, `close` → `Disconnect`), und `onEvent` reicht `AppEvent.Phone` an `onLink`
  weiter. Protokoll, Wiederverbinden auf Tippen, Mikrofon an/aus bei Start, Pause und Verdecken waren schon
  da und mit `FakePhoneLink` getestet (`FluencyLinkTest`).
- Neu: Kommt die Verbindung nicht zustande, zeigt die Startseite den Grund als Hinweis (sie selbst sagt nur
  „getrennt“).
- Manifest: Berechtigungen `PHONE` und `WATCH_MIC`; Version 1.1.0, braucht Schnittstelle 4 (G2 Watch 0.10.0).
- Tests: `WatchPhoneLinkTest` (mit `FakeAppContext`) und in `FluencyHostTest` der ganze Weg durch den echten
  Host bis zur Leitung von „G2 Handy“ (`FakePhoneLine`).

## 10. Auf der echten Hardware zu klären

- Findet die Uhr „G2 Handy“ (Fähigkeit `g2_handy`), und kommt die erste Verbindung innert 15 s zustande?
  Hält der Vordergrund-Dienst von „G2 Handy“ über Stunden, auch mit Akku-Sparen des Herstellers?
- Läuft das Mikrofon der Uhr, während der Bildschirm der Uhr aus ist (Vordergrund-Dienst mit Typ
  `microphone`)?

- Datenschicht über Bluetooth: Verzögerung und Durchsatz für 32 KB/s Ton, während die Brille ihre ≈ 41 KiB/s
  braucht (dasselbe Funkmodul).
- Akku der Uhr mit dauerndem Mikrofon und Funk.
- Reicht das Mikrofon der Uhr für Sprecher in 1–3 m Entfernung (Gegenüber, Fernseher), oder ist das
  Handy-Mikrofon dort besser?
- Zeit vom Satzende bis zum Untertitel auf der Brille; Ziel unter 1,5 s. Fluencys eigenen Anteil zeigt seine
  Latenzzeile (Erkennung, Übersetzung, gesamt); dazu kommen der Weg über die Uhr und das Zeichnen auf der Brille
  (≈ 250 ms, 01 §1).
