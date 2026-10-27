package de.noctunix.obdbridge

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private data class FramingCase(
    val name: String,
    val command: String,
    val notificationsHex: List<String>,
    val complete: Boolean,
    val expectedResponse: String?,
    val trailingDiscardedHex: String?,
)

// The Gradle working directory for this module varies with how the build is invoked (module
// root vs. example app vs. CI), so the shared fixture is located by walking up from it rather
// than assuming a fixed relative path — same approach as HardGuardTest's own fixture lookup.
private fun locateFramingVectorsFile(): File {
    var dir: File? = File(".").absoluteFile
    repeat(8) {
        val current = dir ?: return@repeat
        val candidate = File(current, "test/fixtures/obd/framing_vectors.json")
        if (candidate.isFile) return candidate
        dir = current.parentFile
    }
    error("test/fixtures/obd/framing_vectors.json not found by walking up from ${File(".").absolutePath}")
}

private fun loadFramingCases(): List<FramingCase> {
    val json = JSONObject(locateFramingVectorsFile().readText())
    val array = json.getJSONArray("cases")
    return (0 until array.length()).map { index ->
        val entry = array.getJSONObject(index)
        val hexList = entry.getJSONArray("notificationsHex")
        FramingCase(
            name = entry.getString("name"),
            command = entry.getString("command"),
            notificationsHex = (0 until hexList.length()).map { hexList.getString(it) },
            complete = entry.getBoolean("complete"),
            expectedResponse = if (entry.isNull("expectedResponse")) null else entry.getString("expectedResponse"),
            trailingDiscardedHex = if (entry.has("trailingDiscardedHex")) entry.getString("trailingDiscardedHex") else null,
        )
    }
}

private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
    ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
}

private fun caseByName(name: String): FramingCase = loadFramingCases().first { it.name == name }

/** A resync step whose content is never actually checked — for tests about dispatch timing and
 * teardown ordering, where the point is not the content-verification behaviour itself. */
private fun alwaysOk(command: String) = CxResyncStep(command) { true }

/** Records every write; [writeSucceeds] simulates a transport that can no longer reach the
 * adapter without a real disconnect notification ever arriving. */
private class FakeCxCommandTransport : CxCommandTransport {
    val writes = mutableListOf<String>()
    var writeSucceeds = true

    override fun write(bytes: ByteArray): Boolean {
        writes.add(String(bytes, Charsets.US_ASCII))
        return writeSucceeds
    }
}

/** A virtual clock: [schedule] records the absolute due time ([now] plus the delay) rather than
 * firing order, and [fireNextDue] advances [now] to and fires whichever still-pending entry is
 * due soonest — a short delay scheduled after a long one still fires first, the same as a real
 * clock would, which plain insertion-order firing would get wrong. */
private class FakeCxCommandClock : CxCommandClock {
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

    val pendingCount: Int get() = entries.count { !it.cancelled }

    fun fireNextDue() {
        val entry = entries.filter { !it.cancelled }.minByOrNull { it.dueAt }
            ?: error("nothing scheduled")
        currentTime = entry.dueAt
        entries.remove(entry)
        entry.action()
    }

    /** Advances [now] by [ms] without firing anything — for simulating "some time passed"
     * between two calls into the session. Fails if that would skip past a still-pending entry's
     * due time without firing it, the same as a real clock could not do either. */
    fun advanceBy(ms: Long) {
        val target = currentTime + ms
        check(entries.none { !it.cancelled && it.dueAt <= target }) {
            "advancing to $target would skip a still-pending task without firing it"
        }
        currentTime = target
    }
}

@RunWith(RobolectricTestRunner::class)
class CxCommandSessionTest {

    private fun newSession(
        transport: FakeCxCommandTransport = FakeCxCommandTransport(),
        clock: FakeCxCommandClock = FakeCxCommandClock(),
        maxResponseBytes: Int = CxCommandSession.DEFAULT_MAX_RESPONSE_BYTES,
        quietPeriodMs: Long = 50L,
        maxQuietPeriodMs: Long = 1_000L,
    ) = CxCommandSession(transport, clock, maxResponseBytes, quietPeriodMs = quietPeriodMs, maxQuietPeriodMs = maxQuietPeriodMs)

    @Test
    fun everyCompleteFramingCaseProducesItsExpectedResponse() {
        for (case in loadFramingCases().filter { it.complete }) {
            val transport = FakeCxCommandTransport()
            val session = newSession(transport)
            val results = mutableListOf<CxCommandResult>()

            session.execute(case.command, 1_000L) { results.add(it) }
            for (chunk in case.notificationsHex) {
                session.onNotification(hexToBytes(chunk))
            }

            assertEquals(case.name, 1, results.size)
            assertEquals(case.name, CxCommandOutcome.OK, results.single().outcome)
            assertEquals(case.name, case.expectedResponse, results.single().responseText)
        }
    }

    @Test
    fun aMissingPromptLeavesTheCommandOutstandingUntilItsOwnDeadline() {
        val case = caseByName("missing_prompt")
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock)
        val results = mutableListOf<CxCommandResult>()

        session.execute(case.command, 1_000L) { results.add(it) }
        for (chunk in case.notificationsHex) session.onNotification(hexToBytes(chunk))

        assertTrue(results.isEmpty())

        clock.fireNextDue()

        assertEquals(1, results.size)
        assertEquals(CxCommandOutcome.TIMEOUT, results.single().outcome)
    }

    @Test
    fun fragmentationAtEveryPossibleByteCutProducesTheSameResult() {
        val case = caseByName("ok_with_echo_single_notification")
        val fullBytes = hexToBytes(case.notificationsHex.single())

        for (cut in 0..fullBytes.size) {
            val transport = FakeCxCommandTransport()
            val session = newSession(transport)
            val results = mutableListOf<CxCommandResult>()

            session.execute(case.command, 1_000L) { results.add(it) }
            session.onNotification(fullBytes.copyOfRange(0, cut))
            if (results.isEmpty()) {
                session.onNotification(fullBytes.copyOfRange(cut, fullBytes.size))
            }

            assertEquals("cut at $cut", 1, results.size)
            assertEquals("cut at $cut", CxCommandOutcome.OK, results.single().outcome)
            assertEquals("cut at $cut", case.expectedResponse, results.single().responseText)
        }
    }

    @Test
    fun bytesAfterTheFirstPromptInTheSameBurstAreDiscardedNotAttributedToTheNextCommand() {
        val case = caseByName("two_frames_one_packet")
        val transport = FakeCxCommandTransport()
        val session = newSession(transport)
        val results = mutableListOf<CxCommandResult>()

        session.execute(case.command, 1_000L) { results.add(it) }
        session.onNotification(hexToBytes(case.notificationsHex.single()))
        assertEquals(case.expectedResponse, results.single().responseText)

        // Nothing is outstanding by now; feeding the fixture's own record of the stray trailing
        // bytes again on their own must not conjure up a second result.
        session.onNotification(hexToBytes(requireNotNull(case.trailingDiscardedHex)))
        assertEquals(1, results.size)
    }

    @Test
    fun aSearchingStatusLineDoesNotExtendTheDeadline() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock)
        val results = mutableListOf<CxCommandResult>()

        session.execute("22F4A6", 1_000L) { results.add(it) }
        session.onNotification("SEARCHING...\r".toByteArray(Charsets.US_ASCII))

        // Still exactly the one response-deadline task from the original dispatch — the interim
        // status line did not reschedule it.
        assertEquals(1, clock.pendingCount)
        clock.fireNextDue()
        assertEquals(CxCommandOutcome.TIMEOUT, results.single().outcome)
    }

    @Test
    fun noDataAndQuestionMarkAreOrdinaryRawAnswersNotErrors() {
        val transport = FakeCxCommandTransport()
        val session = newSession(transport)
        val noDataResults = mutableListOf<CxCommandResult>()
        session.execute("22F4A6", 1_000L) { noDataResults.add(it) }
        session.onNotification("22F4A6\rNO DATA\r>".toByteArray(Charsets.US_ASCII))
        assertEquals(CxCommandOutcome.OK, noDataResults.single().outcome)
        assertEquals("NO DATA", noDataResults.single().responseText)

        val unsupportedResults = mutableListOf<CxCommandResult>()
        session.execute("22F40D", 1_000L) { unsupportedResults.add(it) }
        session.onNotification("22F40D\r?\r>".toByteArray(Charsets.US_ASCII))
        assertEquals(CxCommandOutcome.OK, unsupportedResults.single().outcome)
        assertEquals("?", unsupportedResults.single().responseText)
    }

    @Test
    fun aLateResponseArrivingDuringTheQuietWaitExtendsItInsteadOfBeingMistakenForTheResyncStep() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock)
        val resyncStepResults = mutableListOf<CxCommandResult>()
        // ATZ's real response is an adapter identification banner, not "OK" — exactly the
        // content this step must check for, not merely that some frame with a prompt arrived.
        session.configureResync(listOf(CxResyncStep("ATZ") { it.contains("ELM") || it.contains("STN") }), 1_000L) {
            resyncStepResults.add(it)
        }

        val firstResults = mutableListOf<CxCommandResult>()
        session.execute("22F4A6", 1_000L) { firstResults.add(it) }
        clock.fireNextDue() // t=1000: the response deadline fires: TIMEOUT, quiet wait begins
        assertEquals(CxCommandOutcome.TIMEOUT, firstResults.single().outcome)

        // t=1040: the real, if late, response to the timed-out command finally arrives while
        // quiescing — 10ms before the original quiet wait (due at 1050) would have elapsed. It
        // must not be mistaken for whatever gets written next, and must instead restart the
        // wait (now due at 1090), not just be silently ignored.
        clock.advanceBy(40)
        session.onNotification("22F4A6\r62F4A601\r>".toByteArray(Charsets.US_ASCII))
        assertEquals(listOf("22F4A6\r"), transport.writes)

        // t=1050: this is where the *original* quiet deadline would have fired. advanceBy itself
        // would reject this step if anything were still due at or before 1050 — reaching 1050
        // without it throwing is what proves the late bytes actually rescheduled the deadline.
        clock.advanceBy(10)

        // t=1090: only now, once the restarted wait has actually elapsed without further bytes,
        // does the resync step get written.
        clock.fireNextDue()
        assertEquals(listOf("22F4A6\r", "ATZ\r"), transport.writes)

        val secondResults = mutableListOf<CxCommandResult>()
        session.execute("ATRV", 1_000L) { secondResults.add(it) }
        assertTrue(secondResults.isEmpty()) // still queued behind the in-flight resync step

        session.onNotification("ATZ\rELM327 v1.5\r>".toByteArray(Charsets.US_ASCII))
        assertEquals(listOf(CxCommandOutcome.OK), resyncStepResults.map { it.outcome })
        assertEquals(listOf("22F4A6\r", "ATZ\r", "ATRV\r"), transport.writes)

        session.onNotification("ATRV\r14.2V\r>".toByteArray(Charsets.US_ASCII))
        assertEquals(CxCommandOutcome.OK, secondResults.single().outcome)
        assertEquals("14.2V", secondResults.single().responseText)
    }

    @Test
    fun aResyncStepWithWellFormedButWrongContentCountsAsFailureNotSuccess() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock)
        val stepResults = mutableListOf<CxCommandResult>()
        session.configureResync(
            listOf(CxResyncStep("ATZ") { it.contains("ELM") || it.contains("STN") }, CxResyncStep.expectOk("ATE0")),
            1_000L,
        ) { stepResults.add(it) }

        session.execute("22F4A6", 1_000L) { }
        clock.fireNextDue() // times out, quiet wait begins
        clock.fireNextDue() // quiet wait elapses, step 0 ("ATZ") is written
        assertEquals(listOf("22F4A6\r", "ATZ\r"), transport.writes)

        // The adapter answers with a complete, well-formed frame — it just was not actually idle
        // for this command (e.g. still mid-response to something earlier) and says so instead of
        // running it. A bare "frame with a prompt arrived" would wrongly read this as success.
        session.onNotification("ATZ\rSTOPPED\r>".toByteArray(Charsets.US_ASCII))

        assertEquals(listOf(CxCommandOutcome.UNEXPECTED_RESPONSE), stepResults.map { it.outcome })
        // Step 1 ("ATE0") must never run after step 0 failed content verification.
        assertEquals(listOf("22F4A6\r", "ATZ\r"), transport.writes)

        val laterResults = mutableListOf<CxCommandResult>()
        session.execute("ATRV", 10_000L) { laterResults.add(it) }
        assertEquals(listOf("22F4A6\r", "ATZ\r"), transport.writes)
        assertTrue(laterResults.isEmpty())

        // Still recoverable — a later onConnected() gets a fresh attempt from step 0 again.
        session.onConnected()
        clock.fireNextDue()
        assertEquals(listOf("22F4A6\r", "ATZ\r", "ATZ\r"), transport.writes)
    }

    @Test
    fun aListenerThatCallsOnConnectedRightFromAFailingStepsCallbackIsNotIgnoredAsAlreadyInProgress() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock)
        session.configureResync(listOf(CxResyncStep.expectOk("ATZ")), 1_000L) { result ->
            if (result.outcome != CxCommandOutcome.OK) session.onConnected()
        }

        session.execute("22F4A6", 1_000L) { }
        clock.fireNextDue() // times out, quiet wait begins
        clock.fireNextDue() // quiet wait elapses, step 0 ("ATZ") is written
        assertEquals(listOf("22F4A6\r", "ATZ\r"), transport.writes)

        // The step itself times out; its own callback (the listener above) reacts by calling
        // onConnected() immediately. That must not find resyncAttemptInProgress still true and
        // be swallowed as a no-op — a fresh quiet wait (plus its overall deadline) must actually
        // be pending afterwards, not nothing.
        clock.fireNextDue()

        assertEquals(2, clock.pendingCount)
    }

    @Test
    fun resyncAbortsAfterAFailingStepAndStaysBlocked() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock)
        val stepResults = mutableListOf<CxCommandResult>()
        session.configureResync(listOf(alwaysOk("ATZ"), alwaysOk("ATE0")), 1_000L) { stepResults.add(it) }

        session.execute("22F4A6", 1_000L) { }
        clock.fireNextDue() // original command times out, quiet wait begins
        clock.fireNextDue() // quiet wait elapses, step 0 ("ATZ") is written

        assertEquals(listOf("22F4A6\r", "ATZ\r"), transport.writes)

        clock.fireNextDue() // step 0 itself times out

        assertEquals(listOf(CxCommandOutcome.TIMEOUT), stepResults.map { it.outcome })
        // Step 1 ("ATE0") must never run after its predecessor failed.
        assertEquals(listOf("22F4A6\r", "ATZ\r"), transport.writes)

        // A regular command stays queued rather than being written while still blocked.
        val laterResults = mutableListOf<CxCommandResult>()
        session.execute("ATRV", 10_000L) { laterResults.add(it) }
        assertEquals(listOf("22F4A6\r", "ATZ\r"), transport.writes)
        assertTrue(laterResults.isEmpty())

        // Still recoverable — a later onConnected() gets a fresh attempt from step 0 again.
        session.onConnected()
        clock.fireNextDue()
        assertEquals(listOf("22F4A6\r", "ATZ\r", "ATZ\r"), transport.writes)
    }

    @Test
    fun aResyncThatNeverGoesQuietReportsFailureAfterTheOverallDeadlineInsteadOfWaitingForever() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        // A realistic case, not a misconfiguration: the quiet period (50ms) is well inside the
        // overall budget (1000ms, this session's default), but the adapter keeps sending bytes
        // every 40ms, so the quiet period keeps restarting and never once naturally elapses.
        val session = newSession(transport, clock)
        val stepResults = mutableListOf<CxCommandResult>()
        session.configureResync(listOf(alwaysOk("ATZ")), 1_000L) { stepResults.add(it) }

        session.execute("22F4A6", 1_000L) { }
        clock.fireNextDue() // t=1000: times out, quiet wait (due 1050) and overall deadline (due 2000) begin

        // 24 rounds of "40ms pass, a byte arrives and restarts the 50ms quiet wait" reach
        // t=1960 with the quiet wait now due at 2010 — past the fixed overall deadline at 2000,
        // which the restarts never touched.
        repeat(24) {
            clock.advanceBy(40)
            session.onNotification("X".toByteArray(Charsets.US_ASCII))
        }

        clock.fireNextDue() // t=2000: the overall deadline fires, not the (later) quiet wait

        assertEquals(listOf(CxCommandOutcome.QUIET_PERIOD_EXPIRED), stepResults.map { it.outcome })
        // No resync step was ever written for this failed attempt.
        assertEquals(listOf("22F4A6\r"), transport.writes)

        // Still blocked — only a later onConnected() gets another attempt, not another sweep of
        // the same deadline on its own.
        val laterResults = mutableListOf<CxCommandResult>()
        session.execute("ATRV", 10_000L) { laterResults.add(it) }
        assertTrue(laterResults.isEmpty())

        // Still recoverable — a later onConnected() gets a fresh attempt.
        session.onConnected()
        clock.fireNextDue()
        assertEquals(listOf("22F4A6\r", "ATZ\r"), transport.writes)
    }

    @Test
    fun anOnDisconnectedCallFromWithinTheTimeoutCallbackStartsNoQuietPeriodOnTheDeadTransport() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock)

        session.execute("22F4A6", 1_000L) {
            // Escalates the failure to a teardown, as the class documentation itself suggests —
            // this must pre-empt the quiet period failWrittenCommand would otherwise start right
            // after this callback returns.
            session.onDisconnected()
        }
        clock.fireNextDue() // the response deadline fires: TIMEOUT, triggering the disconnect above

        // No quiet wait, no overall deadline — the reentrant disconnect already tore those down,
        // and failWrittenCommand must not resurrect one on a transport already gone.
        assertEquals(0, clock.pendingCount)
        assertEquals(listOf("22F4A6\r"), transport.writes)
    }

    @Test
    fun anOnDisconnectedCallFromWithinTheOverflowCallbackStartsNoQuietPeriodOnTheDeadTransport() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock, maxResponseBytes = 8)

        session.execute("ATZ", 1_000L) { session.onDisconnected() }
        session.onNotification("0123456789".toByteArray(Charsets.US_ASCII)) // 10 bytes, over the limit

        assertEquals(0, clock.pendingCount)
        assertEquals(listOf("ATZ\r"), transport.writes)
    }

    @Test
    fun aSecondOnConnectedCallWhileQuiescingDoesNotPostponeTheQuietDeadline() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock) // quietPeriodMs defaults to 50
        session.configureResync(listOf(alwaysOk("ATZ")), 1_000L) { }

        session.execute("22F4A6", 1_000L) { }
        clock.fireNextDue() // t=1000: times out, quiet wait (due at 1050) begins

        clock.advanceBy(20) // t=1020
        session.onConnected() // already quiescing; must not reschedule the quiet deadline from here

        // Fires the *original* deadline (due at 1050); a wrongly restarted one (due at 1070)
        // would make this land at the wrong time instead.
        clock.fireNextDue()
        assertEquals(1050L, clock.now())
        assertEquals(listOf("22F4A6\r", "ATZ\r"), transport.writes)
    }

    @Test
    fun aSecondOnConnectedCallWhileAResyncStepIsDispatchedDoesNotSwallowItsResponse() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock)
        val stepResults = mutableListOf<CxCommandResult>()
        session.configureResync(listOf(alwaysOk("ATZ")), 1_000L) { stepResults.add(it) }

        session.execute("22F4A6", 1_000L) { }
        clock.fireNextDue() // times out, quiet wait begins
        clock.fireNextDue() // quiet wait elapses, step 0 ("ATZ") is written
        assertEquals(listOf("22F4A6\r", "ATZ\r"), transport.writes)

        session.onConnected() // a resync step is already outstanding; must not re-enter quiescing

        // Had onConnected() wrongly restarted the quiet wait, this would be swallowed as "bytes
        // arriving while quiescing" instead of completing the outstanding step.
        session.onNotification("ATZ\rOK\r>".toByteArray(Charsets.US_ASCII))
        assertEquals(listOf(CxCommandOutcome.OK), stepResults.map { it.outcome })

        // The session actually unblocked — a regular command now dispatches instead of hanging
        // forever behind a resync that silently never got to finish.
        val laterResults = mutableListOf<CxCommandResult>()
        session.execute("ATRV", 1_000L) { laterResults.add(it) }
        assertEquals(listOf("22F4A6\r", "ATZ\r", "ATRV\r"), transport.writes)
    }

    @Test
    fun aResyncWithNoStepsConfiguredReportsItRatherThanHangingSilently() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock)
        val stepResults = mutableListOf<CxCommandResult>()
        session.configureResync(emptyList(), 1_000L) { stepResults.add(it) }

        session.execute("22F4A6", 1_000L) { }
        clock.fireNextDue() // times out, quiet wait begins
        clock.fireNextDue() // quiet wait elapses with nothing configured to run

        assertEquals(listOf(CxCommandOutcome.RESYNC_NOT_CONFIGURED), stepResults.map { it.outcome })
        assertEquals(listOf("22F4A6\r"), transport.writes)

        // Still recoverable, not permanently stuck — a later onConnected() gets another attempt.
        session.onConnected()
        assertTrue(clock.pendingCount > 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aMaxQuietPeriodNotExceedingTheQuietPeriodIsRejectedUpFront() {
        newSession(quietPeriodMs = 100L, maxQuietPeriodMs = 100L)
    }

    @Test
    fun aCommandStillQueuedPastItsOwnDeadlineTimesOutWithoutBeingWritten() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock)

        session.execute("ATZ", 1_000L) { } // becomes active, holding up the queue
        val queuedResults = mutableListOf<CxCommandResult>()
        session.execute("ATRV", 500L) { queuedResults.add(it) }

        // The queued command's own, shorter deadline fires first despite being scheduled second.
        clock.fireNextDue()

        assertEquals(CxCommandOutcome.TIMEOUT, queuedResults.single().outcome)
        assertEquals(listOf("ATZ\r"), transport.writes) // never actually written
    }

    @Test
    fun anOverflowOnARegularCommandAlsoRequiresAResyncBeforeTheNextCommand() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock, maxResponseBytes = 8)
        val overflowResults = mutableListOf<CxCommandResult>()

        session.execute("ATZ", 1_000L) { overflowResults.add(it) }
        session.onNotification("0123456789".toByteArray(Charsets.US_ASCII)) // 10 bytes, over the limit

        assertEquals(CxCommandOutcome.OVERFLOW, overflowResults.single().outcome)
        assertNull(overflowResults.single().responseText)
        // Capped at the response-size limit, not however much the adapter actually sent.
        assertEquals(8, overflowResults.single().rawBytes.size)

        // Blocked behind the quiet wait an overflow requires too, exactly like a timeout.
        val nextResults = mutableListOf<CxCommandResult>()
        session.execute("ATRV", 1_000L) { nextResults.add(it) }
        assertEquals(listOf("ATZ\r"), transport.writes)
        assertTrue(nextResults.isEmpty())
    }

    @Test
    fun aDisconnectWithAnActiveCommandStartsNoTimerAndWaitsForOnConnectedToResume() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock)
        val resyncResults = mutableListOf<CxCommandResult>()
        session.configureResync(listOf(alwaysOk("ATZ")), 1_000L) { resyncResults.add(it) }

        val firstResults = mutableListOf<CxCommandResult>()
        session.execute("22F4A6", 1_000L) { firstResults.add(it) }
        session.onDisconnected() // the link drops before any response, let alone a timeout

        assertEquals(CxCommandOutcome.DISCONNECTED, firstResults.single().outcome)
        // No quiet wait, no overall deadline, nothing — there is no connection to resync over yet.
        assertEquals(0, clock.pendingCount)

        val secondResults = mutableListOf<CxCommandResult>()
        session.execute("ATRV", 1_000L) { secondResults.add(it) }
        assertEquals(listOf("22F4A6\r"), transport.writes) // ATRV blocked behind the still-required resync
        assertTrue(secondResults.isEmpty())

        session.onConnected() // a fresh connection now exists to actually run the resync over

        clock.fireNextDue() // quiet wait elapses, step 0 ("ATZ") gets written
        assertEquals(listOf("22F4A6\r", "ATZ\r"), transport.writes)
        session.onNotification("ATZ\rOK\r>".toByteArray(Charsets.US_ASCII))
        assertEquals(listOf(CxCommandOutcome.OK), resyncResults.map { it.outcome })
        assertEquals(listOf("22F4A6\r", "ATZ\r", "ATRV\r"), transport.writes)

        session.onNotification("ATRV\r14.2V\r>".toByteArray(Charsets.US_ASCII))
        assertEquals(CxCommandOutcome.OK, secondResults.single().outcome)
    }

    @Test
    fun aCancelDuringTheQuietWaitStartsNoTimerAndWaitsForOnConnectedToResume() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock)
        session.configureResync(listOf(alwaysOk("ATZ")), 1_000L) { }

        session.execute("22F4A6", 1_000L) { }
        clock.fireNextDue() // times out, quiet wait and its overall deadline both begin

        session.cancel() // nothing is active or queued right now — the session is just quiescing

        assertEquals(0, clock.pendingCount) // both the quiet-wait and overall-deadline tasks are gone

        session.onConnected()
        clock.fireNextDue() // quiet wait elapses, step 0 ("ATZ") gets written despite the cancel
        assertEquals(listOf("22F4A6\r", "ATZ\r"), transport.writes)
    }

    @Test
    fun onConnectedIsANoOpWhenNoResyncIsRequired() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock)

        session.onConnected()

        assertEquals(0, clock.pendingCount)
        assertTrue(transport.writes.isEmpty())
    }

    @Test
    fun aDisconnectDuringAResyncStepEndsTheChainAsDisconnectedRatherThanWritingTheNextStep() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock)
        val stepResults = mutableListOf<CxCommandResult>()
        session.configureResync(listOf(alwaysOk("ATZ"), alwaysOk("ATE0")), 1_000L) { stepResults.add(it) }

        session.execute("22F4A6", 1_000L) { }
        clock.fireNextDue() // times out, quiet wait begins
        clock.fireNextDue() // quiet wait elapses, step 0 ("ATZ") is written
        assertEquals(listOf("22F4A6\r", "ATZ\r"), transport.writes)

        session.onDisconnected()

        assertEquals(listOf(CxCommandOutcome.DISCONNECTED), stepResults.map { it.outcome })
        // Step 1 ("ATE0") must never be written for a connection that is already gone.
        assertEquals(listOf("22F4A6\r", "ATZ\r"), transport.writes)
        // No timer survives either — onConnected(), not elapsed time, is what resumes this.
        assertEquals(0, clock.pendingCount)
    }

    @Test
    fun aCancelDuringAResyncStepEndsTheChainAsCancelledRatherThanContinuingIt() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock)
        val stepResults = mutableListOf<CxCommandResult>()
        session.configureResync(listOf(alwaysOk("ATZ"), alwaysOk("ATE0")), 1_000L) { stepResults.add(it) }

        session.execute("22F4A6", 1_000L) { }
        clock.fireNextDue()
        clock.fireNextDue()
        assertEquals(listOf("22F4A6\r", "ATZ\r"), transport.writes)

        session.cancel()

        assertEquals(listOf(CxCommandOutcome.CANCELLED), stepResults.map { it.outcome })
        assertEquals(listOf("22F4A6\r", "ATZ\r"), transport.writes)
        assertEquals(0, clock.pendingCount)
    }

    @Test
    fun aDisconnectMidFrameFailsTheOutstandingCommandAndEverythingStillQueued() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock)
        val firstResults = mutableListOf<CxCommandResult>()
        val secondResults = mutableListOf<CxCommandResult>()

        session.execute("ATZ", 1_000L) { firstResults.add(it) }
        session.execute("ATRV", 1_000L) { secondResults.add(it) }
        session.onNotification("ATZ\rO".toByteArray(Charsets.US_ASCII)) // partial, no prompt yet

        session.onDisconnected()

        assertEquals(CxCommandOutcome.DISCONNECTED, firstResults.single().outcome)
        assertEquals(CxCommandOutcome.DISCONNECTED, secondResults.single().outcome)
        // The active command's response deadline and the queued one's queue deadline are both
        // cancelled, and disconnecting starts no timer of its own.
        assertEquals(0, clock.pendingCount)

        // A notification arriving after the disconnect has nothing outstanding to attach to.
        session.onNotification("K\r>".toByteArray(Charsets.US_ASCII))
        assertEquals(1, firstResults.size)
        assertEquals(1, secondResults.size)
    }

    @Test
    fun twoCallersNeverGetTheirCommandsInterleavedOnTheWire() {
        val transport = FakeCxCommandTransport()
        val session = newSession(transport)
        val firstResults = mutableListOf<CxCommandResult>()
        val secondResults = mutableListOf<CxCommandResult>()

        session.execute("ATZ", 1_000L) { firstResults.add(it) }
        session.execute("ATRV", 1_000L) { secondResults.add(it) }

        // The second caller's command must not have reached the wire yet.
        assertEquals(listOf("ATZ\r"), transport.writes)
        assertTrue(secondResults.isEmpty())

        session.onNotification("ATZ\rOK\r>".toByteArray(Charsets.US_ASCII))
        assertEquals(CxCommandOutcome.OK, firstResults.single().outcome)
        assertEquals(listOf("ATZ\r", "ATRV\r"), transport.writes)
        assertTrue(secondResults.isEmpty())

        session.onNotification("ATRV\r14.2V\r>".toByteArray(Charsets.US_ASCII))
        assertEquals(CxCommandOutcome.OK, secondResults.single().outcome)
        assertEquals("14.2V", secondResults.single().responseText)
    }

    @Test
    fun aCommandRejectedByTheGuardNeverReachesTheWire() {
        val transport = FakeCxCommandTransport()
        val session = newSession(transport)
        val results = mutableListOf<CxCommandResult>()

        // HardGuard blocks the VIN DID regardless of what any caller-supplied policy permits —
        // see HardGuard.kt's own doc comment.
        session.execute("22F190", 1_000L) { results.add(it) }

        assertTrue(transport.writes.isEmpty())
        assertEquals(1, results.size)
        assertEquals(CxCommandOutcome.REJECTED_BY_GUARD, results.single().outcome)
    }

    @Test
    fun cancelFailsTheActiveAndEveryStillQueuedCommand() {
        val transport = FakeCxCommandTransport()
        val clock = FakeCxCommandClock()
        val session = newSession(transport, clock)
        val firstResults = mutableListOf<CxCommandResult>()
        val secondResults = mutableListOf<CxCommandResult>()

        session.execute("ATZ", 1_000L) { firstResults.add(it) }
        session.execute("ATRV", 1_000L) { secondResults.add(it) }

        session.cancel()

        assertEquals(CxCommandOutcome.CANCELLED, firstResults.single().outcome)
        assertEquals(CxCommandOutcome.CANCELLED, secondResults.single().outcome)
        // The active command's deadline and the queued one's are both cancelled, and cancelling
        // starts no timer of its own.
        assertEquals(0, clock.pendingCount)
    }

    @Test
    fun aFreshExecuteCallMadeFromWithinATeardownCallbackGetsTheSameOutcomeInsteadOfBeingQueued() {
        val transport = FakeCxCommandTransport()
        val session = newSession(transport)
        val reentrantResults = mutableListOf<CxCommandResult>()

        session.execute("ATZ", 1_000L) {
            session.execute("ATRV", 1_000L) { reentrantResults.add(it) }
        }
        session.cancel()

        assertEquals(CxCommandOutcome.CANCELLED, reentrantResults.single().outcome)
        // The reentrant call was rejected outright, never queued or written.
        assertEquals(listOf("ATZ\r"), transport.writes)
    }
}
