package de.noctunix.obdbridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Scripted [CxBleIo]: `connect` always succeeds immediately with [writeLength], and every
 * [write] synchronously replies through the stored callback's `onNotification` with whatever
 * [responseFor] returns for that command — enough to drive [CxCommandSession] through a whole
 * init/module/vehicle-read sequence without a real adapter or any real waiting. Exactly one
 * command whose text equals [timeoutOnceCommand] is withheld (no reply at all) the first time it
 * is written, to drive a real response timeout through [CxCommandSession]'s own clock-scheduled
 * deadline instead of a synchronous reply. */
private class ScriptedCxBleIo : CxBleIo {
    var writeLength = 20
    var connectSucceeds = true
    var responseFor: (String) -> String = { command -> if (command == "ATZ") "ELM327 v1.5" else "OK" }
    var timeoutOnceCommand: String? = null
    private var timeoutAlreadyTriggered = false

    /** Withholds the reply the Nth time (1-based) [write] is called for the given command text,
     * and every time after that — independent of [timeoutOnceCommand], which only ever withholds
     * the first occurrence and lets every later one through normally. */
    var timeoutFromOccurrence: Pair<String, Int>? = null
    private val occurrenceCounts = mutableMapOf<String, Int>()

    val writtenCommands = mutableListOf<String>()
    var lastCallback: CxBleIoCallback? = null

    override fun hasRequiredPermissions(): Boolean = true

    override fun isBluetoothEnabled(): Boolean = true

    override fun startScan(durationMs: Long, onResult: (CxAdapterInfo) -> Unit, onScanEnded: (CxLinkFailure?) -> Unit) {
        onResult(CxAdapterInfo("handle-1", "CX", -50))
        onScanEnded(null)
    }

    override fun stopScan() {}

    override fun connect(handle: CxAdapterHandle, callback: CxBleIoCallback) {
        lastCallback = callback
        if (connectSucceeds) {
            callback.onConnected(writeLength)
        } else {
            callback.onFailed(CxLinkFailure.NOT_FOUND)
        }
    }

    override fun disconnect() {
        lastCallback?.onDisconnected()
    }

    override fun write(bytes: ByteArray): Boolean {
        val command = String(bytes, Charsets.US_ASCII).removeSuffix("\r")
        writtenCommands.add(command)
        if (command == timeoutOnceCommand && !timeoutAlreadyTriggered) {
            timeoutAlreadyTriggered = true
            return true
        }
        val occurrence = (occurrenceCounts[command] ?: 0) + 1
        occurrenceCounts[command] = occurrence
        val from = timeoutFromOccurrence
        if (from != null && from.first == command && occurrence >= from.second) {
            return true
        }
        val response = responseFor(command)
        lastCallback?.onNotification("$response\r>".toByteArray())
        return true
    }
}

/** Every command in these tests resolves synchronously inside [ScriptedCxBleIo.write] before
 * [CxCommandSession] ever reaches its own `clock.schedule` calls (response timeout, the next
 * poll's delay, ...) — those calls only need to exist, not actually fire, for this suite's
 * single-poll-cycle assertions, so this clock just records and cancels, never executes. */
private class NoopCxCommandClock : CxCommandClock {
    override fun now(): Long = 0L

    override fun schedule(delayMs: Long, action: () -> Unit): CxScheduledTask =
        object : CxScheduledTask {
            override fun cancel() {}
        }
}

/** A virtual clock a test drives explicitly via [fireNextDue] — needed wherever a scenario
 * depends on a real response timeout or the quiet-wait-then-resync cycle actually firing, which
 * [NoopCxCommandClock] never does. Mirrors `CxCommandSessionTest`'s own fake clock. */
private class ManualCxCommandClock : CxCommandClock {
    private class Entry(val dueAt: Long, val action: () -> Unit) {
        var cancelled = false
    }

    private val entries = mutableListOf<Entry>()
    private var currentTime = 0L

    override fun now(): Long = currentTime

    override fun schedule(delayMs: Long, action: () -> Unit): CxScheduledTask {
        val entry = Entry(currentTime + delayMs, action)
        entries.add(entry)
        return object : CxScheduledTask {
            override fun cancel() {
                entry.cancelled = true
            }
        }
    }

    fun fireNextDue() {
        val entry = entries.filter { !it.cancelled }.minByOrNull { it.dueAt } ?: error("nothing scheduled")
        currentTime = entry.dueAt
        entries.remove(entry)
        entry.action()
    }
}

private fun step(signalId: String, moduleId: String, command: String, responseHeader: String, intervalMs: Long = 1000) =
    ObdStepMessage(
        signalId = signalId,
        moduleId = moduleId,
        command = command,
        responseHeader = responseHeader,
        intervalMs = intervalMs,
    )

/** [maxReadsPerSecond] defaults high enough that `1000 / maxReadsPerSecond` (integer division)
 * is exactly `0` — i.e. no enforced spacing — so tests that are not specifically about rate
 * limiting can use [NoopCxCommandClock] (which never fires a positive-delay `clock.schedule`)
 * without the rate limiter itself silently stalling them. See
 * `pollingRespectsTheGlobalMaxReadsPerSecondLimit` for a test that does configure a real limit. */
private fun limits(maxReadsPerSecond: Long = 10_000) = ObdLimitsMessage(
    maxResponseBytes = 4096,
    adapterStepDeadlineMs = 2000,
    vehicleReadDeadlineMs = 2000,
    maxReadsPerSecond = maxReadsPerSecond,
    maxConsecutiveTimeouts = 1_000_000,
    onConsecutiveFailures = ObdConsecutiveFailuresPolicyMessage.END_RECORDING,
    idleVehicleStopAfterMs = Long.MAX_VALUE,
    quietModuleBackoffMs = 0,
    reconnectBackoffMinMs = 5000,
    reconnectBackoffMaxMs = 60000,
    maxReconnectAttemptsPerGap = 10,
    maxRecordingMs = Long.MAX_VALUE,
    maxBufferBytes = Long.MAX_VALUE,
)

private fun testRecordingOptions(holdWakeLock: Boolean = false) =
    ObdRecordingOptionsMessage(holdWakeLock = holdWakeLock, resumeAfterSystemKill = false)

private fun testPlan(signalId: String = "hvEnergyContent", intervalMs: Long = 1000) = ObdPlanMessage(
    planId = "test-plan",
    planVersion = 1,
    formatVersion = 1,
    init = listOf("ATZ", "ATE0"),
    resync = listOf("ATZ", "ATE0"),
    modules = listOf(ObdModuleMessage(moduleId = "m1", protocol = 6, setup = listOf("ATSP6", "ATSH710"))),
    steps = listOf(step(signalId, "m1", "222AB8", "77A", intervalMs)),
    limits = limits(),
)

@RunWith(RobolectricTestRunner::class)
class ObdApiTest {

    private fun <T> awaitResult(block: (callback: (Result<T>) -> Unit) -> Unit): T {
        var result: Result<T>? = null
        block { result = it }
        return result!!.getOrThrow()
    }

    @Test
    fun connectSucceedsAndReportsConnected() {
        val io = ScriptedCxBleIo()
        val api = ObdApi(CxBleClient(io), NoopCxCommandClock())

        val state = awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }

        assertEquals(ObdLinkStateValueMessage.CONNECTED, state.state)
        assertEquals("handle-1", state.adapterHandle)
    }

    @Test
    fun connectFailureReportsFailedWithoutStartingASession() {
        val io = ScriptedCxBleIo().apply { connectSucceeds = false }
        val api = ObdApi(CxBleClient(io), NoopCxCommandClock())

        val state = awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }

        assertEquals(ObdLinkStateValueMessage.FAILED, state.state)
        assertEquals(ObdLinkFailureMessage.NOT_FOUND, state.failure)
    }

    @Test
    fun startLiveFailsWithoutAnActiveConnection() {
        val io = ScriptedCxBleIo()
        val api = ObdApi(CxBleClient(io), NoopCxCommandClock())

        val started = awaitResult<Boolean> { api.startLive(testPlan(), it) }

        assertFalse(started)
    }

    @Test
    fun startLiveRunsInitThenLazilyRunsAModuleSetupBeforeItsFirstRead() {
        val io = ScriptedCxBleIo().apply {
            responseFor = { command ->
                when (command) {
                    "222AB8" -> "77A622AB800000064"
                    "ATZ" -> "ELM327 v1.5"
                    else -> "OK"
                }
            }
        }
        val api = ObdApi(CxBleClient(io), NoopCxCommandClock())
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }

        val started = awaitResult<Boolean> { api.startLive(testPlan(), it) }
        assertTrue(started)

        assertEquals(listOf("ATZ", "ATE0", "ATSP6", "ATSH710", "222AB8"), io.writtenCommands)

        val live = awaitResult<ObdLiveStateMessage> { api.liveState(listOf("hvEnergyContent"), it) }
        assertEquals(ObdLinkStateValueMessage.RUNNING, live.link.state)
        val exchange = live.exchanges.single()
        assertEquals(ObdTransportStatusMessage.OK, exchange.transport)
        assertTrue(exchange.rawResponseText!!.contains("622AB8"))
    }

    @Test
    fun startLiveTakesOverAnAlreadyRunningSessionInsteadOfRejectingIt() {
        val io = ScriptedCxBleIo()
        val api = ObdApi(CxBleClient(io), NoopCxCommandClock())
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }
        val firstStart = awaitResult<Boolean> { api.startLive(testPlan(), it) }
        assertTrue(firstStart)

        // A Flutter page that started this session was torn down without a clean stopLive/
        // disconnect (process death, a crashed widget tree, ...) and now reopens — the native
        // side must let it start again rather than answering "already running" forever.
        val secondStart = awaitResult<Boolean> { api.startLive(testPlan(), it) }

        assertTrue(secondStart)
    }

    @Test
    fun aPollerNobodyAsksAboutAnymoreStopsAndDisconnectsItself() {
        val io = ScriptedCxBleIo()
        val clock = ManualCxCommandClock()
        val api = ObdApi(CxBleClient(io), clock)
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }
        awaitResult<Boolean> { api.startLive(testPlan(intervalMs = 1000), it) }

        // Never once calls api.liveState(...) — exactly the orphaned-page scenario: nothing is
        // driving this session any more, but nothing told native to stop it either. Every
        // successfully-completed command also leaves a now-harmless stale response-timeout task
        // behind (see the resync test's own doc comment on this), so this needs more than the
        // ~30 real reschedules the watchdog timeout alone would take; it stops firing anything at
        // all once the watchdog itself cancels the poller's last scheduled task.
        for (i in 0 until 200) {
            try {
                clock.fireNextDue()
            } catch (e: IllegalStateException) {
                break
            }
        }

        assertEquals(ObdLinkStateValueMessage.DISCONNECTED, api.linkState().state)
    }

    @Test
    fun startLiveSwitchesModulesInOrderWhenStepsAddressDifferentModules() {
        val io = ScriptedCxBleIo()
        val api = ObdApi(CxBleClient(io), NoopCxCommandClock())
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }

        val plan = ObdPlanMessage(
            planId = "test-plan",
            planVersion = 1,
            formatVersion = 1,
            init = listOf("ATZ"),
            resync = listOf("ATZ"),
            modules = listOf(
                ObdModuleMessage(moduleId = "m1", protocol = 6, setup = listOf("ATSP6", "ATSH710")),
                ObdModuleMessage(moduleId = "m2", protocol = 7, setup = listOf("ATSP7", "ATSH18DA06F1")),
            ),
            steps = listOf(
                step("hvEnergyContent", "m1", "222AB8", "77A"),
                step("odometerKm", "m2", "22F4A6", "18DAF106"),
            ),
            limits = limits(),
        )

        val started = awaitResult<Boolean> { api.startLive(plan, it) }
        assertTrue(started)

        // Module 1's setup runs before its own read; module 2's setup runs before ITS read —
        // the adapter can only have one module selected at a time, so a plan with two modules
        // must switch between them, not run one module's setup once and then address every
        // signal under whatever happened to be selected last.
        assertEquals(
            listOf("ATZ", "ATSP6", "ATSH710", "222AB8", "ATSP7", "ATSH18DA06F1", "22F4A6"),
            io.writtenCommands,
        )
    }

    @Test
    fun pollingRespectsTheGlobalMaxReadsPerSecondLimit() {
        val io = ScriptedCxBleIo()
        val clock = ManualCxCommandClock()
        val api = ObdApi(CxBleClient(io), clock)
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }

        // Both signals share one module (so a module switch never explains any spacing) and ask
        // to be read as fast as their own `intervalMs` allows (0) — the only thing that can still
        // separate them is the plan's global `maxReadsPerSecond: 1`, i.e. one read every 1000 ms.
        val plan = ObdPlanMessage(
            planId = "test-plan",
            planVersion = 1,
            formatVersion = 1,
            init = listOf("ATZ"),
            resync = listOf("ATZ"),
            modules = listOf(ObdModuleMessage(moduleId = "m1", protocol = 6, setup = emptyList())),
            steps = listOf(
                step("hvEnergyContent", "m1", "222AB8", "77A", intervalMs = 0),
                step("estimatedRangeKm", "m1", "222AB6", "77A", intervalMs = 0),
            ),
            limits = limits(maxReadsPerSecond = 1),
        )
        val started = awaitResult<Boolean> { api.startLive(plan, it) }
        assertTrue(started)

        // The very first read happens immediately; the second signal is also due from the very
        // start (`intervalMs: 0`) but did not run alongside it — only the global rate limit can
        // explain that, since the two share one module and the same zero per-step interval.
        assertEquals(listOf("ATZ", "222AB8"), io.writtenCommands)

        // The only thing left scheduled is the second read, and it only becomes due 1000 ms
        // after the first — exactly the spacing `maxReadsPerSecond: 1` demands.
        clock.fireNextDue()
        assertEquals(1000L, clock.now())
        assertEquals(listOf("ATZ", "222AB8", "222AB6"), io.writtenCommands)
    }

    @Test
    fun liveStateReportsNotYetPolledForASignalBeforeStartLiveRuns() {
        val io = ScriptedCxBleIo()
        val api = ObdApi(CxBleClient(io), NoopCxCommandClock())
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }

        val live = awaitResult<ObdLiveStateMessage> { api.liveState(listOf("hvEnergyContent"), it) }

        assertEquals(1, live.exchanges.size)
        assertEquals(ObdTransportStatusMessage.NOT_YET_POLLED, live.exchanges.single().transport)
    }

    @Test
    fun aTransientTransportFailureNeverReplacesTheLastSuccessfulReading() {
        val io = ScriptedCxBleIo().apply {
            responseFor = { command ->
                when (command) {
                    "222AB8" -> "77A622AB800000064"
                    "ATZ" -> "ELM327 v1.5"
                    else -> "OK"
                }
            }
            // The *second* "222AB8" write (the poller's own rescheduled read, not the one
            // `startLive` already ran) and every one after that never get a reply — a genuine,
            // permanent timeout, not a stand-in, so this never races a later successful retry
            // into overwriting the cache with (coincidentally identical) fresh content before the
            // assertion below runs.
            timeoutFromOccurrence = Pair("222AB8", 2)
        }
        val clock = ManualCxCommandClock()
        val api = ObdApi(CxBleClient(io), clock)
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }
        awaitResult<Boolean> { api.startLive(testPlan(intervalMs = 1000), it) }

        val afterSuccess = awaitResult<ObdLiveStateMessage> { api.liveState(listOf("hvEnergyContent"), it) }
        assertEquals(ObdTransportStatusMessage.OK, afterSuccess.exchanges.single().transport)
        val firstRawResponse = afterSuccess.exchanges.single().rawResponseText!!

        // Advance the clock to the rescheduled second read (due 1000 ms after the first) and let
        // it — and every retry after it — time out for real.
        repeat(30) { clock.fireNextDue() }

        val live = awaitResult<ObdLiveStateMessage> { api.liveState(listOf("hvEnergyContent"), it) }
        // `latestExchanges` would show TIMEOUT for the second attempt; `liveState` prefers
        // `latestSuccessful`, so the still-cached good value from the first read is what comes
        // back instead.
        assertEquals(ObdTransportStatusMessage.OK, live.exchanges.single().transport)
        assertEquals(firstRawResponse, live.exchanges.single().rawResponseText)
    }

    @Test
    fun startLiveFailsIfAnInitCommandIsRejected() {
        val io = ScriptedCxBleIo().apply {
            responseFor = { command -> if (command == "ATZ") "ELM327 v1.5" else if (command == "ATE0") "?" else "OK" }
        }
        val api = ObdApi(CxBleClient(io), NoopCxCommandClock())
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }

        val started = awaitResult<Boolean> { api.startLive(testPlan(), it) }

        assertFalse(started)
        assertFalse(io.writtenCommands.contains("222AB8"))
    }

    @Test
    fun aVehicleReadTimeoutRecoversThroughResyncUsingPlanResyncAndTheAtzBanner() {
        val io = ScriptedCxBleIo().apply {
            timeoutOnceCommand = "222AB8"
            responseFor = { command ->
                when (command) {
                    "ATZ" -> "ELM327 v1.5"
                    "222AB8" -> "77A622AB800000064"
                    else -> "OK"
                }
            }
        }
        val clock = ManualCxCommandClock()
        val api = ObdApi(CxBleClient(io), clock)
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }

        val plan = ObdPlanMessage(
            planId = "test-plan",
            planVersion = 1,
            formatVersion = 1,
            init = listOf("ATZ", "ATE0"),
            // Deliberately different from `init` (an extra step) so the test can tell whether
            // the resync actually used `resync`, not a reuse of `init`.
            resync = listOf("ATZ", "ATE0", "ATL0"),
            modules = listOf(ObdModuleMessage(moduleId = "m1", protocol = 6, setup = listOf("ATSP6", "ATSH710"))),
            steps = listOf(step("hvEnergyContent", "m1", "222AB8", "77A")),
            limits = limits(),
        )
        val started = awaitResult<Boolean> { api.startLive(plan, it) }
        assertTrue(started)

        // The first read was withheld — drive the response timeout, then the quiet-period wait,
        // then the resync itself, then the rescheduled next read, purely through the clock. Every
        // successfully-completed command also leaves behind its own now-harmless response-timeout
        // task (CxCommandSession schedules one regardless of whether the command already finished
        // synchronously), so this fires considerably more than the "real" 3 steps — each of those
        // extra firings is a no-op guarded by `command.finished`.
        repeat(20) { clock.fireNextDue() }

        // The resync actually ran `plan.resync`, including the step that only exists there.
        assertTrue(io.writtenCommands.contains("ATL0"))
        // The module setup ran again after the resync, because ATZ wipes the adapter's own
        // module selection and the resync must not assume it survived.
        assertEquals(2, io.writtenCommands.count { it == "ATSH710" })

        val live = awaitResult<ObdLiveStateMessage> { api.liveState(listOf("hvEnergyContent"), it) }
        assertEquals(ObdTransportStatusMessage.OK, live.exchanges.single().transport)
    }

    @Test
    fun aTimeoutResetsTheActiveModuleBeforeTheNextSameModuleStepIsQueued() {
        val io = ScriptedCxBleIo().apply {
            timeoutOnceCommand = "222AB8"
            responseFor = { command -> if (command == "ATZ") "ELM327 v1.5" else "OK" }
        }
        val clock = ManualCxCommandClock()
        val api = ObdApi(CxBleClient(io), clock)
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }

        val plan = ObdPlanMessage(
            planId = "test-plan",
            planVersion = 1,
            formatVersion = 1,
            init = listOf("ATZ"),
            resync = listOf("ATZ"),
            modules = listOf(ObdModuleMessage(moduleId = "m1", protocol = 6, setup = listOf("ATSP6", "ATSH710"))),
            steps = listOf(
                step("hvEnergyContent", "m1", "222AB8", "77A", intervalMs = 1000),
                step("estimatedRangeKm", "m1", "222AB6", "77A", intervalMs = 1000),
            ),
            limits = limits(),
        )
        val started = awaitResult<Boolean> { api.startLive(plan, it) }
        assertTrue(started)
        // Module setup ran once, for the first step; the second step is still waiting its turn.
        assertEquals(listOf("ATZ", "ATSP6", "ATSH710", "222AB8"), io.writtenCommands)

        // Drive the first step's response timeout all the way through resync — same mechanism as
        // the dedicated resync test.
        repeat(20) { clock.fireNextDue() }

        // The second step addresses the *same* module the timeout happened under, but the
        // timeout must have cleared the "module already selected" bookkeeping regardless — the
        // adapter's own module selection does not survive an ATZ-based resync. If this step were
        // wrongly skipped, "222AB6" would appear right after the resync's own commands without a
        // fresh "ATSP6"/"ATSH710" pair in between.
        val afterSecondSetup = io.writtenCommands.indexOf("222AB6")
        assertTrue("222AB6 was never written at all", afterSecondSetup >= 0)
        val setupRunsBeforeSecondRead = io.writtenCommands.subList(0, afterSecondSetup)
        assertEquals(2, setupRunsBeforeSecondRead.count { it == "ATSP6" })
        assertEquals(2, setupRunsBeforeSecondRead.count { it == "ATSH710" })
    }

    @Test
    fun aModuleSetupFailureNeverSendsTheReadBehindStaleOrResetHeaders() {
        val io = ScriptedCxBleIo().apply {
            timeoutOnceCommand = "ATSH710"
            responseFor = { command -> if (command == "ATZ") "ELM327 v1.5" else "OK" }
        }
        val clock = ManualCxCommandClock()
        val api = ObdApi(CxBleClient(io), clock)
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }

        val started = awaitResult<Boolean> { api.startLive(testPlan(), it) }
        assertTrue(started)

        // ATSH710 went out but never got a reply (withheld) — the read behind it must not follow
        // regardless, neither under the module it never confirmed nor under whatever a resync
        // eventually resets the adapter to.
        assertEquals(listOf("ATZ", "ATE0", "ATSP6", "ATSH710"), io.writtenCommands)

        // Drive ATSH710's own response timeout, then the quiet-wait-then-resync cycle it
        // triggers, then the rescheduled module setup and read — purely through the clock.
        repeat(20) { clock.fireNextDue() }

        val firstRead = io.writtenCommands.indexOf("222AB8")
        assertTrue("222AB8 was never written at all", firstRead >= 0)
        // A fresh module setup ran again before the read actually went out — proof the failed
        // setup's read was withheld rather than sent under stale or reset headers.
        val beforeRead = io.writtenCommands.subList(0, firstRead)
        assertEquals(2, beforeRead.count { it == "ATSP6" })
        assertEquals(2, beforeRead.count { it == "ATSH710" })
    }

    @Test
    fun aDisconnectWithAReadInFlightNeverLeavesAStraySessionWritingAfterReconnect() {
        val io = ScriptedCxBleIo().apply {
            timeoutOnceCommand = "222AB8"
            responseFor = { command -> if (command == "ATZ") "ELM327 v1.5" else "OK" }
        }
        val clock = ManualCxCommandClock()
        val api = ObdApi(CxBleClient(io), clock)
        val plan = ObdPlanMessage(
            planId = "test-plan",
            planVersion = 1,
            formatVersion = 1,
            init = listOf("ATZ"),
            resync = listOf("ATZ"),
            modules = listOf(ObdModuleMessage(moduleId = "m1", protocol = 6, setup = listOf("ATSP6", "ATSH710"))),
            steps = listOf(step("hvEnergyContent", "m1", "222AB8", "77A")),
            limits = limits(),
        )

        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }
        awaitResult<Boolean> { api.startLive(plan, it) }
        // The read is now in flight (withheld by the fake) when the disconnect happens — the
        // exact precondition for the leak: a `CxCommandSession` torn down with a command active
        // ends up with its own `resyncRequired` set.
        awaitResult<Unit> { api.disconnect(it) }

        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }
        val started = awaitResult<Boolean> { api.startLive(plan, it) }
        assertTrue(started)

        // Advance the clock well past every timer the *old*, already-disconnected session could
        // still have pending (response timeout, quiet period, resync) — if it is still alive, it
        // writes a stray "ATZ" of its own onto the shared connection on top of the new session's.
        for (i in 0 until 50) {
            try {
                clock.fireNextDue()
            } catch (e: IllegalStateException) {
                break
            }
        }

        assertEquals(2, io.writtenCommands.count { it == "ATZ" })
    }

    @Test
    fun isObdSupportedIsAlwaysTrueOnAndroid() {
        val api = ObdApi(CxBleClient(ScriptedCxBleIo()), NoopCxCommandClock())
        assertTrue(api.isObdSupported())
    }

    @Test
    fun aSecondConcurrentConnectIsRejectedInsteadOfOverwritingThePendingOne() {
        val io = ScriptedCxBleIo()
        var firstResult: Result<ObdLinkStateMessage>? = null
        val api = ObdApi(
            CxBleClient(
                object : CxBleIo by io {
                    // Never resolves on its own — simulates a connect attempt still in flight.
                    override fun connect(handle: CxAdapterHandle, callback: CxBleIoCallback) {
                        io.lastCallback = callback
                    }
                },
            ),
            NoopCxCommandClock(),
        )

        api.connect("handle-1") { firstResult = it }
        var secondResolved = false
        api.connect("handle-2") { secondResolved = true }

        assertNull(firstResult)
        assertTrue(secondResolved)
    }

    /** A trivial, never-started [CxPoller] standing in for a recording's own — these tests are
     * about the connection's one-poller-slot bookkeeping ([ObdApi.installRecordingPoller]/
     * [ObdApi.uninstallRecordingPoller]/[ObdApi.prepareConnectionForRecording]), not about
     * anything the poller itself does, so it is never told to [CxPoller.start]. */
    private fun recordingPollerOn(session: CxCommandSession) = CxPoller(
        session = session,
        clock = NoopCxCommandClock(),
        modules = emptyMap(),
        deadlineMs = 2000L,
        setupDeadlineMs = 2000L,
        maxReadsPerSecond = 1,
        monotonicBaseNs = 0L,
    )

    @Test
    fun startLiveRefusesWhileARecordingOccupiesTheConnectionsOnePollerSlot() {
        val io = ScriptedCxBleIo()
        val api = ObdApi(CxBleClient(io), NoopCxCommandClock())
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }
        val recordingSession = api.prepareConnectionForRecording(4096)!!
        api.installRecordingPoller(recordingPollerOn(recordingSession))

        val started = awaitResult<Boolean> { api.startLive(testPlan(), it) }

        assertFalse("a running recording must keep startLive from taking the poller slot over", started)
    }

    @Test
    fun stopLiveLeavesARecordingPollerInstalled() {
        val io = ScriptedCxBleIo()
        val api = ObdApi(CxBleClient(io), NoopCxCommandClock())
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }
        val recordingSession = api.prepareConnectionForRecording(4096)!!
        api.installRecordingPoller(recordingPollerOn(recordingSession))

        awaitResult<Unit> { api.stopLive(it) }

        // RUNNING is reported only while the connection's one poller slot is occupied — a live
        // page's own stopLive must not be able to clear a slot a recording owns.
        assertEquals(ObdLinkStateValueMessage.RUNNING, api.linkState().state)
    }

    @Test
    fun disconnectClearsARecordingPollerSoALaterStartLiveIsNotStillRefused() {
        val io = ScriptedCxBleIo()
        val api = ObdApi(CxBleClient(io), NoopCxCommandClock())
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }
        val recordingSession = api.prepareConnectionForRecording(4096)!!
        api.installRecordingPoller(recordingPollerOn(recordingSession))

        awaitResult<Unit> { api.disconnect(it) }
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }
        val started = awaitResult<Boolean> { api.startLive(testPlan(), it) }

        assertTrue("disconnect must drop the old recording's poller slot claim, not leave it stuck refusing forever", started)
    }

    @Test
    fun startLiveRefusesDuringTheWindowBetweenStartRecordingAcceptingAndThePollerActuallyInstalling() {
        val io = ScriptedCxBleIo()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val api = ObdApi(CxBleClient(io), NoopCxCommandClock(), context)
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }

        val request = ObdStartRecordingRequest(
            sessionId = "session-1",
            vehicleId = "vehicle-1",
            bufferFilePath = "/tmp/does-not-matter.jsonl",
            startedAtEpochMs = 0L,
            plan = testPlan(),
            options = testRecordingOptions(),
        )
        val recordingAccepted = awaitResult<Boolean> { api.startRecording(request, it) }
        assertTrue("a connected recording request should be accepted", recordingAccepted)

        // `CxRecordingService` never actually ran in this unit test — Robolectric does not
        // deliver a start intent to a plain `startForegroundService` call without an explicit
        // `ServiceController` — so `pendingRecordingRequest` is still set, exactly the window
        // this guards: `recordingPoller` itself is still `null` at this point.
        val liveStarted = awaitResult<Boolean> { api.startLive(testPlan(), it) }

        assertFalse(
            "a recording merely accepted, not yet polling, must still block startLive",
            liveStarted,
        )
    }

    @Test
    fun reconnectForRecordingReportsTrueWithoutDisturbingTheRunningSessionForTheSameHandle() {
        val io = ScriptedCxBleIo()
        val api = ObdApi(CxBleClient(io), NoopCxCommandClock())
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }
        val recordingSession = api.prepareConnectionForRecording(4096)!!
        api.installRecordingPoller(recordingPollerOn(recordingSession))
        val timebaseBefore = api.currentTimebase()

        var result: Boolean? = null
        api.reconnectForRecording("handle-1") { result = it }

        assertEquals(true, result)
        assertEquals(
            "reconnecting to the handle already connected must not replace the running " +
                "session (and therefore orphan its installed poller) with a fresh one",
            timebaseBefore,
            api.currentTimebase(),
        )
        assertEquals(ObdLinkStateValueMessage.RUNNING, api.linkState().state)
    }

    @Test
    fun connectToTheAlreadyConnectedHandleKeepsTheRunningSession() {
        val io = ScriptedCxBleIo()
        val api = ObdApi(CxBleClient(io), NoopCxCommandClock())
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }
        val recordingSession = api.prepareConnectionForRecording(4096)!!
        api.installRecordingPoller(recordingPollerOn(recordingSession))
        val timebaseBefore = api.currentTimebase()

        // `CxBleClient.connect` rejects this outright (already connected to the same handle,
        // `activeHandle != null`) and resolves synchronously with the unchanged `Connected`
        // state — exercising `ObdApi.connect`'s own session-replacement guard directly, not
        // `reconnectForRecording`'s early `isConnectedTo` exit (which never calls `connect` at
        // all for this exact case).
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }

        assertEquals(
            "a rejected connect() to the already-connected handle must not replace the running " +
                "session (and therefore orphan its installed poller) with a fresh one",
            timebaseBefore,
            api.currentTimebase(),
        )
        assertEquals(ObdLinkStateValueMessage.RUNNING, api.linkState().state)
    }

    @Test
    fun reconnectForRecordingReportsFalseAndLeavesTheRunningSessionUntouchedForADifferentAdapter() {
        val io = ScriptedCxBleIo()
        val api = ObdApi(CxBleClient(io), NoopCxCommandClock())
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }
        val recordingSession = api.prepareConnectionForRecording(4096)!!
        api.installRecordingPoller(recordingPollerOn(recordingSession))
        val timebaseBefore = api.currentTimebase()

        // `CxBleClient.connect` rejects outright (already connected, to a different handle) —
        // the fake's own `connect` never even runs for this second handle, matching what a real
        // adapter connection would do.
        var result: Boolean? = null
        api.reconnectForRecording("handle-2") { result = it }

        assertEquals(
            "a reconnect to a different adapter than the one already connected must never be " +
                "reported as success — the recording would otherwise keep running on the wrong " +
                "vehicle's adapter under its own vehicleId",
            false,
            result,
        )
        assertEquals("handle-1", api.linkState().adapterHandle)
        assertEquals(
            "the running recording's session/poller must survive a rejected reconnect to an " +
                "unrelated adapter untouched, not get silently replaced",
            timebaseBefore,
            api.currentTimebase(),
        )
        assertEquals(ObdLinkStateValueMessage.RUNNING, api.linkState().state)
    }

    @Test
    fun configureResyncForRecordingIgnoresAStaleSessionsResyncOutcomeAfterItWasRetired() {
        val io = ScriptedCxBleIo().apply {
            timeoutOnceCommand = "222AB8"
            // Withholds the *first* "ATZ" write entirely, so the resync step this test drives
            // is still genuinely outstanding (not yet resolved either way) at the moment the
            // session gets retired — that is the one real race this test exercises, not what
            // the step would eventually have resolved to.
            timeoutFromOccurrence = "ATZ" to 1
        }
        val clock = ManualCxCommandClock()
        val api = ObdApi(CxBleClient(io), clock)
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }
        val oldSession = api.prepareConnectionForRecording(4096)!!
        api.configureResyncForRecording(oldSession, listOf("ATZ"), 2000L) {}

        // A real vehicle-read failure on the old session starts its own quiet-wait-then-resync
        // cycle automatically (`CxCommandSession`'s documented behavior): the withheld reply to
        // "222AB8" drives a genuine response timeout, which in turn schedules the quiet period.
        oldSession.execute("222AB8", 2000L) {}
        clock.fireNextDue() // the 222AB8 response timeout -> starts the quiet period
        clock.fireNextDue() // the quiet period elapses -> writes (withheld) "ATZ"; now active
        assertEquals(
            "the precondition this test relies on: the resync step's own \"ATZ\" must actually " +
                "be outstanding (written, never replied to) at this point",
            listOf("222AB8", "ATZ"),
            io.writtenCommands,
        )

        // A second recording start (e.g. after a reconnect) retires the session while its own
        // "ATZ" resync step is still outstanding — `CxCommandSession.cancel()` resolves any
        // active command synchronously as `CANCELLED`, delivering that outcome straight to the
        // old session's resync listener right here, not through any further scheduled task.
        api.prepareConnectionForRecording(4096)

        assertEquals(
            "a stale CANCELLED resync outcome from an already-retired session must not " +
                "disconnect the connection a newer session is using",
            ObdLinkStateValueMessage.CONNECTED,
            api.linkState().state,
        )
    }

    @Test
    fun acknowledgeImportedForgetsAMatchingFinishedRecordingsLastState() {
        val api = ObdApi(CxBleClient(ScriptedCxBleIo()), NoopCxCommandClock())
        api.reportRecordingEnded(
            CxRecordingState(
                sessionId = "session-1",
                phase = CxRecordingPhase.STOPPED,
                lastFlushedSequence = 5L,
                bufferBytes = 100L,
                gapCount = 0,
                writeError = null,
                resumedAfterRestart = false,
            ),
        )

        awaitResult<Unit> { api.acknowledgeImported("session-1", 5L, it) }

        assertEquals(
            "a matching finished recording's remembered state must be forgotten once Dart " +
                "confirms the import, so a later poll does not keep reporting it",
            ObdRecordingStateValueMessage.IDLE,
            api.recordingState().state,
        )
    }

    @Test
    fun acknowledgeImportedIgnoresAMismatchedSessionId() {
        val api = ObdApi(CxBleClient(ScriptedCxBleIo()), NoopCxCommandClock())
        api.reportRecordingEnded(
            CxRecordingState(
                sessionId = "session-1",
                phase = CxRecordingPhase.STOPPED,
                lastFlushedSequence = 5L,
                bufferBytes = 100L,
                gapCount = 0,
                writeError = null,
                resumedAfterRestart = false,
            ),
        )

        awaitResult<Unit> { api.acknowledgeImported("session-2", 5L, it) }

        assertEquals(
            "acknowledging an unrelated session id must not forget a different, still-pending " +
                "recording's remembered state",
            "session-1",
            api.recordingState().sessionId,
        )
    }

    @Test
    fun acknowledgeImportedNeverForgetsTheCurrentlyRunningRecording() {
        val io = ScriptedCxBleIo()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val api = ObdApi(CxBleClient(io), NoopCxCommandClock(), context)
        awaitResult<ObdLinkStateMessage> { api.connect("handle-1", it) }
        val request = ObdStartRecordingRequest(
            sessionId = "session-1",
            vehicleId = "vehicle-1",
            bufferFilePath = "/tmp/does-not-matter.jsonl",
            startedAtEpochMs = 0L,
            plan = testPlan(),
            options = testRecordingOptions(),
        )
        awaitResult<Boolean> { api.startRecording(request, it) }

        // `CxRecordingService` never actually runs under Robolectric here (see the window test
        // above), so the recording is only "accepted", not yet owned by a `recordingOwner` —
        // acknowledging it anyway must not be able to make `recordingState()` forget a recording
        // Dart itself just started.
        awaitResult<Unit> { api.acknowledgeImported("session-1", 0L, it) }

        assertEquals(
            "a recording accepted but not yet running must still be reported, never forgotten " +
                "by an acknowledgement for the same id",
            ObdRecordingStateValueMessage.RECORDING,
            api.recordingState().state,
        )
    }
}
