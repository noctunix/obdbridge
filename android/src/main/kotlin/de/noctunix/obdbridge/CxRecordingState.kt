package de.noctunix.obdbridge

/** Mirrors [ObdRecordingStateValueMessage] on the native side. */
enum class CxRecordingPhase {
    IDLE,
    RECORDING,
    SUSPENDED_BY_PLATFORM,
    STOPPED,
    INTERRUPTED,
}

/**
 * A snapshot of one recording's progress, read by [ObdApi.recordingState] — this type carries no
 * behavior of its own, it is what [CxRecordingService] reports out and what [ObdApi] translates
 * into [ObdRecordingStateMessage].
 */
data class CxRecordingState(
    val sessionId: String?,
    val phase: CxRecordingPhase,
    val lastFlushedSequence: Long,
    val bufferBytes: Long,
    val gapCount: Int,
    val writeError: String?,
    /** Always `false` in this package — [CxRecordingService] never restarts a recording on its
     * own after a process kill (a deliberate non-feature, not an oversight: see its class doc). */
    val resumedAfterRestart: Boolean,
    /** [CxPoller.oneShotQueueDepth] of this recording's poller, `0` once it is idle/gone — see
     * [ObdRecordingStateMessage.oneShotQueueDepth]'s own doc comment. */
    val oneShotQueueDepth: Int = 0,
) {
    fun toMessage(): ObdRecordingStateMessage = ObdRecordingStateMessage(
        sessionId = sessionId,
        state = when (phase) {
            CxRecordingPhase.IDLE -> ObdRecordingStateValueMessage.IDLE
            CxRecordingPhase.RECORDING -> ObdRecordingStateValueMessage.RECORDING
            CxRecordingPhase.SUSPENDED_BY_PLATFORM -> ObdRecordingStateValueMessage.SUSPENDED_BY_PLATFORM
            CxRecordingPhase.STOPPED -> ObdRecordingStateValueMessage.STOPPED
            CxRecordingPhase.INTERRUPTED -> ObdRecordingStateValueMessage.INTERRUPTED
        },
        lastFlushedSequence = lastFlushedSequence,
        bufferBytes = bufferBytes,
        gapCount = gapCount.toLong(),
        writeError = writeError,
        resumedAfterRestart = resumedAfterRestart,
        oneShotQueueDepth = oneShotQueueDepth.toLong(),
    )

    companion object {
        fun idle() = CxRecordingState(
            sessionId = null,
            phase = CxRecordingPhase.IDLE,
            lastFlushedSequence = 0L,
            bufferBytes = 0L,
            gapCount = 0,
            writeError = null,
            resumedAfterRestart = false,
            oneShotQueueDepth = 0,
        )
    }
}
