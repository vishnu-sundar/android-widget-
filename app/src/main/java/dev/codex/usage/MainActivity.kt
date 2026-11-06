package dev.codex.usage

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.codex.usage.model.*
import dev.codex.usage.widget.SmallReceiver
import dev.codex.usage.widget.LargeReceiver
import dev.codex.usage.widget.MediumReceiver
import kotlinx.coroutines.delay
import java.time.Instant

private val Mint = Color(0xFF79E3B5)
private val Night = darkColorScheme(primary = Mint, background = Color(0xFF0B1015), surface = Color(0xFF131D26), onSurface = Color(0xFFE5F2F1), surfaceVariant = Color(0xFF22313B))
private val Day = lightColorScheme(primary = Color(0xFF08754E), background = Color(0xFFF4F8F5), surface = Color.White)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { UsageApp() }
    }
}

@Composable
fun UsageApp(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val dark = settings.theme == "dark" || settings.theme == "system" && isSystemInDarkTheme()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(Instant.now().epochSecond) }
    LaunchedEffect(Unit) { while (true) { now = Instant.now().epochSecond; delay(15_000) } }
    MaterialTheme(colorScheme = if (dark) Night else Day) {
        Scaffold(bottomBar = {
            NavigationBar {
                listOf("Dashboard" to "◉", "Widgets" to "▦", "Settings" to "⚙").forEachIndexed { i, item ->
                    NavigationBarItem(selected = page == i, onClick = { page = i }, icon = { Text(item.second, fontSize = 20.sp) }, label = { Text(item.first) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("‹/› CODEX", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp)
                        Text(listOf("Usage monitor", "On your home screen", "Settings")[page], style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when (page) {
                    0 -> Dashboard(state, settings, connected, now, busy, vm::refresh) { page = 2 }
                    1 -> WidgetPreviews(state, settings, now)
                    else -> SettingsScreen(vm, settings, connected, busy)
                }
            }
        }
    }
}

@Composable
private fun Dashboard(state: StoredState, settings: Settings, connected: Boolean, now: Long, busy: Boolean, refresh: () -> Unit, setup: () -> Unit) {
    val snapshot = state.snapshot
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (connected && state.error != null) "○  Connection needs attention" else if (connected && snapshot != null) "●  Bridge connected" else if (connected) "○  Awaiting first sync" else if (state.imported) "◷  Offline snapshot" else "○  Connect your account", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text(if (connected) snapshot?.account?.plan?.let { "ChatGPT · $it plan" } ?: "Waiting for account information" else "Read limits from Codex on your computer. Your OpenAI credentials stay there.", style = MaterialTheme.typography.bodyMedium)
            if (!connected) Button(onClick = setup) { Text("Set up connection") }
            Text(dataStatus(state, settings.interval, now), style = MaterialTheme.typography.labelMedium)
        }
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    UsageCard("5-hour limit", snapshot?.fiveHour, now)
    UsageCard("Weekly limit", snapshot?.weekly, now)
    snapshot?.limits.orEmpty().filter { it.id != snapshot?.fiveHour?.id && it.id != snapshot?.weekly?.id }.forEach { UsageCard(it.name, it, now) }
    Text("Code review and other limits appear only when Codex exposes them.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column { Text("Last successful data", style = MaterialTheme.typography.labelSmall)
            Text(localTime(snapshot?.fetchedAt), style = MaterialTheme.typography.bodySmall)
            state.receivedAt?.let { Text("Synced ${localTime(it)}", style = MaterialTheme.typography.labelSmall) }
        }
        Button(onClick = refresh, enabled = connected && !busy) { Text("↻  Refresh") }
    }
    Text("Resets in your device time zone. A passed timer needs a new reading to confirm a reset.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun quotaColor(limit: UsageLimit?): Color = when (limit?.severity) { 2, 3 -> Color(0xFFEF6673); 1 -> Color(0xFFDB923B); 0 -> Color(0xFF36B981); else -> Color(0xFF8D9DA6) }

@Composable
private fun UsageCard(title: String, limit: UsageLimit?, now: Long, compact: Boolean = false) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(if (compact) 14.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (limit?.usedPercent != null && limit.remaining != null) Text("${"%.0f".format(limit.usedPercent)}% used", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(limit?.takeIf { it.remaining != null }?.percentText ?: "—", fontSize = if (compact) 30.sp else 42.sp, fontWeight = FontWeight.Bold, color = quotaColor(limit))
                Text(if (limit?.remaining != null) " remaining" else " unavailable", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodyMedium)
            }
            val progress by animateFloatAsState(targetValue = ((limit?.remaining ?: 0.0) / 100).toFloat(), label = "Remaining quota")
            LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(6.dp), color = quotaColor(limit), trackColor = MaterialTheme.colorScheme.surfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(countdown(limit?.resetsAt, now), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(localTime(limit?.resetsAt), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: MainViewModel, settings: Settings, connected: Boolean, busy: Boolean) {
    var url by remember { mutableStateOf(vm.bridgeUrl) }
    var token by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importSnapshot) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.saveSettings(settings.copy(notifications = granted)) }
    Text("Account connection", style = MaterialTheme.typography.titleLarge)
    Text("Run the included bridge on a computer signed in to Codex. Use its private HTTPS URL and bridge token below.", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("HTTPS bridge URL") }, placeholder = { Text("https://computer.your-tailnet.ts.net") }, singleLine = true, enabled = !busy)
    OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Bridge token") }, supportingText = { Text("Generated by the bridge. Never enter your ChatGPT password.") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy)
    Button(onClick = { vm.connect(url, token); token = "" }, enabled = !busy && url.isNotBlank() && token.length >= 32, modifier = Modifier.fillMaxWidth()) { Text(if (connected) "Reconnect bridge" else "Connect bridge") }
    if (connected || vm.state.value.imported) OutlinedButton(onClick = vm::disconnect, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Disconnect and clear data") }
    HorizontalDivider()
    Text("Automatic refresh", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { listOf(15, 30, 60).forEach { n -> FilterChip(selected = settings.interval == n, onClick = { vm.saveSettings(settings.copy(interval = n)) }, label = { Text("$n min") }, enabled = !busy) } }
    Text("Android may delay background work during Doze, battery saver or force-stop. No exact alarms are used.", style = MaterialTheme.typography.bodySmall)
    Text("Theme", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { listOf("dark", "light", "system").forEach { theme -> FilterChip(settings.theme == theme, { vm.saveSettings(settings.copy(theme = theme)) }, label = { Text(theme.replaceFirstChar { it.uppercase() }) }, enabled = !busy) } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Usage notifications", style = MaterialTheme.typography.titleMedium); Text("Below 25%, below 10%, exhausted and confirmed resets", style = MaterialTheme.typography.bodySmall) }
        Switch(settings.notifications, onCheckedChange = { on ->
            if (on && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else vm.saveSettings(settings.copy(notifications = on))
        }, enabled = !busy)
    }
    HorizontalDivider()
    Text("Offline alternative", style = MaterialTheme.typography.titleMedium)
    Text("Import a usage JSON exported by the bridge. Imported data stays labeled as a snapshot and has no automatic updates or notifications. Importing disconnects the current bridge.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain")) }, enabled = !busy) { Text("Import usage snapshot") }
    Text("How data is retrieved", style = MaterialTheme.typography.titleMedium)
    SelectionContainer { Text("Computer: codex login → python3 bridge/usage_bridge.py init → python3 bridge/usage_bridge.py serve\n\nThe bridge calls account/read and account/rateLimits/read through Codex app-server. Only usage and plan information reach this app. See the repository README for private HTTPS setup.", style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun WidgetPreviews(state: StoredState, settings: Settings, now: Long) {
    var preview by rememberSaveable { mutableIntStateOf(1) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("2×2", "4×2", "4×4").forEachIndexed { i, s -> FilterChip(preview == i, { preview = i }, label = { Text(s) }) } }
    Text("Preview · ${dataStatus(state, settings.interval, now)}", style = MaterialTheme.typography.labelLarge)
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("‹/› Codex", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            UsageCard("5-hour", state.snapshot?.fiveHour, now, true)
            if (preview > 0) UsageCard("Weekly", state.snapshot?.weekly, now, true)
            if (preview == 2) state.snapshot?.limits.orEmpty().filter { it.id != state.snapshot?.fiveHour?.id && it.id != state.snapshot?.weekly?.id }.forEach { UsageCard(it.name, it, now, true) }
            Text("Updated ${localTime(state.snapshot?.fetchedAt)}", style = MaterialTheme.typography.bodySmall)
        }
    }
    Text("Home-screen layouts adapt to the space your launcher provides. Countdown text updates when the widget refreshes.", style = MaterialTheme.typography.bodySmall)
    val context = androidx.compose.ui.platform.LocalContext.current
    var pinMessage by remember { mutableStateOf<String?>(null) }
    Button(onClick = {
        val manager = AppWidgetManager.getInstance(context)
        pinMessage = if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(context, listOf(SmallReceiver::class.java, MediumReceiver::class.java, LargeReceiver::class.java)[preview]), null, null)
            "Confirm the widget in your launcher."
        } else "Long-press your home screen, choose Widgets, then Codex Usage."
    }, modifier = Modifier.fillMaxWidth()) { Text("Add home-screen widget") }
    pinMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    Text("For other sizes, long-press the home screen → Widgets → Codex Usage. Resize by long-pressing a placed widget.", style = MaterialTheme.typography.bodyMedium)
}
