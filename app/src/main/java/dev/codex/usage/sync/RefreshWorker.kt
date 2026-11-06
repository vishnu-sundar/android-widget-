package dev.codex.usage.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import dev.codex.usage.UsageApplication
import dev.codex.usage.data.RefreshOutcome
import dev.codex.usage.widget.updateWidgets
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as UsageApplication).repository
        return when (repository.refresh()) {
            RefreshOutcome.SUCCESS -> Result.success()
            RefreshOutcome.RETRY -> if (runAttemptCount < 4) Result.retry() else Result.failure()
            RefreshOutcome.FAILURE -> Result.failure()
        }
    }
}

object RefreshScheduler {
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun periodic(context: Context, minutes: Int) {
        require(minutes in listOf(15, 30, 60))
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("usage-periodic", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<RefreshWorker>(minutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun now(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork("usage-manual", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("usage-periodic")
        WorkManager.getInstance(context).cancelUniqueWork("usage-manual")
    }
}

class ClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        (context.applicationContext as UsageApplication).scope.launch {
            try { updateWidgets(context) } finally { pending.finish() }
        }
    }
}
