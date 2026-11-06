package dev.codex.usage

import dev.codex.usage.model.*
import dev.codex.usage.data.*
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody

class UsageTest {
    private fun limit(used: Double?, reset: Long? = 100) = UsageLimit("codex/primary", "5-hour", used, 300, reset)
    private fun snapshot(time: Long, used: Double, reset: Long) = Snapshot(fetchedAt = time, source = "codex-app-server", limits = listOf(limit(used, reset)))

    @Test fun percentagesAndThresholds() {
        assertEquals("25%", limit(75.0).percentText)
        assertEquals(listOf(0, 1, 1, 2, 3), listOf(75.0, 75.1, 90.0, 90.1, 100.0).map { limit(it).severity })
        assertNull(limit(null).remaining)
        assertNull(limit(101.0).remaining)
    }

    @Test fun timersNeverInventReset() {
        assertEquals("1m", countdown(101, 100))
        assertEquals("1h 1m", countdown(3661, 1))
        assertEquals("Awaiting refresh", countdown(100, 100))
        assertEquals("Reset unavailable", countdown(null, 100))
    }

    @Test fun distinguishUnavailableImportedAndCached() {
        val state = StoredState(snapshot = snapshot(100, 50.0, 200))
        assertEquals("Unavailable", dataStatus(StoredState(), 15, 100))
        assertEquals("Cached data", dataStatus(state.copy(error = "offline"), 15, 110))
        assertEquals("Cached data", dataStatus(state, 15, 1100))
        assertEquals("Imported snapshot", dataStatus(state.copy(imported = true), 15, 110))
        assertEquals("Refreshed data", dataStatus(state, 15, 110))
        assertNull(state.snapshot?.weekly)
    }

    @Test fun notificationsRequireNewObservationAndDeduplicate() {
        val old = snapshot(90, 95.0, 100)
        val event = alerts(null, old, emptyMap()).single()
        assertEquals(2, event.severity)
        assertTrue(alerts(null, old, mapOf(event.key to 2)).isEmpty())
        assertTrue(alerts(null, snapshot(101, 95.0, 100), emptyMap()).isEmpty())
        assertEquals(0, alerts(old, snapshot(101, 5.0, 200), emptyMap()).single().severity)
        assertTrue(alerts(old, snapshot(99, 5.0, 200), emptyMap()).isEmpty())
    }

    @Test fun noCleartextOrEmbeddedCredentials() {
        for (url in listOf("http://host", "https://user@host", "https://host?token=x")) {
            assertThrows(IllegalArgumentException::class.java) { validateUrl(url) }
        }
    }

    @Test fun handleHttpAndNetworkFailures() {
        for (code in listOf(401, 409, 429, 503, 200)) {
            val client = BridgeClient(OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("test").header("Retry-After", "90").body("{}".toResponseBody()).build()
            }.build())
            val error = assertThrows(BridgeFailure::class.java) { client.fetch("https://example.invalid", "fixture") }
            assertEquals(code == 429 || code == 503, error.retryable)
        }
        val offline = BridgeClient(OkHttpClient.Builder().addInterceptor { throw UnknownHostException() }.build())
        assertThrows(UnknownHostException::class.java) { offline.fetch("https://example.invalid", "fixture") }
    }
}
