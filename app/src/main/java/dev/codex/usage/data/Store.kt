package dev.codex.usage.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.codex.usage.model.*
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.usageStore by preferencesDataStore("usage")
class Store(context: Context) {
    private val data = context.applicationContext.usageStore
    private val stateKey = stringPreferencesKey("state")
    private val settingsKey = stringPreferencesKey("settings")
    val states = data.data.map { p -> p[stateKey]?.let { runCatching { WireJson.decodeFromString<StoredState>(it) }.getOrNull() } ?: StoredState() }
    val settings = data.data.map { p -> p[settingsKey]?.let { runCatching { WireJson.decodeFromString<Settings>(it) }.getOrNull() } ?: Settings() }
    suspend fun updateState(change: (StoredState) -> StoredState) = data.edit { p ->
        val old = p[stateKey]?.let { runCatching { WireJson.decodeFromString<StoredState>(it) }.getOrNull() } ?: StoredState()
        p[stateKey] = WireJson.encodeToString(change(old))
    }
    suspend fun settings(value: Settings) = data.edit { it[settingsKey] = WireJson.encodeToString(value) }
}

/** Only the randomly generated bridge token is stored here; no OpenAI credentials. */
class Credentials(context: Context) {
    private val prefs = context.getSharedPreferences("bridge_secure", Context.MODE_PRIVATE)
    private val alias = "codex-bridge-aes-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun save(url: String, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val value = Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("url", url).putString("token", value).commit())
    }
    @Synchronized fun read(): Pair<String, String>? {
        val url = prefs.getString("url", null) ?: return null
        val value = Base64.decode(prefs.getString("token", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value.copyOfRange(0, 12))) }
        return url to String(cipher.doFinal(value.copyOfRange(12, value.size)), Charsets.UTF_8)
    }
    fun connected() = prefs.contains("url")
    fun url() = prefs.getString("url", "") ?: ""
    @Synchronized fun clear() { check(prefs.edit().clear().commit()) }
}
