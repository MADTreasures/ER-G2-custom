package ch.madtreasures.g2watch.apps.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
import ch.madtreasures.g2watch.link.PhoneLinkWire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The watch's microphone for [PhoneLinks] (packages/fluency/PLATTFORM.md §6.3): [AudioRecord] with
 * VOICE_RECOGNITION (no automatic gain, as speech recognition wants it), 16 kHz, mono, 16 bit, in blocks
 * of 20 ms. Android lets the watch app record only with the permission RECORD_AUDIO, which [prompt]
 * asks the wearer for on the watch's screen, and from the background only while its foreground service
 * has the type `microphone` (GlassesService).
 */
class WatchMicrophone(context: Context, private val prompt: MicPrompt) : PhoneLinks.Microphone {
    private val context = context.applicationContext

    override val allowed: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun ask() = prompt.ask()

    @SuppressLint("MissingPermission") // checked by [allowed] before PhoneLinks records
    override fun record(sink: OutputStream, failed: (String) -> Unit): PhoneLinks.Recording {
        val running = AtomicBoolean(true)
        thread(name = "G2Watch-mic", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var record: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(PhoneLinkWire.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    PhoneLinkWire.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min, PhoneLinkWire.BLOCK_BYTES * 8),
                )
                check(record.state == AudioRecord.STATE_INITIALIZED) { "Das Mikrofon der Uhr ist nicht verfügbar" }
                record.startRecording()
                val block = ByteArray(PhoneLinkWire.BLOCK_BYTES)
                while (running.get()) {
                    var off = 0
                    while (off < block.size && running.get()) {
                        val n = record.read(block, off, block.size - off)
                        check(n >= 0) { "Das Mikrofon der Uhr meldet Fehler $n" }
                        off += n
                    }
                    if (off == block.size) sink.write(block)
                }
            } catch (e: IOException) {
                if (running.get()) failed("Der Ton der Uhr kommt nicht mehr zum Handy")
            } catch (e: RuntimeException) {
                if (running.get()) failed(e.message ?: "Das Mikrofon der Uhr ging nicht")
            } finally {
                runCatching { record?.stop() }
                record?.release()
                runCatching { sink.close() }
            }
        }
        return PhoneLinks.Recording { running.set(false) }
    }
}

/**
 * Carries the question "may G2 Watch use the microphone?" (Android's RECORD_AUDIO) from the phone
 * connections to the watch's screen, which asks as soon as it is in front, and the answer back. Like
 * [ch.madtreasures.g2watch.apps.host.TextPrompts]; any thread.
 */
class MicPrompt {
    private val _wanted = MutableStateFlow(false)

    /** True while the question waits for the screen. */
    val wanted: StateFlow<Boolean> = _wanted.asStateFlow()

    /** Called when the wearer allowed it (the screen's thread). */
    @Volatile var onAllowed: () -> Unit = {}

    fun ask() {
        _wanted.value = true
    }

    fun answered(allowed: Boolean) {
        _wanted.value = false
        if (allowed) onAllowed()
    }
}
