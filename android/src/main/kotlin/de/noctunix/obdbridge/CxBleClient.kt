package de.noctunix.obdbridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.WriteRequest
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.observer.ConnectionObserver

/** Opaque, process-local adapter identity. Never the BLE MAC address itself — the address is
 * kept only inside [CxAdapterHandleRegistry] and never leaves this process, let alone a log
 * line or a future network message. Matches the shape `ObdAdapterMessage.handle` carries it as. */
typealias CxAdapterHandle = String

/** Mirrors the `ObdLinkStateMessage.failure` enum values this will eventually be carried as. */
enum class CxLinkFailure {
    PERMISSION_DENIED,
    BLUETOOTH_OFF,
    NOT_FOUND,
    PAIRING_WINDOW_CLOSED,
    SERVICE_MISSING,
    TIMEOUT,
    PLATFORM_ERROR,
}

/** Mirrors the `ObdLinkStateMessage.state` values this will eventually be carried as, minus
 * `running` and `suspendedByPlatform`, which only apply once a recording exists. */
sealed class CxLinkState {
    data object Idle : CxLinkState()
    data object Scanning : CxLinkState()
    data class Connecting(val handle: CxAdapterHandle) : CxLinkState()
    data class Connected(val handle: CxAdapterHandle, val writeLength: Int) : CxLinkState()
    data class Disconnected(val handle: CxAdapterHandle?) : CxLinkState()
    data class Failed(val failure: CxLinkFailure, val handle: CxAdapterHandle?) : CxLinkState()
}

/** One scan hit. [rssi] and [advertisedName] are display-only; adapter selection is always by
 * [handle], decided by whoever presents the scan results, never automatically picked here. */
data class CxAdapterInfo(
    val handle: CxAdapterHandle,
    val advertisedName: String?,
    val rssi: Int?,
)

/** Terminal outcomes of one [CxBleIo.connect] attempt. At most one of [onConnected], [onDisconnected]
 * and [onFailed] is called for a given attempt — an implementation must not call more than one of
 * them, and must not call [onConnected] after the service/characteristic check in [CxBleIo.connect]
 * has failed. [onNotification] is different: it may fire any number of times, once per GATT
 * notification received on [CxBleUuids.NOTIFY], for as long as the subscription from
 * [CxBleIo.connect] is active — including *before* [onConnected] itself, since the real GATT
 * subscription is set up as part of bonding/service discovery, ahead of the "ready" callback that
 * [onConnected] reports. */
interface CxBleIoCallback {
    fun onConnected(writeLength: Int)
    fun onDisconnected()
    fun onFailed(failure: CxLinkFailure)
    fun onNotification(bytes: ByteArray)
}

/**
 * Seam between [CxBleClient]'s connection state machine and the actual platform BLE stack. The
 * production implementation ([AndroidCxBleIo]) drives a real `BluetoothAdapter`/GATT connection
 * that a plain JVM test cannot genuinely exercise — bonding, the actually negotiated MTU, and
 * reconnection behaviour all need a real Bluetooth radio to observe; [CxBleClientTest] fakes
 * this interface instead for everything that does not.
 */
interface CxBleIo {
    fun hasRequiredPermissions(): Boolean
    fun isBluetoothEnabled(): Boolean

    /** Scans for up to [durationMs]; `onResult` fires once per discovered adapter.
     * `onScanEnded` fires exactly once, with `null` only when [stopScan] ends the scan, and a
     * [CxLinkFailure] when scanning ends on its own — either the platform scan itself failed, or
     * [durationMs] elapsed without `stopScan` having been called (mapped to [CxLinkFailure.NOT_FOUND]:
     * nothing worth connecting to showed up in time). A second [startScan] call must first stop
     * whatever scan is still running (there is only ever one at a time). */
    fun startScan(durationMs: Long, onResult: (CxAdapterInfo) -> Unit, onScanEnded: (CxLinkFailure?) -> Unit)
    fun stopScan()
    fun connect(handle: CxAdapterHandle, callback: CxBleIoCallback)
    fun disconnect()

    /** Writes [bytes] to [CxBleUuids.WRITE] on the currently connected device, split into chunks
     * of the negotiated write length if needed. Returns `false` immediately, without attempting
     * anything, if there is no active connection — it does not wait for the write to actually
     * complete on the wire, matching [CxCommandTransport.write]'s own contract. */
    fun write(bytes: ByteArray): Boolean
}

/**
 * Reusable BLE client for the OBDLink CX: scan for the UART service, let the caller pick one
 * adapter deliberately, bond, connect, verify the three CX characteristics actually exist and
 * are actually usable (not just present under the right UUID), subscribe to notifications, and
 * report the negotiated write length. [write] and [setNotificationListener] carry raw bytes
 * across the connection for a higher layer ([CxCommandSession], via `ObdApi`); this class itself
 * has no idea what those bytes mean — it frames nothing, interprets nothing, and decides which
 * command to send to no one.
 *
 * What this class deliberately does **not** do: read back anything that identifies the specific
 * adapter (firmware/model string) to confirm it really is an OBDLink CX rather than any other BLE
 * peripheral that happens to advertise the same generic UART service UUID. Passing the service/
 * characteristic check here is necessary but not sufficient proof of device identity.
 *
 * Connection state is independent of any `Activity`/Flutter-engine lifecycle: this class holds
 * it itself and notifies [setStateListener] on every transition, so it can later be driven from
 * a background service without redesign.
 *
 * Deliberate single-adapter guard: once a `connect()` call is accepted, [connect] rejects any
 * further call for a *different* handle until [disconnect] runs, and [isAuthorizedHandle] lets a
 * later command layer refuse to address any device other than the one this class is actually
 * connected to. Every callback from one connection attempt carries that attempt's own generation
 * number; a callback arriving after [disconnect] (or after a terminal outcome already applied)
 * is for an attempt that no longer matters and is ignored rather than allowed to contradict
 * whatever happened since. All public methods and the [CxBleIo] callbacks they install run under
 * this instance's own monitor, so a callback racing a concurrently called [disconnect] from
 * another thread cannot interleave with it.
 */
class CxBleClient(private val io: CxBleIo) {

    @Volatile
    private var state: CxLinkState = CxLinkState.Idle

    /** The handle a `connect()` call is in progress or succeeded for; `null` once disconnected
     * or failed. Guards against a second, different handle being dispatched concurrently. */
    @Volatile
    private var activeHandle: CxAdapterHandle? = null

    /** Bumped on every [connect] and [disconnect] call. A callback captures the value current
     * at the time it was issued; if that no longer matches by the time the callback actually
     * fires, the attempt it belongs to has already been superseded or torn down, and the
     * callback changes nothing. */
    @Volatile
    private var generation: Long = 0L

    private var listener: ((CxLinkState) -> Unit)? = null

    /** Set by whoever drives commands over this connection (e.g. `ObdApi`); receives every
     * [CxBleIoCallback.onNotification] for as long as the connection it was set during stays up —
     * each dispatched callback re-checks [generation] itself (see [connect]'s anonymous
     * [CxBleIoCallback]) before invoking this, so bytes for an attempt already superseded or torn
     * down never reach it. */
    private var notificationListener: ((ByteArray) -> Unit)? = null

    fun setNotificationListener(listener: ((ByteArray) -> Unit)?) {
        this.notificationListener = listener
    }

    /** Writes [bytes] over the active connection. `false` without attempting anything if not
     * currently [CxLinkState.Connected]. Mirrors [CxCommandTransport.write]'s contract —
     * `ObdApi` wires a [CxCommandSession] directly to this method. */
    @Synchronized
    fun write(bytes: ByteArray): Boolean {
        if (state !is CxLinkState.Connected) return false
        return io.write(bytes)
    }

    fun setStateListener(listener: ((CxLinkState) -> Unit)?) {
        this.listener = listener
    }

    fun currentState(): CxLinkState = state

    fun hasRequiredPermissions(): Boolean = io.hasRequiredPermissions()

    fun isBluetoothEnabled(): Boolean = io.isBluetoothEnabled()

    /** Whether [handle] is the one adapter this client is currently `Connected` to — the check
     * a later command layer uses before writing anything to `FFF2`. */
    fun isAuthorizedHandle(handle: CxAdapterHandle): Boolean {
        val connected = state as? CxLinkState.Connected ?: return false
        return connected.handle == handle
    }

    /** Starts scanning for the UART service; `onResult` fires once per discovered adapter.
     * Does not connect to anything — adapter selection stays with the caller. Returns `false`
     * immediately, without ever asking the platform to scan, when permissions are missing,
     * Bluetooth is off, a scan is already running, or a connection attempt/connection is already
     * active. A `true` return means scanning was requested; it does not guarantee scanning is
     * still running by the time the caller checks again, since the platform scan can still end
     * on its own right afterward — watch [setStateListener] for the actual outcome. */
    @Synchronized
    fun startScan(
        durationMs: Long = DEFAULT_SCAN_DURATION_MS,
        onResult: (CxAdapterInfo) -> Unit,
    ): Boolean {
        if (state == CxLinkState.Scanning || activeHandle != null) {
            return false
        }
        if (!io.hasRequiredPermissions()) {
            setState(CxLinkState.Failed(CxLinkFailure.PERMISSION_DENIED, null))
            return false
        }
        if (!io.isBluetoothEnabled()) {
            setState(CxLinkState.Failed(CxLinkFailure.BLUETOOTH_OFF, null))
            return false
        }
        val myGeneration = ++generation
        setState(CxLinkState.Scanning)
        io.startScan(durationMs, onResult) { failure ->
            synchronized(this) {
                if (myGeneration != generation || state != CxLinkState.Scanning) return@startScan
                setState(if (failure != null) CxLinkState.Failed(failure, null) else CxLinkState.Idle)
            }
        }
        return true
    }

    @Synchronized
    fun stopScan() {
        io.stopScan()
        if (state == CxLinkState.Scanning) {
            generation++
            setState(CxLinkState.Idle)
        }
    }

    /** Connects to the deliberately chosen [handle]. Rejected (returns `false`, no state change)
     * when a connection attempt or connection to a *different* handle is already active — call
     * [disconnect] first. Calling again for the same handle while already connecting/connected
     * is likewise rejected rather than restarting the attempt. A scan still running when this is
     * called is stopped first; scanning and connecting never happen at the same time. */
    @Synchronized
    fun connect(handle: CxAdapterHandle): Boolean {
        if (activeHandle != null) {
            return false
        }
        if (!io.hasRequiredPermissions()) {
            setState(CxLinkState.Failed(CxLinkFailure.PERMISSION_DENIED, handle))
            return false
        }
        if (!io.isBluetoothEnabled()) {
            setState(CxLinkState.Failed(CxLinkFailure.BLUETOOTH_OFF, handle))
            return false
        }
        if (state == CxLinkState.Scanning) {
            io.stopScan()
            generation++
        }
        activeHandle = handle
        val myGeneration = ++generation
        setState(CxLinkState.Connecting(handle))
        io.connect(
            handle,
            object : CxBleIoCallback {
                override fun onConnected(writeLength: Int) {
                    synchronized(this@CxBleClient) {
                        if (myGeneration != generation) return
                        setState(CxLinkState.Connected(handle, writeLength))
                    }
                }

                override fun onDisconnected() {
                    synchronized(this@CxBleClient) {
                        if (myGeneration != generation) return
                        activeHandle = null
                        setState(CxLinkState.Disconnected(handle))
                    }
                }

                override fun onFailed(failure: CxLinkFailure) {
                    synchronized(this@CxBleClient) {
                        if (myGeneration != generation) return
                        activeHandle = null
                        setState(CxLinkState.Failed(failure, handle))
                    }
                }

                override fun onNotification(bytes: ByteArray) {
                    synchronized(this@CxBleClient) {
                        if (myGeneration != generation) return
                        notificationListener?.invoke(bytes)
                    }
                }
            },
        )
        return true
    }

    /** Disconnects the active adapter, if any, and stops a still-running scan instead; idempotent.
     * Always clears [activeHandle], bumps [generation] (so a callback still in flight from the
     * attempt being torn down is ignored), and moves to [CxLinkState.Disconnected], even if
     * nothing was connected. */
    @Synchronized
    fun disconnect() {
        if (state == CxLinkState.Scanning) {
            io.stopScan()
        }
        val handle = activeHandle
        generation++
        io.disconnect()
        activeHandle = null
        setState(CxLinkState.Disconnected(handle))
    }

    private fun setState(newState: CxLinkState) {
        state = newState
        listener?.invoke(newState)
    }

    companion object {
        /** How long [startScan] looks for an adapter before giving up with
         * [CxLinkFailure.NOT_FOUND] if [stopScan] was never called. A starting value, not yet
         * measured against the real adapter. */
        const val DEFAULT_SCAN_DURATION_MS = 10_000L
    }
}

/** UART service/characteristic UUIDs the manufacturer documents for the OBDLink CX, confirmed
 * against the adapter itself. Fixed values, not derived from any scan result. */
object CxBleUuids {
    val SERVICE: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805f9b34fb")
    val NOTIFY: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805f9b34fb")
    val WRITE: UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805f9b34fb")
}

/**
 * Maps a discovered adapter's BLE MAC address to an opaque, per-process [CxAdapterHandle] and
 * back. The same address always yields the same handle for the lifetime of this registry; a
 * fresh process (and therefore a fresh registry) hands out a new one for the same physical
 * adapter. Thread-safety matters here specifically because scan results and connect calls can
 * originate from different callback threads on the real platform.
 */
class CxAdapterHandleRegistry {
    private val addressByHandle = mutableMapOf<CxAdapterHandle, String>()
    private val handleByAddress = mutableMapOf<String, CxAdapterHandle>()

    @Synchronized
    fun handleFor(address: String): CxAdapterHandle =
        handleByAddress.getOrPut(address) {
            val handle = UUID.randomUUID().toString()
            addressByHandle[handle] = address
            handle
        }

    @Synchronized
    fun addressFor(handle: CxAdapterHandle): String? = addressByHandle[handle]
}

/**
 * Pure, platform-object-level check for whether a GATT service actually is a usable CX UART
 * service: the right UUID is not enough on its own — a characteristic has to both exist under
 * the expected UUID *and* carry the property this client actually needs it for. Takes a
 * [BluetoothGattService] rather than a whole [BluetoothGatt] so it is exercisable in a plain
 * test without a live GATT connection.
 */
object CxServiceCheck {
    class Result(val notify: BluetoothGattCharacteristic?, val write: BluetoothGattCharacteristic?) {
        val isSupported: Boolean get() = notify != null && write != null
    }

    fun check(service: BluetoothGattService?): Result {
        if (service == null) return Result(null, null)
        val notify = service.getCharacteristic(CxBleUuids.NOTIFY)
            ?.takeIf { hasProperty(it, BluetoothGattCharacteristic.PROPERTY_NOTIFY) }
        val write = service.getCharacteristic(CxBleUuids.WRITE)
            ?.takeIf {
                hasProperty(it, BluetoothGattCharacteristic.PROPERTY_WRITE) ||
                    hasProperty(it, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
            }
        return Result(notify, write)
    }

    private fun hasProperty(characteristic: BluetoothGattCharacteristic, property: Int): Boolean =
        characteristic.properties and property != 0
}

/** Requested MTU on connect. [CxGattManager.negotiatedWriteLength] always reads back whatever
 * was actually negotiated rather than assuming this request took effect — a laptop/BlueZ peer
 * has so far only ever negotiated the default ATT MTU (23) against this adapter; Android may
 * negotiate higher. */
private const val REQUESTED_MTU = 247

/**
 * Production [CxBleIo]: Android's own `BluetoothLeScanner` for discovery (deliberately not
 * adding a second scanning library next to the one below), the Nordic Android BLE Library for
 * bonding, connecting, service verification and notification subscription.
 */
class AndroidCxBleIo(private val context: Context) : CxBleIo {

    private val bluetoothManager: BluetoothManager?
        get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val handles = CxAdapterHandleRegistry()
    private val scanTimeoutHandler = Handler(Looper.getMainLooper())

    private var activeScanCallback: ScanCallback? = null
    private var activeScanTimeout: Runnable? = null
    private var currentSession: ConnectingSession? = null

    override fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Pre-API-31: BLE scanning requires a location permission; CONNECT/SCAN did not
            // exist yet and ordinary GATT connect needs no runtime permission of its own.
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun isBluetoothEnabled(): Boolean = bluetoothManager?.adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    override fun startScan(
        durationMs: Long,
        onResult: (CxAdapterInfo) -> Unit,
        onScanEnded: (CxLinkFailure?) -> Unit,
    ) {
        // Only one scan at a time: stop whatever is still running (and its timeout) before
        // starting a new one, so an old scan can never keep delivering results into a new
        // caller's onResult after it should have ended.
        stopScanInternal()
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            onScanEnded(CxLinkFailure.PLATFORM_ERROR)
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(CxBleUuids.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (activeScanCallback !== this) return
                val device = result.device
                val handle = handles.handleFor(device.address)
                onResult(CxAdapterInfo(handle, device.name, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                if (activeScanCallback !== this) return
                stopScanInternal()
                onScanEnded(CxLinkFailure.PLATFORM_ERROR)
            }
        }
        val timeoutRunnable = Runnable {
            if (activeScanCallback === callback) {
                stopScanInternal()
                onScanEnded(CxLinkFailure.NOT_FOUND)
            }
        }
        activeScanCallback = callback
        activeScanTimeout = timeoutRunnable
        scanner.startScan(listOf(filter), settings, callback)
        scanTimeoutHandler.postDelayed(timeoutRunnable, durationMs)
    }

    override fun stopScan() = stopScanInternal()

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        activeScanTimeout?.let { scanTimeoutHandler.removeCallbacks(it) }
        activeScanTimeout = null
        val callback = activeScanCallback ?: return
        activeScanCallback = null
        bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(callback)
    }

    @SuppressLint("MissingPermission")
    override fun connect(handle: CxAdapterHandle, callback: CxBleIoCallback) {
        if (currentSession != null) {
            // Unreachable via CxBleClient's own handle guard; kept as this layer's own
            // invariant in case that guard is ever bypassed.
            callback.onFailed(CxLinkFailure.PLATFORM_ERROR)
            return
        }
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            callback.onFailed(CxLinkFailure.BLUETOOTH_OFF)
            return
        }
        val address = handles.addressFor(handle)
        val device: BluetoothDevice? = address?.let {
            try {
                adapter.getRemoteDevice(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        if (device == null) {
            callback.onFailed(CxLinkFailure.NOT_FOUND)
            return
        }

        val manager = CxGattManager(context)
        val attempt = ConnectionAttempt({ manager.negotiatedWriteLength }, callback)
        val session = ConnectingSession(manager, attempt)
        currentSession = session

        manager.pairingFailure = { failAndTearDown(session, CxLinkFailure.PAIRING_WINDOW_CLOSED) }
        manager.notificationFailure = { failAndTearDown(session, CxLinkFailure.SERVICE_MISSING) }
        manager.onNotificationData = { bytes -> callback.onNotification(bytes) }
        manager.setConnectionObserver(
            object : ConnectionObserver {
                override fun onDeviceConnecting(device: BluetoothDevice) {}
                override fun onDeviceConnected(device: BluetoothDevice) {}

                override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                    failAndTearDown(session, failureFor(reason))
                }

                override fun onDeviceReady(device: BluetoothDevice) {
                    if (manager.notifyCharacteristic == null || manager.writeCharacteristic == null) {
                        failAndTearDown(session, CxLinkFailure.SERVICE_MISSING)
                    } else {
                        attempt.reportConnected()
                    }
                }

                override fun onDeviceDisconnecting(device: BluetoothDevice) {}

                override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                    attempt.reportDisconnected(failureFor(reason))
                    finishSession(session)
                    manager.close()
                }
            },
        )
        manager.connect(device)
            .useAutoConnect(false)
            .retry(2, 200)
            .timeout(CONNECT_TIMEOUT_MS)
            .fail { _, status -> failAndTearDown(session, failureForFailCallbackStatus(status)) }
            .enqueue()
    }

    @SuppressLint("MissingPermission")
    override fun write(bytes: ByteArray): Boolean {
        val session = currentSession ?: return false
        if (session.manager.writeCharacteristic == null) return false
        // No `.fail {}` handler: `CxCommandTransport.write`'s contract is "could this even be
        // attempted", not "did it arrive" — an async GATT write failure here degrades to the
        // same response timeout a lost-over-the-air write would, which `CxCommandSession`
        // already treats as a transport failure rather than silently hanging.
        session.manager.writeData(bytes).split().enqueue()
        return true
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        val session = currentSession ?: return
        currentSession = null
        // Cancel first: this is a deliberate, user-initiated teardown, and CxBleClient already
        // moves its own state to Disconnected synchronously regardless of what the GATT layer
        // reports afterwards — an async callback arriving once close() is in flight must not
        // contradict that by reporting a failure or a redundant disconnect of its own.
        session.attempt.cancel()
        session.manager.disconnect().enqueue()
        session.manager.close()
    }

    /** A connection attempt that ends in failure (bonding, service verification, notification
     * subscription, or the GATT connect itself) must still release whatever the Nordic manager
     * already holds — an open GATT link and its two internal broadcast receivers — before the
     * attempt is considered over; `initialize()`'s own failures do not disconnect on their own.
     * Always runs the teardown, whether or not [ConnectionAttempt.reportFailed] actually
     * delivers (a cancelled or already-terminated attempt still needs its manager released). */
    private fun failAndTearDown(session: ConnectingSession, failure: CxLinkFailure) {
        session.manager.disconnect().enqueue()
        session.manager.close()
        finishSession(session)
        session.attempt.reportFailed(failure)
    }

    /** Clears [currentSession] only if it is still *this* session — a stale, already-terminated
     * session's own cleanup must never clobber a newer session's reference. */
    private fun finishSession(session: ConnectingSession) {
        if (currentSession === session) {
            currentSession = null
        }
    }

    private fun failureFor(disconnectionReason: Int): CxLinkFailure = when (disconnectionReason) {
        ConnectionObserver.REASON_NOT_SUPPORTED -> CxLinkFailure.SERVICE_MISSING
        ConnectionObserver.REASON_TIMEOUT -> CxLinkFailure.TIMEOUT
        else -> CxLinkFailure.PLATFORM_ERROR
    }

    private fun failureForFailCallbackStatus(status: Int): CxLinkFailure = when (status) {
        FailCallback.REASON_DEVICE_NOT_SUPPORTED -> CxLinkFailure.SERVICE_MISSING
        FailCallback.REASON_TIMEOUT -> CxLinkFailure.TIMEOUT
        FailCallback.REASON_BLUETOOTH_DISABLED -> CxLinkFailure.BLUETOOTH_OFF
        else -> CxLinkFailure.PLATFORM_ERROR
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000L
    }
}

/** Pairs one connection attempt's [ConnectionAttempt] (the reporting/outcome logic, testable on
 * its own) with the [CxGattManager] instance it actually drives, so [AndroidCxBleIo] can tear
 * the GATT connection down without that ability leaking into [ConnectionAttempt] itself. */
private class ConnectingSession(val manager: CxGattManager, val attempt: ConnectionAttempt)

/**
 * Tracks one connection attempt's outcome so it is reported to [callback] at most once, and
 * never at all once [cancel] has run. A GATT connection moves through several independent async
 * callbacks (connect, bond, service discovery, MTU, notifications) that can each fail or, after
 * a deliberate disconnect, arrive late — this is the single place that turns "several things can
 * happen, in an unpredictable order, possibly after we stopped caring" into "exactly one outcome,
 * or none". [writeLength] is a supplier rather than a direct manager reference so this class has
 * no access to (and therefore cannot accidentally skip) the GATT teardown its caller owns.
 */
internal class ConnectionAttempt(
    private val writeLength: () -> Int,
    private val callback: CxBleIoCallback,
) {
    @Volatile
    private var reported = false

    @Volatile
    private var connected = false

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun reportFailed(failure: CxLinkFailure) {
        if (reported || cancelled) return
        reported = true
        callback.onFailed(failure)
    }

    fun reportConnected() {
        if (reported || cancelled) return
        reported = true
        connected = true
        callback.onConnected(writeLength())
    }

    /** An async disconnect notification from the GATT layer. Once [reportConnected] has run,
     * this is a genuine, later disconnect (e.g. the adapter went out of range) and is forwarded
     * as such. Before that, it is folded into the attempt's own outcome — unless a failure was
     * already reported, in which case that failure reason must stand: a disconnect notification
     * arriving alongside or after it never overwrites *why* the attempt failed with a plain,
     * reason-less "disconnected". */
    fun reportDisconnected(failureIfNeverConnected: CxLinkFailure) {
        if (cancelled) return
        when {
            connected -> callback.onDisconnected()
            !reported -> reportFailed(failureIfNeverConnected)
        }
    }
}

/**
 * Nordic `BleManager` for the CX's UART service: verifies all three documented characteristics
 * are actually present and actually usable ([CxServiceCheck]; a mismatch fails
 * `isRequiredServiceSupported`, which the library reports through
 * [ConnectionObserver.onDeviceFailedToConnect] with `REASON_NOT_SUPPORTED`), creates the bond,
 * requests [REQUESTED_MTU], and subscribes to notifications on [CxBleUuids.NOTIFY]. [writeData]
 * exposes the actual write to [CxBleUuids.WRITE]; this class holds no command vocabulary of its
 * own, it only forwards whatever [AndroidCxBleIo.write] gives it.
 */
private class CxGattManager(context: Context) : BleManager(context) {

    var notifyCharacteristic: BluetoothGattCharacteristic? = null
    var writeCharacteristic: BluetoothGattCharacteristic? = null

    /** Always the value [BleManager.getMtu] reports right now, which reflects reality whether or
     * not the explicit [REQUESTED_MTU] request below actually succeeded — so this never needs
     * its own cached copy that could silently drift from what was really negotiated. */
    val negotiatedWriteLength: Int get() = getMtu() - 3

    /** Set by [AndroidCxBleIo.connect] before `connect()` is enqueued; invoked when bonding
     * cannot be created (the manufacturer documents the adapter's bonding window as open only
     * briefly after power-on). */
    var pairingFailure: (() -> Unit)? = null

    /** Set by [AndroidCxBleIo.connect]; invoked when the notification subscription itself fails
     * even though the characteristic exists — treated the same as a missing characteristic,
     * since either way the notify path this client needs is not actually usable. */
    var notificationFailure: (() -> Unit)? = null

    /** Set by [AndroidCxBleIo.connect]; invoked with the raw bytes of every GATT notification
     * received on [CxBleUuids.NOTIFY] once subscribed. */
    var onNotificationData: ((ByteArray) -> Unit)? = null

    /** `writeCharacteristic(...)` itself is `protected` on [BleManager], reachable only from
     * inside a subclass — this exposes it to [AndroidCxBleIo.write], which is not one. */
    fun writeData(bytes: ByteArray): WriteRequest {
        // CxServiceCheck accepts either PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE on FFF2 —
        // a characteristic that only advertises the latter can reject (or silently misbehave on)
        // a write requesting an acknowledged response, so the actual write type has to follow
        // whatever the characteristic itself declares, not assume the acknowledged default.
        val characteristic = writeCharacteristic
        val writeType = if (characteristic != null &&
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 &&
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        return writeCharacteristic(characteristic, bytes, writeType)
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val result = CxServiceCheck.check(gatt.getService(CxBleUuids.SERVICE))
        notifyCharacteristic = result.notify
        writeCharacteristic = result.write
        return result.isSupported
    }

    override fun onServicesInvalidated() {
        notifyCharacteristic = null
        writeCharacteristic = null
    }

    override fun initialize() {
        // `createBondInsecure()`, not `ensureBond()`: ensureBond() unconditionally removes and
        // recreates any existing bond, which would tear down a working pairing on every
        // reconnect to an adapter whose pairing window (see pairingFailure's doc comment) only
        // opens briefly. createBondInsecure() reports immediate success when a bond already
        // exists instead (the deprecated createBond() is identical but only forwards to this).
        createBondInsecure()
            .fail { _, _ -> pairingFailure?.invoke() }
            .enqueue()
        requestMtu(REQUESTED_MTU).enqueue()
        setNotificationCallback(notifyCharacteristic).with { _, data ->
            onNotificationData?.invoke(data.value ?: ByteArray(0))
        }
        enableNotifications(notifyCharacteristic)
            .fail { _, _ -> notificationFailure?.invoke() }
            .enqueue()
    }
}
