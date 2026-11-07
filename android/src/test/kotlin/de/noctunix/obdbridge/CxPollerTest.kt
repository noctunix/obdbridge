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

/** Like [ScriptedTransport], but a vehicle-read reply listed in [held] is only delivered once
 * [release] is called — so a test can act while that read is still in flight. */
private class HeldTransport(private val responseFor: (String) -> String?, private val held: Set<String>) : CxCommandTransport {
    lateinit var session: CxCommandSession
    val writes = mutableListOf<String>()
    private val pending = ArrayDeque<String>()

    override fun write(bytes: ByteArray): Boolean {
        val command = String(bytes, Charsets.US_ASCII).removeSuffix("\r")
        writes.add(command)
        val response = responseFor(command) ?: return true
        if (command in held) {
            pending.addLast(response)
        } else {
            session.onNotification("$response\r>".toByteArray())
        }
        return true
    }

    fun release() {
        while (pending.isNotEmpty()) session.onNotification("${pending.removeFirst()}\r>".toByteArray())
    }
}

/** Answers every vehicle read only [readDurationMs] of [clock] time after it was written (module
 * setup at once), so each read occupies the bus for a while the way a real one does. */
private class DelayedTransport(private val clock: FakeClock, private val readDurationMs: Long) : CxCommandTransport {
    lateinit var session: CxCommandSession

    override fun write(bytes: ByteArray): Boolean {
        val command = String(bytes, Charsets.US_ASCII).removeSuffix("\r")
        if (command.startsWith("22")) {
            clock.schedule(readDurationMs) { session.onNotification("62${command.drop(2)}00\r>".toByteArray()) }
        } else {
            session.onNotification("OK\r>".toByteArray())
        }
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

    /** Moves time forward without firing anything — for a test that needs time to pass while a
     * read is still in flight, before any scheduled entry is due. */
    fun advanceTo(time: Long) {
        check(entries.none { !it.cancelled && it.dueAt < time }) { "an entry is due before $time" }
        currentTime = time
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

private fun longIntervalSteps(count: Int, intervalMs: Long) = (0 until count).map {
    ObdStepMessage(
        signalId = "long$it",
        moduleId = "m1",
        command = "22%04X".format(0x1000 + it),
        responseHeader = "77A",
        intervalMs = intervalMs,
    )
}

/** Robolectric is needed because [CxPoller] calls `SystemClock.elapsedRealtimeNanos()`, whose
 * stub in `android.jar` throws "not mocked" on a plain JVM. */
@RunWith(RobolectricTestRunner::class)
class CxPollerTest {

    private fun buildPoller(
        responses: MutableMap<String, String?>,
        clock: FakeClock,
        maxConsecutiveFailures: Int = Int.MAX_VALUE,
        onConsecutiveFailuresPolicy: ObdConsecutiveFailuresPolicyMessage = ObdConsecutiveFailuresPolicyMessage.END_RECORDING,
        maxResyncCyclesBeforeGivingUp: Int = Int.MAX_VALUE,
        idleVehicleStopAfterMs: Long = Long.MAX_VALUE,
        onConsecutiveFailuresExceeded: ((Boolean) -> Unit)? = null,
        onIdleVehicleDetected: (() -> Unit)? = null,
        sink: ((CxPollExchange) -> Unit)? = null,
        oneShotQueueCapacity: Int = CxPoller.DEFAULT_ONE_SHOT_QUEUE_CAPACITY,
        quietModuleBackoffMs: Long = 0L,
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
            maxResyncCyclesBeforeGivingUp = maxResyncCyclesBeforeGivingUp,
            idleVehicleStopAfterMs = idleVehicleStopAfterMs,
            onConsecutiveFailuresExceeded = onConsecutiveFailuresExceeded,
            onIdleVehicleDetected = onIdleVehicleDetected,
            sink = sink,
            oneShotQueueCapacity = oneShotQueueCapacity,
            quietModuleBackoffMs = quietModuleBackoffMs,
        )
    }

    /** Runs [steps] over a [DelayedTransport] taking 100 ms per read until [untilMs] and returns
     * every completed read as its signal id and completion time. */
    private fun readsOverDelayedTransport(steps: List<ObdStepMessage>, untilMs: Long): List<Pair<String, Long>> {
        val clock = FakeClock()
        val transport = DelayedTransport(clock, readDurationMs = 100L)
        val session = CxCommandSession(transport, clock)
        transport.session = session
        val reads = mutableListOf<Pair<String, Long>>()
        val poller = CxPoller(
            session = session,
            clock = clock,
            modules = mapOf("m1" to moduleA),
            deadlineMs = 2000L,
            setupDeadlineMs = 2000L,
            maxReadsPerSecond = 10,
            monotonicBaseNs = 0L,
            sink = { reads.add(it.signalId to clock.now()) },
        )
        poller.start(steps)
        drive(clock, maxSteps = 10_000, until = { clock.now() >= untilMs })
        poller.stop()
        return reads
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
            onConsecutiveFailuresExceeded = { _ -> exceededCalls++ },
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
            onConsecutiveFailuresExceeded = { _ -> exceededCalls++ },
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
            onConsecutiveFailuresExceeded = { _ -> exceededCalls++ },
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
    fun resyncAndContinueGivesUpAndStopsAfterTheConfiguredNumberOfCycles() {
        val clock = FakeClock()
        // No resync sequence is configured here, so after the very first vehicle-read timeout
        // `CxCommandSession` stays permanently blocked: every later "failure" is actually a
        // queued command (module setup, then the read) timing out on its own queue deadline,
        // never reaching the transport again — this test is only about the poller's own
        // give-up-after-N-cycles counting, not `CxCommandSession`'s resync mechanics (see
        // `consecutiveFailuresAcrossPollsStopThePollerAndFireTheCallbackExactlyOnce`'s own
        // comment on the same point).
        val responses = mutableMapOf<String, String?>("ATSP6" to "OK", "222AB8" to null)
        var exceededCalls = 0
        var lastFiringStoppedThePoller = false
        val poller = buildPoller(
            responses,
            clock,
            maxConsecutiveFailures = 2,
            onConsecutiveFailuresPolicy = ObdConsecutiveFailuresPolicyMessage.RESYNC_AND_CONTINUE,
            maxResyncCyclesBeforeGivingUp = 2,
            onConsecutiveFailuresExceeded = { stopped ->
                exceededCalls++
                lastFiringStoppedThePoller = stopped
            },
        )

        poller.start(listOf(stepA))
        drive(clock, maxSteps = 200, until = { lastFiringStoppedThePoller })

        assertTrue("the give-up cycle must report that it stopped the poller", lastFiringStoppedThePoller)
        assertEquals("the callback fires once per cycle, including the give-up cycle", 2, exceededCalls)
        // A stopped poller rejects every further enqueueOneShot call outright (see
        // `enqueueOneShot`'s own `stopped ||` check) — confirms the poller is actually stopped,
        // not just that the callback claimed so.
        assertEquals(
            ObdOneShotEnqueueResultMessage.QUEUE_FULL,
            poller.enqueueOneShot(emptyList(), listOf(oneShotStepA)),
        )
    }

    @Test
    fun aSuccessfulReadBetweenResyncCyclesResetsTheGiveUpCounterToo() {
        val clock = FakeClock()
        // Two failures (hits the threshold once), one success, then two more failures (hits it
        // a second time) — with only one hit ever following a success, the give-up bound of 2
        // must never be reached, proving the streak actually resets rather than merely slowing
        // its climb.
        var attempt = 0
        val transport = ScriptedTransport { command ->
            when (command) {
                "ATSP6" -> "OK"
                // A timed-out vehicle read leaves `CxCommandSession` permanently blocked until a
                // resync actually completes (its own documented behavior) — configured so the
                // session can ever dispatch a second real attempt at all, not because this test
                // cares about resync content itself.
                "ATZ" -> "OK"
                "222AB8" -> {
                    attempt++
                    if (attempt == 3) "OK" else null
                }
                else -> null
            }
        }
        val session = CxCommandSession(transport, clock)
        transport.session = session
        session.configureResync(listOf(CxResyncStep("ATZ") { true }), 2000L)
        var exceededCalls = 0
        var lastFiringStoppedThePoller = false
        val poller = CxPoller(
            session = session,
            clock = clock,
            modules = mapOf("m1" to moduleA),
            deadlineMs = 2000L,
            setupDeadlineMs = 2000L,
            maxReadsPerSecond = 10,
            monotonicBaseNs = 0L,
            maxConsecutiveFailures = 2,
            onConsecutiveFailuresPolicy = ObdConsecutiveFailuresPolicyMessage.RESYNC_AND_CONTINUE,
            maxResyncCyclesBeforeGivingUp = 2,
            onConsecutiveFailuresExceeded = { stopped ->
                exceededCalls++
                lastFiringStoppedThePoller = stopped
            },
        )

        poller.start(listOf(stepA))
        drive(clock, maxSteps = 200, until = { exceededCalls >= 2 })

        assertEquals("both threshold hits must still fire", 2, exceededCalls)
        assertTrue("a success must actually have run between the two hits", attempt >= 5)
        assertFalse(
            "a success between the two hits must reset the give-up streak",
            lastFiringStoppedThePoller,
        )
        // A stopped poller rejects every further enqueueOneShot call outright — accepted here
        // is this test's own proof that the poller is genuinely still running, not just that the
        // callback's own argument claimed so.
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
    fun enqueueingWhileARecurringReadIsInFlightNeverStartsASecondPoll() {
        val clock = FakeClock()
        val responses = mapOf("ATSP6" to "OK", "222AB8" to "622AB80005E3", "222AC0" to "622AC0FF")
        val transport = HeldTransport({ responses[it] }, held = setOf("222AB8"))
        val session = CxCommandSession(transport, clock)
        transport.session = session
        val exchanges = mutableListOf<CxPollExchange>()
        val poller = CxPoller(
            session = session,
            clock = clock,
            modules = mapOf("m1" to moduleA),
            deadlineMs = 2000L,
            setupDeadlineMs = 2000L,
            maxReadsPerSecond = 10,
            monotonicBaseNs = 0L,
            sink = { exchanges.add(it) },
        )

        poller.start(listOf(stepA))
        // stepA's read is written and still waiting for its reply, past the global rate-limit
        // spacing — the way a real read takes longer than `1000 / maxReadsPerSecond`.
        clock.advanceTo(300L)
        poller.enqueueOneShot(emptyList(), listOf(oneShotStepA))
        poller.enqueueOneShot(emptyList(), listOf(oneShotStepA.copy(signalId = "oneShotA2")))
        transport.release()
        drive(clock, maxSteps = 20, until = { exchanges.size >= 3 || clock.now() >= 900L })

        assertEquals(
            "one recurring read per interval, however many batches arrived while it was in flight",
            1,
            transport.writes.count { it == "222AB8" },
        )
        assertEquals(listOf("sigA", "oneShotA", "oneShotA2"), exchanges.map { it.signalId })
    }

    @Test
    fun aModuleAnsweringOnlyNoDataIsHeldBackAndProbedOncePerBackoff() {
        val clock = FakeClock()
        val responses = mutableMapOf<String, String?>("ATSP6" to "OK", "222AB8" to "NO DATA", "222AF7" to "NO DATA")
        val readAt = mutableListOf<Long>()
        val poller = buildPoller(responses, clock, sink = { readAt.add(clock.now()) }, quietModuleBackoffMs = 60_000L)

        poller.start(listOf(stepA, stepB))
        drive(clock, maxSteps = 200, until = { readAt.size >= 8 })

        // With only two steps, both answering NO DATA once already makes the module quiet; from
        // then on a single probe per backoff instead of both steps every second.
        assertEquals(listOf(0L, 100L, 60_100L, 120_100L, 180_100L, 240_100L, 300_100L, 360_100L), readAt)
    }

    @Test
    fun aQuietModuleResumesItsNormalScheduleOnceItAnswersAgain() {
        val clock = FakeClock()
        val responses = mutableMapOf<String, String?>("ATSP6" to "OK", "222AB8" to "NO DATA", "222AF7" to "NO DATA")
        val readAt = mutableListOf<Long>()
        val poller = buildPoller(responses, clock, sink = { readAt.add(clock.now()) }, quietModuleBackoffMs = 60_000L)

        poller.start(listOf(stepA, stepB))
        drive(clock, until = { readAt.size >= 3 })
        val quietSince = clock.now()

        responses["222AB8"] = "622AB80005E3"
        responses["222AF7"] = "622AF700"
        drive(clock, maxSteps = 400, until = { clock.now() >= quietSince + 120_000L })

        val afterQuiet = readAt.drop(3)
        assertEquals("nothing runs until the backoff ends", quietSince + 60_000L, afterQuiet.first())
        assertTrue(
            "both steps must be back on their 1 s interval after the probe, got ${afterQuiet.size} reads",
            afterQuiet.size >= 100,
        )
    }

    @Test
    fun anAnswerOnTheSameModuleBreaksTheNoDataStreak() {
        val clock = FakeClock()
        val responses = mutableMapOf<String, String?>("ATSP6" to "OK", "222AB8" to "NO DATA", "222AF7" to "622AF700")
        val exchanges = mutableListOf<CxPollExchange>()
        val poller = buildPoller(responses, clock, sink = { exchanges.add(it) }, quietModuleBackoffMs = 60_000L)

        poller.start(listOf(stepA, stepB))
        drive(clock, maxSteps = 400, until = { clock.now() >= 30_000L })

        assertTrue("sigB keeps answering, so the module never goes quiet", exchanges.count { it.signalId == "sigA" } >= 25)
    }

    @Test
    fun noDataNeverHoldsAModuleBackWhenTheBackoffIsDisabled() {
        val clock = FakeClock()
        val responses = mutableMapOf<String, String?>("ATSP6" to "OK", "222AB8" to "NO DATA", "222AF7" to "NO DATA")
        val exchanges = mutableListOf<CxPollExchange>()
        val poller = buildPoller(responses, clock, sink = { exchanges.add(it) })

        poller.start(listOf(stepA, stepB))
        drive(clock, maxSteps = 400, until = { clock.now() >= 30_000L })

        assertTrue(exchanges.size >= 50)
    }

    @Test
    fun aQueuedOneShotRunsWhileTheOnlyRecurringModuleIsQuiet() {
        val clock = FakeClock()
        val responses = mutableMapOf<String, String?>(
            "ATSP6" to "OK",
            "222AB8" to "NO DATA",
            "222AF7" to "NO DATA",
            "222AC0" to "622AC0FF",
        )
        val exchanges = mutableListOf<CxPollExchange>()
        val poller = buildPoller(responses, clock, sink = { exchanges.add(it) }, quietModuleBackoffMs = 60_000L)

        poller.start(listOf(stepA, stepB))
        drive(clock, until = { exchanges.size >= 3 })
        val quietSince = clock.now()
        poller.enqueueOneShot(emptyList(), listOf(oneShotStepA))
        drive(clock, maxSteps = 5, until = { exchanges.size > 3 })

        assertEquals("oneShotA", exchanges.last().signalId)
        assertTrue("the one-shot must not wait out the quiet module's backoff", clock.now() < quietSince + 1_000L)
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

    /** Reproduces the field reports behind the writeFailed floods: once a module needs its setup
     * re-run and the transport cannot actually write (a real adapter would be this unwritable
     * for as long as a BLE link drop or a stuck GATT queue lasts), [CxPoller] must not drain the
     * *entire* one-shot backlog as instant failures in one synchronous call — that is CPU time,
     * not time actually spent waiting on anything, and it burns through recording time the
     * connection could otherwise use once it is actually writable again. */
    @Test
    fun aPersistentWriteFailureMustNotDrainTheWholeOneShotQueueInOneSynchronousBurst() {
        val clock = FakeClock()
        val transport = object : CxCommandTransport {
            override fun write(bytes: ByteArray): Boolean = false
        }
        val session = CxCommandSession(transport, clock)
        val exchanges = mutableListOf<CxPollExchange>()
        val poller = CxPoller(
            session = session,
            clock = clock,
            modules = mapOf("m1" to moduleA),
            deadlineMs = 2000L,
            setupDeadlineMs = 2000L,
            maxReadsPerSecond = 10,
            monotonicBaseNs = 0L,
            sink = { exchanges.add(it) },
        )
        poller.start(emptyList())
        val batch = (0 until 1000).map { oneShotStepA.copy(signalId = "s$it") }

        poller.enqueueOneShot(emptyList(), batch)

        assertTrue(
            "a persistently unwritable connection must not fail the whole queue before any " +
                "time passes to let the connection actually recover, got ${exchanges.size} of " +
                "${batch.size} resolved at clock time ${clock.now()}",
            exchanges.size < batch.size,
        )
    }

    @Test
    fun aModulesOwnReadDeadlineOverridesThePlanDefaultForASlowEcu() {
        val clock = FakeClock()
        val slowModule = moduleA.copy(readDeadlineMs = 8000L)
        val transport = object : CxCommandTransport {
            lateinit var session: CxCommandSession

            override fun write(bytes: ByteArray): Boolean {
                val command = String(bytes, Charsets.US_ASCII).removeSuffix("\r")
                when (command) {
                    "ATSP6" -> session.onNotification("OK\r>".toByteArray())
                    // Answers only after the plan's own 2 s default has already elapsed, but
                    // within this module's own 8 s override -- an ECU repeatedly extending
                    // P2* with NRC 0x78 (responsePending), modelled here as a plain late reply.
                    "222AB8" -> clock.schedule(5000L) {
                        session.onNotification("622AB80005E3\r>".toByteArray())
                    }
                }
                return true
            }
        }
        val session = CxCommandSession(transport, clock)
        transport.session = session
        val exchanges = mutableListOf<CxPollExchange>()
        val poller = CxPoller(
            session = session,
            clock = clock,
            modules = mapOf("m1" to slowModule),
            deadlineMs = 2000L,
            setupDeadlineMs = 2000L,
            maxReadsPerSecond = 10,
            monotonicBaseNs = 0L,
            sink = { exchanges.add(it) },
        )

        poller.start(listOf(stepA))
        drive(clock, maxSteps = 50, until = { exchanges.isNotEmpty() })

        assertEquals(1, exchanges.size)
        assertEquals(
            "the module's own longer deadline must let a reply later than the plan default through",
            ObdTransportStatusMessage.OK,
            exchanges.single().transport,
        )
    }

    @Test
    fun aOneShotStepReenqueuingAPlanModuleWithoutRepeatingItsReadDeadlineKeepsThePlansOwnValue() {
        val clock = FakeClock()
        val slowModule = moduleA.copy(readDeadlineMs = 8000L)
        // The caller enqueueing oneShotStepA addresses "m1" again with a fresh ObdModuleMessage
        // that never repeats readDeadlineMs -- exactly what happens when a generic caller builds
        // one from scratch for a module it already knows the setup for, like
        // `BridgeReadExecutor._toBridgeModule` does for every one-shot read.
        val oneShotModuleOverride = ObdModuleMessage(moduleId = "m1", protocol = 6L, setup = listOf("ATSP6"))
        val transport = object : CxCommandTransport {
            lateinit var session: CxCommandSession

            override fun write(bytes: ByteArray): Boolean {
                val command = String(bytes, Charsets.US_ASCII).removeSuffix("\r")
                when (command) {
                    "ATSP6" -> session.onNotification("OK\r>".toByteArray())
                    "222AC0" -> clock.schedule(5000L) {
                        session.onNotification("622AC0FF\r>".toByteArray())
                    }
                }
                return true
            }
        }
        val session = CxCommandSession(transport, clock)
        transport.session = session
        val exchanges = mutableListOf<CxPollExchange>()
        val poller = CxPoller(
            session = session,
            clock = clock,
            modules = mapOf("m1" to slowModule),
            deadlineMs = 2000L,
            setupDeadlineMs = 2000L,
            maxReadsPerSecond = 10,
            monotonicBaseNs = 0L,
            sink = { exchanges.add(it) },
        )

        poller.start(emptyList())
        poller.enqueueOneShot(listOf(oneShotModuleOverride), listOf(oneShotStepA))
        drive(clock, maxSteps = 50, until = { exchanges.isNotEmpty() })

        assertEquals(1, exchanges.size)
        assertEquals(
            "re-registering m1's setup without repeating readDeadlineMs must not lose it",
            ObdTransportStatusMessage.OK,
            exchanges.single().transport,
        )
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

    @Test
    fun aShortIntervalStepKeepsItsCadenceWhileManyLongIntervalStepsAreDueAtStart() {
        // stepA (1 s) listed after 50 steps at 60 s, all due at start: 5 s of reads ahead of it.
        val reads = readsOverDelayedTransport(longIntervalSteps(50, intervalMs = 60_000L) + stepA, untilMs = 20_000L)
        val shortReads = reads.filter { it.first == "sigA" }.map { it.second }
        val gaps = shortReads.zipWithNext { a, b -> b - a }

        assertTrue("the short step must not wait for every long step, first read at ${shortReads.first()}", shortReads.first() <= 200L)
        assertTrue("the short step must keep its 1 s cadence, got gaps $gaps", gaps.all { it <= 1200L })
        assertEquals("every long step is still read once", 50, reads.count { it.first.startsWith("long") })
    }

    @Test
    fun overloadedLongIntervalStepsAreAllStillReadWhileAShortStepKeepsItsCadence() {
        // 50 long steps x 100 ms = 5 s of reads per 4 s interval: more than the bus can serve.
        val reads = readsOverDelayedTransport(longIntervalSteps(50, intervalMs = 4_000L) + stepA, untilMs = 60_000L)
        val longCounts = reads.filter { it.first.startsWith("long") }.groupingBy { it.first }.eachCount()
        val shortReads = reads.filter { it.first == "sigA" }.map { it.second }
        val shortGaps = shortReads.zipWithNext { a, b -> b - a }

        assertEquals(50, longCounts.size)
        assertTrue("no long step may starve, got $longCounts", longCounts.values.all { it >= 8 })
        assertTrue("the short step must run from the start, first read at ${shortReads.first()}", shortReads.first() <= 200L)
        assertTrue("the short step must keep its 1 s cadence, got gaps $shortGaps", shortGaps.all { it <= 1200L })
    }
}
