package de.noctunix.obdbridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric for two reasons: a real `Context` for `SharedPreferences`, and
 * `org.json.JSONObject`'s stub throwing "not mocked" on a plain JVM. */
@RunWith(RobolectricTestRunner::class)
class CxRecordingRestartMarkerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val prefsName = "obdbridge_recording_restart_test"

    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun noMarkerMeansNothingToResolve() {
        assertNull(CxRecordingRestartMarker.resolveAbandoned(context(), prefsName))
    }

    @Test
    fun aSavedMarkerIsClearedByResolveSoItIsNeverReportedTwice() {
        val context = context()
        val file = tempFolder.newFile("buffer.jsonl")
        CxRecordingRestartMarker.save(
            context,
            prefsName,
            sessionId = "session-1",
            bufferFilePath = file.absolutePath,
            startedAtEpochMs = 1_700_000_000_000L,
        )

        val first = CxRecordingRestartMarker.resolveAbandoned(context, prefsName)
        assertEquals("session-1", first?.sessionId)

        val second = CxRecordingRestartMarker.resolveAbandoned(context, prefsName)
        assertNull("a resolved marker must not be reported again on a later check", second)
    }

    @Test
    fun resolvedStateIsInterruptedAndNeverClaimsToBeResumed() {
        val context = context()
        val file = tempFolder.newFile("buffer.jsonl")
        CxRecordingRestartMarker.save(
            context,
            prefsName,
            sessionId = "session-1",
            bufferFilePath = file.absolutePath,
            startedAtEpochMs = 0L,
        )

        val resolved = CxRecordingRestartMarker.resolveAbandoned(context, prefsName)!!

        assertEquals(CxRecordingPhase.INTERRUPTED, resolved.phase)
        assertFalse(
            "discovering an abandoned recording is not resuming it — a later consumer reading " +
                "true here would wrongly conclude the recording is still going",
            resolved.resumedAfterRestart,
        )
    }

    @Test
    fun appendsAGapLineContinuingTheExistingSequenceAndGapNumbering() {
        val context = context()
        val file = tempFolder.newFile("buffer.jsonl")
        // A header plus two exchanges and one earlier gap, as a real recording would have left
        // behind before the process was killed.
        file.writeText(
            listOf(
                """{"format":"obdbridge-buffer","formatVersion":1,"sessionId":"session-1"}""",
                """{"sequence":1,"signalId":"a","transport":"ok"}""",
                """{"gap":1,"afterSequence":1,"reason":"connection lost"}""",
                """{"sequence":2,"signalId":"a","transport":"ok"}""",
            ).joinToString("\n") + "\n",
        )
        CxRecordingRestartMarker.save(
            context,
            prefsName,
            sessionId = "session-1",
            bufferFilePath = file.absolutePath,
            startedAtEpochMs = 0L,
        )

        val resolved = CxRecordingRestartMarker.resolveAbandoned(context, prefsName)!!

        val lines = file.readLines()
        assertEquals(5, lines.size)
        val appended = JSONObject(lines.last())
        assertEquals(
            "the appended gap must continue the file's own existing sequence, not restart at 0",
            2L,
            appended.getLong("afterSequence"),
        )
        assertEquals(
            "the appended gap must continue the file's own existing gap numbering, not restart at 1",
            2,
            appended.getInt("gap"),
        )
        assertEquals(2L, resolved.lastFlushedSequence)
        assertEquals(2, resolved.gapCount)
    }

    @Test
    fun aTornLastLineIsSkippedAndTheSequenceContinuesFromTheLastCompleteOne() {
        val context = context()
        val file = tempFolder.newFile("buffer.jsonl")
        // The process died mid-write: the last line never got its closing brace or newline.
        file.writeText(
            listOf(
                """{"format":"obdbridge-buffer","formatVersion":1,"sessionId":"session-1"}""",
                """{"sequence":1,"signalId":"a","transport":"ok"}""",
                """{"sequence":2,"signalId":"a","transport":"ok"}""",
            ).joinToString("\n") + "\n" + """{"sequence":3,"sig""",
        )
        CxRecordingRestartMarker.save(
            context,
            prefsName,
            sessionId = "session-1",
            bufferFilePath = file.absolutePath,
            startedAtEpochMs = 0L,
        )

        val resolved = CxRecordingRestartMarker.resolveAbandoned(context, prefsName)!!

        val lines = file.readLines()
        // Header + 2 exchanges + the repaired (now newline-terminated) torn line + the appended
        // gap — the torn line itself survives verbatim, on its own line, never fused with the
        // gap line that follows it.
        assertEquals(5, lines.size)
        assertEquals("""{"sequence":3,"sig""", lines[3])
        val appended = JSONObject(lines.last())
        assertTrue(appended.has("gap"))
        assertEquals(
            "the torn line carried no complete sequence of its own — the last one actually " +
                "written and parsed successfully was 2",
            2L,
            appended.getLong("afterSequence"),
        )
        assertEquals(2L, resolved.lastFlushedSequence)
    }

    @Test
    fun aMissingBufferFileIsNotRecreatedJustToHoldOneGapLine() {
        val context = context()
        val missingPath = tempFolder.root.absolutePath + "/never_existed.jsonl"
        CxRecordingRestartMarker.save(
            context,
            prefsName,
            sessionId = "session-1",
            bufferFilePath = missingPath,
            startedAtEpochMs = 0L,
        )

        val resolved = CxRecordingRestartMarker.resolveAbandoned(context, prefsName)!!

        assertEquals(CxRecordingPhase.INTERRUPTED, resolved.phase)
        assertTrue(
            "a buffer file that was never actually created must not be conjured into existence " +
                "containing nothing but a lone gap line",
            !java.io.File(missingPath).exists(),
        )
    }

    @Test
    fun clearRemovesAPreviouslySavedMarker() {
        val context = context()
        val file = tempFolder.newFile("buffer.jsonl")
        CxRecordingRestartMarker.save(
            context,
            prefsName,
            sessionId = "session-1",
            bufferFilePath = file.absolutePath,
            startedAtEpochMs = 0L,
        )
        CxRecordingRestartMarker.clear(context, prefsName)

        assertNull(CxRecordingRestartMarker.resolveAbandoned(context, prefsName))
    }
}
