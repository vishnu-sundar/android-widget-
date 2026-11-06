package dev.codex.usage.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.math.BigDecimal

val WireJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

@Serializable
data class Account(val label: String? = null, val plan: String? = null)

@Serializable
data class UsageLimit(
    val id: String,
    val name: String,
    val usedPercent: Double? = null,
    val windowMinutes: Int? = null,
    val resetsAt: Long? = null,
) {
    val remaining: Double? get() = usedPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }?.let { 100 - it }
    val percentText: String get() = remaining?.let { "${BigDecimal.valueOf(it).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()}%" } ?: "Unavailable"
    val severity: Int get() = remaining?.let { when { it <= 0 -> 3; it < 10 -> 2; it < 25 -> 1; else -> 0 } } ?: -1
}

@Serializable
data class Snapshot(
    val schemaVersion: Int = 1,
    val fetchedAt: Long,
    val source: String,
    val account: Account? = null,
    val limits: List<UsageLimit> = emptyList(),
) {
    val fiveHour get() = limits.firstOrNull { it.id.startsWith("codex/") && it.windowMinutes == 300 }
    val weekly get() = limits.firstOrNull { it.id.startsWith("codex/") && it.windowMinutes == 10080 }
}

fun parseSnapshot(text: String, now: Long = Instant.now().epochSecond): Snapshot {
    require(text.length <= 262_144) { "Snapshot is too large" }
    val value = WireJson.decodeFromString<Snapshot>(text)
    require(value.schemaVersion == 1) { "Unsupported snapshot version" }
    require(value.source == "codex-app-server") { "Unsupported usage source" }
    require(value.fetchedAt > 0 && value.fetchedAt <= now + 300) { "Invalid snapshot time; check device clock" }
    require(value.limits.size <= 64 && value.limits.map { it.id }.distinct().size == value.limits.size) { "Invalid quota list" }
    require(value.limits.all { it.id.length in 1..160 && it.name.length in 1..160 }) { "Invalid quota label" }
    return value
}

fun countdown(reset: Long?, now: Long = Instant.now().epochSecond): String {
    if (reset == null || reset <= 0) return "Reset unavailable"
    val seconds = reset - now
    if (seconds <= 0) return "Awaiting refresh"
    val minutes = (seconds + 59) / 60
    val days = minutes / 1440
    val hours = minutes % 1440 / 60
    val mins = minutes % 60
    return when { days > 0 -> "${days}d ${hours}h"; hours > 0 -> "${hours}h ${mins}m"; else -> "${mins}m" }
}

fun localTime(epoch: Long?): String = epoch?.takeIf { it > 0 }?.let {
    runCatching { DateTimeFormatter.ofPattern("d MMM, h:mm a").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(it)) }.getOrNull()
} ?: "Unavailable"

@Serializable
data class Settings(val interval: Int = 30, val theme: String = "dark", val notifications: Boolean = false)

@Serializable
data class StoredState(
    val snapshot: Snapshot? = null,
    val receivedAt: Long? = null,
    val imported: Boolean = false,
    val error: String? = null,
    val lastAttempt: Long? = null,
    val retryAfter: Long = 0,
    val notified: Map<String, Int> = emptyMap(),
)

fun dataStatus(state: StoredState, interval: Int, now: Long = Instant.now().epochSecond): String = when {
    state.snapshot == null -> "Unavailable"
    state.imported -> "Imported snapshot"
    state.error != null || now - state.snapshot.fetchedAt > interval * 60L + 60 -> "Cached data"
    else -> "Refreshed data"
}

data class UsageAlert(val key: String, val severity: Int, val message: String)

fun alerts(previous: Snapshot?, current: Snapshot, sent: Map<String, Int>): List<UsageAlert> = current.limits.mapNotNull { limit ->
    val reset = limit.resetsAt
    if (reset != null && current.fetchedAt >= reset) return@mapNotNull null
    val key = "${limit.id}:$reset"
    val old = previous?.limits?.firstOrNull { it.id == limit.id }
    val resetConfirmed = old?.resetsAt?.let { reset != null && it < reset && it <= current.fetchedAt } == true &&
        old.usedPercent != null && limit.usedPercent != null && limit.usedPercent < old.usedPercent
    val severity = limit.severity
    when {
        severity > 0 && severity > (sent[key] ?: 0) -> UsageAlert(key, severity, "${limit.name}: ${limit.percentText} remaining")
        resetConfirmed && sent[key] == null -> UsageAlert(key, 0, "${limit.name}: reset confirmed by Codex")
        else -> null
    }
}
