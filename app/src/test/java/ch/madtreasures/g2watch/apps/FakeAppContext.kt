package ch.madtreasures.g2watch.apps

import ch.madtreasures.g2watch.apps.host.PageCommands
import kotlinx.serialization.json.JsonElement

/**
 * [AppContext] for app unit tests (03 §7): records every command, keeps the pages the way the host
 * does (`definePages`, `show`, `patch`, `setBlocks` with the host's own checks) and throws
 * [IllegalArgumentException] where the host would refuse a command and only log it.
 *
 * ```
 * val ui = FakeAppContext()
 * app.onEvent(AppEvent.Start, ui)
 * app.onEvent(AppEvent.Click("p_main", "startstop"), ui)
 * assertEquals("Stopp", ui.page("p_main").textOf("startstop"))
 * ```
 */
class FakeAppContext(
    /** What the wearer allowed; the host asks on the glasses. */
    var granted: Set<Permission> = emptySet(),
) : AppContext {
    val commands = mutableListOf<AppCommand>()
    val pages = LinkedHashMap<String, Page>()
    val history = mutableListOf<String>()
    val timers = LinkedHashMap<String, AppCommand.Timer>()
    val logs = mutableListOf<String>()
    var menuItems: List<MenuItem> = emptyList()
        private set
    var closed = false
        private set

    /** Requests made with [fetch]; answer them with [answer]. */
    val requests = mutableListOf<Pair<HttpRequest, (HttpResult) -> Unit>>()
    override val storage = MemoryStorage()

    val toasts: List<String> get() = commands.filterIsInstance<AppCommand.Toast>().map { it.text }
    val currentPage: Page? get() = history.lastOrNull()?.let { pages[it] }

    fun page(id: String): Page = pages[id] ?: throw AssertionError("Seite „$id“ ist nicht definiert")

    /** Answers the oldest open request. */
    fun answer(result: HttpResult) = requests.removeAt(0).second(result)

    private fun need(p: Permission) {
        if (p !in granted) throw CommandException(CommandError.PERMISSION_DENIED, "Berechtigung „${p.label}“ fehlt")
    }

    private fun known(id: String): Page =
        pages[id] ?: throw CommandException(CommandError.UNKNOWN_PAGE, "Seite „$id“ gibt es nicht")

    override fun definePages(pages: List<Page>) {
        PageCommands.checkDefine(this.pages, pages)
        pages.forEach { this.pages[it.id] = it }
        commands += AppCommand.DefinePages(pages)
    }

    override fun definePages(project: BaukastenProject) = definePages(project.pages)

    override fun show(pageId: String) {
        known(pageId)
        if (history.lastOrNull() != pageId) history += pageId
        commands += AppCommand.Show(pageId)
    }

    override fun replace(pageId: String) {
        known(pageId)
        if (history.isEmpty()) history += pageId else history[history.lastIndex] = pageId
        commands += AppCommand.Replace(pageId)
    }

    override fun patch(pageId: String, changes: PatchBuilder.() -> Unit) {
        val built = PatchBuilder().apply(changes).build()
        pages[pageId] = PageCommands.patch(known(pageId), built, pages.keys)
        commands += AppCommand.Patch(pageId, built)
    }

    override fun setBlocks(pageId: String, blocks: List<Block>) {
        PageCommands.checkSetBlocks(pages, pageId, blocks)
        pages[pageId] = pages.getValue(pageId).copy(blocks = blocks)
        commands += AppCommand.SetBlocks(pageId, blocks)
    }

    override fun toast(text: String, ms: Int) {
        commands += AppCommand.Toast(text, ms)
    }

    override fun vibrate(pattern: Vibration) {
        commands += AppCommand.Vibrate(pattern)
    }

    override fun menu(items: List<MenuItem>) {
        require(items.size <= 10) { "höchstens 10 Menüeinträge" }
        require(items.all { it.text.toByteArray().size <= 32 }) { "Menütext höchstens 32 Byte" }
        menuItems = items
        commands += AppCommand.Menu(items)
    }

    override fun buzz(notes: List<BuzzNote>) {
        need(Permission.BUZZER)
        require(notes.size <= 48) { "höchstens 48 Töne" }
        commands += AppCommand.Buzz(notes)
    }

    override fun timer(tag: String, ms: Long, repeat: Boolean) {
        require(ms > 0 && (!repeat || ms >= 100)) { "Timer $ms ms" }
        val t = AppCommand.Timer(tag, ms, repeat)
        timers[tag] = t
        commands += t
    }

    override fun cancelTimer(tag: String) {
        timers.remove(tag)
        commands += AppCommand.CancelTimer(tag)
    }

    override fun subscribe(sensor: Sensor, rate: Int) {
        sensor.permission?.let { need(it) }
        commands += AppCommand.Subscribe(sensor, rate)
    }

    override fun unsubscribe(sensor: Sensor) {
        commands += AppCommand.Unsubscribe(sensor)
    }

    override fun audio(on: Boolean) {
        need(Permission.MIC)
        commands += AppCommand.Audio(on)
    }

    override fun fetch(request: HttpRequest, onResult: (HttpResult) -> Unit) {
        need(Permission.NETWORK)
        require(request.url.startsWith("https://")) { "nur https://" }
        requests += request to onResult
    }

    override fun log(message: String) {
        logs += message
    }

    override fun close() {
        closed = true
        commands += AppCommand.Close
    }
}

/** An [AppStorage] in memory. */
class MemoryStorage : AppStorage {
    val values = LinkedHashMap<String, JsonElement>()

    override fun get(key: String): JsonElement? = values[key]

    override fun set(key: String, value: JsonElement?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** The text of a heading, text, button, toggle, value or progress block. */
fun Page.textOf(blockId: String): String = when (val b = block(blockId)) {
    is Block.Heading -> b.text
    is Block.Text -> b.text
    is Block.Button -> b.text
    is Block.Toggle -> b.text
    is Block.Value -> b.text
    is Block.Progress -> b.text
    else -> throw AssertionError("„$blockId“ hat keinen Text (${b?.type})")
}

/** The value of a value block. */
fun Page.valueOf(blockId: String): String = (block(blockId) as? Block.Value)?.value ?: throw AssertionError("„$blockId“ ist kein Wert")

/** The rows of a list block. */
fun Page.itemsOf(blockId: String): List<ListItem> = (block(blockId) as? Block.List)?.items ?: throw AssertionError("„$blockId“ ist keine Liste")
