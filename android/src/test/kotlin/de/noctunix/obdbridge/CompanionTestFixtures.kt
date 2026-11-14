package de.noctunix.obdbridge

internal fun sampleLimits() = ObdLimitsMessage(
    maxResponseBytes = 4096,
    adapterStepDeadlineMs = 2000,
    vehicleReadDeadlineMs = 1500,
    maxReadsPerSecond = 10,
    maxConsecutiveTimeouts = 3,
    onConsecutiveFailures = ObdConsecutiveFailuresPolicyMessage.RESYNC_AND_CONTINUE,
    idleVehicleStopAfterMs = 60_000,
    quietModuleBackoffMs = 5_000,
    reconnectBackoffMinMs = 1_000,
    reconnectBackoffMaxMs = 30_000,
    maxReconnectAttemptsPerGap = 5,
    maxRecordingMs = 3_600_000,
    maxBufferBytes = 1_000_000,
)

internal fun samplePlan() = ObdPlanMessage(
    planId = "plan-1",
    planVersion = 2,
    formatVersion = 1,
    init = listOf("ATZ", "ATE0"),
    resync = listOf("ATZ"),
    modules = listOf(
        ObdModuleMessage("m1", 6, listOf("ATSP6", "ATSH7E4"), readDeadlineMs = 3000),
        ObdModuleMessage("m2", 7, listOf("ATSP7")),
    ),
    steps = listOf(ObdStepMessage("soc", "m1", "220101", "7EC", 1000)),
    limits = sampleLimits(),
)

internal fun sampleRequest(bufferFilePath: String = "/data/trips/{sessionId}.jsonl") = ObdStartRecordingRequest(
    sessionId = "template-session",
    vehicleId = "vehicle-1",
    bufferFilePath = bufferFilePath,
    startedAtEpochMs = 1L,
    plan = samplePlan(),
    options = ObdRecordingOptionsMessage(holdWakeLock = true, resumeAfterSystemKill = false),
    notification = ObdRecordingNotificationMessage("Recording", "Title", "Body", "Stop"),
)

internal fun samplePairing(associationId: Int? = 7) = CompanionPairing(
    associationId = associationId,
    macAddress = "00:11:22:33:44:55",
    handle = "handle-remembered",
    displayName = "Adapter",
)
