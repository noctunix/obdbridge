package de.noctunix.obdbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Exercises [CxRecordingService]'s wake-lock lifecycle directly, rather than through a full
 * [ObdApi]/BLE-backed recording start — Robolectric does not deliver a real start intent to a
 * plain `startForegroundService` call (see `ObdApiTest`'s own comment on that), so driving this
 * through [CxRecordingService.onStartCommand] would need a real connected [ObdApi] singleton,
 * which needs a real BLE stack this test environment does not have. */
@RunWith(RobolectricTestRunner::class)
class CxRecordingServiceTest {

    private fun newService(): CxRecordingService =
        Robolectric.buildService(CxRecordingService::class.java).create().get()

    @Test
    fun acquireWakeLockIfRequestedHoldsItUntilReleased() {
        val service = newService()

        service.acquireWakeLockIfRequested(hold = true)
        assertTrue(service.isWakeLockHeld())

        service.releaseWakeLock()
        assertFalse(service.isWakeLockHeld())
    }

    @Test
    fun acquireWakeLockIfRequestedIsANoOpWhenNotRequested() {
        val service = newService()

        service.acquireWakeLockIfRequested(hold = false)
        assertFalse(service.isWakeLockHeld())

        // Releasing an already-absent lock must not throw.
        service.releaseWakeLock()
        assertFalse(service.isWakeLockHeld())
    }

    @Test
    fun releaseWakeLockIsIdempotent() {
        val service = newService()
        service.acquireWakeLockIfRequested(hold = true)

        service.releaseWakeLock()
        service.releaseWakeLock()

        assertFalse(service.isWakeLockHeld())
    }
}
