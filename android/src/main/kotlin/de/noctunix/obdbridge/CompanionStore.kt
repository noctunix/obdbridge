package de.noctunix.obdbridge

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The adapter remembered after a companion-device pairing. [macAddress] never leaves the process;
 * Dart only ever sees [handle]. [associationId] is `null` for a pairing made below API 33, which
 * has no association ids.
 */
internal data class CompanionPairing(
    val associationId: Int?,
    val macAddress: String,
    val handle: CxAdapterHandle,
    val displayName: String?,
)

/** A recording native started on its own, kept until the host acknowledges it. */
internal data class AutoSession(
    val sessionId: String,
    val bufferFilePath: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val endReason: String? = null,
    /** The recording never began, so there is no buffer to import. */
    val failedStart: Boolean = false,
)

/** Armed when an automatically started recording ended and released its link; see
 * [CompanionPresencePolicy.isSuppressed]. */
internal data class AutoStartSuppression(
    val endedAtEpochMs: Long,
    /** An appearance was seen after the end. Only then does a disappearance count: one before
     * it can still belong to the trip that just ended. */
    val appearedAfterEnd: Boolean,
    val disappearedAtEpochMs: Long?,
)

/**
 * Plugin-private persistence for everything the process must still know after a restart: the
 * pairing, whether presence observation was requested, the request a presence event starts, and
 * the host's [ObdBridgeConfigMessage]. Kept in its own preferences file rather than the
 * caller-named one so it does not depend on [NativeObdApi.configure] having run.
 *
 * Every write is `commit()`ed: the process can be killed right after the call that made it.
 */
internal class CompanionStore(
    private val prefs: SharedPreferences,
    /** The host app's version code; a stored recording request from another version is dropped. */
    private val appVersionCode: () -> Long = { 0L },
) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        { hostVersionCode(context) },
    )

    val pairing: CompanionPairing?
        get() {
            val mac = prefs.getString(KEY_MAC, null) ?: return null
            val handle = prefs.getString(KEY_HANDLE, null) ?: return null
            val id = if (prefs.contains(KEY_ASSOCIATION_ID)) prefs.getInt(KEY_ASSOCIATION_ID, 0) else null
            return CompanionPairing(id, mac, handle, prefs.getString(KEY_NAME, null))
        }

    fun savePairing(pairing: CompanionPairing) {
        prefs.edit().apply {
            putString(KEY_MAC, pairing.macAddress)
            putString(KEY_HANDLE, pairing.handle)
            putString(KEY_NAME, pairing.displayName)
            if (pairing.associationId != null) {
                putInt(KEY_ASSOCIATION_ID, pairing.associationId)
            } else {
                remove(KEY_ASSOCIATION_ID)
            }
        }.commit()
    }

    /** Drops the pairing and everything that only makes sense with it: the observation flag and
     * the stored recording request. The host config is kept. */
    fun clearPairing() {
        prefs.edit()
            .remove(KEY_MAC)
            .remove(KEY_HANDLE)
            .remove(KEY_NAME)
            .remove(KEY_ASSOCIATION_ID)
            .remove(KEY_PRESENCE_OBSERVED)
            .remove(KEY_AUTO_RECORDING)
            .remove(KEY_AUTO_RECORDING_VERSION)
            .remove(KEY_SUPPRESSED)
            .remove(KEY_SUPPRESSED_ENDED_AT)
            .remove(KEY_APPEARED_AFTER_END)
            .remove(KEY_DISAPPEARED_AT)
            .commit()
    }

    var presenceObserved: Boolean
        get() = prefs.getBoolean(KEY_PRESENCE_OBSERVED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PRESENCE_OBSERVED, value).commit()
        }

    /** `null` when none is stored, the stored one can no longer be decoded, or it was stored by
     * another version of the host app (it is then deleted: a plan the update tightened must not
     * run before the host stores it again). */
    var autoRecording: ObdStartRecordingRequest?
        get() {
            val stored = decode(KEY_AUTO_RECORDING, CompanionJson::decodeRequest) ?: return null
            if (prefs.getLong(KEY_AUTO_RECORDING_VERSION, 0L) != appVersionCode()) {
                autoRecording = null
                return null
            }
            return stored
        }
        set(value) {
            encode(KEY_AUTO_RECORDING, value, CompanionJson::encodeRequest)
            prefs.edit().apply {
                if (value == null) remove(KEY_AUTO_RECORDING_VERSION) else putLong(KEY_AUTO_RECORDING_VERSION, appVersionCode())
            }.commit()
        }

    val autoStartSuppression: AutoStartSuppression?
        get() = if (prefs.getBoolean(KEY_SUPPRESSED, false)) {
            AutoStartSuppression(
                prefs.getLong(KEY_SUPPRESSED_ENDED_AT, 0L),
                prefs.getBoolean(KEY_APPEARED_AFTER_END, false),
                if (prefs.contains(KEY_DISAPPEARED_AT)) prefs.getLong(KEY_DISAPPEARED_AT, 0L) else null,
            )
        } else {
            null
        }

    fun suppressAutoStart(endedAtEpochMs: Long) {
        prefs.edit().putBoolean(KEY_SUPPRESSED, true).putLong(KEY_SUPPRESSED_ENDED_AT, endedAtEpochMs).remove(KEY_APPEARED_AFTER_END).remove(KEY_DISAPPEARED_AT).commit()
    }

    fun noteDisappearedWhileSuppressed(nowMs: Long) {
        val current = autoStartSuppression ?: return
        if (current.appearedAfterEnd && current.disappearedAtEpochMs == null) prefs.edit().putLong(KEY_DISAPPEARED_AT, nowMs).commit()
    }

    fun noteAppearedWhileSuppressed() {
        if (autoStartSuppression != null) prefs.edit().putBoolean(KEY_APPEARED_AFTER_END, true).commit()
    }

    fun clearAutoStartSuppression() {
        prefs.edit().remove(KEY_SUPPRESSED).remove(KEY_SUPPRESSED_ENDED_AT).remove(KEY_APPEARED_AFTER_END).remove(KEY_DISAPPEARED_AT).commit()
    }

    /** Entries that cannot be decoded are skipped; the rest stay usable. */
    fun autoSessions(): List<AutoSession> {
        val text = prefs.getString(KEY_AUTO_SESSIONS, null) ?: return emptyList()
        val array = try {
            JSONArray(text)
        } catch (e: JSONException) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull {
            try {
                CompanionJson.decodeAutoSession(array.getJSONObject(it))
            } catch (e: JSONException) {
                null
            }
        }
    }

    private fun saveAutoSessions(sessions: List<AutoSession>) {
        val kept = sessions.toMutableList()
        // Starts that never produced a buffer carry nothing to import, so they go before real ones.
        while (kept.size > MAX_AUTO_SESSIONS) {
            val victim = kept.indexOfFirst { it.failedStart && it.endedAtEpochMs != null }.takeIf { it >= 0 } ?: 0
            kept.removeAt(victim)
        }
        prefs.edit().putString(KEY_AUTO_SESSIONS, JSONArray(kept.map(CompanionJson::encodeAutoSession)).toString()).commit()
    }

    fun addAutoSession(session: AutoSession) {
        saveAutoSessions(autoSessions().filter { it.sessionId != session.sessionId } + session)
    }

    fun isAutoSession(sessionId: String?): Boolean = autoSessions().any { it.sessionId == sessionId }

    /** Marks [sessionId] ended; `false` when it is not an automatically started session. */
    fun finishAutoSession(sessionId: String?, endedAtEpochMs: Long, reason: String, failedStart: Boolean = false): Boolean {
        val sessions = autoSessions()
        if (sessions.none { it.sessionId == sessionId }) return false
        saveAutoSessions(
            sessions.map {
                if (it.sessionId == sessionId && it.endedAtEpochMs == null) {
                    it.copy(endedAtEpochMs = endedAtEpochMs, endReason = reason, failedStart = failedStart)
                } else {
                    it
                }
            },
        )
        return true
    }

    /** Closes every entry still open; used when no recording of this process can own one. */
    fun finishOpenAutoSessions(endedAtEpochMs: Long, reason: String) {
        val sessions = autoSessions()
        if (sessions.none { it.endedAtEpochMs == null }) return
        saveAutoSessions(
            sessions.map {
                if (it.endedAtEpochMs == null) it.copy(endedAtEpochMs = endedAtEpochMs, endReason = reason, failedStart = true) else it
            },
        )
    }

    fun acknowledgeAutoSession(sessionId: String) {
        saveAutoSessions(autoSessions().filter { it.sessionId != sessionId })
    }

    var config: ObdBridgeConfigMessage?
        get() = decode(KEY_CONFIG, CompanionJson::decodeConfig)
        set(value) = encode(KEY_CONFIG, value, CompanionJson::encodeConfig)

    private fun <T> decode(key: String, parse: (JSONObject) -> T): T? {
        val text = prefs.getString(key, null) ?: return null
        return try {
            parse(JSONObject(text))
        } catch (e: JSONException) {
            null
        }
    }

    private fun <T : Any> encode(key: String, value: T?, write: (T) -> JSONObject) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, write(value).toString())
        }.commit()
    }

    companion object {
        const val PREFS_NAME = "obdbridge_companion"
        private const val KEY_MAC = "mac"
        private const val KEY_HANDLE = "handle"
        private const val KEY_NAME = "name"
        private const val KEY_ASSOCIATION_ID = "association_id"
        private const val KEY_PRESENCE_OBSERVED = "presence_observed"
        private const val KEY_AUTO_RECORDING = "auto_recording"
        private const val KEY_CONFIG = "config"
        private const val KEY_AUTO_RECORDING_VERSION = "auto_recording_version"
        private const val KEY_SUPPRESSED = "auto_start_suppressed"
        private const val KEY_SUPPRESSED_ENDED_AT = "auto_start_suppressed_ended_at"
        private const val KEY_APPEARED_AFTER_END = "auto_start_appeared_after_end"
        private const val KEY_DISAPPEARED_AT = "auto_start_disappeared_at"
        private const val KEY_AUTO_SESSIONS = "auto_sessions"

        /** Oldest entries go first so an unattended host cannot grow the file without bound. */
        const val MAX_AUTO_SESSIONS = 100

        private fun hostVersionCode(context: Context): Long =
            try {
                PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
            } catch (e: PackageManager.NameNotFoundException) {
                0L
            }
    }
}

/** JSON form of the Pigeon messages [CompanionStore] persists. A missing or mistyped field
 * throws [JSONException], which the store reads as "nothing stored". */
internal object CompanionJson {

    fun encodeConfig(config: ObdBridgeConfigMessage) = JSONObject().apply {
        put("activityClassName", config.activityClassName)
        put("smallIconResourceName", config.smallIconResourceName)
        put("sharedPreferencesName", config.sharedPreferencesName)
        put("bufferFormatId", config.bufferFormatId)
        config.autoStartSuppressionMinutes?.let { put("autoStartSuppressionMinutes", it) }
    }

    fun decodeConfig(json: JSONObject) = ObdBridgeConfigMessage(
        activityClassName = json.getString("activityClassName"),
        smallIconResourceName = json.getString("smallIconResourceName"),
        sharedPreferencesName = json.getString("sharedPreferencesName"),
        bufferFormatId = json.getString("bufferFormatId"),
        autoStartSuppressionMinutes = if (json.has("autoStartSuppressionMinutes")) json.getLong("autoStartSuppressionMinutes") else null,
    )

    fun encodeAutoSession(session: AutoSession) = JSONObject().apply {
        put("sessionId", session.sessionId)
        put("bufferFilePath", session.bufferFilePath)
        put("startedAtEpochMs", session.startedAtEpochMs)
        session.endedAtEpochMs?.let { put("endedAtEpochMs", it) }
        session.endReason?.let { put("endReason", it) }
        if (session.failedStart) put("failedStart", true)
    }

    fun decodeAutoSession(json: JSONObject) = AutoSession(
        sessionId = json.getString("sessionId"),
        bufferFilePath = json.getString("bufferFilePath"),
        startedAtEpochMs = json.getLong("startedAtEpochMs"),
        endedAtEpochMs = if (json.has("endedAtEpochMs")) json.getLong("endedAtEpochMs") else null,
        endReason = json.optString("endReason").takeIf { json.has("endReason") },
        failedStart = json.optBoolean("failedStart", false),
    )

    fun encodeRequest(request: ObdStartRecordingRequest) = JSONObject().apply {
        put("sessionId", request.sessionId)
        put("vehicleId", request.vehicleId)
        put("bufferFilePath", request.bufferFilePath)
        put("startedAtEpochMs", request.startedAtEpochMs)
        put("plan", encodePlan(request.plan))
        put("holdWakeLock", request.options.holdWakeLock)
        put("resumeAfterSystemKill", request.options.resumeAfterSystemKill)
        request.notification?.let { put("notification", encodeNotification(it)) }
    }

    fun decodeRequest(json: JSONObject) = ObdStartRecordingRequest(
        sessionId = json.getString("sessionId"),
        vehicleId = json.getString("vehicleId"),
        bufferFilePath = json.getString("bufferFilePath"),
        startedAtEpochMs = json.getLong("startedAtEpochMs"),
        plan = decodePlan(json.getJSONObject("plan")),
        options = ObdRecordingOptionsMessage(
            holdWakeLock = json.getBoolean("holdWakeLock"),
            resumeAfterSystemKill = json.getBoolean("resumeAfterSystemKill"),
        ),
        notification = json.optJSONObject("notification")?.let(::decodeNotification),
    )

    private fun encodeNotification(n: ObdRecordingNotificationMessage) = JSONObject().apply {
        put("channelName", n.channelName)
        put("title", n.title)
        put("body", n.body)
        put("stopActionLabel", n.stopActionLabel)
        n.connectingBody?.let { put("connectingBody", it) }
    }

    private fun decodeNotification(json: JSONObject) = ObdRecordingNotificationMessage(
        channelName = json.getString("channelName"),
        title = json.getString("title"),
        body = json.getString("body"),
        stopActionLabel = json.getString("stopActionLabel"),
        connectingBody = json.optString("connectingBody").takeIf { json.has("connectingBody") },
    )

    private fun encodePlan(plan: ObdPlanMessage) = JSONObject().apply {
        put("planId", plan.planId)
        put("planVersion", plan.planVersion)
        put("formatVersion", plan.formatVersion)
        put("init", JSONArray(plan.init))
        put("resync", JSONArray(plan.resync))
        put("modules", JSONArray(plan.modules.map(::encodeModule)))
        put("steps", JSONArray(plan.steps.map(::encodeStep)))
        put("limits", encodeLimits(plan.limits))
    }

    private fun decodePlan(json: JSONObject) = ObdPlanMessage(
        planId = json.getString("planId"),
        planVersion = json.getLong("planVersion"),
        formatVersion = json.getLong("formatVersion"),
        init = strings(json.getJSONArray("init")),
        resync = strings(json.getJSONArray("resync")),
        modules = objects(json.getJSONArray("modules")).map(::decodeModule),
        steps = objects(json.getJSONArray("steps")).map(::decodeStep),
        limits = decodeLimits(json.getJSONObject("limits")),
    )

    private fun encodeModule(module: ObdModuleMessage) = JSONObject().apply {
        put("moduleId", module.moduleId)
        put("protocol", module.protocol)
        put("setup", JSONArray(module.setup))
        module.readDeadlineMs?.let { put("readDeadlineMs", it) }
    }

    private fun decodeModule(json: JSONObject) = ObdModuleMessage(
        moduleId = json.getString("moduleId"),
        protocol = json.getLong("protocol"),
        setup = strings(json.getJSONArray("setup")),
        readDeadlineMs = if (json.has("readDeadlineMs")) json.getLong("readDeadlineMs") else null,
    )

    private fun encodeStep(step: ObdStepMessage) = JSONObject().apply {
        put("signalId", step.signalId)
        put("moduleId", step.moduleId)
        put("command", step.command)
        put("responseHeader", step.responseHeader)
        put("intervalMs", step.intervalMs)
    }

    private fun decodeStep(json: JSONObject) = ObdStepMessage(
        signalId = json.getString("signalId"),
        moduleId = json.getString("moduleId"),
        command = json.getString("command"),
        responseHeader = json.getString("responseHeader"),
        intervalMs = json.getLong("intervalMs"),
    )

    private fun encodeLimits(limits: ObdLimitsMessage) = JSONObject().apply {
        put("maxResponseBytes", limits.maxResponseBytes)
        put("adapterStepDeadlineMs", limits.adapterStepDeadlineMs)
        put("vehicleReadDeadlineMs", limits.vehicleReadDeadlineMs)
        put("maxReadsPerSecond", limits.maxReadsPerSecond)
        put("maxConsecutiveTimeouts", limits.maxConsecutiveTimeouts)
        put("onConsecutiveFailures", limits.onConsecutiveFailures.name)
        put("idleVehicleStopAfterMs", limits.idleVehicleStopAfterMs)
        put("quietModuleBackoffMs", limits.quietModuleBackoffMs)
        put("reconnectBackoffMinMs", limits.reconnectBackoffMinMs)
        put("reconnectBackoffMaxMs", limits.reconnectBackoffMaxMs)
        put("maxReconnectAttemptsPerGap", limits.maxReconnectAttemptsPerGap)
        put("maxRecordingMs", limits.maxRecordingMs)
        put("maxBufferBytes", limits.maxBufferBytes)
    }

    private fun decodeLimits(json: JSONObject) = ObdLimitsMessage(
        maxResponseBytes = json.getLong("maxResponseBytes"),
        adapterStepDeadlineMs = json.getLong("adapterStepDeadlineMs"),
        vehicleReadDeadlineMs = json.getLong("vehicleReadDeadlineMs"),
        maxReadsPerSecond = json.getLong("maxReadsPerSecond"),
        maxConsecutiveTimeouts = json.getLong("maxConsecutiveTimeouts"),
        onConsecutiveFailures = try {
            ObdConsecutiveFailuresPolicyMessage.valueOf(json.getString("onConsecutiveFailures"))
        } catch (e: IllegalArgumentException) {
            throw JSONException("unknown onConsecutiveFailures")
        },
        idleVehicleStopAfterMs = json.getLong("idleVehicleStopAfterMs"),
        quietModuleBackoffMs = json.getLong("quietModuleBackoffMs"),
        reconnectBackoffMinMs = json.getLong("reconnectBackoffMinMs"),
        reconnectBackoffMaxMs = json.getLong("reconnectBackoffMaxMs"),
        maxReconnectAttemptsPerGap = json.getLong("maxReconnectAttemptsPerGap"),
        maxRecordingMs = json.getLong("maxRecordingMs"),
        maxBufferBytes = json.getLong("maxBufferBytes"),
    )

    private fun strings(array: JSONArray): List<String> = List(array.length()) { array.getString(it) }

    private fun objects(array: JSONArray): List<JSONObject> = List(array.length()) { array.getJSONObject(it) }
}
