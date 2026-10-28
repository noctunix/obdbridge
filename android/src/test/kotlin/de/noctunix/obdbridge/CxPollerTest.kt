package de.noctunix.obdbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Records every written command and, when a reply is configured for it, feeds it straight back
 * into [session] as a BLE notification — enough to drive a whole module-setup + vehicle-read
 * cycle through [CxPoller] without any real adapter. A command with no configured reply (an
 * absent key, or an explicit `null`) is left to time out against [clock] instead. */
private class ScriptedTransport(private val responseFor: (String) -> String?) : CxCommandTransport {
    lateinit var session: CxCommandSession
    val writes = mutableListOf<String>()

    override fun write(bytes: ByteArray): Boolean {
        val command = String(bytes, Charsets.US_ASCII).removeSuffix("\r")
        writes.add(command)
        val response = responseFor(command) ?: return true
        session.onNotification("$response\r>".toByteArray())
        return true
    }
}

/** Same shape as `CxCommandSessionTest`'s own fake clock: fires whichever still-pending entry is
 * due soonest, not in scheduling order. */
private class FakeClock : CxCommandClock {
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

/** Fires up to [maxSteps] scheduled entries in due order, stopping early once nothing is left
 * scheduled — the poll/resync/quiet-period cycle this drives can in principle run forever (a
 * live/recording poller reschedules itself after every cycle), so every test bounds it rather
 * than draining to exhaustion. */
private fun drive(clock: FakeClock, maxSteps: Int = 50, until: () -> Boolean = { false }) {
    for (i in 0 until maxSteps) {
        if (until()) return
        try {
            clock.fireNextDue()
        } catch (e: IllegalStateException) {
            return
        }
    }
}

private val moduleA = ObdModuleMessage(moduleId = "m1", protocol = 6L, setup = listOf("ATSP6"))
private val moduleB = ObdModuleMessage(moduleId = "m2", protocol = 6L, setup = listOf("ATSP6", "ATSH720"))
private val stepA = ObdStepMessage(signalId = "sigA", moduleId = "m1", command = "222AB8", responseHeader = "77A", intervalMs = 1000L)
private val stepB = ObdStepMessage(signalId = "sigB", moduleId = "m1", command = "222AF7", responseHeader = "77A", intervalMs = 1000L)
private val oneShotStepA = ObdStepMessage(signalId = "oneShotA", moduleId = "m1", command = "222AC0", responseHeader = "77A", intervalMs = 0L)
private val oneShotStepB = ObdStepMessage(signalId = "oneShotB", moduleId = "m2", command = "222AD0", responseHeader = "720", intervalMs = 0L)

/** Robolectric is needed because [CxPoller] calls `SystemClock.elapsedRealtimeNanos()`, whose
 * stub in `android.jar` throws "not mocked" on a plain JVM. */
@RunWith(RobolectricTestRunner::class)
class CxPollerTest {

    private fun buildPoller(
        responses: MutableMap<String, String?>,
        clock: FakeClock,
        maxConsecutiveFailures: Int = Int.MAX_VALUE,
        onConsecutiveFailuresPolicy: ObdConsecutiveFailuresPolicyMessage = ObdConsecutiveFailuresPolicyMessage.END_RECORDING,
        idleVehicleStopAfterMs: Long = Long.MAX_VALUE,
        onConsecutiveFailuresExceeded: (() -> Unit)? = null,
        onIdleVehicleDetected: (() -> Unit)? = null,
        sink: ((CxPollExchange) -> Unit)? = null,
        oneShotQueueCapacity: Int = CxPoller.DEFAULT_ONE_SHOT_QUEUE_CAPACITY,
    ): CxPoller {
        val transport = ScriptedTransport { command -> responses[command] }
        val session = CxCommandSession(transport, clock)
        transport.session = session
        return CxPoller(
            session = session,
            clock = clock,
            modules = mapOf("m1" to moduleA),
            deadlineMs = 2000L,
            setupDeadlineMs = 2000L,
            maxReadsPerSecond = 10,
            monotonicBaseNs = 0L,
            maxConsecutiveFailures = maxConsecutiveFailures,
            onConsecutiveFailuresPolicy = onConsecutiveFailuresPolicy,
            idleVehicleStopAfterMs = idleVehicleStopAfterMs,
            onConsecutiveFailuresExceeded = onConsecutiveFailuresExceeded,
            onIdleVehicleDetected = onIdleVehicleDetected,
            sink = sink,
            oneShotQueueCapacity = oneShotQueueCapacity,
        )
    }

    @Test
    fun runsModuleSetupOnceThenReportsEverySuccessfulExchangeToTheSink() {
        val clock = FakeClock()
        val exchanges = mutableListOf<CxPollExchange>()
        val responses = mutableMapOf<String, String?>("ATSP6" to "OK", "222AB8" to "622AB80005E3")
        val poller = buildPoller(responses, clock, sink = { exchanges.add(it) })

        poller.start(listOf(stepA))

        assertEquals(1, exchanges.size)
        assertEquals("sigA", exchanges.single().signalId)
        assertEquals(ObdTransportStatusMessage.OK, exchanges.single().transport)
        assertEquals("622AB80005E3", exchanges.single().responseText)
    }

    @Test
    fun aFailedExchangeIsReportedToTheSinkTooWithNoResponseText() {
        val clock = FakeClock()
        val exchanges = mutableListOf<CxPollExchange>()
        // ATSP6 succeeds, but the vehicle read itself gets no reply at all -> times out.
        val responses = mutableMapOf<String, String?>("ATSP6" to "OK", "222AB8" to null)
        val poller = buildPoller(responses, clock, sink = { exchanges.add(it) })

        poller.start(listOf(stepA))
        drive(clock, until = { exchanges.isNotEmpty() })

        assertEquals(1, exchanges.size)
        assertEquals(ObdTransportStatusMessage.TIMEOUT, exchanges.single().transport)
        assertEquals(null, exchanges.single().responseText)
    }

    /** Exercises [CxPoller]'s own consecutive-failure counter in isolation, independent of
     * [CxCommandSession]'s resync machinery — no resync sequence is configured here, so the
     * "failure" this drives past the deadline is a queued-command timeout on an already-blocked
     * session, not the "one resync attempt, then the session gives up" path a real adapter
     * would go through. [CxRecordingService] wires both together; this test is only about the
     * counter itself. */
    @Test
    fun consecutiveFailuresAcrossPollsStopThePollerAndFireTheCallbackExactlyOnce() {
        val clock = FakeClock()
        // Every vehicle read times out; module setup always succeeds.
        val responses = mutableMapOf<String, String?>("ATSP6" to "OK", "222AB8" to null)
        var exceededCalls = 0
        val poller = buildPoller(
            responses,
            clock,
            maxConsecutiveFailures = 2,
            onConsecutiveFailuresExceeded = { exceededCalls++ },
        )

        poller.start(listOf(stepA))
        drive(clock, until = { exceededCalls > 0 })

        assertEquals(1, exceededCalls)
    }

    @Test
    fun oneSuccessfulReadResetsTheConsecutiveFailureCounter() {
        val clock = FakeClock()
        // Every odd vehicle-read attempt times out, every even one succeeds — a real reset
        // test, not merely a run of successes that never gave the counter a chance to climb at
        // all: if a single success did not actually reset it, this alternation would reach the
        // limit (2) within the first few cycles.
        var attempt = 0
        val transport = ScriptedTransport { command ->
            when (command) {
                "ATSP6" -> "OK"
                // A timed-out vehicle read leaves `CxCommandSession` permanently blocked until a
                // resync actually completes (its own documented behavior) — configured below so
                // the alternation can ever reach a second real attempt at all, not because this
                // test cares about resync content itself.
                "ATZ" -> "OK"
                "222AB8" -> {
                    attempt++
                    if (attempt % 2 == 1) null else "OK"
                }
                else -> null
            }
        }
        val session = CxCommandSession(transport, clock)
        transport.session = session
        session.configureResync(listOf(CxResyncStep("ATZ") { true }), 2000L)
        var exceededCalls = 0
        val poller = CxPoller(
            session = session,
            clock = clock,
            modules = mapOf("m1" to moduleA),
            deadlineMs = 2000L,
            setupDeadlineMs = 2000L,
            maxReadsPerSecond = 10,
            monotonicBaseNs = 0L,
            maxConsecutiveFailures = 2,
            onConsecutiveFailuresExceeded = { exceededCalls++ },
        )

        poller.start(listOf(stepA))
        drive(clock, maxSteps = 200)

        assertEquals("a success in between must reset the counter, not merely delay tripping it", 0, exceededCalls)
        assertTrue("the alternation must actually have run multiple attempts", attempt >= 6)
    }

    @Test
    fun resyncAndContinueFiresTheCallbackWithoutStoppingThePoller() {
        val clock = FakeClock()
        // Every vehicle read times out; module setup always succeeds, so the counter climbs
        // without end once CxCommandSession's own resync is never actually configured here —
        // this test is only about the poller continuing to run past the threshold, the resync
        // sequence itself is CxCommandSession's own concern (see CxPollerTest's other tests).
        val responses = mutableMapOf<String, String?>("ATSP6" to "OK", "222AB8" to null)
        var exceededCalls = 0
        val poller = buildPoller(
            responses,
            clock,
            maxConsecutiveFailures = 2,
            onConsecutiveFailuresPolicy = ObdConsecutiveFailuresPolicyMessage.RESYNC_AND_CONTINUE,
            onConsecutiveFailuresExceeded = { exceededCalls++ },
        )

        poller.start(listOf(stepA))
        drive(clock, maxSteps = 100, until = { exceededCalls > 0 })

        assertTrue("the threshold must still fire the callback", exceededCalls > 0)
        // A stopped poller rejects every further enqueueOneShot call outright (see
        // `enqueueOneShot`'s own `stopped ||` check) — accepted here is this test's proof that
        // resyncAndContinue never called `stop()` for hitting the budget.
        assertEquals(
            ObdOneShotEnqueueResultMessage.ACCEPTED,
            poller.enqueueOneShot(emptyList(), listOf(oneShotStepA)),
        )
    }

    @Test
    fun allStepsAnsweringNoDataForTheConfiguredDurationFiresIdleVehicleDetected() {
        val clock = FakeClock()
        val responses = mutableMapOf<String, String?>("ATSP6" to "OK", "222AB8" to "NO DATA", "222AF7" to "NO DATA")
        var idleCalls = 0
        val poller = buildPoller(
            responses,
            clock,
            idleVehicleStopAfterMs = 5000L,
            onIdleVehicleDetected = { idleCalls++ },
        )

        poller.start(listOf(stepA, stepB))
        drive(clock, maxSteps = 200, until = { idleCalls > 0 })

        assertEquals(1, idleCalls)
    }

    @Test
    fun aSingleRealValueAmongNoDataAnswersNeverCountsAsIdle() {
        val clock = FakeClock()
        val responses = mutableMapOf<String, String?>("ATSP6" to "OK", "222AB8" to "NO DATA", "222AF7" to "622AF700")
        var idleCalls = 0
        val poller = buildPoller(
            responses,
            clock,
            idleVehicleStopAfterMs = 1000L,
            onIdleVehicleDetected = { idleCalls++ },
        )

        poller.start(listOf(stepA, stepB))
        drive(clock, maxSteps = 100)

        assertEquals("sigB answers real data every cycle, so the vehicle is never all-NO-DATA", 0, idleCalls)
    }

    @Test
    fun stoppingBeforeTheNextScheduledPollPreventsItFromEverRunning() {
        val clock = FakeClock()
        val responses = mutableMapOf<String, String?>("ATSP6" to "OK", "222AB8" to "OK")
        val exchanges = mutableListOf<CxPollExchange>()
        val poller = buildPoller(responses, clock, sink = { exchanges.add(it) })

        poller.start(listOf(stepA))
        // The first cycle already resolved synchronously (the scripted transport replies
        // in-line) — this is the one real exchange this test expects.
        assertEquals(1, exchanges.size)

        poller.stop()
        // The next poll cycle (one interval later) is cancelled scheduled work, not yet fired;
        // draining every remaining scheduled task must not produce a second exchange.
        drive(clock, maxSteps = 50)

        assertEquals("stop() must prevent every later poll, not just pause it", 1, exchanges.size)
    }

    @Test
    fun aQueuedOneShotStepRunsOnceInTheIdleGapWithoutDelayingTheNextRecurringPoll() {
        val clock = FakeClock()
        val responses = mutableMapOf<String, String?>(
            "ATSP6" to "OK",
            "222AB8" to "622AB80005E3",
            "222AC0" to "622AC0FF",
        )
        val exchanges = mutableListOf<CxPollExchange>()
        val poller = buildPoller(responses, clock, sink = { exchanges.add(it) })

        poller.start(listOf(stepA))
        // The first stepA cycle resolves synchronously; stepA is next due at t=1000.
        assertEquals(1, exchanges.size)

        val result = poller.enqueueOneShot(emptyList(), listOf(oneShotStepA))
        assertEquals(ObdOneShotEnqueueResultMessage.ACCEPTED, result)

        drive(clock, until = { exchanges.size >= 2 })
        assertEquals(2, exchanges.size)
        val oneShotExchange = exchanges[1]
        assertEquals("oneShotA", oneShotExchange.signalId)
        assertTrue(oneShotExchange.oneShot)
        assertEquals(ObdTransportStatusMessage.OK, oneShotExchange.transport)
        assertEquals(0, poller.oneShotQueueDepth)

        drive(clock, maxSteps = 50, until = { exchanges.size >= 3 })
        assertEquals("the recurring stepA cycle must still run at its own due time", 3, exchanges.size)
        val secondStepA = exchanges[2]
        assertEquals("sigA", secondStepA.signalId)
        assertFalse(secondStepA.oneShot)
    }

    @Test
    fun aOneShotStepAddressingADifferentModuleRunsThatModulesSetupFirst() {
        val clock = FakeClock()
        val responses = mutableMapOf<String, String?>(
            "ATSP6" to "OK",
            "ATSH720" to "OK",
            "222AB8" to "622AB80005E3",
            "222AD0" to "622AD0FF",
        )
        val writes = mutableListOf<String>()
        val transport = ScriptedTransport { command ->
            writes.add(command)
            responses[command]
        }
        val session = CxCommandSession(transport, clock)
        transport.session = session
        val poller = CxPoller(
            session = session,
            clock = clock,
            modules = mapOf("m1" to moduleA),
            deadlineMs = 2000L,
            setupDeadlineMs = 2000L,
            maxReadsPerSecond = 10,
            monotonicBaseNs = 0L,
        )

        poller.start(listOf(stepA))
        val result = poller.enqueueOneShot(listOf(moduleB), listOf(oneShotStepB))
        assertEquals(ObdOneShotEnqueueResultMessage.ACCEPTED, result)
        drive(clock, until = { writes.contains("222AD0") })

        assertTrue("moduleB's setup must run before its one-shot step", writes.containsAll(listOf("ATSH720", "222AD0")))
        assertTrue("ATSH720 must precede the step it sets up for", writes.indexOf("ATSH720") < writes.indexOf("222AD0"))
    }

    @Test
    fun aOneShotStepWhoseModuleSetupFailsStillReportsExactlyOneResult() {
        val clock = FakeClock()
        val responses = mutableMapOf<String, String?>(
            "ATSP6" to "OK",
            // moduleB's own setup command never answers — the one-shot step addressing it must
            // not simply vanish just because the setup it needed never completed.
            "ATSH720" to null,
            "222AB8" to "622AB80005E3",
        )
        val exchanges = mutableListOf<CxPollExchange>()
        val poller = buildPoller(responses, clock, sink = { exchanges.add(it) })

        poller.start(listOf(stepA))
        assertEquals(1, exchanges.size)

        val result = poller.enqueueOneShot(listOf(moduleB), listOf(oneShotStepB))
        assertEquals(ObdOneShotEnqueueResultMessage.ACCEPTED, result)
        drive(clock, maxSteps = 100, until = { exchanges.size >= 2 })

        assertEquals(2, exchanges.size)
        val failed = exchanges[1]
        assertEquals("oneShotB", failed.signalId)
        assertTrue(failed.oneShot)
        assertEquals(ObdTransportStatusMessage.WRITE_FAILED, failed.transport)
        assertEquals(null, failed.responseText)
        assertEquals(0, poller.oneShotQueueDepth)
    }

    @Test
    fun enqueueOneShotRejectsTheWholeBatchOnceItWouldExceedCapacity() {
        val clock = FakeClock()
        // stepA's own cycle issues at t=0 and sets the global rate-limit floor to t=100 — the
        // window this test relies on so that the batch below sits in the queue unexamined,
        // rather than one entry being dispatched (and dequeued) the instant it is enqueued.
        val responses = mutableMapOf<String, String?>("ATSP6" to "OK", "222AB8" to "OK")
        val poller = buildPoller(responses, clock, oneShotQueueCapacity = 2)
        poller.start(listOf(stepA))

        val first = poller.enqueueOneShot(emptyList(), listOf(oneShotStepA, oneShotStepA))
        assertEquals(ObdOneShotEnqueueResultMessage.ACCEPTED, first)
        assertEquals(2, poller.oneShotQueueDepth)

        val second = poller.enqueueOneShot(emptyList(), listOf(oneShotStepA))
        assertEquals(
            "a batch that would exceed capacity must be rejected whole, not partially queued",
            ObdOneShotEnqueueResultMessage.QUEUE_FULL,
            second,
        )
        assertEquals(2, poller.oneShotQueueDepth)
    }

    @Test
    fun stoppingThePollerReportsEveryQueuedOneShotStepAsCancelledRatherThanDroppingIt() {
        val clock = FakeClock()
        // Same rate-limit window as above, so the queued one-shot below is still sitting
        // unexamined in the queue when `stop()` runs, not already dispatched.
        val responses = mutableMapOf<String, String?>("ATSP6" to "OK", "222AB8" to "OK", "222AC0" to "OK")
        val exchanges = mutableListOf<CxPollExchange>()
        val poller = buildPoller(responses, clock, sink = { exchanges.add(it) })
        poller.start(listOf(stepA))

        poller.enqueueOneShot(emptyList(), listOf(oneShotStepA))
        assertEquals(1, poller.oneShotQueueDepth)

        poller.stop()

        assertEquals(0, poller.oneShotQueueDepth)
        assertEquals(
            "a stopped poller must never run work left in the one-shot queue — the only " +
                "other exchange here is stepA's own synchronous first cycle",
            2,
            exchanges.size,
        )
        val cancelled = exchanges[1]
        assertEquals("oneShotA", cancelled.signalId)
        assertTrue(cancelled.oneShot)
        assertEquals(ObdTransportStatusMessage.CANCELLED, cancelled.transport)
        assertEquals(null, cancelled.responseText)
        assertEquals(0, cancelled.rawBytes.size)
        assertEquals(
            ObdOneShotEnqueueResultMessage.QUEUE_FULL,
            poller.enqueueOneShot(emptyList(), listOf(oneShotStepA)),
        )
    }
}
