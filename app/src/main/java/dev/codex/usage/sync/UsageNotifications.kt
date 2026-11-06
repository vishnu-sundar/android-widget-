package dev.codex.usage.sync

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.codex.usage.MainActivity
import dev.codex.usage.R
import dev.codex.usage.model.UsageAlert

class UsageNotifications(private val context: Context) {
    fun post(alert: UsageAlert): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(NotificationChannel("usage", "Codex usage alerts", NotificationManager.IMPORTANCE_DEFAULT))
        if (system.getNotificationChannel("usage")?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, "usage").setSmallIcon(R.drawable.ic_usage)
            .setContentTitle("Codex usage").setContentText(alert.message).setContentIntent(intent).setAutoCancel(true).build()
        return try { manager.notify((alert.key + alert.severity).hashCode(), notification); true } catch (_: SecurityException) { false }
    }
    fun clear() = NotificationManagerCompat.from(context).cancelAll()
}
