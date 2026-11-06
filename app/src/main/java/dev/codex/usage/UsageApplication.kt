package dev.codex.usage

import android.app.Application
import dev.codex.usage.data.Repository
import dev.codex.usage.sync.RefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class UsageApplication : Application() {
    val repository by lazy { Repository(this) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun onCreate() {
        super.onCreate()
        scope.launch { if (repository.credentials.connected()) RefreshScheduler.periodic(this@UsageApplication, repository.store.settings.first().interval) }
    }
}
