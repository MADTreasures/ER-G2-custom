package ch.madtreasures.g2watch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeSource
import androidx.wear.compose.material3.TimeTextDefaults
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.desktop.PointerMotion
import ch.madtreasures.g2watch.glasses.GlassesState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The watch while it drives the glasses. Near the top stand the time, the battery of watch and
 * glasses and a gear; the whole face is a relative touchpad for the pointer on the glasses. Only
 * finger *movement* moves the pointer, so putting the finger down elsewhere never makes it jump.
 * A double tap anywhere but on the gear is a click at the pointer, the crown sets the pointer
 * speed. Holding the finger on the gear opens the settings; a ring round the gear fills meanwhile.
 *
 * Only while a finger touches the face, a dark glass disc with a glowing blue rim sits under it and
 * follows it; the moment the finger lifts, the disc is gone. When the touch is the second of a
 * double tap, the rim lights up brightly: lifting now clicks.
 *
 * In [gestureMode] (an app with `input: "gestures"` on the glasses, 02 §7) there is no pointer: the
 * face reports gestures instead. A swipe up is [GestureKind.SCROLL_DOWN] (the next thing, as on a
 * phone), down [GestureKind.SCROLL_UP], left and right [GestureKind.SWIPE_LEFT] / [GestureKind.SWIPE_RIGHT]
 * (right means back); a tap is a click once no second tap followed, two taps a double click, and holding
 * a long press with its release. Holding the gear still opens the settings.
 */
@Composable
fun TouchpadScreen(
    glasses: GlassesState,
    speed: Float,
    onMove: (dx: Float, dy: Float) -> Unit,
    onSpeed: (Float) -> Unit,
    onClick: () -> Unit,
    onOpenSettings: () -> Unit,
    timeSource: TimeSource = TimeTextDefaults.rememberTimeSource(TimeTextDefaults.timeFormat()),
    gestureMode: Boolean = false,
    onGesture: (GestureKind) -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    // pointerInput(Unit) lives as long as the screen; always use the latest values.
    val currentSpeed by rememberUpdatedState(speed)
    val move by rememberUpdatedState(onMove)
    val setSpeed by rememberUpdatedState(onSpeed)
    val click by rememberUpdatedState(onClick)
    val openSettings by rememberUpdatedState(onOpenSettings)
    val gestures by rememberUpdatedState(gestureMode)
    val gesture by rememberUpdatedState(onGesture)
    val track = remember { GestureTrack() }
    val scope = rememberCoroutineScope()
    var lastTapAt by remember { mutableLongStateOf(NO_TAP) }
    // Whether the touch now down came soon enough after a tap to click when it lifts.
    var clicking by remember { mutableStateOf(false) }
    var speedShownAt by remember { mutableLongStateOf(0L) }
    val focusRequester = remember { FocusRequester() }
    val feedback = rememberTouchFeedback()
    val watchBattery = rememberWatchBattery()
    val gear = remember { GearSpot() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(speedShownAt) {
        if (speedShownAt != 0L) {
            delay(1_500)
            speedShownAt = 0L
        }
    }

    // The large time below replaces the small one along the top edge.
    ScreenScaffold(timeText = {}) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onGloballyPositioned { gear.touchpad = it }
                .onRotaryScrollEvent { event ->
                    setSpeed(currentSpeed + event.verticalScrollPixels / 500f)
                    speedShownAt = System.currentTimeMillis()
                    true
                }
                .focusRequester(focusRequester)
                .focusable()
                .pointerInput(Unit) {
                    relativeTouchpad(
                        onDown = { position, timeMs ->
                            val onGear = gear.contains(position)
                            if (gestures) {
                                track.start()
                                clicking = !onGear && track.tapPending()
                            } else {
                                clicking = !onGear && timeMs - lastTapAt <= DOUBLE_TAP_MS
                                // A tap waits for one more touch only; this is it.
                                if (clicking) lastTapAt = NO_TAP
                            }
                            feedback.down(position, onGear = onGear, clicking = clicking)
                        },
                        onPosition = { feedback.follow(it) },
                        onMove = { dx, dy, dtMs ->
                            clicking = false
                            lastTapAt = NO_TAP
                            feedback.moving()
                            if (gestures) {
                                track.dx += dx
                                track.dy += dy
                            } else {
                                val (gx, gy) = PointerMotion.toGlasses(dx / density, dy / density, dtMs, currentSpeed)
                                move(gx, gy)
                            }
                        },
                        onUp = {
                            feedback.up()
                            if (gestures) track.swipeOrRelease(SWIPE_MIN_DP * density)?.let { gesture(it) }
                        },
                        onTap = { at, position ->
                            when {
                                // The gear opens only when held; a tap on it is nothing.
                                gear.contains(position) -> lastTapAt = NO_TAP
                                gestures -> track.tap(scope) { kind ->
                                    if (kind == GestureKind.DOUBLE_CLICK) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    gesture(kind)
                                }
                                clicking -> {
                                    clicking = false
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    click()
                                }
                                else -> lastTapAt = at
                            }
                        },
                        onLongPress = { position ->
                            when {
                                gear.contains(position) -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    openSettings()
                                    true
                                }
                                gestures -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    track.longPressed = true
                                    gesture(GestureKind.LONG_PRESS)
                                    true
                                }
                                // Resting elsewhere is part of aiming: the finger may move on.
                                else -> false
                            }
                        },
                    )
                },
        ) {
            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = TOP_SPACE),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(timeSource.currentTime(), fontSize = 52.sp, fontWeight = FontWeight.Medium, color = Color.White)
                BatteryRow(watchBattery, glasses)
                FirmwareRow(glasses)
                Spacer(Modifier.height(8.dp))
                GearButton(
                    feedback,
                    Modifier
                        .size(GEAR_SIZE)
                        .testTag(GEAR_TAG)
                        .onGloballyPositioned { gear.button = it },
                )
                if (speedShownAt != 0L && !gestureMode) {
                    Text(String.format(Locale.GERMANY, "Tempo %.1f×", speed), fontSize = 14.sp, color = Color.White)
                }
                if (gestureMode) {
                    Text("Gesten", fontSize = 13.sp, color = RIM_TOP, modifier = Modifier.testTag(GESTURES_TAG))
                }
            }
            TouchFeedbackLayer(feedback, Modifier.fillMaxSize())
        }
    }
}

/**
 * One touch in gesture mode: how far the finger went, whether it was held, and a tap that waits
 * [DOUBLE_TAP_MS] for a second one before it counts as a click.
 */
private class GestureTrack {
    var dx = 0f
    var dy = 0f
    var longPressed = false
    private var pendingTap: Job? = null

    fun start() {
        dx = 0f
        dy = 0f
        longPressed = false
    }

    /** A first tap is waiting for its second one. */
    fun tapPending(): Boolean = pendingTap?.isActive == true

    /** The finger lifted: the release of a long press, a swipe of at least [minPx], or nothing. */
    fun swipeOrRelease(minPx: Float): GestureKind? = when {
        longPressed -> GestureKind.LONG_PRESS_RELEASE
        maxOf(abs(dx), abs(dy)) < minPx -> null
        abs(dx) > abs(dy) -> if (dx > 0) GestureKind.SWIPE_RIGHT else GestureKind.SWIPE_LEFT
        // Finger up shows what comes next, as on a phone.
        else -> if (dy < 0) GestureKind.SCROLL_DOWN else GestureKind.SCROLL_UP
    }

    fun tap(scope: CoroutineScope, emit: (GestureKind) -> Unit) {
        val waiting = pendingTap
        if (waiting != null && waiting.isActive) {
            waiting.cancel()
            pendingTap = null
            emit(GestureKind.DOUBLE_CLICK)
            return
        }
        pendingTap = scope.launch {
            delay(DOUBLE_TAP_MS)
            pendingTap = null
            emit(GestureKind.CLICK)
        }
    }
}

/** Where the gear sits on the touchpad; read by the gesture handler, never drawn. */
private class GearSpot {
    var touchpad: LayoutCoordinates? = null
    var button: LayoutCoordinates? = null

    /** Whether [position] (touchpad coordinates) lies on the gear, with a little room around it. */
    fun contains(position: Offset): Boolean {
        val pad = touchpad ?: return false
        val gear = button ?: return false
        if (!pad.isAttached || !gear.isAttached) return false
        val radius = gear.size.width / 2f
        val center = pad.localPositionOf(gear, Offset(radius, gear.size.height / 2f))
        return (position - center).getDistance() <= radius * GEAR_HIT
    }
}

/** State of the disc under the finger and of the gear ring. Updated from the gesture handler. */
@Stable
class TouchFeedback internal constructor(private val scope: CoroutineScope) {
    /** Where the disc is; null whenever no finger touches the face, or while it rests on the gear. */
    var finger by mutableStateOf<Offset?>(null)
        private set

    /** The finger came down on the gear: the ring round it fills instead of a disc showing. */
    var onGear by mutableStateOf(false)
        private set

    /** 0 to 1 while the finger rests on the gear: the settings open at 1. */
    val hold = Animatable(0f)

    /** How brightly the rim glows: [GLOW_REST] normally, up to 1 while lifting the finger would click. */
    val glow = Animatable(GLOW_REST)

    private var holding: Job? = null
    private var glowing: Job? = null
    private var last = Offset.Zero

    /** A finger came down; [clicking] if lifting it soon would complete a double tap. */
    fun down(position: Offset, onGear: Boolean, clicking: Boolean) {
        last = position
        this.onGear = onGear
        finger = if (onGear) null else position
        holding?.cancel()
        holding = if (!onGear) null else scope.launch {
            hold.snapTo(0f)
            hold.animateTo(1f, tween(LONG_PRESS_MS.toInt(), easing = LinearEasing))
        }
        glowing?.cancel()
        glowing = scope.launch {
            glow.snapTo(GLOW_REST)
            if (clicking && !onGear) {
                glow.animateTo(1f, tween(GLOW_IN_MS))
                // Held longer, the touch is no tap any more and lifting will not click.
                delay(TAP_MAX_MS - GLOW_IN_MS)
                glow.animateTo(GLOW_REST, tween(GLOW_IN_MS))
            }
        }
    }

    fun follow(position: Offset) {
        last = position
        if (!onGear) finger = position
    }

    /** The finger started to move the pointer: it is the touchpad now, even if it began on the gear. */
    fun moving() {
        if (onGear) {
            onGear = false
            finger = last
        }
        stopHolding()
        // A moving finger does not click: the rim calms down.
        if (glowing != null) {
            glowing?.cancel()
            glowing = scope.launch { glow.animateTo(GLOW_REST, tween(GLOW_IN_MS)) }
        }
    }

    /** The finger lifted (or the touch was cancelled): nothing of the disc stays. */
    fun up() {
        finger = null
        onGear = false
        stopHolding()
        glowing?.cancel()
        glowing = null
        scope.launch { glow.snapTo(GLOW_REST) }
    }

    private fun stopHolding() {
        if (holding == null) return
        holding?.cancel()
        holding = null
        scope.launch { hold.snapTo(0f) }
    }
}

@Composable
fun rememberTouchFeedback(): TouchFeedback {
    val scope = rememberCoroutineScope()
    return remember { TouchFeedback(scope) }
}

/**
 * Draws [feedback]: the glass disc under the finger while it touches the face, nothing otherwise.
 * The glass is opaque, so what lies beneath does not show through.
 */
@Composable
fun TouchFeedbackLayer(feedback: TouchFeedback, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        feedback.finger?.let { drawGlassDisc(it, TOUCH_RADIUS.toPx(), feedback.glow.value) }
    }
}

/**
 * A dark glass disc with a blue rim that glows, stronger underneath, after the picture the wearer
 * chose. [glow] runs from resting (about 0.3) to fully lit (1): the halo grows and the rim turns
 * towards white.
 */
private fun DrawScope.drawGlassDisc(center: Offset, radius: Float, glow: Float) {
    if (radius <= 0f) return
    val rim = minOf(RIM.toPx(), radius)
    drawIntoCanvas { canvas ->
        val halo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = rim * (1.6f + 2.4f * glow)
            color = HALO.copy(alpha = 0.5f + 0.5f * glow).toArgb()
            maskFilter = android.graphics.BlurMaskFilter(
                radius * (0.10f + 0.14f * glow),
                android.graphics.BlurMaskFilter.Blur.NORMAL,
            )
        }
        canvas.nativeCanvas.drawCircle(center.x, center.y, radius, halo)
        // Like the template: the light gathers along the lower edge.
        halo.strokeWidth = rim * (2.2f + 3f * glow)
        halo.color = HALO.copy(alpha = 0.3f + 0.6f * glow).toArgb()
        canvas.nativeCanvas.drawArc(
            center.x - radius, center.y - radius, center.x + radius, center.y + radius,
            25f, 130f, false, halo,
        )
    }
    val top = center.y - radius
    val bottom = center.y + radius
    drawCircle(Brush.verticalGradient(listOf(GLASS_TOP, GLASS_MID, GLASS_BOTTOM), startY = top, endY = bottom), radius, center)
    drawCircle(
        Brush.verticalGradient(
            listOf(lerp(RIM_TOP, Color.White, 0.6f * glow), lerp(RIM_BOTTOM, Color.White, 0.8f * glow)),
            startY = top,
            endY = bottom,
        ),
        radius - rim / 2,
        center,
        style = Stroke(rim),
    )
    // The thin second edge along the top of the glass.
    val inner = radius - rim * 2.2f
    if (inner > 0f) {
        drawArc(
            INNER_EDGE,
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(center.x - inner, center.y - inner),
            size = Size(2 * inner, 2 * inner),
            style = Stroke(rim * 0.45f),
        )
    }
}

/** The gear under the batteries: a small glass button, and while it is held a filling blue ring. */
@Composable
private fun GearButton(feedback: TouchFeedback, modifier: Modifier) {
    Canvas(modifier) {
        val ring = GEAR_RING.toPx()
        val outer = size.minDimension / 2
        val body = outer - ring - GEAR_RING_GAP.toPx()
        drawCircle(Brush.verticalGradient(listOf(GLASS_TOP, GLASS_BOTTOM), startY = center.y - body, endY = center.y + body), body, center)
        drawCircle(RIM_TOP, body - 0.6.dp.toPx(), center, style = Stroke(1.2.dp.toPx()))
        drawGear(center, body * 0.66f)
        val held = feedback.hold.value
        if (feedback.onGear && held > 0f) {
            val r = outer - ring / 2
            drawArc(
                RIM_BOTTOM,
                startAngle = -90f,
                sweepAngle = 360f * held,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(2 * r, 2 * r),
                style = Stroke(ring, cap = StrokeCap.Round),
            )
        }
    }
}

/** A white gear with eight teeth and a hole in the middle. */
private fun DrawScope.drawGear(center: Offset, radius: Float) {
    val teeth = 8
    val root = radius * 0.76f
    val step = 2 * PI / teeth
    fun at(r: Float, a: Double) = Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
    val path = Path()
    for (i in 0 until teeth) {
        val a = i * step - PI / 2
        val points = listOf(
            at(root, a - step * 0.30),
            at(radius, a - step * 0.17),
            at(radius, a + step * 0.17),
            at(root, a + step * 0.30),
            at(root, a + step * 0.5),
        )
        points.forEachIndexed { j, p -> if (i == 0 && j == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
    }
    path.close()
    drawPath(path, Color.White)
    drawCircle(GLASS_MID, radius * 0.34f, center)
}

/** Room left above the time, so the round edge does not clip it. */
private val TOP_SPACE = 22.dp

/** The gear button, with the ring that fills while it is held. */
private val GEAR_SIZE = 46.dp
private val GEAR_RING = 3.dp
private val GEAR_RING_GAP = 2.dp

/** How far outside the gear a touch still counts as on it, relative to its radius. */
private const val GEAR_HIT = 1.15f

/** Test tag of the gear, so tests can find where it is. */
internal const val GEAR_TAG = "gear"

/** Test tag of the hint that the face reports gestures. */
internal const val GESTURES_TAG = "gestures"

/** A swipe in gesture mode must cover at least this much. */
private const val SWIPE_MIN_DP = 24f

/** Finger must rest this long (without moving past the touch slop) on the gear to open the settings. */
private const val LONG_PRESS_MS = 900L

/** A touch shorter than this that stays within the touch slop is a tap. */
private const val TAP_MAX_MS = 300L

/** A second tap starting at most this long after the first one ended makes a double tap. */
private const val DOUBLE_TAP_MS = 400L

/** How long the rim takes to light up on the second touch of a double tap, and to calm down. */
private const val GLOW_IN_MS = 80

/** No tap is waiting for a second touch. */
private const val NO_TAP = -10_000L

/** How much the rim glows while the finger just rests or moves; a coming click lights it up to 1. */
internal const val GLOW_REST = 0.3f

/** Radius of the disc under the finger; larger than a fingertip, so its edge stays visible. */
internal val TOUCH_RADIUS = 38.dp

/** Width of the blue rim. */
private val RIM = 2.5.dp

/** The colours of the template: slate glass, darker underneath, a pale blue rim, a vivid blue halo. */
private val GLASS_TOP = Color(0xFF6A83A8)
private val GLASS_MID = Color(0xFF41567A)
private val GLASS_BOTTOM = Color(0xFF1B2539)
internal val RIM_TOP = Color(0xFFA3BFEE)
internal val RIM_BOTTOM = Color(0xFFD8E7FF)
private val INNER_EDGE = Color(0xFF7F99C6)
private val HALO = Color(0xFF4C8DFF)

/**
 * Relative pointer tracking, from G2 Direct. Only deltas between successive events of the same
 * finger are reported; a new touch (or a second finger taking over) re-anchors without moving.
 * Movement within the touch slop is held back until the finger clearly moves, so a tap never
 * nudges the pointer; the held-back part is then sent along and nothing is lost.
 *
 * After the finger rested [LONG_PRESS_MS] within the slop, [onLongPress] gets where it came down.
 * If that opened something (true), the rest of the touch is ignored; otherwise it goes on as usual.
 */
private suspend fun PointerInputScope.relativeTouchpad(
    onDown: (position: Offset, timeMs: Long) -> Unit,
    onPosition: (position: Offset) -> Unit,
    onMove: (dx: Float, dy: Float, dtMs: Float) -> Unit,
    onUp: () -> Unit,
    onTap: (upTimeMs: Long, position: Offset) -> Unit,
    onLongPress: (downPosition: Offset) -> Boolean,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        onDown(down.position, down.uptimeMillis)
        var tracked = down.id
        var last = down.position
        var lastTime = down.uptimeMillis
        var travelled = 0f
        var longPressed = false
        var waitedOut = false
        var multiFinger = false
        var heldX = 0f
        var heldY = 0f
        var heldMs = 0f
        var upTime = down.uptimeMillis
        try {
            while (true) {
                val waitingForLongPress = !waitedOut && travelled <= slop
                val event = if (waitingForLongPress) {
                    val remaining = LONG_PRESS_MS - (lastTime - down.uptimeMillis)
                    withTimeoutOrNull(remaining.coerceAtLeast(1L)) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }
                if (event == null) {
                    waitedOut = true
                    longPressed = onLongPress(down.position)
                    continue
                }
                val change = event.changes.firstOrNull { it.id == tracked }
                if (change == null || !change.pressed) {
                    val other = event.changes.firstOrNull { it.pressed }
                    if (other == null) {
                        upTime = change?.uptimeMillis ?: lastTime
                        break
                    }
                    // Another finger is still down: continue with it, re-anchored (no jump).
                    multiFinger = true
                    tracked = other.id
                    last = other.position
                    lastTime = other.uptimeMillis
                    onPosition(other.position)
                    event.changes.forEach { it.consume() }
                    continue
                }
                val delta = change.position - last
                val dt = (change.uptimeMillis - lastTime).coerceAtLeast(1L).toFloat()
                last = change.position
                lastTime = change.uptimeMillis
                onPosition(change.position)
                if ((delta.x != 0f || delta.y != 0f) && !longPressed) {
                    travelled += delta.getDistance()
                    if (travelled <= slop) {
                        heldX += delta.x
                        heldY += delta.y
                        heldMs += dt
                    } else {
                        onMove(heldX + delta.x, heldY + delta.y, heldMs + dt)
                        heldX = 0f
                        heldY = 0f
                        heldMs = 0f
                    }
                }
                event.changes.forEach { it.consume() }
            }
        } finally {
            // Also when the gesture is cancelled, e.g. because the settings took over the screen.
            onUp()
        }
        if (!longPressed && !multiFinger && travelled <= slop && upTime - down.uptimeMillis <= TAP_MAX_MS) {
            onTap(upTime, last)
        }
    }
}
