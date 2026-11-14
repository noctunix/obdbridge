package de.noctunix.obdbridge

import java.security.SecureRandom
import java.util.UUID

/** Receives the paired adapter's presence changes inside the host process, with or without a
 * Flutter engine. Set [ObdBridgePresence.listener] from `Application.onCreate`: the platform can
 * start the process just to deliver an event, and an engine-bound listener would not exist yet. */
fun interface ObdPresenceListener {
    fun onAdapterPresence(present: Boolean)
}

object ObdBridgePresence {
    @Volatile
    var listener: ObdPresenceListener? = null
}

/** Why an automatically started recording ended, as far as it matters for pausing further
 * automatic starts. */
internal enum class AutoEndCause {
    /** The vehicle went quiet: the car is parked. */
    IDLE_STOP,
    USER_STOP,
    /** Transport errors, exhausted reconnects, buffer or duration limits, service teardown. */
    OTHER,
    ;

    val suppressesNextStart: Boolean get() = this != OTHER
}

/** Drops an event that repeats the last processed state within [windowMs]: from API 33 the
 * platform can report one event through more than one callback generation. */
internal class PresenceDeduper(private val windowMs: Long = 3_000L) {
    private var lastPresent: Boolean? = null
    private var lastAtMs = 0L

    /** `true` when the event should be processed. */
    fun accept(present: Boolean, nowMs: Long): Boolean {
        if (lastPresent == present && nowMs - lastAtMs in 0 until windowMs) return false
        lastPresent = present
        lastAtMs = nowMs
        return true
    }
}

/** The only recording decision a presence event can cause. */
internal object CompanionPresencePolicy {
    const val DEFAULT_SUPPRESSION_MINUTES = 30L
    const val MAX_SUPPRESSION_MINUTES = 1440L

    /** [minutes] as milliseconds: `null` is the default, anything else is clamped to
     * 0..[MAX_SUPPRESSION_MINUTES] so a stored value can neither go negative nor overflow. */
    fun suppressionGapMs(minutes: Long?): Long =
        (minutes ?: DEFAULT_SUPPRESSION_MINUTES).coerceIn(0L, MAX_SUPPRESSION_MINUTES) * 60_000L

    /** Start only for an appearance, with a stored request, nothing recording or about to, and
     * no suppression in force. A disappearance never stops anything: the adapter stops
     * advertising once connected, so the platform reports it gone in the middle of every trip,
     * and the recording's own idle and limit logic owns its end. */
    fun shouldStartRecording(
        present: Boolean,
        storedRequest: ObdStartRecordingRequest?,
        recordingBusy: Boolean,
        suppression: AutoStartSuppression? = null,
        nowMs: Long = 0L,
        suppressionGapMs: Long = 0L,
    ): Boolean =
        present && storedRequest != null && !recordingBusy && !isSuppressed(suppression, nowMs, suppressionGapMs)

    /** After an automatic recording ended and released its link the adapter advertises again, so
     * its next appearance would start an empty recording next to a parked car. An appearance is
     * suppressed only while no disappearance was seen since the end and less than [gapMs] have
     * passed; a disappearance proves the car left or went to sleep, so any later appearance is
     * a genuine new one. Only a disappearance that follows an appearance after the end counts
     * ([AutoStartSuppression.appearedAfterEnd]): the platform may report the disappearance
     * that belongs to the trip itself after a short recording already ended. */
    fun isSuppressed(suppression: AutoStartSuppression?, nowMs: Long, gapMs: Long): Boolean =
        suppression != null && suppression.disappearedAtEpochMs == null && nowMs - suppression.endedAtEpochMs < gapMs

    /** An event belongs to the pairing when the association id matches or, for events that carry
     * only an address, or whose pairing has no id yet, when the address does. */
    fun matches(pairing: CompanionPairing?, associationId: Int?, macAddress: String?): Boolean {
        if (pairing == null) return false
        if (associationId != null && pairing.associationId != null) return associationId == pairing.associationId
        return macAddress != null && macAddress.equals(pairing.macAddress, ignoreCase = true)
    }

    /** The pairing with [associationId] filled in, when it had none and the event matched by
     * address; `null` when nothing needs to change. */
    fun withBackfilledId(pairing: CompanionPairing, associationId: Int?, macAddress: String?): CompanionPairing? =
        if (pairing.associationId == null && associationId != null && matches(pairing, associationId, macAddress)) {
            pairing.copy(associationId = associationId)
        } else {
            null
        }
}

/** Turns one presence event for the paired adapter into its three effects, each independent of
 * the others: the optional recording start, the engine event, the host listener. */
internal class CompanionPresenceHandler(
    private val store: CompanionStore,
    private val recordingBusy: () -> Boolean,
    private val startRecording: (ObdStartRecordingRequest) -> Boolean,
    private val emitToEngine: (Boolean) -> Unit,
    private val hostListener: () -> ObdPresenceListener?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val suppressionGapMs: () -> Long = { CompanionPresencePolicy.suppressionGapMs(null) },
) {
    fun onPresence(present: Boolean) {
        val now = nowMs()
        if (!present) store.noteDisappearedWhileSuppressed(now)
        val request = store.autoRecording
        val suppression = store.autoStartSuppression
        if (CompanionPresencePolicy.shouldStartRecording(present, request, recordingBusy(), suppression, now, suppressionGapMs())) {
            if (startRecording(checkNotNull(request)) && suppression != null) store.clearAutoStartSuppression()
        } else if (present) {
            store.noteAppearedWhileSuppressed()
        }
        emitToEngine(present)
        hostListener()?.onAdapterPresence(present)
    }
}

/** How long and how often a presence start keeps trying to reach the adapter before giving up. */
internal object PresenceConnectPolicy {
    /** Hard cap on the whole connect phase, however generous the plan's own limits are. */
    const val MAX_WINDOW_MS = 180_000L

    /** Delay before the next attempt after [failedAttempts] failures, or `null` to give up: the
     * plan's attempt budget is spent, or the next attempt would not start inside the window.
     * Backoff doubles from [minMs] and is capped at [maxMs]. */
    fun nextDelayMs(failedAttempts: Int, maxAttempts: Long, elapsedMs: Long, minMs: Long, maxMs: Long): Long? {
        if (failedAttempts >= maxAttempts.coerceAtLeast(1L)) return null
        val floor = minMs.coerceAtLeast(1L)
        val doublings = (failedAttempts - 1).coerceIn(0, 20)
        val delay = (floor shl doublings).coerceAtMost(maxMs.coerceAtLeast(floor))
        return if (elapsedMs + delay >= MAX_WINDOW_MS) null else delay
    }
}

/** Builds the per-trip request from the stored template. */
internal object AutoRecordingTemplate {
    const val SESSION_ID_PLACEHOLDER = "{sessionId}"

    private val random = SecureRandom()

    fun instantiate(template: ObdStartRecordingRequest, nowMs: Long, sessionId: String = uuidV7(nowMs)) =
        template.copy(
            sessionId = sessionId,
            bufferFilePath = template.bufferFilePath.replace(SESSION_ID_PLACEHOLDER, sessionId),
            startedAtEpochMs = nowMs,
        )

    /** RFC 9562 UUIDv7: 48-bit Unix millisecond timestamp, then random bits. */
    fun uuidV7(nowMs: Long): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        for (i in 0 until 6) bytes[i] = (nowMs ushr (8 * (5 - i))).toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        var high = 0L
        var low = 0L
        for (i in 0 until 8) high = (high shl 8) or (bytes[i].toLong() and 0xFF)
        for (i in 8 until 16) low = (low shl 8) or (bytes[i].toLong() and 0xFF)
        return UUID(high, low).toString()
    }
}
