package dev.codex.usage.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.*
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import dev.codex.usage.MainActivity
import dev.codex.usage.UsageApplication
import dev.codex.usage.model.*
import dev.codex.usage.sync.RefreshScheduler
import kotlinx.coroutines.flow.first

class UsageWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp), DpSize(250.dp, 250.dp)))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = (context.applicationContext as UsageApplication).repository
        val initial = repo.store.states.first()
        val config = repo.store.settings.first()
        provideContent {
            val state by repo.store.states.collectAsState(initial)
            val settings by repo.store.settings.collectAsState(config)
            WidgetBody(state, settings)
        }
    }
}

class SmallReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = UsageWidget() }
class MediumReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = UsageWidget() }
class LargeReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = UsageWidget() }

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val repo = (context.applicationContext as UsageApplication).repository
        if (repo.credentials.connected()) RefreshScheduler.now(context) else UsageWidget().update(context, glanceId)
    }
}

suspend fun updateWidgets(context: Context) {
    val manager = android.appwidget.AppWidgetManager.getInstance(context)
    val glance = GlanceAppWidgetManager(context)
    val widget = UsageWidget()
    for (receiver in listOf(SmallReceiver::class.java, MediumReceiver::class.java, LargeReceiver::class.java)) {
        for (id in manager.getAppWidgetIds(android.content.ComponentName(context, receiver))) {
            widget.update(context, glance.getGlanceIdBy(id))
        }
    }
}

@Composable
private fun WidgetBody(state: StoredState, settings: Settings) {
    val size = LocalSize.current
    val large = size.height >= 250.dp
    val medium = size.width >= 250.dp
    val systemDark = LocalContext.current.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val dark = settings.theme == "dark" || settings.theme == "system" && systemDark
    val fg = if (dark) Color(0xFFE5F2F1) else Color(0xFF10251F)
    val muted = if (dark) Color(0xFF9BAFB9) else Color(0xFF50656D)
    val snapshot = state.snapshot
    if (!medium) {
        val reading = snapshot?.fiveHour
        val source = when {
            state.imported -> "Imported"
            dataStatus(state, settings.interval) == "Cached data" -> "Cached"
            else -> "Updated"
        }
        val stamp = snapshot?.fetchedAt?.let {
            java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm")
                .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochSecond(it))
        }
        Column(GlanceModifier.fillMaxSize().background(if (dark) Color(0xFF111B24) else Color(0xFFF0F7F3))
            .cornerRadius(16.dp).clickable(actionStartActivity<MainActivity>()).padding(7.dp)) {
            Text("‹/› Codex", style = TextStyle(color = ColorProvider(fg), fontSize = 13.sp, fontWeight = FontWeight.Bold))
            Text(reading?.percentText ?: "—", style = TextStyle(color = ColorProvider(tint(reading)), fontSize = 23.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            Text("5-hour remaining", style = TextStyle(color = ColorProvider(fg), fontSize = 10.sp), maxLines = 1)
            Text(countdown(reading?.resetsAt), style = TextStyle(color = ColorProvider(muted), fontSize = 10.sp), maxLines = 1)
            Spacer(GlanceModifier.defaultWeight())
            Text(stamp?.let { "$source $it" } ?: "Connect in app", style = TextStyle(color = ColorProvider(muted), fontSize = 9.sp), maxLines = 1)
        }
        return
    }
    Column(GlanceModifier.fillMaxSize().background(if (dark) Color(0xFF111B24) else Color(0xFFF0F7F3))
        .cornerRadius(22.dp).clickable(actionStartActivity<MainActivity>()).padding(14.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹/› Codex", GlanceModifier.defaultWeight(), style = TextStyle(color = ColorProvider(fg), fontSize = 15.sp, fontWeight = FontWeight.Bold))
            if (medium) Text("↻ Refresh", GlanceModifier.clickable(actionRunCallback<RefreshAction>()).padding(4.dp), style = TextStyle(color = ColorProvider(muted), fontSize = 12.sp))
        }
        if (large) Text(snapshot?.account?.plan?.let { "${snapshot.account.label ?: "ChatGPT account"} · $it" } ?: "Plan unavailable", style = TextStyle(color = ColorProvider(muted), fontSize = 12.sp), maxLines = 1)
        Spacer(GlanceModifier.height(8.dp))
        if (large) {
            LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                item { WidgetLimit("5-hour limit", snapshot?.fiveHour, fg, muted, true) }
                item { WidgetLimit("Weekly limit", snapshot?.weekly, fg, muted, true) }
                val extras = snapshot?.limits.orEmpty().filter { it.id != snapshot?.fiveHour?.id && it.id != snapshot?.weekly?.id }
                items(extras) { WidgetLimit(it.name, it, fg, muted, true) }
            }
        } else {
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                Column(GlanceModifier.defaultWeight()) { WidgetLimit("5-hour", snapshot?.fiveHour, fg, muted, false) }
                Spacer(GlanceModifier.width(14.dp))
                Column(GlanceModifier.defaultWeight()) { WidgetLimit("Weekly", snapshot?.weekly, fg, muted, false) }
            }
        }
        Spacer(GlanceModifier.height(4.dp))
        Text("${dataStatus(state, settings.interval)} · ${snapshot?.fetchedAt?.let { localTime(it) } ?: "Connect in app"}",
            style = TextStyle(color = ColorProvider(muted), fontSize = 10.sp), maxLines = 2)
    }
}

private fun tint(limit: UsageLimit?): Color = when (limit?.severity) { 3, 2 -> Color(0xFFFF6B75); 1 -> Color(0xFFFFB45E); 0 -> Color(0xFF36B981); else -> Color(0xFF8D9DA6) }

@Composable
private fun WidgetLimit(title: String, limit: UsageLimit?, fg: Color, muted: Color, large: Boolean) {
    Column(GlanceModifier.fillMaxWidth().padding(bottom = if (large) 10.dp else 0.dp)) {
        Text(title, style = TextStyle(color = ColorProvider(muted), fontSize = 11.sp), maxLines = 1)
        Text(limit?.percentText?.let { "$it left" } ?: "Unavailable", style = TextStyle(color = ColorProvider(tint(limit)), fontSize = if (large) 22.sp else 19.sp, fontWeight = FontWeight.Bold))
        if (limit?.remaining != null) LinearProgressIndicator(progress = (limit.remaining!! / 100).toFloat(), modifier = GlanceModifier.fillMaxWidth().height(4.dp), color = ColorProvider(tint(limit)), backgroundColor = ColorProvider(Color(0xFF3D4C54)))
        Text(countdown(limit?.resetsAt), style = TextStyle(color = ColorProvider(fg), fontSize = 12.sp))
        if (large) Text(localTime(limit?.resetsAt), style = TextStyle(color = ColorProvider(muted), fontSize = 10.sp))
    }
}
