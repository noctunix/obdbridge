package de.noctunix.obdbridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompanionPresenceTest {

    private lateinit var store: CompanionStore
    private val started = mutableListOf<ObdStartRecordingRequest>()
    private val emitted = mutableListOf<Boolean>()
    private val heard = mutableListOf<Boolean>()
    private var busy = false
    private var listener: ObdPresenceListener? = ObdPresenceListener { heard.add(it) }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(CompanionStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        store = CompanionStore(context)
    }

    private fun handler() = CompanionPresenceHandler(
        store = store,
        recordingBusy = { busy },
        startRecording = { started.add(it); true },
        emitToEngine = { emitted.add(it) },
        hostListener = { listener },
    )

    @Test
    fun appearingWithAStoredRequestAndNothingRunningStartsTheRecording() {
        store.autoRecording = sampleRequest()

        handler().onPresence(true)

        assertEquals(listOf(sampleRequest()), started)
    }

    @Test
    fun appearingWithoutAStoredRequestStartsNothingButStillReports() {
        handler().onPresence(true)

        assertTrue(started.isEmpty())
        assertEquals(listOf(true), emitted)
        assertEquals(listOf(true), heard)
    }

    @Test
    fun appearingWhileARecordingRunsOrIsStartingStartsNothing() {
        store.autoRecording = sampleRequest()
        busy = true

        handler().onPresence(true)

        assertTrue(started.isEmpty())
    }

    @Test
    fun disappearingNeverStartsAndNeverStopsAnything() {
        store.autoRecording = sampleRequest()

        handler().onPresence(false)

        assertTrue(started.isEmpty())
        assertEquals("the stored request must survive a disappearance", sampleRequest(), store.autoRecording)
        assertEquals(listOf(false), emitted)
        assertEquals(listOf(false), heard)
    }

    @Test
    fun withoutAHostListenerTheOtherEffectsStillRun() {
        store.autoRecording = sampleRequest()
        listener = null

        handler().onPresence(true)

        assertEquals(1, started.size)
        assertEquals(listOf(true), emitted)
    }

    @Test
    fun eventsMatchThePairingByAssociationIdOrByAddress() {
        val pairing = samplePairing(associationId = 7)

        assertTrue(CompanionPresencePolicy.matches(pairing, 7, null))
        assertFalse(CompanionPresencePolicy.matches(pairing, 8, pairing.macAddress))
        assertTrue(CompanionPresencePolicy.matches(pairing, null, pairing.macAddress.lowercase()))
        assertFalse(CompanionPresencePolicy.matches(pairing, null, "AA:AA:AA:AA:AA:AA"))
        assertTrue(CompanionPresencePolicy.matches(samplePairing(associationId = null), 3, "00:11:22:33:44:55"))
        assertFalse(CompanionPresencePolicy.matches(null, 7, pairing.macAddress))
    }

    @Test
    fun templateGetsAFreshSessionIdPathAndStartTimeEveryTrip() {
        val template = sampleRequest()

        val first = AutoRecordingTemplate.instantiate(template, nowMs = 1_700_000_000_000L)
        val second = AutoRecordingTemplate.instantiate(template, nowMs = 1_700_000_000_000L)

        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals("/data/trips/${first.sessionId}.jsonl", first.bufferFilePath)
        assertEquals(1_700_000_000_000L, first.startedAtEpochMs)
        assertEquals(template.plan, first.plan)
        assertEquals(template.vehicleId, first.vehicleId)
    }

    @Test
    fun uuidV7CarriesVersionVariantAndTheTimestampInItsFirstFortyEightBits() {
        val now = 0x0123_4567_89ABL
        val uuid = UUID.fromString(AutoRecordingTemplate.uuidV7(now))

        assertEquals(7, uuid.version())
        assertEquals(2, uuid.variant())
        assertEquals(now, uuid.mostSignificantBits ushr 16)
    }

    private var now = 1_000_000L

    private fun timedHandler() = CompanionPresenceHandler(
        store = store,
        recordingBusy = { busy },
        startRecording = { started.add(it); true },
        emitToEngine = { emitted.add(it) },
        hostListener = { listener },
        nowMs = { now },
        suppressionGapMs = { 30 * 60_000L },
    )

    @Test
    fun aQuickReappearanceAfterAnAutomaticRecordingEndedStartsNothing() {
        store.autoRecording = sampleRequest()
        store.suppressAutoStart(endedAtEpochMs = now)
        val handler = timedHandler()

        now += 60_000L
        handler.onPresence(true)
        now += 20 * 60_000L
        handler.onPresence(true)

        assertTrue(started.isEmpty())
    }

    @Test
    fun withoutADisappearanceTheFullGapAloneLiftsTheSuppression() {
        store.autoRecording = sampleRequest()
        store.suppressAutoStart(endedAtEpochMs = now)

        now += 31 * 60_000L
        timedHandler().onPresence(true)

        assertEquals(1, started.size)
        assertNull(store.autoStartSuppression)
    }

    @Test
    fun aDisappearanceAfterTheEndLiftsTheSuppressionImmediately() {
        store.autoRecording = sampleRequest()
        store.suppressAutoStart(endedAtEpochMs = now)
        val handler = timedHandler()

        now += 60_000L
        handler.onPresence(true)
        now += 60_000L
        handler.onPresence(false)
        now += 1_000L
        handler.onPresence(true)

        assertEquals(1, started.size)
    }

    @Test
    fun aLateDisappearanceOfTheTripItselfDoesNotLiftTheSuppression() {
        store.autoRecording = sampleRequest()
        store.suppressAutoStart(endedAtEpochMs = now)
        val handler = timedHandler()

        now += 30_000L
        handler.onPresence(false)
        now += 30_000L
        handler.onPresence(true)

        assertTrue(started.isEmpty())
        assertTrue(checkNotNull(store.autoStartSuppression).appearedAfterEnd)
    }

    @Test
    fun theCarSleepingAfterTheReleaseAllowsTheNextMorningsStart() {
        store.autoRecording = sampleRequest()
        store.suppressAutoStart(endedAtEpochMs = now)
        val handler = timedHandler()

        now += 60_000L
        handler.onPresence(true)
        now += 5 * 60_000L
        handler.onPresence(false)
        now += 12 * 3_600_000L
        handler.onPresence(true)

        assertEquals(1, started.size)
        assertNull(store.autoStartSuppression)
    }

    @Test
    fun aDisappearanceWithoutSuppressionRecordsNothing() {
        timedHandler().onPresence(false)

        assertNull(store.autoStartSuppression)
    }

    @Test
    fun suppressionGapIsDefaultedAndClamped() {
        assertEquals(30 * 60_000L, CompanionPresencePolicy.suppressionGapMs(null))
        assertEquals(0L, CompanionPresencePolicy.suppressionGapMs(-5))
        assertEquals(1440 * 60_000L, CompanionPresencePolicy.suppressionGapMs(Long.MAX_VALUE))
        assertEquals(12 * 60_000L, CompanionPresencePolicy.suppressionGapMs(12))
    }

    @Test
    fun onlyIdleAndUserStopsCountAsTheCarBeingParked() {
        assertTrue(AutoEndCause.IDLE_STOP.suppressesNextStart)
        assertTrue(AutoEndCause.USER_STOP.suppressesNextStart)
        assertFalse(AutoEndCause.OTHER.suppressesNextStart)
    }

    @Test
    fun theDeduperDropsARepeatedStateWithinTheWindowOnly() {
        val deduper = PresenceDeduper(windowMs = 3_000L)

        assertTrue(deduper.accept(true, 1_000L))
        assertFalse(deduper.accept(true, 2_000L))
        assertTrue("a different state is never a duplicate", deduper.accept(false, 2_500L))
        assertTrue(deduper.accept(true, 2_600L))
        assertTrue("the same state after the window is a new event", deduper.accept(true, 6_000L))
    }

    @Test
    fun connectRetryBacksOffDoublingFromTheMinimumUpToTheMaximum() {
        fun delay(failed: Int) = PresenceConnectPolicy.nextDelayMs(failed, 10, 0, 1_000, 4_000)

        assertEquals(1_000L, delay(1))
        assertEquals(2_000L, delay(2))
        assertEquals(4_000L, delay(3))
        assertEquals(4_000L, delay(8))
    }

    @Test
    fun connectRetryStopsWhenTheAttemptBudgetIsSpent() {
        assertNull(PresenceConnectPolicy.nextDelayMs(3, 3, 0, 1_000, 4_000))
        assertEquals(1_000L, PresenceConnectPolicy.nextDelayMs(1, 3, 0, 1_000, 4_000))
        assertNull("a budget of zero still allows exactly one attempt", PresenceConnectPolicy.nextDelayMs(1, 0, 0, 1_000, 4_000))
    }

    @Test
    fun connectRetryStopsAtTheHardWindowWhateverThePlanAllows() {
        assertNull(
            PresenceConnectPolicy.nextDelayMs(2, 100, PresenceConnectPolicy.MAX_WINDOW_MS - 1_500, 1_000, 30_000),
        )
        assertEquals(
            2_000L,
            PresenceConnectPolicy.nextDelayMs(2, 100, PresenceConnectPolicy.MAX_WINDOW_MS - 2_001, 1_000, 30_000),
        )
    }

    @Test
    fun anEventCarryingBothIdAndAddressMatchesAMacOnlyPairingAndBackfillsItsId() {
        val macOnly = samplePairing(associationId = null)

        assertTrue(CompanionPresencePolicy.matches(macOnly, 3, macOnly.macAddress))
        assertEquals(3, CompanionPresencePolicy.withBackfilledId(macOnly, 3, macOnly.macAddress)?.associationId)
        assertNull(CompanionPresencePolicy.withBackfilledId(samplePairing(associationId = 7), 7, "00:11:22:33:44:55"))
        assertNull(CompanionPresencePolicy.withBackfilledId(macOnly, 3, "AA:AA:AA:AA:AA:AA"))
    }
}
