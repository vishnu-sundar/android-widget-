package dev.codex.usage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import dev.codex.usage.sync.RefreshScheduler
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RebootTest {
    @Test fun periodicRequestSurvivesReboot() {
        val phase = InstrumentationRegistry.getArguments().getString("phase")
        assumeTrue(phase == "before" || phase == "after")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val idFile = File(app.filesDir, "test-periodic-work-id")
        if (phase == "before") RefreshScheduler.periodic(app, 30)
        val job = WorkManager.getInstance(app).getWorkInfosForUniqueWork("usage-periodic").get()
            .single { !it.state.isFinished }
        if (phase == "before") {
            idFile.writeText(job.id.toString())
        } else {
            assertEquals(idFile.readText(), job.id.toString())
            assertEquals(30 * 60_000L, job.periodicityInfo!!.repeatIntervalMillis)
            RefreshScheduler.cancel(app)
        }
    }
}
