package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.apps.AppCommand
import ch.madtreasures.g2watch.apps.AppStorage
import ch.madtreasures.g2watch.apps.BaukastenProject
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.BuzzNote
import ch.madtreasures.g2watch.apps.HttpRequest
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.MenuItem
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.PatchBuilder
import ch.madtreasures.g2watch.apps.Sensor
import ch.madtreasures.g2watch.apps.Vibration
import ch.madtreasures.g2watch.desktop.GrayRaster
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** The [AppContext][ch.madtreasures.g2watch.apps.AppContext] a session sees: every call becomes a command for [host]. */
internal class HostContext(private val host: AppHost, private val s: Session, platform: AppPlatform) : InternalContext {

    override fun definePages(pages: List<Page>) = host.execute(s, AppCommand.DefinePages(pages))

    override fun definePages(project: BaukastenProject) {
        s.project = project
        host.execute(s, AppCommand.DefinePages(project.pages))
    }

    override fun show(pageId: String) = host.execute(s, AppCommand.Show(pageId))

    override fun replace(pageId: String) = host.execute(s, AppCommand.Replace(pageId))

    override fun patch(pageId: String, changes: PatchBuilder.() -> Unit) =
        host.execute(s, AppCommand.Patch(pageId, PatchBuilder().apply(changes).build()))

    override fun setBlocks(pageId: String, blocks: List<Block>) = host.execute(s, AppCommand.SetBlocks(pageId, blocks))

    override fun toast(text: String, ms: Int) = host.execute(s, AppCommand.Toast(text, ms))

    override fun vibrate(pattern: Vibration) = host.execute(s, AppCommand.Vibrate(pattern))

    override fun menu(items: List<MenuItem>) = host.execute(s, AppCommand.Menu(items))

    override fun buzz(notes: List<BuzzNote>) = host.execute(s, AppCommand.Buzz(notes))

    override fun timer(tag: String, ms: Long, repeat: Boolean) = host.execute(s, AppCommand.Timer(tag, ms, repeat))

    override fun cancelTimer(tag: String) = host.execute(s, AppCommand.CancelTimer(tag))

    override fun subscribe(sensor: Sensor, rate: Int) = host.execute(s, AppCommand.Subscribe(sensor, rate))

    override fun unsubscribe(sensor: Sensor) = host.execute(s, AppCommand.Unsubscribe(sensor))

    override fun audio(on: Boolean) = host.execute(s, AppCommand.Audio(on))

    override fun fetch(request: HttpRequest, onResult: (HttpResult) -> Unit) = host.fetch(s, request, onResult)

    override val storage: AppStorage = HostStorage(platform, s.id) { host.appLog(s, it) }

    override fun log(message: String) = host.appLog(s, message)

    override fun close() = host.execute(s, AppCommand.Close)

    override fun setRaster(blockId: String, raster: GrayRaster) = host.setRaster(s, blockId, raster)

    override val glasses: StateFlow<GlassesStatus> get() = host.glasses

    override fun post(action: () -> Unit) = host.postFor(s, action)
}

/** A session's store: JSON texts per key, written through to [platform]; at most [LIMIT] bytes. */
internal class HostStorage(
    private val platform: AppPlatform,
    private val appId: String,
    private val complain: (String) -> Unit,
) : AppStorage {
    private var values: MutableMap<String, String>? = null

    private fun loaded(): MutableMap<String, String> = values ?: platform.loadStorage(appId).toMutableMap().also { values = it }

    override fun get(key: String): JsonElement? {
        val raw = loaded()[key] ?: return null
        return try {
            Json.parseToJsonElement(raw)
        } catch (e: SerializationException) {
            complain("Speicher „$key“ ist beschädigt und wird ignoriert")
            null
        }
    }

    override fun set(key: String, value: JsonElement?) {
        val current = loaded()
        val next = HashMap(current)
        if (value == null) next.remove(key) else next[key] = value.toString()
        val size = next.entries.sumOf { it.key.toByteArray().size + it.value.toByteArray().size }
        if (size > LIMIT) {
            complain("Speicher voll: „$key“ nicht gespeichert (${size / 1024} KiB, erlaubt ${LIMIT / 1024} KiB)")
            return
        }
        current.clear()
        current.putAll(next)
        platform.saveStorage(appId, current.toMap())
    }

    companion object {
        const val LIMIT = 256 * 1024
    }
}
