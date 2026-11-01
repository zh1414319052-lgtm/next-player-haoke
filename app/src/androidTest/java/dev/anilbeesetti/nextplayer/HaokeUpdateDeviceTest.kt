package dev.anilbeesetti.nextplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anilbeesetti.nextplayer.updates.HaokeUpdateController
import dev.anilbeesetti.nextplayer.updates.HaokeUpdateService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real stable feed acceptance on the actual signed package; never publishes anything. */
@RunWith(AndroidJUnit4::class)
class HaokeUpdateDeviceTest {
    @Test fun installedFormalIdentityAcceptsItsCurrentSignedFeed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        org.junit.Assume.assumeTrue(context.packageName == "dev.anilbeesetti.nextplayer.haoke")
        val complete = CountDownLatch(1)
        var status: HaokeUpdateService.Status? = null
        lateinit var updates: HaokeUpdateController
        instrumentation.runOnMainSync {
            updates = HaokeUpdateController(context)
            updates.check(true) { result ->
                status = result.status
                complete.countDown()
            }
        }
        try {
            assertTrue("Bounded signed feed request completes", complete.await(20, TimeUnit.SECONDS))
            assertEquals(HaokeUpdateService.Status.CURRENT, status)
        } finally {
            instrumentation.runOnMainSync { updates.close() }
        }
    }
}
