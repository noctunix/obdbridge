package de.noctunix.obdbridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompanionStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(CompanionStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun pairingSurvivesANewStoreOnTheSameFile() {
        CompanionStore(context).savePairing(samplePairing())

        assertEquals(samplePairing(), CompanionStore(context).pairing)
    }

    @Test
    fun aPairingWithoutAssociationIdRoundTripsAsNull() {
        val store = CompanionStore(context)
        store.savePairing(samplePairing(associationId = 9))
        store.savePairing(samplePairing(associationId = null))

        assertNull(CompanionStore(context).pairing?.associationId)
    }

    @Test
    fun noPairingIsNull() {
        assertNull(CompanionStore(context).pairing)
    }

    @Test
    fun storedRequestRoundTripsEveryField() {
        val request = sampleRequest()
        CompanionStore(context).autoRecording = request

        assertEquals(request, CompanionStore(context).autoRecording)
    }

    @Test
    fun storedRequestWithoutOptionalFieldsRoundTrips() {
        val plain = sampleRequest().copy(
            notification = null,
            plan = samplePlan().copy(modules = listOf(ObdModuleMessage("m2", 7, listOf("ATSP7")))),
        )
        CompanionStore(context).autoRecording = plain

        val read = CompanionStore(context).autoRecording
        assertEquals(plain, read)
        assertNull(read?.notification)
        assertNull(read?.plan?.modules?.single()?.readDeadlineMs)
    }

    @Test
    fun settingNullClearsTheStoredRequest() {
        val store = CompanionStore(context)
        store.autoRecording = sampleRequest()
        store.autoRecording = null

        assertNull(CompanionStore(context).autoRecording)
    }

    @Test
    fun anUndecodableStoredRequestReadsAsNothingStored() {
        context.getSharedPreferences(CompanionStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("auto_recording", "{\"sessionId\":\"x\"}").commit()

        assertNull(CompanionStore(context).autoRecording)
    }

    @Test
    fun clearingThePairingAlsoClearsObservationAndRequestButKeepsTheConfig() {
        val store = CompanionStore(context)
        val config = ObdBridgeConfigMessage("com.example.Main", "ic_stat", "prefs", "format-1")
        store.savePairing(samplePairing())
        store.presenceObserved = true
        store.autoRecording = sampleRequest()
        store.config = config

        store.clearPairing()

        val fresh = CompanionStore(context)
        assertNull(fresh.pairing)
        assertFalse(fresh.presenceObserved)
        assertNull(fresh.autoRecording)
        assertEquals(config, fresh.config)
    }

    @Test
    fun configRoundTrips() {
        val config = ObdBridgeConfigMessage("com.example.Main", "ic_stat", "prefs", "format-1")
        CompanionStore(context).config = config

        assertTrue(CompanionStore(context).config == config)
        assertNotNull(CompanionStore(context).config)
    }

    private fun storeAt(version: Long) =
        CompanionStore(context.getSharedPreferences(CompanionStore.PREFS_NAME, Context.MODE_PRIVATE)) { version }

    @Test
    fun aRequestStoredByAnotherAppVersionIsDiscardedAndDeleted() {
        storeAt(1).autoRecording = sampleRequest()

        assertNull(storeAt(2).autoRecording)
        assertNull("the discard must be permanent, not only for that version", storeAt(1).autoRecording)
    }

    @Test
    fun aRequestStoredByTheSameVersionSurvives() {
        storeAt(3).autoRecording = sampleRequest()

        assertEquals(sampleRequest(), storeAt(3).autoRecording)
    }

    @Test
    fun autoSessionsRoundTripAndEndOnce() {
        val store = CompanionStore(context)
        store.addAutoSession(AutoSession("s1", "/b/s1.jsonl", 10L))
        store.addAutoSession(AutoSession("s2", "/b/s2.jsonl", 20L))

        assertTrue(store.finishAutoSession("s1", 30L, "idle"))
        assertTrue(store.finishAutoSession("s1", 40L, "later"))
        assertFalse(store.finishAutoSession("unknown", 40L, "x"))

        val read = CompanionStore(context).autoSessions()
        assertEquals(listOf("s1", "s2"), read.map { it.sessionId })
        assertEquals(AutoSession("s1", "/b/s1.jsonl", 10L, 30L, "idle"), read[0])
        assertNull(read[1].endedAtEpochMs)
    }

    @Test
    fun acknowledgingForgetsOnlyThatSessionAndIsIdempotent() {
        val store = CompanionStore(context)
        store.addAutoSession(AutoSession("s1", "/b/s1.jsonl", 10L))
        store.addAutoSession(AutoSession("s2", "/b/s2.jsonl", 20L))

        store.acknowledgeAutoSession("s1")
        store.acknowledgeAutoSession("s1")

        assertEquals(listOf("s2"), store.autoSessions().map { it.sessionId })
    }

    @Test
    fun theSessionListIsBoundedDroppingTheOldest() {
        val store = CompanionStore(context)
        repeat(CompanionStore.MAX_AUTO_SESSIONS + 5) { store.addAutoSession(AutoSession("s$it", "/b/$it", it.toLong())) }

        val sessions = store.autoSessions()
        assertEquals(CompanionStore.MAX_AUTO_SESSIONS, sessions.size)
        assertEquals("s5", sessions.first().sessionId)
    }

    @Test
    fun forgettingThePairingKeepsUnacknowledgedSessionsButDropsSuppression() {
        val store = CompanionStore(context)
        store.savePairing(samplePairing())
        store.addAutoSession(AutoSession("s1", "/b/s1.jsonl", 10L))
        store.suppressAutoStart(endedAtEpochMs = 5L)

        store.clearPairing()

        assertEquals(1, store.autoSessions().size)
        assertNull(store.autoStartSuppression)
    }

    @Test
    fun configKeepsTheSuppressionOverrideOrItsAbsence() {
        val store = CompanionStore(context)
        val withOverride = ObdBridgeConfigMessage("a", "b", "c", "d", autoStartSuppressionMinutes = 12)
        store.config = withOverride
        assertEquals(withOverride, store.config)

        val without = ObdBridgeConfigMessage("a", "b", "c", "d")
        store.config = without
        assertNull(store.config?.autoStartSuppressionMinutes)
    }

    @Test
    fun aCorruptSessionEntryIsSkippedWithoutDroppingTheRest() {
        val store = CompanionStore(context)
        store.addAutoSession(AutoSession("s1", "/b/s1.jsonl", 10L))
        val prefs = context.getSharedPreferences(CompanionStore.PREFS_NAME, Context.MODE_PRIVATE)
        val good = prefs.getString("auto_sessions", null)!!
        prefs.edit().putString("auto_sessions", good.dropLast(1) + ",{\"sessionId\":\"broken\"}]").commit()

        assertEquals(listOf("s1"), CompanionStore(context).autoSessions().map { it.sessionId })
    }

    @Test
    fun capEvictionDropsFailedStartsBeforeRealRecordings() {
        val store = CompanionStore(context)
        store.addAutoSession(AutoSession("real-oldest", "/b/r", 1L, 2L, "idle"))
        store.addAutoSession(AutoSession("failed", "/b/f", 3L, 4L, "unreachable", failedStart = true))
        repeat(CompanionStore.MAX_AUTO_SESSIONS - 1) { store.addAutoSession(AutoSession("s$it", "/b/$it", 10L + it)) }

        val ids = store.autoSessions().map { it.sessionId }
        assertEquals(CompanionStore.MAX_AUTO_SESSIONS, ids.size)
        assertTrue("real-oldest" in ids)
        assertFalse("failed" in ids)
    }

    @Test
    fun finishingOpenSessionsClosesOnlyTheOpenOnes() {
        val store = CompanionStore(context)
        store.addAutoSession(AutoSession("done", "/b/d", 1L, 2L, "idle"))
        store.addAutoSession(AutoSession("open", "/b/o", 3L))

        store.finishOpenAutoSessions(9L, "start did not complete")

        val read = store.autoSessions().associateBy { it.sessionId }
        assertEquals("idle", read["done"]?.endReason)
        assertEquals(9L, read["open"]?.endedAtEpochMs)
        assertTrue(read["open"]!!.failedStart)
    }

    @Test
    fun connectingBodyRoundTripsAndIsOptional() {
        val store = CompanionStore(context)
        val with = sampleRequest().copy(notification = ObdRecordingNotificationMessage("c", "t", "b", "s", connectingBody = "connecting"))
        store.autoRecording = with
        assertEquals("connecting", CompanionStore(context).autoRecording?.notification?.connectingBody)
        store.autoRecording = sampleRequest()
        assertNull(CompanionStore(context).autoRecording?.notification?.connectingBody)
    }
}
