package de.noctunix.obdbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [CxBleClient]'s connection state machine against a scripted
 * [FakeCxBleIo] — no real Bluetooth radio, no Nordic `BleManager`, no
 * Robolectric shadow is involved or needed, since [CxBleClient] itself never
 * touches an Android API; only [AndroidCxBleIo] (untested here; see
 * [CxServiceCheckTest] and [CxAdapterHandleRegistryTest] for the pieces of it
 * that are) does.
 */
private class FakeCxBleIo : CxBleIo {
    var permissionsGranted = true
    var bluetoothEnabled = true
    var scanStarted = false
    var scanStopped = false
    var lastScanDurationMs: Long? = null
    var disconnectCalls = 0
    var lastConnectHandle: CxAdapterHandle? = null
    var lastCallback: CxBleIoCallback? = null

    var onScanResult: ((CxAdapterInfo) -> Unit)? = null
    var onScanEnded: ((CxLinkFailure?) -> Unit)? = null

    override fun hasRequiredPermissions(): Boolean = permissionsGranted

    override fun isBluetoothEnabled(): Boolean = bluetoothEnabled

    override fun startScan(
        durationMs: Long,
        onResult: (CxAdapterInfo) -> Unit,
        onScanEnded: (CxLinkFailure?) -> Unit,
    ) {
        scanStarted = true
        lastScanDurationMs = durationMs
        this.onScanResult = onResult
        this.onScanEnded = onScanEnded
    }

    override fun stopScan() {
        scanStopped = true
    }

    override fun connect(handle: CxAdapterHandle, callback: CxBleIoCallback) {
        lastConnectHandle = handle
        lastCallback = callback
    }

    override fun disconnect() {
        disconnectCalls++
    }

    var writtenBytes: MutableList<ByteArray> = mutableListOf()
    var writeResult = true

    override fun write(bytes: ByteArray): Boolean {
        writtenBytes.add(bytes)
        return writeResult
    }
}

class CxBleClientTest {

    @Test
    fun scanThenConnectThenServiceCheckThenSubscribePathReachesConnected() {
        val io = FakeCxBleIo()
        val client = CxBleClient(io)
        val states = mutableListOf<CxLinkState>()
        client.setStateListener { states.add(it) }
        val found = mutableListOf<CxAdapterInfo>()

        val scanAccepted = client.startScan { found.add(it) }
        assertTrue(scanAccepted)
        assertTrue(io.scanStarted)
        assertEquals(CxBleClient.DEFAULT_SCAN_DURATION_MS, io.lastScanDurationMs)
        io.onScanResult?.invoke(CxAdapterInfo("opaque-handle-1", "OBDLink CX", -60))
        assertEquals(1, found.size)
        client.stopScan()
        assertTrue(io.scanStopped)
        assertEquals(CxLinkState.Idle, client.currentState())

        val connectAccepted = client.connect("opaque-handle-1")
        assertTrue(connectAccepted)
        assertEquals(CxLinkState.Connecting("opaque-handle-1"), client.currentState())

        // The fake plays the role of the Nordic manager succeeding bonding,
        // service verification and notification subscription, then reports
        // the write length derived from the MTU actually negotiated (not
        // assumed) — 182 here stands in for "whatever Android negotiated".
        io.lastCallback?.onConnected(writeLength = 182)

        assertEquals(
            CxLinkState.Connected("opaque-handle-1", 182),
            client.currentState(),
        )
        assertTrue(client.isAuthorizedHandle("opaque-handle-1"))
        assertFalse(client.isAuthorizedHandle("opaque-handle-2"))
        assertEquals(
            listOf(
                CxLinkState.Scanning,
                CxLinkState.Idle,
                CxLinkState.Connecting("opaque-handle-1"),
                CxLinkState.Connected("opaque-handle-1", 182),
            ),
            states,
        )
    }

    @Test
    fun scanEndingOnItsOwnWithoutStopScanReportsTheFailure() {
        val io = FakeCxBleIo()
        val client = CxBleClient(io)

        client.startScan { }
        // The platform scan ended by itself (duration elapsed with nothing
        // worth connecting to, or the scan itself failed) — never called via
        // stopScan(), so it carries a failure rather than Idle.
        io.onScanEnded?.invoke(CxLinkFailure.NOT_FOUND)

        assertEquals(CxLinkState.Failed(CxLinkFailure.NOT_FOUND, null), client.currentState())
    }

    @Test
    fun secondScanWhileAlreadyScanningIsRejected() {
        val io = FakeCxBleIo()
        val client = CxBleClient(io)
        client.startScan { }

        val secondAccepted = client.startScan { }

        assertFalse(secondAccepted)
    }

    @Test
    fun scanWhileConnectedIsRejected() {
        val io = FakeCxBleIo()
        val client = CxBleClient(io)
        client.connect("opaque-handle-1")
        io.lastCallback?.onConnected(writeLength = 20)

        val scanAccepted = client.startScan { }

        assertFalse(scanAccepted)
        assertEquals(CxLinkState.Connected("opaque-handle-1", 20), client.currentState())
    }

    @Test
    fun connectWhileScanningStopsTheScanFirst() {
        val io = FakeCxBleIo()
        val client = CxBleClient(io)
        client.startScan { }

        val accepted = client.connect("opaque-handle-1")

        assertTrue(accepted)
        assertTrue(io.scanStopped)
        assertEquals(CxLinkState.Connecting("opaque-handle-1"), client.currentState())
    }

    @Test
    fun disconnectWhileScanningStopsTheScanAndAllowsAFreshScanAfterwards() {
        val io = FakeCxBleIo()
        val client = CxBleClient(io)
        client.startScan { }
        assertEquals(CxLinkState.Scanning, client.currentState())

        client.disconnect()

        assertTrue(io.scanStopped)
        assertEquals(CxLinkState.Disconnected(null), client.currentState())

        // The scan guard was released along with the Scanning state, so a
        // fresh scan is accepted right away — nothing is left stuck.
        val secondScanAccepted = client.startScan { }
        assertTrue(secondScanAccepted)
    }

    @Test
    fun aStaleScanTimeoutArrivingAfterDisconnectDuringScanningChangesNothing() {
        val io = FakeCxBleIo()
        val client = CxBleClient(io)
        client.startScan { }
        val staleOnScanEnded = io.onScanEnded
        client.disconnect()
        assertEquals(CxLinkState.Disconnected(null), client.currentState())

        // The first scan's own timeout fires late, after disconnect() already
        // moved the client past Scanning — must not resurrect it.
        staleOnScanEnded?.invoke(CxLinkFailure.NOT_FOUND)

        assertEquals(CxLinkState.Disconnected(null), client.currentState())
    }

    @Test
    fun connectWithoutPermissionFailsDistinctlyAndNeverReachesIo() {
        val io = FakeCxBleIo()
        io.permissionsGranted = false
        val client = CxBleClient(io)

        val accepted = client.connect("opaque-handle-1")

        assertFalse(accepted)
        assertEquals(
            CxLinkState.Failed(CxLinkFailure.PERMISSION_DENIED, "opaque-handle-1"),
            client.currentState(),
        )
        assertNull(io.lastConnectHandle)
    }

    @Test
    fun connectWithBluetoothOffFailsDistinctlyAndNeverReachesIo() {
        val io = FakeCxBleIo()
        io.bluetoothEnabled = false
        val client = CxBleClient(io)

        val accepted = client.connect("opaque-handle-1")

        assertFalse(accepted)
        assertEquals(
            CxLinkState.Failed(CxLinkFailure.BLUETOOTH_OFF, "opaque-handle-1"),
            client.currentState(),
        )
        assertNull(io.lastConnectHandle)
    }

    @Test
    fun missingServiceFailsWithServiceMissingInsteadOfASilentPartialConnection() {
        val io = FakeCxBleIo()
        val client = CxBleClient(io)

        client.connect("opaque-handle-1")
        // Standing in for a real adapter whose GATT services do not actually
        // carry all three usable CX characteristics; see CxServiceCheckTest
        // for the actual presence/property check this stands in for here.
        io.lastCallback?.onFailed(CxLinkFailure.SERVICE_MISSING)

        assertEquals(
            CxLinkState.Failed(CxLinkFailure.SERVICE_MISSING, "opaque-handle-1"),
            client.currentState(),
        )
        assertFalse(client.isAuthorizedHandle("opaque-handle-1"))
    }

    @Test
    fun secondConnectToADifferentHandleWhileConnectedIsRejected() {
        val io = FakeCxBleIo()
        val client = CxBleClient(io)
        client.connect("opaque-handle-1")
        io.lastCallback?.onConnected(writeLength = 20)
        assertEquals(
            CxLinkState.Connected("opaque-handle-1", 20),
            client.currentState(),
        )

        val secondAccepted = client.connect("opaque-handle-2")

        assertFalse(secondAccepted)
        assertEquals("opaque-handle-1", io.lastConnectHandle)
        // State is unchanged — still connected to the first adapter, not
        // overwritten by the rejected attempt.
        assertEquals(
            CxLinkState.Connected("opaque-handle-1", 20),
            client.currentState(),
        )
        assertTrue(client.isAuthorizedHandle("opaque-handle-1"))
        assertFalse(client.isAuthorizedHandle("opaque-handle-2"))
    }

    @Test
    fun secondConnectWhileAConnectionAttemptIsStillInFlightIsAlsoRejected() {
        val io = FakeCxBleIo()
        val client = CxBleClient(io)
        client.connect("opaque-handle-1")
        assertEquals(CxLinkState.Connecting("opaque-handle-1"), client.currentState())

        val secondAccepted = client.connect("opaque-handle-2")

        assertFalse(secondAccepted)
        assertEquals(CxLinkState.Connecting("opaque-handle-1"), client.currentState())
    }

    @Test
    fun disconnectClearsStateAndAllowsConnectingToAnotherHandleAfterwards() {
        val io = FakeCxBleIo()
        val client = CxBleClient(io)
        client.connect("opaque-handle-1")
        io.lastCallback?.onConnected(writeLength = 20)

        client.disconnect()

        assertEquals(1, io.disconnectCalls)
        assertEquals(
            CxLinkState.Disconnected("opaque-handle-1"),
            client.currentState(),
        )
        assertFalse(client.isAuthorizedHandle("opaque-handle-1"))

        // The handle guard was released, so a fresh connect (to any handle,
        // including a new one) is accepted again.
        val reconnectAccepted = client.connect("opaque-handle-2")
        assertTrue(reconnectAccepted)
        assertEquals(CxLinkState.Connecting("opaque-handle-2"), client.currentState())
    }

    @Test
    fun disconnectWithoutAnyActiveConnectionIsIdempotent() {
        val io = FakeCxBleIo()
        val client = CxBleClient(io)

        client.disconnect()

        assertEquals(1, io.disconnectCalls)
        assertEquals(CxLinkState.Disconnected(null), client.currentState())
    }

    @Test
    fun aCallbackFromAnAbandonedAttemptArrivingAfterDisconnectChangesNothing() {
        val io = FakeCxBleIo()
        val client = CxBleClient(io)
        client.connect("opaque-handle-1")
        val staleCallback = io.lastCallback
        client.disconnect()
        assertEquals(CxLinkState.Disconnected("opaque-handle-1"), client.currentState())

        // The old attempt's callback fires late — e.g. a GATT connect result
        // for a connection this client already tore down. It must not
        // resurrect the handle guard or overwrite the Disconnected state.
        staleCallback?.onFailed(CxLinkFailure.PLATFORM_ERROR)

        assertEquals(CxLinkState.Disconnected("opaque-handle-1"), client.currentState())

        // Nothing is stuck either: a fresh connect is still accepted.
        val reconnectAccepted = client.connect("opaque-handle-2")
        assertTrue(reconnectAccepted)
    }

    @Test
    fun aCallbackFromAnAttemptSupersededByANewOneChangesNothing() {
        val io = FakeCxBleIo()
        val client = CxBleClient(io)
        client.connect("opaque-handle-1")
        val firstCallback = io.lastCallback
        firstCallback?.onFailed(CxLinkFailure.TIMEOUT)
        assertEquals(
            CxLinkState.Failed(CxLinkFailure.TIMEOUT, "opaque-handle-1"),
            client.currentState(),
        )

        client.connect("opaque-handle-2")
        assertEquals(CxLinkState.Connecting("opaque-handle-2"), client.currentState())

        // The first attempt's callback is handed a second, unrelated signal
        // after a new attempt already started; it must not touch the new
        // attempt's state.
        firstCallback?.onConnected(writeLength = 99)

        assertEquals(CxLinkState.Connecting("opaque-handle-2"), client.currentState())
    }

    @Test
    fun writeIsRejectedBeforeAConnectionIsEstablished() {
        val io = FakeCxBleIo()
        val client = CxBleClient(io)

        assertFalse(client.write("ATZ\r".toByteArray()))
        assertEquals(0, io.writtenBytes.size)

        client.connect("opaque-handle-1")
        // Connecting, not yet Connected: still rejected.
        assertFalse(client.write("ATZ\r".toByteArray()))
        assertEquals(0, io.writtenBytes.size)
    }

    @Test
    fun writeDelegatesToIoOnceConnected() {
        val io = FakeCxBleIo()
        val client = CxBleClient(io)
        client.connect("opaque-handle-1")
        io.lastCallback?.onConnected(writeLength = 20)

        val accepted = client.write("ATZ\r".toByteArray())

        assertTrue(accepted)
        assertEquals(1, io.writtenBytes.size)
        assertEquals("ATZ\r", String(io.writtenBytes.single()))
    }

    @Test
    fun notificationListenerReceivesBytesForwardedFromTheActiveConnection() {
        val io = FakeCxBleIo()
        val client = CxBleClient(io)
        val received = mutableListOf<String>()
        client.setNotificationListener { bytes -> received.add(String(bytes)) }

        client.connect("opaque-handle-1")
        io.lastCallback?.onConnected(writeLength = 20)
        io.lastCallback?.onNotification("OK\r>".toByteArray())

        assertEquals(listOf("OK\r>"), received)
    }

    @Test
    fun aNotificationFromACallbackSupersededByANewAttemptIsIgnored() {
        val io = FakeCxBleIo()
        val client = CxBleClient(io)
        val received = mutableListOf<String>()
        client.setNotificationListener { bytes -> received.add(String(bytes)) }

        client.connect("opaque-handle-1")
        val staleCallback = io.lastCallback
        client.disconnect()
        client.connect("opaque-handle-2")

        staleCallback?.onNotification("stale\r>".toByteArray())

        assertTrue(received.isEmpty())
    }
}
