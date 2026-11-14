package de.noctunix.obdbridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/** Drives the real service and the process-wide [ObdApi] through the phase of a presence start
 * that is still connecting. The BLE stack is Robolectric's shadow, so the connect attempt never
 * completes by itself, which is exactly the window these tests need. */
@RunWith(RobolectricTestRunner::class)
class CxRecordingServicePresenceStartTest {

    private lateinit var context: Context
    private lateinit var api: ObdApi
    private lateinit var store: CompanionStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ObdApi::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        context.getSharedPreferences(CompanionStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setState(BluetoothAdapter.STATE_ON)
        store = CompanionStore(context)
        store.savePairing(samplePairing())
        store.autoRecording = sampleRequest()
        api = ObdApi.getInstance(context)
    }

    private fun startAwaitingService(): Pair<ServiceController<CxRecordingService>, ObdStartRecordingRequest> {
        api.onAdapterPresence(true)
        val pending = checkNotNull(api.peekPendingRecordingRequest())
        val controller = Robolectric.buildService(
            CxRecordingService::class.java,
            Intent(context, CxRecordingService::class.java).putExtra(CxRecordingService.EXTRA_CONNECT_FIRST, true),
        ).create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        return controller to pending
    }

    private fun reasonOf(sessionId: String) = api.pendingAutoSessions().single { it.sessionId == sessionId }.endReason

    @Test
    fun theServiceWaitsForTheConnectionWithThePendingRequestHeld() {
        val (controller, pending) = startAwaitingService()

        assertEquals(pending, api.peekPendingRecordingRequest())
        assertNotNull(api.pendingStartOwner)
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun theStopActionWhileConnectingDropsTheStartSuppressesAndStopsTheService() {
        val (controller, pending) = startAwaitingService()

        controller.get().requestStop()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(api.peekPendingRecordingRequest())
        assertNull(api.pendingStartOwner)
        assertEquals("stopped before the adapter connected", reasonOf(pending.sessionId))
        assertNotNull(store.autoStartSuppression)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun stopRecordingFromDartWhileConnectingDoesTheSame() {
        val (controller, pending) = startAwaitingService()

        api.stopRecording(pending.sessionId) { }
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(api.peekPendingRecordingRequest())
        assertNotNull(store.autoStartSuppression)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun forgettingTheAdapterWhileConnectingAbortsWithoutSuppressing() {
        val (controller, pending) = startAwaitingService()

        api.forgetAdapter { }
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(api.peekPendingRecordingRequest())
        assertEquals("stopped before the adapter connected", reasonOf(pending.sessionId))
        assertNull(store.autoStartSuppression)
        assertNull(store.pairing)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun destroyingTheServiceWhileConnectingReleasesThePendingRequest() {
        val (controller, pending) = startAwaitingService()

        controller.destroy()

        assertNull(api.peekPendingRecordingRequest())
        assertNull(api.pendingStartOwner)
        assertEquals("service destroyed before the adapter connected", reasonOf(pending.sessionId))
    }

    @Test
    fun aConnectFirstStartWithoutAPairingFailsInsteadOfUsingWhateverLinkExists() {
        api.onAdapterPresence(true)
        val pending = checkNotNull(api.peekPendingRecordingRequest())
        store.clearPairing()

        val controller = Robolectric.buildService(
            CxRecordingService::class.java,
            Intent(context, CxRecordingService::class.java).putExtra(CxRecordingService.EXTRA_CONNECT_FIRST, true),
        ).create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(api.peekPendingRecordingRequest())
        assertEquals("no paired adapter", reasonOf(pending.sessionId))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun withoutBluetoothPermissionTheStartFailsAtOnceWithoutRetrying() {
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        val (controller, pending) = startAwaitingService()

        assertNull(api.peekPendingRecordingRequest())
        assertEquals("bluetooth off or permission missing", reasonOf(pending.sessionId))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }
}
