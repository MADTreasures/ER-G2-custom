package ch.madtreasures.g2watch.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import ch.madtreasures.g2watch.input.ExternalDevice
import ch.madtreasures.g2watch.input.FoundDevice
import ch.madtreasures.g2watch.input.HidPairing
import ch.madtreasures.g2watch.input.KeyLayout

/**
 * Settings → "Tastatur & Maus" (03 §5.4): the keyboards and mice connected now, pairing a new one
 * (Wear OS's Bluetooth list does not show them), the layout of the keyboard, the keys in short, and
 * what is not known yet about Wear OS and such devices.
 */
@Composable
fun InputDevicesScreen(
    devices: List<ExternalDevice>,
    layout: KeyLayout,
    /** What "Automatisch" means on this watch; null: the keyboard's own layout. */
    autoLayout: KeyLayout?,
    pairing: HidPairing.State,
    onLayout: (KeyLayout) -> Unit,
    onScan: () -> Unit,
    onPair: (FoundDevice) -> Unit,
    onBluetoothSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = { EdgeButton(onClick = onBack) { Text("Zurück") } },
    ) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Tastatur & Maus") } }
            if (devices.isEmpty()) {
                item { CenterText("Nichts verbunden.", size = 12) }
            } else {
                items(devices, key = { it.id }) { d ->
                    CenterText("${deviceKind(d)}: ${d.name}", size = 13)
                }
            }

            item { ListSubHeader { Text("Neues Gerät") } }
            item {
                FilledTonalButton(
                    onClick = onScan,
                    enabled = !pairing.scanning,
                    modifier = Modifier.fillMaxWidth().testTag(PAIR_SCAN_TAG),
                    label = { Text("Gerät koppeln") },
                    secondaryLabel = {
                        Text(if (pairing.scanning) "Suche …" else "Tastatur oder Maus in der Nähe", fontSize = 11.sp)
                    },
                )
            }
            pairing.message?.let { item { CenterText(it, size = 12) } }
            items(pairing.found, key = { it.address }) { f ->
                FilledTonalButton(
                    onClick = { onPair(f) },
                    enabled = !f.bonded,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(f.name) },
                    secondaryLabel = { Text(f.kind.label + if (f.bonded) " · gekoppelt" else " · koppeln", fontSize = 11.sp) },
                )
            }
            item {
                OutlinedButton(onClick = onBluetoothSettings, modifier = Modifier.fillMaxWidth()) { Text("Bluetooth-Einstellungen") }
            }

            item { ListSubHeader { Text("Tastaturbelegung") } }
            items(KeyLayout.entries) { choice ->
                RadioButton(
                    selected = choice == layout,
                    onSelect = { onLayout(choice) },
                    modifier = Modifier.fillMaxWidth().testTag(LAYOUT_TAG + choice.id),
                    label = { Text(choice.label) },
                    secondaryLabel = if (choice == KeyLayout.AUTO) {
                        { Text("jetzt: " + (autoLayout?.label ?: "wie die Tastatur"), fontSize = 11.sp) }
                    } else {
                        null
                    },
                )
            }

            item { ListSubHeader { Text("Tasten") } }
            item { CenterText(KEY_HELP, size = 11) }
            item { CenterText(INPUT_NOTE, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 11) }
        }
    }
}

/** "Tastatur", "Maus" or both, for one connected device. */
internal fun deviceKind(d: ExternalDevice): String = when {
    d.keyboard && d.mouse -> "Tastatur und Maus"
    d.keyboard -> "Tastatur"
    else -> "Maus"
}

/** Under "Tastatur & Maus" in the settings: what is connected. */
internal fun inputSummary(devices: List<ExternalDevice>): String {
    val keyboard = devices.any { it.keyboard }
    val mouse = devices.any { it.mouse }
    return when {
        keyboard && mouse -> "Tastatur und Maus verbunden"
        keyboard -> "Tastatur verbunden"
        mouse -> "Maus verbunden"
        else -> "nichts verbunden"
    }
}

internal const val KEY_HELP =
    "Maus: links = Klick, rechts = App-Menü, Mitte oder Zurück-Taste = zurück, Rad = scrollen. " +
        "Tastatur: Pfeile und Tab = nächster Knopf, Enter = Klick, Esc oder Alt+← = zurück, " +
        "Menütaste oder F10 = App-Menü, Leertaste und Bild↑/↓ = scrollen. Fragt eine App nach Text, " +
        "erscheint die Frage auf der Brille, getippt wird dort."

internal const val INPUT_NOTE =
    "Noch nicht auf der Uhr geprüft: ob Wear OS Tastaturen und Mäuse überhaupt annimmt. Eingaben gehen nur, " +
        "solange G2 Watch vorne ist und der Bildschirm an. Das Scrollen der Magic Mouse braucht einen Treiber, " +
        "den die Uhr vielleicht nicht hat."

internal const val PAIR_SCAN_TAG = "input-pair"
internal const val LAYOUT_TAG = "input-layout-"
