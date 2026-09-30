package ch.madtreasures.g2watch.geckoprobe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import java.io.File

/**
 * The probe's only screen: the watch, the tests (in the order to run them), the values with their
 * verdicts, the latest picture and the log. The tests themselves run in [GeckoLab] and go on when
 * the screen goes dark.
 */
class ProbeActivity : ComponentActivity() {

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val probe = application as ProbeApp
        if (!probe.isMainProcess) {
            finish()
            return
        }
        // The notification of the measuring service; the tests also run without it.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val lab = probe.lab
        setContent {
            MaterialTheme {
                AppScaffold(timeText = { TimeText() }) {
                    ProbeScreen(lab, keepScreenOn = { on ->
                        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    })
                }
            }
        }
    }
}

@Composable
private fun ProbeScreen(lab: GeckoLab, keepScreenOn: (Boolean) -> Unit) {
    val results by lab.results.collectAsStateWithLifecycle()
    val running by lab.running.collectAsStateWithLifecycle()
    val instruction by lab.instruction.collectAsStateWithLifecycle()
    val screenOn by lab.keepScreenOn.collectAsStateWithLifecycle()
    val preview by lab.preview.collectAsStateWithLifecycle()
    val log by lab.log.collectAsStateWithLifecycle()
    var saved by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(screenOn) { keepScreenOn(screenOn) }

    val listState = rememberScalingLazyListState()
    val busy = running != null
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            if (busy) {
                EdgeButton(onClick = { lab.cancel() }) { Text("Abbrechen") }
            } else {
                EdgeButton(onClick = { saved = lab.saveReport() }) { Text("Bericht") }
            }
        },
    ) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Gecko-Test") } }
            if (busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp).padding(end = 6.dp))
                        Text("$running läuft", fontSize = 13.sp)
                    }
                }
            }
            instruction?.let { item { Centered(it, color = Warn, size = 14) } }

            item { ListSubHeader { Text("Tests (der Reihe nach)") } }
            item { TestButton("1 · Schnelltest", "Engine, Brücke, 3 Apps · ~1 min", busy) { lab.quickTest() } }
            item { TestButton("2 · Timer-Test", "2 min an, 2 min aus · 4 min", busy) { lab.timerTest() } }
            item { TestButton("3 · Dauertest", "Uhr 30 min tragen", busy) { lab.enduranceTest() } }
            item { TestButton("4 · Seite rendern", "Testseite → Brillen-Raster", busy) { lab.renderTest() } }
            item { TestButton("5 · Wikipedia rendern", "braucht Internet", busy) { lab.renderTest(WIKIPEDIA) } }

            item { ListSubHeader { Text("Ergebnis") } }
            items(results.lines()) { line -> Centered(line, color = colourOf(line), size = 12) }
            saved?.let { file ->
                item { Centered("Gespeichert. Am Rechner:\nadb pull ${file.absolutePath}", color = Ok, size = 11) }
            }

            preview?.let { (title, bitmap) ->
                item { ListSubHeader { Text(title, maxLines = 2) } }
                item {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = title,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
            }

            item { ListSubHeader { Text("Protokoll") } }
            // Newest first: the interesting part is at the top without scrolling.
            items(log.takeLast(40).asReversed()) { line ->
                Text(
                    line,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    color = if (line.contains("ABBRUCH") || line.contains("Fehler")) Bad else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun TestButton(label: String, detail: String, busy: Boolean, onClick: () -> Unit) {
    if (busy) {
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, secondaryLabel = { Text(detail, fontSize = 11.sp) })
    } else {
        FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, secondaryLabel = { Text(detail, fontSize = 11.sp) })
    }
}

@Composable
private fun Centered(text: String, color: Color, size: Int) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        Text(text, textAlign = TextAlign.Center, color = color, fontSize = size.sp, modifier = Modifier.fillMaxWidth())
    }
}

private fun colourOf(line: String): Color = when {
    line.contains("✗") || line.startsWith("Empfehlung: GeckoView nein") -> Bad
    line.contains(" ~") -> Warn
    line.contains("✓") || line.startsWith("Empfehlung: GeckoView ja") -> Ok
    else -> Color.White
}

private val Ok = Color(0xFF7CFFA0)
private val Warn = Color(0xFFFFC266)
private val Bad = Color(0xFFFF7B7B)

/** A long, text-heavy page with pictures: the kind the browser must make readable. */
const val WIKIPEDIA = "https://de.m.wikipedia.org/wiki/Brille"
