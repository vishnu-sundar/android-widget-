package dev.codex.usage.data

import dev.codex.usage.model.Snapshot
import dev.codex.usage.model.parseSnapshot
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class BridgeFailure(val safeMessage: String, val retryable: Boolean = false, val retryAt: Long = 0) : IOException(safeMessage)

fun validateUrl(raw: String): String {
    val url = raw.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid HTTPS bridge URL")
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "Use HTTPS without credentials, query parameters or fragments"
    }
    require(url.encodedPath.trimEnd('/') == "") { "Enter the bridge base URL without a path" }
    return url.toString().trimEnd('/')
}

class BridgeClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()) {
    fun fetch(baseUrl: String, token: String): Snapshot {
        val base = validateUrl(baseUrl)
        val request = Request.Builder().url("$base/usage").header("Authorization", "Bearer $token").header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val now = Instant.now().epochSecond
                val retry = response.header("Retry-After")?.let { raw -> raw.toLongOrNull()?.let { now + it.coerceAtLeast(0) }
                    ?: runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }.getOrNull() } ?: now + 60
                throw when (response.code) {
                    401, 403 -> BridgeFailure("Bridge authentication failed. Reconnect with the bridge token.")
                    409 -> BridgeFailure("Codex is not signed in with ChatGPT on the bridge computer.")
                    429 -> BridgeFailure("Rate limited. Refresh will wait before retrying.", true, retry)
                    in 500..599 -> BridgeFailure("Bridge or Codex is unavailable. Cached data is retained.", true, retry)
                    else -> BridgeFailure("Bridge returned HTTP ${response.code}. Check the bridge URL.")
                }
            }
            val body = response.body ?: throw BridgeFailure("Bridge returned no usage data")
            if (body.contentLength() > 262_144) throw BridgeFailure("Bridge response is too large")
            val source = body.source()
            if (source.request(262_145L)) throw BridgeFailure("Bridge response is too large")
            return try { parseSnapshot(source.readUtf8()) } catch (_: IllegalArgumentException) {
                throw BridgeFailure("Bridge returned invalid or unsupported usage data")
            }
        }
    }
}
