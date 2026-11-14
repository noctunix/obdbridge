package de.noctunix.obdbridge

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.DeviceNotAssociatedException
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID

internal data class PlatformAssociation(val id: Int, val address: String)

/**
 * Pairing, forgetting and presence observation through `CompanionDeviceManager`. All state lives
 * in [store]; this class only drives the platform and keeps [handles] in step with the pairing so
 * [ObdApi.connect] resolves the remembered handle after a restart without a scan.
 *
 * Must be used from the main thread, like the rest of the connection state.
 */
internal class CompanionController(
    private val context: Context,
    private val store: CompanionStore,
    private val handles: CxAdapterHandleRegistry,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    /** Addresses the platform still lists as associated, or `null` when it cannot be asked. */
    private val associatedAddresses: () -> List<String>? = { null },
    /** The platform's associations with their ids (API 33+; empty below). */
    private val platformAssociations: () -> List<PlatformAssociation> = { emptyList() },
    private val connectGranted: () -> Boolean = { true },
) {
    private var activity: Activity? = null
    private var pendingPairing: ((Unit) -> Unit)? = null

    private val manager: CompanionDeviceManager?
        get() = if (sdkInt >= MIN_ASSOCIATION_SDK) {
            context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
        } else {
            null
        }

    init {
        store.pairing?.let { handles.register(it.handle, it.macAddress) }
    }

    fun attachActivity(activity: Activity?) {
        this.activity = activity
    }

    /** Also drops a pairing the platform no longer knows (e.g. preferences restored from a
     * backup onto another device), so the status never claims an adapter that cannot be reached. */
    fun status(): ObdCompanionStatusMessage {
        dropPairingIfPlatformForgotIt()
        val pairing = store.pairing
        return ObdCompanionStatusMessage(
            supported = sdkInt >= MIN_ASSOCIATION_SDK,
            presenceSupported = sdkInt >= MIN_PRESENCE_SDK,
            paired = pairing != null,
            adapterHandle = pairing?.handle,
            advertisedName = pairing?.displayName,
            presenceObserved = pairing != null && store.presenceObserved,
            autoRecordingArmed = pairing != null && store.autoRecording != null,
            connectGranted = connectGranted(),
        )
    }

    private fun dropPairingIfPlatformForgotIt() {
        val pairing = store.pairing ?: return
        val known = associatedAddresses() ?: return
        if (known.none { it.equals(pairing.macAddress, ignoreCase = true) }) {
            handles.remove(pairing.handle)
            store.clearPairing()
        }
    }

    /** Runs the pairing dialog; [onDone] is called exactly once, after the outcome is stored. A
     * dialog still pending from an earlier call (its activity went away without an answer) is
     * resolved as unpaired first. */
    @SuppressLint("NewApi")
    fun pair(onDone: () -> Unit) {
        finishPairing()
        val cdm = manager
        if (activity == null || cdm == null) {
            onDone()
            return
        }
        pendingPairing = { onDone() }
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(CxBleUuids.SERVICE)).build())
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = launchConsent(intentSender)

            override fun onDeviceFound(intentSender: IntentSender) = launchConsent(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                val mac = associationInfo.deviceMacAddress?.toString()
                if (mac != null) {
                    adopt(associationInfo.id, mac, associationInfo.displayName?.toString())
                }
                finishPairing()
            }

            override fun onFailure(error: CharSequence?) = finishPairing()
        }
        try {
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                cdm.associate(request, ContextCompat.getMainExecutor(context), callback)
            } else {
                cdm.associate(request, callback, Handler(Looper.getMainLooper()))
            }
        } catch (e: IllegalStateException) {
            finishPairing()
        } catch (e: SecurityException) {
            finishPairing()
        } catch (e: IllegalArgumentException) {
            finishPairing()
        }
    }

    private fun launchConsent(intentSender: IntentSender) {
        val host = activity
        if (host == null) {
            finishPairing()
            return
        }
        try {
            host.startIntentSenderForResult(intentSender, REQUEST_CODE_PAIR, null, 0, 0, 0)
        } catch (e: IntentSender.SendIntentException) {
            finishPairing()
        }
    }

    /** Result of the consent dialog. Returns `true` when [requestCode] was ours. */
    @Suppress("DEPRECATION")
    @SuppressLint("NewApi")
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CODE_PAIR) return false
        if (resultCode == Activity.RESULT_OK && data != null) {
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                    ?.let { info ->
                        info.deviceMacAddress?.toString()?.let { adopt(info.id, it, info.displayName?.toString()) }
                    }
            } else {
                val mac = when (val found = data.getParcelableExtra<android.os.Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)) {
                    is ScanResult -> found.device.address
                    is BluetoothDevice -> found.address
                    else -> null
                }
                if (mac != null) adopt(null, mac, null)
            }
        }
        finishPairing()
        return true
    }

    private fun finishPairing() {
        val done = pendingPairing ?: return
        pendingPairing = null
        done(Unit)
    }

    /** Stores the new pairing, first undoing the previous one if there was one; nothing but the
     * pairing keys changes on a first pairing. The address is upper-cased because
     * `BluetoothAdapter.getRemoteDevice` rejects lower-case ones. */
    private fun adopt(associationId: Int?, macAddress: String, displayName: String?) {
        val mac = macAddress.uppercase()
        val previous = store.pairing
        if (previous != null && previous.macAddress == mac && previous.associationId == associationId) return
        if (previous != null) {
            removePlatformAssociation(previous, stopObserving = true)
            handles.remove(previous.handle)
            // The new association is not observed yet; the stored request is the host's and stays.
            store.presenceObserved = false
        }
        store.clearAutoStartSuppression()
        val pairing = CompanionPairing(associationId, mac, UUID.randomUUID().toString(), displayName)
        store.savePairing(pairing)
        handles.register(pairing.handle, pairing.macAddress)
    }

    /** Returns the status after the stored pairing and its platform association are gone. */
    fun forget(): ObdCompanionStatusMessage {
        store.pairing?.let {
            removePlatformAssociation(it, stopObserving = true)
            handles.remove(it.handle)
        }
        store.clearPairing()
        return status()
    }

    @SuppressLint("NewApi")
    private fun removePlatformAssociation(pairing: CompanionPairing, stopObserving: Boolean) {
        val cdm = manager ?: return
        if (stopObserving) observe(pairing, false)
        try {
            val id = pairing.associationId
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU && id != null) {
                cdm.disassociate(id)
            } else {
                @Suppress("DEPRECATION")
                cdm.disassociate(pairing.macAddress)
            }
        } catch (e: IllegalArgumentException) {
            // Already gone on the platform side.
        }
    }

    fun setPresenceObservation(enabled: Boolean): ObdCompanionStatusMessage {
        val pairing = store.pairing
        if (pairing != null && sdkInt >= MIN_PRESENCE_SDK) {
            store.presenceObserved = observe(pairing, enabled) && enabled
        }
        return status()
    }

    /** Whether the platform accepted the request. */
    @SuppressLint("NewApi")
    private fun observe(pairing: CompanionPairing, enabled: Boolean): Boolean {
        val cdm = manager ?: return false
        if (sdkInt < MIN_PRESENCE_SDK) return false
        return try {
            if (sdkInt >= Build.VERSION_CODES.BAKLAVA) {
                val id = pairing.associationId ?: resolveAssociationId()?.associationId ?: return false
                val request = ObservingDevicePresenceRequest.Builder().setAssociationId(id).build()
                if (enabled) cdm.startObservingDevicePresence(request) else cdm.stopObservingDevicePresence(request)
            } else {
                @Suppress("DEPRECATION")
                if (enabled) {
                    cdm.startObservingDevicePresence(pairing.macAddress)
                } else {
                    cdm.stopObservingDevicePresence(pairing.macAddress)
                }
            }
            true
        } catch (e: DeviceNotAssociatedException) {
            false
        } catch (e: SecurityException) {
            // The observe-presence permission is missing from the merged manifest.
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /** A pairing made below API 33 has no id; after an OS upgrade the platform knows it. Stores
     * and returns the pairing with the id filled in, or `null` when the platform lists none for
     * the stored address. */
    fun resolveAssociationId(): CompanionPairing? {
        val pairing = store.pairing ?: return null
        if (pairing.associationId != null) return pairing
        val id = platformAssociations().firstOrNull { it.address.equals(pairing.macAddress, ignoreCase = true) }?.id ?: return null
        return pairing.copy(associationId = id).also(store::savePairing)
    }

    fun setAutoRecording(request: ObdStartRecordingRequest?) {
        store.autoRecording = request
    }

    companion object {
        const val MIN_ASSOCIATION_SDK = Build.VERSION_CODES.O
        const val MIN_PRESENCE_SDK = Build.VERSION_CODES.S
        const val REQUEST_CODE_PAIR = 0x0B1D

        /** Reads the platform's associations with ids; empty below API 33. */
        fun platformAssociationList(context: Context): () -> List<PlatformAssociation> = {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                emptyList()
            } else {
                (context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager)
                    ?.myAssociations.orEmpty()
                    .mapNotNull { info -> info.deviceMacAddress?.toString()?.let { PlatformAssociation(info.id, it) } }
            }
        }

        /** Reads the platform's list of associated addresses; `null` below API 26. */
        fun platformAssociatedAddresses(context: Context): () -> List<String>? = {
            if (Build.VERSION.SDK_INT < MIN_ASSOCIATION_SDK) {
                null
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager)
                    ?.associations
            }
        }
    }
}
