package ch.madtreasures.g2handy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.madtreasures.g2watch.link.PhoneLinkWire
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable

/** G2 Handy's only page: ready for the watch or not, whether the watch is reachable, what is connected. */
class MainActivity : ComponentActivity() {
    private var ready by mutableStateOf(true)
    private var watch by mutableStateOf<String?>(Texts.SEARCHING)

    /** Raised to start the relay; the screen asks for notifications first (Android 13+). */
    private var starts by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = G2HandyApp.of(this)
        setContent {
            val links by app.status.collectAsStateWithLifecycle()
            val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { RelayService.start(this) }
            LaunchedEffect(starts) {
                if (starts == 0) return@LaunchedEffect
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) // starts the service when answered
                } else {
                    RelayService.start(this@MainActivity)
                }
            }
            HandyTheme {
                HandyScreen(
                    ready = ready,
                    watch = watch,
                    links = links.map { Texts.label(this, it.partner) to it.open },
                    version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
                    onReady = ::switchReady,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The notification's "Ausschalten" may have switched it off meanwhile.
        ready = Ready.get(this)
        // Started while on screen, the foreground service may run on in the background (Android 12+).
        if (ready) startRelay()
        watch = Texts.SEARCHING
        Wearable.getCapabilityClient(this).getCapability(PhoneLinkWire.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info -> watch = info.nodes.firstOrNull()?.displayName }
            .addOnFailureListener { watch = null }
    }

    private fun switchReady(on: Boolean) {
        ready = on
        Ready.set(this, on)
        if (on) startRelay() else RelayService.stop(this)
    }

    private fun startRelay() {
        starts++
    }
}

/** The texts of G2 Handy, German. */
object Texts {
    const val SEARCHING = "…"

    /** The name of the app [partner] for the wearer, e.g. "Fluency". */
    fun label(context: Context, partner: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(partner, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        partner
    }

    /** The line of the notification. */
    fun notice(links: List<Relay.Status>, label: (String) -> String): String =
        if (links.isEmpty()) "Bereit für die Uhr" else links.joinToString(" · ") { "${label(it.partner)}: ${state(it.open)}" }

    fun state(open: Boolean) = if (open) "verbunden" else "verbindet …"

    /** What the page says about the watch: its name, still searching, or not reachable (null). */
    fun watch(name: String?): String = when (name) {
        SEARCHING -> "wird gesucht …"
        null -> "nicht erreichbar. Läuft auf der Uhr G2 Watch ab 0.10.0, und ist die Uhr mit diesem Handy gekoppelt?"
        else -> "$name – erreichbar"
    }
}

@Composable
private fun HandyTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors) { Surface(Modifier.fillMaxSize()) { content() } }
}

@Composable
fun HandyScreen(ready: Boolean, watch: String?, links: List<Pair<String, Boolean>>, version: String, onReady: (Boolean) -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("G2 Handy", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Verbindet Apps auf der Uhr (G2 Watch) mit Apps auf diesem Handy, zum Beispiel die Brillen-App „Fluency“ " +
                "mit dem Übersetzer Fluency.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Für die Uhr bereit", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Dafür zeigt Android eine dauernde Mitteilung; nur so bleibt G2 Handy im Hintergrund wach.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = ready, onCheckedChange = onReady)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Uhr", style = MaterialTheme.typography.titleMedium)
            Text(Texts.watch(watch), style = MaterialTheme.typography.bodyMedium)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Verbindungen", style = MaterialTheme.typography.titleMedium)
            if (links.isEmpty()) Text("keine", style = MaterialTheme.typography.bodyMedium)
            links.forEach { (name, open) -> Text("$name: ${Texts.state(open)}", style = MaterialTheme.typography.bodyMedium) }
        }
        Text(
            "Uhr-App und G2 Handy müssen mit demselben Schlüssel gebaut sein (beide aus diesem Repo), sonst stellt " +
                "Wear OS nichts zu. G2 Handy $version",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
