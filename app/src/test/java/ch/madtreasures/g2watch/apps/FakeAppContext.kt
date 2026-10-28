package ch.madtreasures.g2watch.apps

import ch.madtreasures.g2watch.apps.host.PageState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * The [AppContext] for unit tests of an app (03 §7): it keeps the pages exactly as the host would (same
 * [PageState]), records everything else, and throws [IllegalArgumentException] where the host would
 * refuse a command and write it to the log. So a test sees mistakes at once:
 *
 *     val ui = FakeAppContext.forApp(app)
 *     app.onEvent(AppEvent.Start, ui)
 *     app.onEvent(AppEvent.Click("p_main", "startstop"), ui)
 *     assertEquals("Stopp", ui.page("p_main").textOf("startstop"))
 */
class FakeAppContext(
    /** What the app may use; by default everything its manifest asks for (as if the wearer allowed it). */
    val granted: Set<Permission> = Permission.entries.toSet(),
) : AppContext {
    val pages = PageState()
    val toasts = mutableListOf<String>()
    val vibrations = mutableListOf<Vibration>()
    var menu: List<MenuItem> = emptyList()
        private set

    /** Running timers: tag → (ms, repeat). */
    val timers = LinkedHashMap<String, Pair<Long, Boolean>>()
    val subscriptions = mutableSetOf<Sensor>()
    var audioOn = false
        private set
    val buzzes = mutableListOf<List<BuzzNote>>()
    val logs = mutableListOf<String>()

    /** Requests the app made; answer them with [answer]. */
    val requests = mutableListOf<Pair<HttpRequest, (HttpResult) -> Unit>>()

    /** The open text question (tag, prompt, suggestions), or null; the test answers with an [AppEvent.TextInput]. */
    var question: Triple<String, String, List<String>>? = null
        private set

    /** Video commands in order: block → action. */
    val videos = mutableListOf<Pair<String, VideoAction>>()

    /** Web commands in order: block → action. */
    val webs = mutableListOf<Pair<String, WebAction>>()

    /** Phone commands in order (interface 4); the app hears back through [phoneEvent]. */
    val phones = mutableListOf<PhoneAction>()

    /** Music commands in order (interface 6); the test answers with [AppEvent.Media]. */
    val mediaActions = mutableListOf<MediaAction>()

    /** Stream commands in order (interface 6); the test answers with [AppEvent.Player]. */
    val playerActions = mutableListOf<PlayerAction>()

    /** Whether the phone connection stands, as the host would know it; [phoneEvent] keeps it. */
    var phoneConnected = false

    /** Searches the app made; answer them with [answerSearch]. */
    val searches = mutableListOf<Pair<String, (VideoSearchResult) -> Unit>>()
    var closed = false
        private set

    override val storage = MemoryStorage()

    /** A Bluetooth keyboard on the watch ([AppContext.keyboard]); tests switch it on. */
    override var keyboard = false

    /** The app's folder for large files (interface 5): a fresh temporary folder, or one a test sets. */
    var filesDir: File = kotlin.io.path.createTempDirectory("g2-app-files").toFile().apply { deleteOnExit() }

    /** Free bytes the watch reports ([AppFiles.freeBytes]); tests lower it. */
    var freeBytes = 20L shl 30

    /** Downloads the app started, in order; the test answers with [AppEvent.Download] ([finishDownload] helps). */
    val downloads = mutableListOf<FakeFileDownload>()

    /** Tags of the downloads the app cancelled. */
    val cancelledDownloads = mutableListOf<String>()

    override val files: AppFiles = object : AppFiles {
        override val dir: File get() = filesDir

        override val freeBytes: Long get() = this@FakeAppContext.freeBytes

        override fun download(tag: String, url: String, name: String, wifiOnly: Boolean) {
            require(Permission.NETWORK)
            require(tag.isNotEmpty() && tag.length <= 40) { "download tag $tag" }
            require(url.startsWith("https://")) { "https only: $url" }
            require(AppFiles.isValidName(name)) { "${CommandException.BAD_VALUE}: file name $name" }
            downloads.removeAll { it.tag == tag }
            downloads += FakeFileDownload(tag, url, name, wifiOnly)
        }

        override fun cancelDownload(tag: String) {
            cancelledDownloads += tag
        }
    }

    /** The open download [tag]. */
    fun download(tag: String): FakeFileDownload = downloads.lastOrNull { it.tag == tag } ?: throw AssertionError("no download $tag (have ${downloads.map { it.tag }})")

    /**
     * Completes download [tag] as the host would: [content] lands in its file. Returns the event the app
     * then gets.
     */
    fun finishDownload(tag: String, content: ByteArray): AppEvent.Download {
        val d = download(tag)
        downloads.remove(d)
        File(filesDir, d.name).apply { parentFile?.mkdirs() }.writeBytes(content)
        return AppEvent.Download(tag, DownloadState.DONE, content.size.toLong(), content.size.toLong())
    }

    /** With [queueWork], [work] tasks wait in [waitingWork] until [runWork]; otherwise they run at once. */
    var queueWork = false

    /** Waiting work: tag → the task together with its [onDone]. */
    val waitingWork = mutableListOf<Pair<String, () -> Unit>>()

    override fun <T> work(tag: String, task: () -> T, onDone: (Result<T>) -> Unit) {
        require(tag.isNotEmpty() && tag.length <= 40) { "work tag $tag" }
        val job = { onDone(runCatching(task)) }
        if (!queueWork) return job()
        // As in the host: a task that has not started gives way to a newer one with its tag.
        waitingWork.removeAll { it.first == tag }
        waitingWork += tag to job
    }

    /** The app's assets, from its package's `src/main/assets` (or the app's and the tests' asset folders). */
    override fun asset(path: String): ByteArray? = assetFile(path).takeIf { it.isFile }?.readBytes()

    /** Runs the waiting work, oldest first, including what it adds, until none is left. */
    fun runWork() {
        while (waitingWork.isNotEmpty()) waitingWork.removeAt(0).second()
    }

    val current: Page? get() = pages.current

    fun page(id: String): Page = pages.page(id) ?: throw AssertionError("no page $id (have ${pages.pageIds()})")

    /** Answers the oldest open request. */
    fun answer(result: HttpResult) = requests.removeAt(0).second(result)

    /** Answers the oldest open search. */
    fun answerSearch(result: VideoSearchResult) = searches.removeAt(0).second(result)

    /** The last video command for [block]. */
    fun lastVideo(block: String): VideoAction? = videos.lastOrNull { it.first == block }?.second

    /** The last web command for [block]. */
    fun lastWeb(block: String): WebAction? = webs.lastOrNull { it.first == block }?.second

    /** Whether a web page is open in [block]: opened and not stopped since. */
    fun webOpen(block: String): Boolean {
        val mine = webs.filter { it.first == block }.map { it.second }
        val opened = mine.indexOfLast { it is WebAction.Open }
        return opened >= 0 && mine.drop(opened).none { it == WebAction.Stop }
    }

    private inline fun checked(block: () -> Unit) {
        try {
            block()
        } catch (e: CommandException) {
            throw IllegalArgumentException("${e.code}: ${e.message}", e)
        }
    }

    private fun require(permission: Permission) {
        if (permission !in granted) throw IllegalArgumentException("${CommandException.PERMISSION_DENIED}: ${permission.label}")
    }

    override fun definePages(pages: List<Page>) = checked { this.pages.define(pages) }

    override fun definePages(project: BaukastenProject) = definePages(project.pages)

    override fun show(pageId: String) = checked { pages.show(pageId) }

    override fun replace(pageId: String) = checked { pages.replace(pageId) }

    override fun patch(pageId: String, changes: PatchBuilder.() -> Unit) =
        checked { pages.patch(pageId, PatchBuilder().apply(changes).build()) }

    override fun setBlocks(pageId: String, blocks: List<Block>) = checked { pages.setBlocks(pageId, blocks) }

    override fun toast(text: String, ms: Int) {
        require(text.isNotBlank()) { "toast without text" }
        toasts += text
    }

    override fun vibrate(pattern: Vibration) {
        vibrations += pattern
    }

    override fun menu(items: List<MenuItem>) {
        require(items.size <= 10) { "at most 10 menu entries" }
        require(items.all { it.text.toByteArray(Charsets.UTF_8).size in 1..32 }) { "menu text 1 to 32 bytes" }
        menu = items
    }

    override fun buzz(notes: List<BuzzNote>) {
        require(Permission.BUZZER)
        require(notes.size in 1..48) { "1 to 48 notes" }
        buzzes += notes
    }

    override fun timer(tag: String, ms: Long, repeat: Boolean) {
        require(tag.isNotEmpty() && ms >= 0 && (!repeat || ms >= 50)) { "timer $tag $ms" }
        timers[tag] = Pair(ms, repeat)
    }

    override fun cancelTimer(tag: String) {
        timers.remove(tag)
    }

    override fun subscribe(sensor: Sensor, rate: Int) {
        sensor.permission?.let { require(it) }
        subscriptions += sensor
    }

    override fun unsubscribe(sensor: Sensor) {
        subscriptions -= sensor
    }

    override fun audio(on: Boolean) {
        require(Permission.MIC)
        audioOn = on
    }

    override fun fetch(request: HttpRequest, onResult: (HttpResult) -> Unit) {
        require(Permission.NETWORK)
        require(request.url.startsWith("https://")) { "https only: ${request.url}" }
        requests += Pair(request, onResult)
    }

    override fun askText(tag: String, prompt: String, suggestions: List<String>) {
        require(tag.isNotEmpty() && prompt.length <= 100) { "askText $tag" }
        require(suggestions.size <= 5 && suggestions.all { it.isNotBlank() && it.length <= 40 }) { "at most 5 suggestions of 1 to 40 characters" }
        question = Triple(tag, prompt, suggestions)
    }

    /** The app got its answer (or a new question replaced it). */
    fun questionAnswered() {
        question = null
    }

    override fun video(block: String, action: VideoAction) {
        if (action is VideoAction.Play) {
            checked {
                if (pages.block(block) !is Block.Image) throw CommandException.unknownBlock(block, pages.current?.id ?: "?")
            }
            when {
                action.src.startsWith("https://") -> require(Permission.NETWORK)
                action.src.startsWith("test:") -> Unit
                else -> throw IllegalArgumentException("${CommandException.BAD_VALUE}: video source ${action.src}")
            }
        } else {
            require(videos.any { it.first == block && it.second is VideoAction.Play }) { "no video in $block" }
        }
        videos += Pair(block, action)
    }

    override fun web(block: String, action: WebAction) {
        if (action is WebAction.Open) {
            checked {
                if (pages.block(block) !is Block.Image) throw CommandException.unknownBlock(block, pages.current?.id ?: "?")
            }
            if (!action.url.startsWith("https://") && !action.url.startsWith("http://")) {
                throw IllegalArgumentException("${CommandException.BAD_VALUE}: web address ${action.url}")
            }
            require(Permission.NETWORK)
        } else {
            require(webOpen(block)) { "no web page in $block" }
            if (action is WebAction.Type) require(action.text.length <= 2_000) { "at most 2000 characters" }
            if (action is WebAction.Insert) require(action.text.length <= 2_000) { "at most 2000 characters" }
            if (action is WebAction.Key) require(action.key.isNotEmpty() && action.key.length <= 32) { "key name ${action.key}" }
            if (action is WebAction.Tap) {
                val image = pages.block(block) as Block.Image
                require(action.x in 0 until image.w && action.y in 0 until image.h) { "tap outside $block" }
            }
        }
        webs += Pair(block, action)
    }

    override fun phone(action: PhoneAction) {
        when (action) {
            is PhoneAction.Connect -> {
                require(Permission.PHONE)
                require(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(action.partner)) {
                    "${CommandException.BAD_VALUE}: no package name: ${action.partner}"
                }
            }
            is PhoneAction.Send -> {
                require(Permission.PHONE)
                require(phoneConnected) { "${CommandException.BAD_VALUE}: Keine Verbindung zum Handy" }
                require(action.text.encodeToByteArray().size <= PhoneAction.MAX_TEXT_BYTES) { "at most 64 KiB" }
            }
            is PhoneAction.Microphone -> {
                require(Permission.WATCH_MIC)
                require(phoneConnected) { "${CommandException.BAD_VALUE}: Keine Verbindung zum Handy" }
            }
            PhoneAction.Disconnect -> phoneConnected = false
        }
        phones += action
    }

    override fun media(action: MediaAction) {
        require(Permission.MEDIA)
        fun app(app: String?) = require(app == null || MediaAction.APP.matches(app)) { "${CommandException.BAD_VALUE}: no package name: $app" }
        when (action) {
            is MediaAction.Play -> app(action.app)
            is MediaAction.Pause -> app(action.app)
            is MediaAction.PlayPause -> app(action.app)
            is MediaAction.Next -> app(action.app)
            is MediaAction.Previous -> app(action.app)
            is MediaAction.Seek -> require(action.positionMs >= 0) { "seek ${action.positionMs}" }
            is MediaAction.Search -> {
                app(action.app)
                require(action.query.isNotBlank() && action.query.length <= MediaAction.MAX_QUERY) { "search ${action.query}" }
            }
            is MediaAction.Open -> app(action.app)
            is MediaAction.Volume -> require(action.steps != 0 && kotlin.math.abs(action.steps) <= 15) { "volume ${action.steps}" }
            MediaAction.Follow, MediaAction.Unfollow -> Unit
        }
        mediaActions += action
    }

    /** Whether the app follows the players now: followed, and not unfollowed since. */
    fun followsMedia(): Boolean = mediaActions.lastOrNull { it == MediaAction.Follow || it == MediaAction.Unfollow } == MediaAction.Follow

    override fun player(action: PlayerAction) {
        when (action) {
            is PlayerAction.Play -> {
                require(Permission.NETWORK)
                require(action.url.startsWith("https://") || action.url.startsWith("http://")) { "${CommandException.BAD_VALUE}: stream ${action.url}" }
            }
            PlayerAction.Pause, PlayerAction.Resume -> require(stream() != null) { "${CommandException.BAD_VALUE}: Kein Stream" }
            PlayerAction.Stop -> Unit
        }
        playerActions += action
    }

    /** The stream the app plays now: its last [PlayerAction.Play], unless stopped since. */
    fun stream(): PlayerAction.Play? {
        val last = playerActions.lastOrNull { it is PlayerAction.Play || it == PlayerAction.Stop }
        return last as? PlayerAction.Play
    }

    /** What the host reports about the connection, for `app.onEvent(…)`; it also keeps [phoneConnected]. */
    fun phoneEvent(state: PhoneState, text: String? = null, message: String? = null): AppEvent.Phone {
        when (state) {
            PhoneState.CONNECTED -> phoneConnected = true
            PhoneState.DISCONNECTED -> phoneConnected = false
            PhoneState.MESSAGE -> Unit
        }
        return AppEvent.Phone(state, text, message)
    }

    /** The messages the app sent to the phone. */
    fun phoneMessages(): List<String> = phones.filterIsInstance<PhoneAction.Send>().map { it.text }

    /** Whether the app has the watch's microphone on, after its last command. */
    fun phoneMicOn(): Boolean = phoneConnected && (phones.lastOrNull { it is PhoneAction.Microphone } as? PhoneAction.Microphone)?.on == true

    override fun videoSearch(query: String, onResult: (VideoSearchResult) -> Unit) {
        require(Permission.NETWORK)
        require(query.isNotBlank()) { "empty search" }
        searches += Pair(query, onResult)
    }

    override fun log(message: String) {
        logs += message
    }

    override fun close() {
        closed = true
    }

    /** In-memory store with the 256 KiB limit of the real one. */
    class MemoryStorage : AppStorage {
        val values = LinkedHashMap<String, JsonElement>()

        override fun get(key: String): JsonElement? = values[key]

        override fun set(key: String, value: JsonElement) {
            val next = LinkedHashMap(values)
            if (value is JsonNull) next.remove(key) else next[key] = value
            require(JsonObject(next).toString().toByteArray(Charsets.UTF_8).size <= AppStorage.MAX_BYTES) { "storage full" }
            values.clear()
            values.putAll(next)
        }
    }

    companion object {
        /**
         * A context with the Baukasten pages of [app]'s manifest already defined, as the host loads
         * them from `src/main/assets` (test apps: `src/test/resources`) before [AppEvent.Start], and the
         * permissions the manifest asks for.
         */
        fun forApp(app: G2App): FakeAppContext {
            val ui = FakeAppContext(app.manifest.permissions)
            app.manifest.ui?.let { path ->
                ui.definePages(BaukastenProject.parse(assetFile(path).readText()))
            }
            return ui
        }
    }
}

/**
 * An asset of the app (`src/main/assets`), of a test (`src/test/resources`) or of an app package
 * (`packages/<name>/src/main/assets`). Unit tests run in the
 * module directory.
 */
fun assetFile(path: String): File =
    (listOf(File("src/main/assets"), File("src/test/resources")) + packageAssetDirs).map { File(it, path) }.firstOrNull { it.isFile }
        ?: File("src/main/assets", path)

/** `src/main/assets` of every app package (09). */
private val packageAssetDirs: List<File> = File("../packages").listFiles().orEmpty().sortedBy { it.name }.map { File(it, "src/main/assets") }

/** The text of a heading, text, button or toggle, or the label of a value or progress block. */
fun Page.textOf(blockId: String): String = when (val b = block(blockId)) {
    is Block.Heading -> b.text
    is Block.Text -> b.text
    is Block.Button -> b.text
    is Block.Toggle -> b.text
    is Block.Value -> b.text
    is Block.Progress -> b.text
    else -> throw AssertionError("$blockId has no text: $b")
}

/** The shown value of a value block. */
fun Page.valueOf(blockId: String): String = (block(blockId) as? Block.Value)?.value ?: throw AssertionError("$blockId is no value block")

/** The rows of a list block. */
fun Page.itemsOf(blockId: String): List<ListItem> = (block(blockId) as? Block.List)?.items ?: throw AssertionError("$blockId is no list")

/** A download an app started through [FakeAppContext.files]. */
data class FakeFileDownload(val tag: String, val url: String, val name: String, val wifiOnly: Boolean)
