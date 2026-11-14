package de.noctunix.obdbridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompanionControllerTest {

    private lateinit var context: Context
    private lateinit var store: CompanionStore
    private val handles = CxAdapterHandleRegistry()
    private var platformAddresses: List<String>? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(CompanionStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        store = CompanionStore(context)
    }

    private fun controller(sdkInt: Int = 34, registry: CxAdapterHandleRegistry = handles) =
        CompanionController(context, store, registry, sdkInt, associatedAddresses = { platformAddresses })

    @Test
    fun supportIsReportedSeparatelyForPairingAndPresence() {
        assertFalse(controller(sdkInt = 25).status().supported)
        controller(sdkInt = 28).status().let {
            assertTrue(it.supported)
            assertFalse(it.presenceSupported)
        }
        controller(sdkInt = 31).status().let {
            assertTrue(it.supported)
            assertTrue(it.presenceSupported)
        }
    }

    @Test
    fun anUnpairedAdapterHasNoHandleAndNothingArmed() {
        val status = controller().status()

        assertFalse(status.paired)
        assertNull(status.adapterHandle)
        assertFalse(status.presenceObserved)
        assertFalse(status.autoRecordingArmed)
    }

    @Test
    fun theRememberedHandleResolvesInAFreshProcessWithoutAScan() {
        store.savePairing(samplePairing())
        val freshRegistry = CxAdapterHandleRegistry()

        val status = controller(registry = freshRegistry).status()

        assertEquals("handle-remembered", status.adapterHandle)
        assertEquals("00:11:22:33:44:55", freshRegistry.addressFor("handle-remembered"))
        assertEquals("Adapter", status.advertisedName)
    }

    @Test
    fun settingAndClearingTheAutoRecordingRequestShowsInTheStatus() {
        store.savePairing(samplePairing())
        val controller = controller()

        controller.setAutoRecording(sampleRequest())
        assertTrue(controller.status().autoRecordingArmed)

        controller.setAutoRecording(null)
        assertFalse(controller.status().autoRecordingArmed)
    }

    @Test
    fun presenceObservationIsANoOpBelowApi31AndWithoutAPairing() {
        store.savePairing(samplePairing())
        assertFalse(controller(sdkInt = 30).setPresenceObservation(true).presenceObserved)

        store.clearPairing()
        assertFalse(controller().setPresenceObservation(true).presenceObserved)
    }

    @Test
    fun forgetErasesThePairingTheRequestAndTheHandle() {
        store.savePairing(samplePairing())
        store.autoRecording = sampleRequest()
        val controller = controller(sdkInt = 25)

        val status = controller.forget()

        assertFalse(status.paired)
        assertFalse(status.autoRecordingArmed)
        assertNull(handles.addressFor("handle-remembered"))
        assertNull(CompanionStore(context).pairing)
    }

    @Test
    fun aPairingThePlatformNoLongerListsIsDroppedFromTheStatus() {
        store.savePairing(samplePairing())
        platformAddresses = listOf("AA:AA:AA:AA:AA:AA")

        val status = controller().status()

        assertFalse(status.paired)
        assertNull(store.pairing)
    }

    @Test
    fun aPairingThePlatformStillListsIsKeptCaseInsensitively() {
        store.savePairing(samplePairing())
        platformAddresses = listOf("00:11:22:33:44:55".lowercase())

        assertTrue(controller().status().paired)
    }

    @Test
    fun pairingWithoutAnActivityReportsUnpairedAndStillCompletes() {
        var done = 0

        controller().pair { done++ }

        assertEquals(1, done)
        assertNull(store.pairing)
    }

    private fun pairingResult(address: String) = android.content.Intent().putExtra(
        android.companion.CompanionDeviceManager.EXTRA_DEVICE,
        android.bluetooth.BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address),
    )

    @Test
    fun aFirstPairingKeepsTheStoredRequestAndObservationFlag() {
        store.autoRecording = sampleRequest()
        store.presenceObserved = true
        val controller = controller(sdkInt = 30)

        controller.onActivityResult(CompanionController.REQUEST_CODE_PAIR, android.app.Activity.RESULT_OK, pairingResult("AA:BB:CC:DD:EE:FF"))

        assertEquals("AA:BB:CC:DD:EE:FF", store.pairing?.macAddress)
        assertEquals(sampleRequest(), store.autoRecording)
        assertTrue(store.presenceObserved)
    }

    @Test
    fun replacingAPairingKeepsTheRequestButResetsObservationAndHandle() {
        store.savePairing(samplePairing(associationId = null))
        store.autoRecording = sampleRequest()
        store.presenceObserved = true
        val controller = controller(sdkInt = 30)

        controller.onActivityResult(CompanionController.REQUEST_CODE_PAIR, android.app.Activity.RESULT_OK, pairingResult("AA:BB:CC:DD:EE:FF"))

        assertEquals("AA:BB:CC:DD:EE:FF", store.pairing?.macAddress)
        assertEquals(sampleRequest(), store.autoRecording)
        assertFalse(store.presenceObserved)
        assertNull(handles.addressFor("handle-remembered"))
    }

    @Test
    fun connectGrantedFollowsTheConnectLevelCheckNotTheScanLevelOne() {
        var granted = false
        val controller = CompanionController(context, store, handles, 34, connectGranted = { granted })

        assertFalse(controller.status().connectGranted)
        granted = true
        assertTrue(controller.status().connectGranted)
    }
}
