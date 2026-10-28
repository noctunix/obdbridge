package de.noctunix.obdbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CxRecordingStateTest {

    @Test
    fun idleIsEmptyAndNotResumed() {
        val idle = CxRecordingState.idle()
        assertEquals(CxRecordingPhase.IDLE, idle.phase)
        assertNull(idle.sessionId)
        assertEquals(0L, idle.lastFlushedSequence)
        assertEquals(0L, idle.bufferBytes)
        assertEquals(0, idle.gapCount)
        assertNull(idle.writeError)
        assertFalse(idle.resumedAfterRestart)
    }

    @Test
    fun everyPhaseMapsToItsOwnDistinctMessageValue() {
        val mapped = CxRecordingPhase.entries.associateWith { phase ->
            CxRecordingState(
                sessionId = "s",
                phase = phase,
                lastFlushedSequence = 1L,
                bufferBytes = 2L,
                gapCount = 3,
                writeError = null,
                resumedAfterRestart = false,
            ).toMessage().state
        }
        assertEquals(CxRecordingPhase.entries.size, mapped.values.toSet().size)
        assertEquals(ObdRecordingStateValueMessage.IDLE, mapped[CxRecordingPhase.IDLE])
        assertEquals(ObdRecordingStateValueMessage.RECORDING, mapped[CxRecordingPhase.RECORDING])
        assertEquals(ObdRecordingStateValueMessage.SUSPENDED_BY_PLATFORM, mapped[CxRecordingPhase.SUSPENDED_BY_PLATFORM])
        assertEquals(ObdRecordingStateValueMessage.STOPPED, mapped[CxRecordingPhase.STOPPED])
        assertEquals(ObdRecordingStateValueMessage.INTERRUPTED, mapped[CxRecordingPhase.INTERRUPTED])
    }

    @Test
    fun toMessageCarriesEveryFieldAcrossUnchanged() {
        val state = CxRecordingState(
            sessionId = "session-1",
            phase = CxRecordingPhase.INTERRUPTED,
            lastFlushedSequence = 42L,
            bufferBytes = 1024L,
            gapCount = 2,
            writeError = "disk full",
            resumedAfterRestart = false,
            oneShotQueueDepth = 7,
        )
        val message = state.toMessage()
        assertEquals("session-1", message.sessionId)
        assertEquals(42L, message.lastFlushedSequence)
        assertEquals(1024L, message.bufferBytes)
        assertEquals(2L, message.gapCount)
        assertEquals("disk full", message.writeError)
        assertFalse(message.resumedAfterRestart)
        assertEquals(7L, message.oneShotQueueDepth)
    }
}
