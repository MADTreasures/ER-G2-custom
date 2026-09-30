package ch.madtreasures.g2watch.apps.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/** A question for the wearer that the watch shows as keyboard and voice input (Wear OS RemoteInput). */
data class TextPrompt(val id: Int, val prompt: String, val suggestions: List<String>)

/**
 * Carries [HostPorts.askText] questions from the app host to the watch screen and the answers back
 * (any thread). The screen shows [open] as soon as it is in front; until then the question waits.
 */
class TextPrompts {
    private val _open = MutableStateFlow<TextPrompt?>(null)

    /** The question to show, or null. */
    val open: StateFlow<TextPrompt?> = _open.asStateFlow()

    private var done: ((String?) -> Unit)? = null

    // Not from 1: the watch screen remembers the last number it showed across a restart of the process.
    private var counter = Random.nextInt(1, 1 shl 30)

    @Synchronized
    fun ask(prompt: String, suggestions: List<String>, done: (String?) -> Unit) {
        this.done = done
        _open.value = TextPrompt(++counter, prompt, suggestions)
    }

    /** The wearer's [text] for question [id] (null: cancelled); answers to older questions are dropped. */
    fun answer(id: Int, text: String?) {
        val callback = synchronized(this) {
            if (_open.value?.id != id) return
            _open.value = null
            done.also { done = null }
        }
        callback?.invoke(text)
    }

    @Synchronized
    fun cancel() {
        done = null
        _open.value = null
    }
}
