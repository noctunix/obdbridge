package de.noctunix.obdbridge

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import androidx.annotation.RequiresApi

/**
 * Bound by the platform while the paired adapter is in radio range. It does no work itself: every
 * callback is reduced to "did the paired adapter appear or disappear" and handed to
 * [ObdApi.onCompanionEvent]. The overrides deliberately do not call `super`; an event the
 * platform reports through two callback generations is dropped there by state.
 *
 * The platform unbinds this service a few seconds after "disappeared", which happens in the
 * middle of every trip because a connected adapter stops advertising. Nothing started from here
 * may depend on this service staying bound.
 */
@RequiresApi(31)
class ObdCompanionService : CompanionDeviceService() {

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        val present = when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED, DevicePresenceEvent.EVENT_BT_CONNECTED -> true
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED, DevicePresenceEvent.EVENT_BT_DISCONNECTED -> false
            else -> return
        }
        dispatch(event.associationId, null, present)
    }

    override fun onDeviceAppeared(associationInfo: AssociationInfo) =
        dispatch(associationInfo.id, associationInfo.deviceMacAddress?.toString(), true)

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) =
        dispatch(associationInfo.id, associationInfo.deviceMacAddress?.toString(), false)

    @Deprecated("Superseded by the AssociationInfo overload from API 33")
    override fun onDeviceAppeared(address: String) = dispatch(null, address, true)

    @Deprecated("Superseded by the AssociationInfo overload from API 33")
    override fun onDeviceDisappeared(address: String) = dispatch(null, address, false)

    private fun dispatch(associationId: Int?, address: String?, present: Boolean) {
        ObdApi.getInstance(applicationContext).onCompanionEvent(associationId, address, present)
    }
}
