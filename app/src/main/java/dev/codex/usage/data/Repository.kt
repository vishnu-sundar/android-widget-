package dev.codex.usage.data

import android.content.Context
import dev.codex.usage.model.*
import dev.codex.usage.sync.UsageNotifications
import dev.codex.usage.widget.updateWidgets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

enum class RefreshOutcome { SUCCESS, RETRY, FAILURE }

class Repository(private val context: Context) {
    val store = Store(context)
    val credentials = Credentials(context)
    private val client = BridgeClient()
    private val mutex = Mutex()
    suspend fun refresh(): RefreshOutcome = mutex.withLock {
        val now = Instant.now().epochSecond
        val before = store.states.first()
        if (now < before.retryAfter) return@withLock RefreshOutcome.RETRY
        // Coalesce taps and work arriving at nearly the same time.
        if (before.lastAttempt?.let { now - it in 0..14 } == true) return@withLock RefreshOutcome.FAILURE
        try {
            val credential = credentials.read() ?: return@withLock RefreshOutcome.FAILURE
            store.updateState { it.copy(lastAttempt = now) }
            val snapshot = withContext(Dispatchers.IO) { client.fetch(credential.first, credential.second) }
            val config = store.settings.first()
            val events = if (snapshot.fetchedAt > (before.snapshot?.fetchedAt ?: 0) && !before.imported)
                alerts(before.snapshot, snapshot, before.notified) else emptyList()
            val delivered = if (config.notifications) events.filter { UsageNotifications(context).post(it) } else emptyList()
            store.updateState { it.copy(snapshot = snapshot, receivedAt = now, imported = false, error = null,
                retryAfter = 0, notified = (it.notified + delivered.associate { a -> a.key to a.severity }).entries.toList().takeLast(128).associate { e -> e.toPair() }) }
            updateWidgets(context)
            RefreshOutcome.SUCCESS
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            val failure = when (e) {
                is BridgeFailure -> e
                is IOException -> BridgeFailure("Network unavailable. Showing the last successful data.", true)
                else -> BridgeFailure("Could not read the secure connection. Disconnect and reconnect the bridge.")
            }
            store.updateState { it.copy(error = failure.safeMessage, lastAttempt = now, retryAfter = failure.retryAt) }
            updateWidgets(context)
            if (failure.retryable) RefreshOutcome.RETRY else RefreshOutcome.FAILURE
        }
    }
    suspend fun connect(url: String, token: String) = mutex.withLock {
        require(token.length in 32..512 && token.all { it.code in 33..126 }) { "Enter the bridge token (at least 32 characters)" }
        val validUrl = validateUrl(url)
        withContext(Dispatchers.IO) { credentials.save(validUrl, token) }
        store.updateState { StoredState() }
        updateWidgets(context)
    }
    suspend fun disconnect() = mutex.withLock {
        credentials.clear()
        store.updateState { StoredState() }
        UsageNotifications(context).clear()
        updateWidgets(context)
    }
    suspend fun import(text: String) = mutex.withLock {
        val snapshot = parseSnapshot(text)
        credentials.clear()
        store.updateState { StoredState(snapshot = snapshot, receivedAt = Instant.now().epochSecond, imported = true) }
        UsageNotifications(context).clear()
        updateWidgets(context)
    }
}
