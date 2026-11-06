package dev.codex.usage

import android.appwidget.*
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import dev.codex.usage.model.Settings
import dev.codex.usage.sync.RefreshScheduler
import dev.codex.usage.widget.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UsageApplication

    private fun render(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(app.filesDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun containsText(view: View, match: String): Boolean {
        if (view is TextView && view.text.toString().contains(match)) return true
        return view is ViewGroup && (0 until view.childCount).any { containsText(view.getChildAt(it), match) }
    }

    @Test fun dashboardAndHostedWidgets() {
        runBlocking { app.repository.disconnect(); app.repository.store.settings(Settings()) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { render(it.window.decorView, "dashboard") }
            val host = AppWidgetHost(app, 81)
            val manager = AppWidgetManager.getInstance(app)
            val views = mutableListOf<AppWidgetHostView>()
            val names = listOf("widget-small", "widget-medium", "widget-large")
            val receivers = listOf(SmallReceiver::class.java, MediumReceiver::class.java, LargeReceiver::class.java)
            try {
                scenario.onActivity { activity ->
                    val frame = FrameLayout(activity)
                    activity.setContentView(frame)
                    host.startListening()
                    for ((i, receiver) in receivers.withIndex()) {
                        val w = if (i == 0) 160 else 310
                        val h = if (i == 2) 300 else 156
                        val options = Bundle().apply {
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h)
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
                            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(w.toFloat(), h.toFloat())))
                        }
                        val id = host.allocateAppWidgetId()
                        assertTrue("Test host needs bind permission", manager.bindAppWidgetIdIfAllowed(id, ComponentName(app, receiver), options))
                        val view = host.createView(activity, id, manager.getAppWidgetInfo(id))
                        val scale = activity.resources.displayMetrics.density
                        frame.addView(view, FrameLayout.LayoutParams((w * scale).toInt(), (h * scale).toInt()))
                        view.updateAppWidgetSize(options, listOf(SizeF(w.toFloat(), h.toFloat())))
                        views.add(view)
                    }
                }
                fun awaitText(label: String) {
                    val deadline = android.os.SystemClock.elapsedRealtime() + 45000
                    while (android.os.SystemClock.elapsedRealtime() < deadline) {
                        var ready = false
                        scenario.onActivity { ready = views.all { containsText(it, label) } }
                        if (ready) return
                        Thread.sleep(250)
                    }
                    fail("Widgets did not all render '$label'")
                }
                awaitText("Connect in app")
                scenario.onActivity { views.forEachIndexed { i, view -> render(view, names[i]) } }
                runBlocking {
                    app.repository.store.settings(Settings(theme = "light"))
                    updateWidgets(app)
                    app.repository.import("""{"source":"codex-app-server","fetchedAt":100,"limits":[]}""")
                }
                awaitText("Imported")
                assertEquals("light", runBlocking { app.repository.store.settings.first().theme })
            } finally {
                scenario.onActivity { host.stopListening(); host.deleteHost() }
                runBlocking { app.repository.disconnect(); app.repository.store.settings(Settings()) }
            }
        }
    }

    @Test fun periodicWorkIsUniqueAndNetworkConstrained() {
        for (interval in listOf(15, 30, 60)) RefreshScheduler.periodic(app, interval)
        val jobs = WorkManager.getInstance(app).getWorkInfosForUniqueWork("usage-periodic").get()
        assertEquals(1, jobs.count { !it.state.isFinished })
        assertEquals(androidx.work.NetworkType.CONNECTED, jobs.first { !it.state.isFinished }.constraints.requiredNetworkType)
        RefreshScheduler.cancel(app)
    }
}
