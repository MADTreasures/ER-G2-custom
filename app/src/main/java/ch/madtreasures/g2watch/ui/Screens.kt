package ch.madtreasures.g2watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import ch.madtreasures.g2watch.G2WatchApp
import ch.madtreasures.g2watch.ble.G2Pair
import ch.madtreasures.g2watch.glasses.FirmwareRequirement
import ch.madtreasures.g2watch.glasses.GlassesState
import ch.madtreasures.g2watch.glasses.Stage

val OkGreen = Color(0xFF7CFFA0)
val WarnOrange = Color(0xFFFFC266)
val ErrorRed = Color(0xFFFF7B7B)

/** Green when the glasses show the desktop, orange while getting there, red on trouble. */
fun stageColor(stage: Stage): Color = when (stage) {
    Stage.CONNECTED -> OkGreen
    Stage.FAILED -> ErrorRed
    Stage.INCOMPATIBLE, Stage.CHECKING, Stage.CONNECTING, Stage.RECONNECTING, Stage.DISCONNECTING, Stage.CHARGING -> WarnOrange
    Stage.IDLE -> Color(0xFFB0B0B0)
}

/** Multi-line text, kept clear of the round edge. */
@Composable
internal fun CenterText(text: String, color: Color = MaterialTheme.colorScheme.onSurface, size: Int = 13) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        textAlign = TextAlign.Center,
        color = color,
        fontSize = size.sp,
    )
}

// ---------------------------------------------------------------------------------------------

@Composable
fun PermissionScreen(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Berechtigung") } }
            item {
                CenterText(
                    "Für die Verbindung zur Brille braucht die App „Geräte in der Nähe“ (Bluetooth). " +
                        "Eine Mitteilung zeigt an, solange die Brille verbunden ist oder Firmware aufgespielt wird.",
                    size = 14,
                )
            }
            item {
                Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) { Text("Erlauben") }
            }
            item {
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("App-Einstellungen")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
fun DevicesScreen(
    pairs: List<G2Pair>,
    scanning: Boolean,
    bluetoothOn: Boolean,
    scanError: String?,
    lastPair: G2WatchApp.LastPair?,
    onScan: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onConnectPair: (G2Pair) -> Unit,
    onConnectLast: (G2WatchApp.LastPair) -> Unit,
    onForgetLast: () -> Unit,
    onLog: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = onScan, enabled = bluetoothOn && !scanning) {
                Text(if (scanning) "Suche…" else "Suchen")
            }
        },
    ) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Brille wählen") } }
            if (!bluetoothOn) {
                item { CenterText("Bluetooth ist ausgeschaltet.", color = ErrorRed, size = 14) }
                item {
                    Button(onClick = onEnableBluetooth, modifier = Modifier.fillMaxWidth()) { Text("Bluetooth einschalten") }
                }
            }
            if (scanError != null) item { CenterText(scanError, color = ErrorRed) }
            if (lastPair != null) {
                item {
                    Button(
                        onClick = { onConnectLast(lastPair) },
                        modifier = Modifier.fillMaxWidth(),
                        secondaryLabel = { Text("zuletzt verwendet", fontSize = 12.sp) },
                        label = { Text(lastPair.title, maxLines = 1) },
                    )
                }
            }
            if (scanning && pairs.isEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        Text("Suche G2…", fontSize = 14.sp)
                    }
                }
            }
            items(pairs, key = { it.key }) { pair -> PairButton(pair, onConnectPair) }
            if (!scanning && pairs.isEmpty()) {
                item {
                    CenterText(
                        "Keine G2 gefunden. Brille aus dem Etui nehmen und am Smartphone Bluetooth " +
                            "ausschalten oder die Even-App beenden – sonst bleibt die Brille belegt.",
                        color = WarnOrange,
                    )
                }
            }
            item {
                CenterText(
                    "Die App liest zuerst nur die Firmware-Version. Die Anzeige startet nur mit " +
                        "Faceclaw-Firmware Revision ${FirmwareRequirement.REQUIRED_REVISION}; aufspielen " +
                        "lässt sie sich in den Einstellungen.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 11,
                )
            }
            if (lastPair != null) {
                item {
                    OutlinedButton(onClick = onForgetLast, modifier = Modifier.fillMaxWidth()) {
                        Text("„Zuletzt“ vergessen", fontSize = 13.sp)
                    }
                }
            }
            item {
                OutlinedButton(onClick = onLog, modifier = Modifier.fillMaxWidth()) { Text("Protokoll") }
            }
        }
    }
}

@Composable
private fun PairButton(pair: G2Pair, onConnect: (G2Pair) -> Unit) {
    val arms = (if (pair.left != null) "L ✓ " else "L – ") + (if (pair.right != null) "R ✓" else "R –")
    val right = pair.right
    val secondary = when {
        right == null -> "rechter Bügel fehlt – er führt die Verbindung"
        right.advertising && right.rssi != null -> "$arms · ${right.rssi} dBm"
        right.bonded -> "$arms · gekoppelt"
        else -> arms
    }
    FilledTonalButton(
        onClick = { onConnect(pair) },
        enabled = pair.right != null,
        modifier = Modifier.fillMaxWidth(),
        secondaryLabel = { Text(secondary, fontSize = 11.sp, maxLines = 2) },
        label = { Text(pair.title, maxLines = 1) },
    )
}

// ---------------------------------------------------------------------------------------------

@Composable
fun StatusScreen(
    state: GlassesState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenTouchpad: () -> Unit,
    onSettings: () -> Unit,
    onLog: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            when {
                state.stage == Stage.FAILED -> EdgeButton(onClick = onRetry) { Text("Erneut") }
                state.stage == Stage.CONNECTED || state.stage == Stage.CHARGING ->
                    EdgeButton(onClick = onOpenTouchpad) { Text("Touchpad") }
                state.stage.busy -> EdgeButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) { Text("Abbrechen") }
                else -> EdgeButton(onClick = onCancel) { Text("Zurück") }
            }
        },
    ) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text(state.title ?: "G2") } }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.stage.busy) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text(
                        state.stage.label,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = stageColor(state.stage),
                    )
                }
            }
            if (state.detail.isNotEmpty()) item { CenterText(state.detail, size = 13) }
            state.firmware?.let { item { CenterText("Firmware: ${it.summary}", color = MaterialTheme.colorScheme.onSurfaceVariant, size = 12) } }
            if (state.stage == Stage.INCOMPATIBLE) {
                val newer = state.firmware?.let { v -> FirmwareRequirement.isNewerThanBase(v.leftVersion) || FirmwareRequirement.isNewerThanBase(v.rightVersion) } == true
                item {
                    CenterText(
                        if (newer) {
                            "Diese Brille hat eine neuere Firmware als ${FirmwareRequirement.BASE_STOCK_VERSION}. " +
                                "Darauf spielt die App nichts auf; die Custom-Firmware gibt es nur für ${FirmwareRequirement.BASE_STOCK_VERSION}."
                        } else {
                            "Die App verändert keine Firmware von sich aus. In den Einstellungen lässt sich die " +
                                "passende Custom-Firmware aufspielen oder die Original-Firmware zurückspielen."
                        },
                        color = WarnOrange,
                        size = 12,
                    )
                }
                item { FilledTonalButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Einstellungen") } }
            } else if (!state.stage.busy) {
                // Firmware (e.g. back to the original) stays reachable whatever the connection does.
                item { OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Einstellungen") } }
            }
            if (state.stage == Stage.FAILED) {
                item {
                    CenterText(
                        "Tipps: Brille aus dem Etui nehmen, am Smartphone Bluetooth aus oder die Even-App " +
                            "beenden, Brille neu starten.",
                        color = WarnOrange,
                        size = 12,
                    )
                }
                item {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Andere Brille") }
                }
            }
            item { OutlinedButton(onClick = onLog, modifier = Modifier.fillMaxWidth()) { Text("Protokoll") } }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
fun LogScreen(lines: List<String>, onBack: () -> Unit) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = { EdgeButton(onClick = onBack) { Text("Zurück") } },
    ) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Protokoll") } }
            if (lines.isEmpty()) item { CenterText("Noch keine Einträge.") }
            // Newest first: the interesting part is at the top without scrolling.
            items(lines.asReversed()) { line ->
                val color = when {
                    line.contains("FEHLER") -> ErrorRed
                    line.contains("Warnung") -> WarnOrange
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(
                    line, fontSize = 10.sp, color = color, lineHeight = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }
        }
    }
}
