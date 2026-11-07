package dev.codex.usage

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class NativeCodexProbe {
    @Test fun probeNativeAppServer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "native-codex-probe").apply { mkdirs() }
        val home = File(folder, "home").apply { mkdirs() }
        val codexHome = File(home, ".codex").apply { mkdirs() }
        val binary = File(context.applicationInfo.nativeLibraryDir, "libcodex.so")
        val report = JSONObject().put("androidSdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS.first()).put("uid", Process.myUid())
            .put("binaryExists", binary.exists()).put("binaryExecutable", binary.canExecute())
        val certs = File(folder, "roots.pem")
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        certs.writeText(store.aliases().toList().joinToString("\n") { alias ->
            "-----BEGIN CERTIFICATE-----\n" +
                Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(store.getCertificate(alias).encoded) +
                "\n-----END CERTIFICATE-----"
        })
        fun launch(vararg args: String): java.lang.Process {
            val builder = ProcessBuilder(listOf(binary.absolutePath) + args)
            builder.directory(home)
            val env = builder.environment()
            for (name in listOf("OPENAI_API_KEY", "CODEX_API_KEY", "CODEX_ACCESS_TOKEN")) env.remove(name)
            env["HOME"] = home.absolutePath
            env["CODEX_HOME"] = codexHome.absolutePath
            env["TMPDIR"] = folder.absolutePath
            env["SSL_CERT_FILE"] = certs.absolutePath
            return builder.start()
        }
        try {
            val version = launch("--version")
            if (!version.waitFor(30, TimeUnit.SECONDS)) {
                version.destroyForcibly()
                error("Version process timed out")
            }
            report.put("versionExit", version.exitValue())
            report.put("version", version.inputStream.bufferedReader().readText().take(160))
            report.put("versionStderr", version.errorStream.bufferedReader().readText().take(600))
            val child = launch("app-server", "--listen", "stdio://")
            val replies = LinkedBlockingQueue<String>()
            val stderr = StringBuilder()
            thread(isDaemon = true) {
                child.inputStream.bufferedReader().useLines { lines -> lines.forEach { replies.offer(it) } }
            }
            thread(isDaemon = true) {
                child.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { synchronized(stderr) { if (stderr.length < 2000) stderr.append(it).append('\n') } }
                }
            }
            val writer = child.outputStream.bufferedWriter()
            fun rpc(id: Int, method: String, params: JSONObject = JSONObject()): JSONObject {
                val packet = JSONObject().put("id", id).put("method", method).put("params", params)
                writer.write(packet.toString() + "\n")
                writer.flush()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
                while (System.nanoTime() < deadline) {
                    val line = replies.poll(1, TimeUnit.SECONDS)
                    if (line == null) {
                        if (!child.isAlive) error("Codex exited with " + child.exitValue())
                        continue
                    }
                    val reply = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    if (reply.optInt("id", -1) == id) return reply
                }
                error("Timed out waiting for " + method)
            }
            try {
                val init = rpc(1, "initialize", JSONObject().put("clientInfo",
                    JSONObject().put("name", "codex_android_probe").put("version", "0.1.0")))
                report.put("initializeSucceeded", init.has("result"))
                if (init.has("error")) report.put("initializeError", init.getJSONObject("error"))
                writer.write("{\"method\":\"initialized\",\"params\":{}}\n")
                writer.flush()
                val account = rpc(2, "account/read", JSONObject().put("refreshToken", false))
                report.put("accountReadSucceeded", account.has("result"))
                report.put("accountSignedIn", account.optJSONObject("result")?.optJSONObject("account") != null)
                val usage = rpc(3, "account/rateLimits/read")
                report.put("rateLimitsHasResult", usage.has("result"))
                if (usage.has("error")) report.put("rateLimitsError", usage.getJSONObject("error"))
                val login = rpc(4, "account/login/start", JSONObject().put("type", "chatgptDeviceCode"))
                val result = login.optJSONObject("result")
                report.put("deviceLoginStarted", result != null)
                // Never publish login codes, URLs, identifiers or tokens.
                report.put("deviceLoginHasUserCode", result?.has("userCode") == true)
                report.put("deviceLoginHasVerificationUrl", result?.has("verificationUrl") == true)
                if (login.has("error")) report.put("deviceLoginErrorCode", login.getJSONObject("error").optInt("code"))
            } finally {
                child.destroyForcibly()
                child.waitFor(5, TimeUnit.SECONDS)
                report.put("serverStderr", synchronized(stderr) { stderr.toString() })
            }
        } catch (error: Exception) {
            report.put("probeFailure", error.javaClass.simpleName + ": " + error.message)
        } finally {
            File(context.filesDir, "codex-probe.json").writeText(report.toString(2))
            folder.deleteRecursively()
        }
    }
}
