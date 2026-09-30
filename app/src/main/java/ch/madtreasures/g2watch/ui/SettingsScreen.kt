package ch.madtreasures.g2watch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import ch.madtreasures.g2watch.desktop.DesktopController
import ch.madtreasures.g2watch.glasses.FirmwareInstall
import ch.madtreasures.g2watch.glasses.FirmwareRequirement
import ch.madtreasures.g2watch.glasses.FirmwareTarget
import ch.madtreasures.g2watch.glasses.GlassesState
import ch.madtreasures.g2watch.glasses.TransferStats
import ch.madtreasures.g2watch.glasses.title
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI

/**
 * The settings, opened by holding the gear on the touchpad (or from the status page when the
 * firmware does not fit or the connection failed). First how fast frames go from the watch to the
 * glasses, then the firmware (original or custom), then the pointer and the connection.
 */
@Composable
fun SettingsScreen(
    state: GlassesState,
    speed: Float,
    describeFirmware: (FirmwareTarget) -> String?,
    onBack: () -> Unit,
    onCloseWindow: () -> Unit,
    onCenter: () -> Unit,
    onSpeed: (Float) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onFirmware: (FirmwareTarget) -> Unit,
    onLog: () -> Unit,
    backLabel: String = "Touchpad",
    onRisks: () -> Unit = {},
    onApps: () -> Unit = {},
    appsSummary: String? = null,
    protocolFile: Boolean = false,
    onProtocolFile: (Boolean) -> Unit = {},
) {
    val listState = rememberScalingLazyListState()
    val watchBattery = rememberWatchBattery()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = { EdgeButton(onClick = onBack) { Text(backLabel) } },
    ) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Einstellungen") } }
            item { BatteryRow(watchBattery, state) }
            item { CenterText(state.stage.label + (state.title?.let { " · $it" } ?: ""), size = 12) }

            item { ListSubHeader { Text("Apps") } }
            item {
                FilledTonalButton(
                    onClick = onApps,
                    modifier = Modifier.fillMaxWidth().testTag(APPS_TAG),
                    label = { Text("Apps installieren") },
                    secondaryLabel = appsSummary?.let { { Text(it, fontSize = 11.sp) } },
                )
            }

            item { ListSubHeader { Text("Übertragung Uhr → Brille") } }
            item { TransferPanel(state.transfer, state.framesSent) }

            item { ListSubHeader { Text("Firmware") } }
            item {
                CenterText(
                    state.firmware?.let { "Auf der Brille: ${it.name}\n${it.temples}" } ?: "Auf der Brille: noch nicht gelesen",
                    size = 12,
                )
            }
            items(FirmwareTarget.entries) { target ->
                FilledTonalButton(
                    onClick = { onFirmware(target) },
                    modifier = Modifier.fillMaxWidth(),
                    secondaryLabel = { Text(describeFirmware(target) ?: "nicht eingerichtet", fontSize = 11.sp) },
                    label = { Text(target.label) },
                )
            }
            item {
                OutlinedButton(onClick = onRisks, modifier = Modifier.fillMaxWidth().testTag(RISKS_TAG)) { Text("Risiken & Rückweg") }
            }

            item { ListSubHeader { Text("Zeiger") } }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = { onSpeed(speed - DesktopController.SPEED_STEP) }) { Text("−") }
                    Text(String.format(Locale.GERMANY, "Tempo %.1f×", speed), fontSize = 13.sp)
                    OutlinedButton(onClick = { onSpeed(speed + DesktopController.SPEED_STEP) }) { Text("+") }
                }
            }
            if (state.stage.hasSession) {
                item {
                    FilledTonalButton(onClick = onCenter, modifier = Modifier.fillMaxWidth()) { Text("Zeiger zentrieren") }
                }
                item {
                    FilledTonalButton(onClick = onCloseWindow, modifier = Modifier.fillMaxWidth()) { Text("Fenster schließen") }
                }
            }

            item { ListSubHeader { Text("Verbindung") } }
            if (state.stage.hasSession || state.stage.busy) {
                item {
                    Button(
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) { Text("Trennen") }
                }
            } else {
                item {
                    FilledTonalButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Brille verbinden") }
                }
            }
            item { ListSubHeader { Text("Protokoll") } }
            item { OutlinedButton(onClick = onLog, modifier = Modifier.fillMaxWidth()) { Text("Protokoll") } }
            item {
                SwitchButton(
                    checked = protocolFile,
                    onCheckedChange = onProtocolFile,
                    modifier = Modifier.fillMaxWidth().testTag(PROTOCOL_FILE_TAG),
                    label = { Text("Als Datei speichern") },
                    secondaryLabel = { Text(if (protocolFile) "an · für Android Studio" else "aus", fontSize = 11.sp) },
                )
            }
        }
    }
}

/** The last transfer time large, the recent ones as bars, fastest, slowest and the frame count. */
@Composable
private fun TransferPanel(stats: TransferStats?, frames: Long) {
    if (stats == null) {
        CenterText("Noch keine Bilder übertragen.", size = 12)
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("${stats.lastMs} ms", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Text("zuletzt · Ø ${stats.avgMs} ms", fontSize = 12.sp, color = Color.White)
        TransferChart(stats, Modifier.fillMaxWidth().height(38.dp).testTag(TRANSFER_CHART_TAG))
        Text("Spanne ${stats.minMs} – ${stats.maxMs} ms", fontSize = 12.sp, color = Color.White)
        Text("$frames Bilder übertragen", fontSize = 11.sp, color = Color.White)
    }
}

/** One bar per recent frame, oldest left; the newest is the brightest. */
@Composable
private fun TransferChart(stats: TransferStats, modifier: Modifier) {
    Canvas(modifier) {
        val slots = TransferStats.WINDOW
        val gap = 1.5.dp.toPx()
        val bar = (size.width - gap * (slots - 1)) / slots
        // At least 50 ms full height, so small differences do not look dramatic.
        val scale = size.height / maxOf(stats.maxMs, 50)
        drawLine(RIM_TOP.copy(alpha = 0.5f), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        val first = slots - stats.recent.size
        stats.recent.forEachIndexed { i, ms ->
            val h = (ms * scale).coerceIn(2.dp.toPx(), size.height)
            val x = (first + i) * (bar + gap)
            val color = if (i == stats.recent.lastIndex) RIM_BOTTOM else RIM_TOP.copy(alpha = 0.75f)
            drawRect(color, Offset(x, size.height - h), Size(bar, h))
        }
    }
}

/**
 * Asks before firmware goes to the glasses. The easy button at the bottom edge cancels; sending
 * needs the confirm button held for two seconds, so it never happens by accident. Without a way
 * to install the target ([description] null) or while something stands in the way ([blocker],
 * e.g. no glasses chosen yet) there is no confirm button at all. After the hold the glasses ask once
 * more themselves, and only a "Yes" there starts the transfer.
 */
@Composable
fun FirmwareConfirmScreen(
    target: FirmwareTarget,
    description: String?,
    onGlasses: String?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    blocker: String? = null,
    onRisks: () -> Unit = {},
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = { EdgeButton(onClick = onCancel) { Text("Abbrechen") } },
    ) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text(target.label) } }
            item { CenterText("Neu: " + (description ?: "nicht eingerichtet"), size = 13) }
            item { CenterText("Jetzt: " + (onGlasses ?: "unbekannt"), color = MaterialTheme.colorScheme.onSurfaceVariant, size = 12) }
            item { CenterText(firmwareWarning(target), color = WarnOrange, size = 12) }
            item { CenterText(FIRMWARE_STEPS, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 11) }
            item { OutlinedButton(onClick = onRisks, modifier = Modifier.fillMaxWidth()) { Text("Risiken & Rückweg") } }
            when {
                description == null -> Unit
                blocker != null -> item { CenterText(blocker, color = WarnOrange, size = 13) }
                else -> item { HoldToConfirm("Zum Aufspielen 2 s halten", onConfirm) }
            }
        }
    }
}

/** What can go wrong and how to get back, as far as it is known (RECHERCHE_FIRMWARE.md, g2flash). */
@Composable
fun RisksScreen(onBack: () -> Unit) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = { EdgeButton(onClick = onBack) { Text("Zurück") } },
    ) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Risiken & Rückweg") } }
            items(RISKS) { (title, text) ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Where a firmware transfer stands. While it runs there is no way back from this page. */
@Composable
fun FirmwareProgressScreen(install: FirmwareInstall, onClose: () -> Unit) {
    val listState = rememberScalingLazyListState()
    val content: @Composable BoxScope.(PaddingValues) -> Unit = { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            when (install) {
                is FirmwareInstall.Running -> {
                    item { ListHeader { Text(install.title) } }
                    item { ProgressRing(install.percent) }
                    item { CenterText(install.step, size = 13) }
                    item {
                        CenterText("Nicht abbrechen, die Uhr in der Nähe der Brille lassen.", color = WarnOrange, size = 12)
                    }
                }
                is FirmwareInstall.Done -> {
                    item { ListHeader { Text(install.title) } }
                    item { CenterText("Fertig", color = OkGreen, size = 15) }
                    item { CenterText(install.message, size = 12) }
                }
                is FirmwareInstall.Failed -> {
                    item { ListHeader { Text(install.title) } }
                    item { CenterText("Fehlgeschlagen", color = ErrorRed, size = 15) }
                    item { CenterText(install.message, size = 12) }
                }
                is FirmwareInstall.Unavailable -> {
                    item { ListHeader { Text(install.target.label) } }
                    item { CenterText("Nicht eingerichtet", color = WarnOrange, size = 15) }
                    item { CenterText(install.message, size = 12) }
                }
                FirmwareInstall.Idle -> item { CenterText("Keine Übertragung.", size = 13) }
            }
        }
    }
    if (install is FirmwareInstall.Running) {
        ScreenScaffold(scrollState = listState, content = content)
    } else {
        ScreenScaffold(
            scrollState = listState,
            edgeButton = { EdgeButton(onClick = onClose) { Text("OK") } },
            content = content,
        )
    }
}

/**
 * The progress of a transfer: a closed grey ring, and on it a blue arc that grows with [percent] from the
 * top clockwise, so the ring is closed only at 100 %. The arc's round ends are accounted for, so what is
 * blue is exactly [percent] of the ring. Without a number (preparing), a short blue arc circles instead.
 */
@Composable
internal fun ProgressRing(percent: Int?) {
    val fraction = percent?.let { (it / 100f).coerceIn(0f, 1f) }
    val shown by animateFloatAsState(fraction ?: 0f, tween(400), label = "progress")
    val spin = rememberInfiniteTransition(label = "spin")
    val turn by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "turn")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(RING_SIZE + 8.dp)
            .testTag(PROGRESS_RING_TAG)
            .semantics { if (fraction != null) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
    ) {
        Canvas(Modifier.size(RING_SIZE)) {
            val stroke = RING_STROKE.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(RING_TRACK, 0f, 360f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(stroke))
            // Degrees one round end adds to an arc; both ends together add this much.
            val cap = (stroke / (arc.width / 2)) * (180f / PI.toFloat())
            if (fraction == null) {
                drawArc(RING_BLUE, turn - 90f, 70f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            } else if (shown > 0f) {
                val sweep = progressSweep(shown, cap)
                val full = sweep >= 360f
                drawArc(
                    RING_BLUE, if (full) -90f else -90f + cap / 2, sweep, false, topLeft, arc,
                    style = Stroke(stroke, cap = if (full) StrokeCap.Butt else StrokeCap.Round),
                )
            }
        }
        if (percent != null) {
            Text("$percent %", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

/**
 * Degrees to draw for [fraction] of a ring whose arc has round ends, each adding half of [capDegrees]:
 * the visible arc is then exactly [fraction] of the ring, and closed only at 1.
 */
internal fun progressSweep(fraction: Float, capDegrees: Float): Float = when {
    fraction >= 1f -> 360f
    fraction <= 0f -> 0f
    else -> (360f * fraction - capDegrees).coerceAtLeast(0f)
}

/**
 * A button that fills while pressed and only fires once it was held for [HOLD_CONFIRM_MS]; letting
 * go early resets it. The hold is timed by the clock, not by the fill animation: with animations
 * turned off in the developer options (needed to sideload the app) an animation finishes at once,
 * which would turn the hold into a tap.
 */
@Composable
private fun HoldToConfirm(label: String, onConfirm: () -> Unit) {
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    val confirm by rememberUpdatedState(onConfirm)
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(CONFIRM_BACKGROUND)
            .drawBehind { drawRect(CONFIRM_FILL, size = Size(size.width * progress.value, size.height)) }
            .border(1.5.dp, RIM_TOP, shape)
            .testTag(HOLD_TO_CONFIRM_TAG)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val filling = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(HOLD_CONFIRM_MS, easing = LinearEasing))
                    }
                    var letGo = false
                    withTimeoutOrNull(HOLD_CONFIRM_MS.toLong()) {
                        waitForUpOrCancellation()
                        letGo = true
                    }
                    if (letGo) {
                        filling.cancel()
                        scope.launch { progress.animateTo(0f, tween(150)) }
                    } else {
                        scope.launch { progress.snapTo(1f) }
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        confirm()
                        waitForUpOrCancellation()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, color = Color.White)
    }
}

/** How long the confirm button must be held before firmware is sent. */
internal const val HOLD_CONFIRM_MS = 2_000

internal const val HOLD_TO_CONFIRM_TAG = "hold-to-confirm"
internal const val RISKS_TAG = "firmware-risks"
internal const val APPS_TAG = "settings-apps"
internal const val PROTOCOL_FILE_TAG = "settings-protocol-file"
internal const val PROGRESS_RING_TAG = "progress-ring"

private val RING_SIZE = 96.dp
private val RING_STROKE = 9.dp
private val RING_TRACK = Color(0xFF3A3A40)
private val RING_BLUE = Color(0xFF4C8DFF)

/**
 * What the wearer should know before a transfer, in the order it matters. Worded after the
 * research: an interrupted transfer is usually harmless, the lasting risks are rare.
 */
internal fun firmwareWarning(target: FirmwareTarget): String = when (target) {
    FirmwareTarget.CUSTOM ->
        "Mit einer Custom-Firmware erlischt die Garantie. Das Aufspielen dauert bis etwa 35 Minuten (etwa 17 pro " +
            "Glas); die Brille startet zwischen den Gläsern neu. Beide Gläser und die Uhr brauchen mindestens 50 % Akku, und die Brille " +
            "bleibt bis zum Ende bei der Uhr und nicht im Etui. Ein Abbruch ist meist harmlos; selten kann ein Fehler " +
            "einen Bügel dauerhaft lahmlegen, und dafür gibt es keinen erprobten Rettungsweg."
    FirmwareTarget.ORIGINAL ->
        "Evens Firmware ${FirmwareRequirement.BASE_STOCK_VERSION} ersetzt die Custom-Firmware; die Anzeige der Uhr auf " +
            "der Brille geht dann nicht mehr. Das Aufspielen dauert bis etwa 35 Minuten. Beide Gläser und die Uhr " +
            "brauchen mindestens 50 % Akku, und die Brille bleibt bis zum Ende bei der Uhr und nicht im Etui."
}

/** Title and text of each point on [RisksScreen]. */
internal val RISKS = listOf(
    "Rückweg" to "Solange die Brille startet, sich verbinden lässt und höchstens ${FirmwareRequirement.BASE_STOCK_VERSION} meldet, spielt „Original-Firmware“ hier Evens Firmware " +
        "${FirmwareRequirement.BASE_STOCK_VERSION} zurück. Auch ein Update der Even-App entfernt die Custom-Firmware, " +
        "sobald Even eine neuere Version anbietet.",
    "Abbruch" to "Bricht die Übertragung ab, startet die Brille meist mit der bisherigen Firmware. Dann einfach neu " +
        "starten und erneut aufspielen. Die App wiederholt nie von selbst.",
    "Dauerhafte Schäden" to "Nach heutigem Wissen nur, wenn die Firmware beim Start abstürzt, zu groß für den Speicher ist " +
        "(prüft die App vor jeder Übertragung) oder beim Kopieren des Bootloaders der Strom ausfällt. Dafür gibt es " +
        "keinen erprobten Rettungsweg ohne Debugger oder Service.",
    "Was die App absichert" to "Nur zwei Images sind erlaubt (SHA-256 fest eingetragen), die Custom-Firmware wird auf der " +
        "Uhr aus Evens Original und dem Patch-Set von g2flash gebaut und vollständig geprüft. Vorher: Akku von Uhr " +
        "und Brille, Frage auf der Brille selbst; übertragen wird nur über eine ausreichend breite Bluetooth-Verbindung.",
    "Neustart der Brille" to "5× schnell auf beide Touchflächen tippen oder den Strom des Etuis 3× innerhalb von 7 s trennen.",
    "Garantie" to "Schäden durch nicht autorisierte Software sind von der Garantie ausgeschlossen.",
    "Empfehlung" to "Nach dem ersten Custom-Aufspielen einmal zurück auf Original und wieder auf Custom – dann ist der " +
        "Rückweg für diese Brille erprobt.",
)

internal const val FIRMWARE_STEPS =
    "Vorher: Even-App beenden oder am Handy Bluetooth aus. Nach dem Halten fragt die Brille selbst noch " +
        "einmal – dort „Yes, flash“ wählen. Zurück zur Original-Firmware geht es auf demselben Weg, " +
        "solange die Brille startet und sich verbinden lässt."
internal const val TRANSFER_CHART_TAG = "transfer-chart"

private val CONFIRM_BACKGROUND = Color(0xFF1B2539)
private val CONFIRM_FILL = Color(0xFF3D6FD6)
