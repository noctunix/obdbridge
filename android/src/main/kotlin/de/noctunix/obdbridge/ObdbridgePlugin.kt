package de.noctunix.obdbridge

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.PluginRegistry

/**
 * Registers [ObdApi] on the Pigeon `NativeObdApi` channel and wires its outgoing
 * `ObdEventsApi` on plugin attach/detach. [ObdApi] itself is a process-wide singleton
 * ([ObdApi.getInstance]) so a live view and a later recording share one BLE connection
 * regardless of how many Flutter engines attach this plugin over the app's lifetime.
 *
 * Activity awareness exists only for the companion-device pairing dialog, which has to be
 * launched from an `Activity` and answers through its result.
 */
class ObdbridgePlugin : FlutterPlugin, ActivityAware {
    private var eventsApi: ObdEventsApi? = null
    private var presenceEventsApi: ObdPresenceEventsApi? = null
    private var api: ObdApi? = null
    private var activityBinding: ActivityPluginBinding? = null

    private val activityResultListener = PluginRegistry.ActivityResultListener { requestCode, resultCode, data ->
        api?.companion?.onActivityResult(requestCode, resultCode, data) ?: false
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val api = ObdApi.getInstance(binding.applicationContext)
        this.api = api
        NativeObdApi.setUp(binding.binaryMessenger, api)
        val events = ObdEventsApi(binding.binaryMessenger)
        eventsApi = events
        api.attachEventsApi(events)
        val presence = ObdPresenceEventsApi(binding.binaryMessenger)
        presenceEventsApi = presence
        api.attachPresenceEventsApi(presence)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        NativeObdApi.setUp(binding.binaryMessenger, null)
        ObdApi.getInstance(binding.applicationContext).attachEventsApi(null)
        ObdApi.getInstance(binding.applicationContext).attachPresenceEventsApi(null)
        eventsApi = null
        presenceEventsApi = null
        api = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        binding.addActivityResultListener(activityResultListener)
        api?.companion?.attachActivity(binding.activity)
    }

    override fun onDetachedFromActivityForConfigChanges() = onDetachedFromActivity()

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) =
        onAttachedToActivity(binding)

    override fun onDetachedFromActivity() {
        activityBinding?.removeActivityResultListener(activityResultListener)
        activityBinding = null
        api?.companion?.attachActivity(null)
    }
}
