package ch.madtreasures.g2watch.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import androidx.wear.compose.material3.Text
import ch.madtreasures.g2watch.apps.PackageManifest
import ch.madtreasures.g2watch.apps.packages.WaitingPackage

/**
 * The watch's own apps (docs/app-entwicklung/09 §4): package files waiting in the folder for new apps,
 * each installed with one tap; the installed apps, removed with two taps; and the apps built into the
 * watch app. Where new files go is said at the bottom.
 */
@Composable
fun AppsScreen(
    waiting: List<WaitingPackage>,
    installed: List<PackageManifest>,
    builtIn: List<String>,
    message: String?,
    busy: Boolean,
    inboxPath: String?,
    onInstall: (WaitingPackage) -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    // The app whose removal waits for the second tap.
    var removing by remember { mutableStateOf<String?>(null) }
    ScreenScaffold(
        scrollState = listState,
        edgeButton = { EdgeButton(onClick = onBack) { Text("Zurück") } },
    ) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Apps") } }
            if (busy) item { CenterText("Wird installiert …", size = 12) }
            message?.let { item { CenterText(it, size = 12) } }

            if (waiting.isNotEmpty()) {
                item { ListSubHeader { Text("Neu auf der Uhr") } }
                items(waiting, key = { it.file.path }) { w ->
                    val m = w.manifest
                    FilledTonalButton(
                        onClick = { if (m != null && !busy) onInstall(w) },
                        enabled = m != null && !busy,
                        modifier = Modifier.fillMaxWidth().testTag(INSTALL_TAG),
                        label = { Text(m?.app?.name ?: w.file.name) },
                        secondaryLabel = {
                            Text(if (m != null) "${m.app.version} · installieren" else w.problem.orEmpty(), fontSize = 11.sp)
                        },
                    )
                }
            }

            item { ListSubHeader { Text("Installiert") } }
            if (installed.isEmpty()) item { CenterText("Noch keine eigenen Apps.", size = 12) }
            items(installed, key = { it.app.id }) { p ->
                val confirm = removing == p.app.id
                Button(
                    onClick = {
                        if (confirm) {
                            removing = null
                            onRemove(p.app.id)
                        } else {
                            removing = p.app.id
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag(REMOVE_TAG),
                    colors = if (confirm) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    } else {
                        ButtonDefaults.filledTonalButtonColors()
                    },
                    label = { Text(p.app.name) },
                    secondaryLabel = {
                        Text(if (confirm) "Nochmals tippen: entfernen" else "${p.app.version} · entfernen", fontSize = 11.sp)
                    },
                )
            }

            if (builtIn.isNotEmpty()) {
                item { ListSubHeader { Text("Fest eingebaut") } }
                item { CenterText(builtIn.joinToString(", "), size = 12) }
            }

            item { ListSubHeader { Text("Neue App") } }
            item {
                CenterText(
                    "Datei *.g2app auf die Uhr legen, in den Ordner " +
                        (inboxPath?.substringAfter("/Android/")?.let { "Android/$it" } ?: "Android/data/ch.madtreasures.g2watch/files/apps") +
                        " (Android Studio: Device Explorer). Dann erscheint sie hier.",
                    size = 11,
                )
            }
            if (removing != null) {
                item { OutlinedButton(onClick = { removing = null }, modifier = Modifier.fillMaxWidth()) { Text("Nicht entfernen") } }
            }
        }
    }
}

const val INSTALL_TAG = "apps-install"
const val REMOVE_TAG = "apps-remove"
