package de.noctunix.obdbridge

import io.flutter.embedding.engine.plugins.FlutterPlugin

/**
 * Registers [ObdApi] on the Pigeon `NativeObdApi` channel and wires its outgoing
 * `ObdEventsApi` on plugin attach/detach. [ObdApi] itself is a process-wide singleton
 * ([ObdApi.getInstance]) so a live view and a later recording share one BLE connection
 * regardless of how many Flutter engines attach this plugin over the app's lifetime.
 */
class ObdbridgePlugin : FlutterPlugin {
    private var eventsApi: ObdEventsApi? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val api = ObdApi.getInstance(binding.applicationContext)
        NativeObdApi.setUp(binding.binaryMessenger, api)
        val events = ObdEventsApi(binding.binaryMessenger)
        eventsApi = events
        api.attachEventsApi(events)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        NativeObdApi.setUp(binding.binaryMessenger, null)
        ObdApi.getInstance(binding.applicationContext).attachEventsApi(null)
        eventsApi = null
    }
}
