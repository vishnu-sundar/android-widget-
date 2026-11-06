package dev.codex.usage

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.codex.usage.model.Settings
import dev.codex.usage.model.StoredState
import dev.codex.usage.sync.RefreshScheduler
import dev.codex.usage.widget.updateWidgets
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as UsageApplication
    private val repository = app.repository
    val state = repository.store.states.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StoredState())
    val settings = repository.store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Settings())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val connected = MutableStateFlow(repository.credentials.connected())
    val bridgeUrl get() = repository.credentials.url()
    private fun operation(block: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true; message.value = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = if (e is IllegalArgumentException) e.message ?: "Invalid settings" else "Could not complete the operation. Check the file or connection." }
            finally { busy.value = false; connected.value = repository.credentials.connected() }
        }
    }
    fun refresh() = operation { repository.refresh() }
    fun connect(url: String, token: String) = operation {
        repository.connect(url, token)
        RefreshScheduler.cancel(app)
        RefreshScheduler.periodic(app, settings.value.interval)
        repository.refresh()
    }
    fun disconnect() = operation { RefreshScheduler.cancel(app); repository.disconnect() }
    fun importSnapshot(uri: Uri) = operation {
        val text = withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(262_145)
                var count = 0
                while (count < buffer.size) { val n = input.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                require(count <= 262_144) { "Snapshot file is too large" }
                String(buffer, 0, count, Charsets.UTF_8)
            } ?: error("Cannot read file")
        }
        repository.import(text)
        RefreshScheduler.cancel(app)
    }
    fun saveSettings(value: Settings) = operation {
        repository.store.settings(value)
        if (repository.credentials.connected()) RefreshScheduler.periodic(app, value.interval)
        updateWidgets(app)
    }
}
