package de.noctunix.obdbridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric is needed because `org.json.JSONObject`'s stub in `android.jar` throws
 * "not mocked" on a plain JVM. */
@RunWith(RobolectricTestRunner::class)
class CxBufferWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newWriter(maxBufferBytes: Long = 1_000_000L): Pair<java.io.File, CxBufferWriter> {
        val file = tempFolder.newFile("buffer_${System.nanoTime()}.jsonl")
        return file to CxBufferWriter(file.absolutePath, maxBufferBytes)
    }

    @Test
    fun headerIsAlwaysTheFirstLineAndCarriesEveryField() {
        val (file, writer) = newWriter()
        writer.writeHeader(
            sessionId = "session-1",
            bufferFormatId = "obdbridge-buffer",
            planId = "plan-1",
            planVersion = 3L,
            timebaseId = "tb-1",
            createdUtcMs = 1_700_000_000_000L,
        )
        writer.close()

        val header = JSONObject(file.readLines().single())
        assertEquals("obdbridge-buffer", header.getString("format"))
        assertEquals(1, header.getInt("formatVersion"))
        assertEquals("session-1", header.getString("sessionId"))
        assertEquals("plan-1", header.getString("planId"))
        assertEquals(3, header.getInt("planVersion"))
        assertEquals("tb-1", header.getString("timebaseId"))
        assertEquals(1_700_000_000_000L, header.getLong("createdUtcMs"))
    }

    @Test
    fun sequenceIsGaplessAcrossSuccessiveExchanges() {
        val (file, writer) = newWriter()
        writer.writeHeader("s", "fmt", "p", 1L, "tb", 0L)

        val first = writer.appendExchange(
            signalId = "a",
            requestStartedUtcMs = 10L,
            receivedUtcMs = 20L,
            monotonicStartNs = 0L,
            durationNs = 5L,
            timebaseId = "tb",
            rawBytes = byteArrayOf(0x6f, 0x6b),
            transport = "ok",
        )
        val second = writer.appendExchange(
            signalId = "b",
            requestStartedUtcMs = 30L,
            // A non-ok transport carries no received timestamp — matches how
            // `CxRecordingService` actually calls this (see its own doc comment).
            receivedUtcMs = null,
            monotonicStartNs = 10L,
            durationNs = 5L,
            timebaseId = "tb",
            rawBytes = ByteArray(0),
            transport = "timeout",
        )
        writer.close()

        assertEquals(1L, first)
        assertEquals(2L, second)
        assertEquals(2L, writer.lastSequence)

        val lines = file.readLines()
        assertEquals(3, lines.size) // header + 2 exchanges
        val exchangeOne = JSONObject(lines[1])
        assertEquals(1, exchangeOne.getInt("sequence"))
        assertEquals("a", exchangeOne.getString("signalId"))
        assertEquals("6f6b", exchangeOne.getString("raw"))
        assertEquals("ok", exchangeOne.getString("transport"))

        val exchangeTwo = JSONObject(lines[2])
        assertEquals(2, exchangeTwo.getInt("sequence"))
        // This writer only ever hex-encodes whatever rawBytes the caller actually passed in —
        // here that is deliberately empty (a timeout/overflow/disconnect/cancellation can still
        // carry partial bytes in production; this test just did not give it any).
        assertEquals("", exchangeTwo.getString("raw"))
        assertFalse("a non-ok exchange must carry no receivedUtcMs", exchangeTwo.has("receivedUtcMs"))
    }

    @Test
    fun hexEncodingIsLowercaseAndByteExact() {
        val bytes = byteArrayOf(0x00.toByte(), 0x0A.toByte(), 0xFF.toByte(), 0x7A.toByte())
        assertEquals("000aff7a", CxBufferWriter.toHex(bytes))
    }

    @Test
    fun aGapIsItsOwnLineNotAMissingSequence() {
        val (file, writer) = newWriter()
        writer.writeHeader("s", "fmt", "p", 1L, "tb", 0L)
        writer.appendExchange("a", 0L, 0L, 0L, 0L, "tb", byteArrayOf(1), "ok")
        writer.appendGap("connection lost")
        writer.appendExchange("a", 0L, 0L, 0L, 0L, "tb", byteArrayOf(2), "ok")
        writer.close()

        val lines = file.readLines()
        assertEquals(4, lines.size) // header + exchange + gap + exchange
        val gap = JSONObject(lines[2])
        assertTrue(gap.has("gap"))
        assertEquals(1L, gap.getLong("afterSequence"))
        assertEquals("connection lost", gap.getString("reason"))
        assertFalse("a gap line must not itself carry a sequence", gap.has("sequence"))

        // The exchange after the gap continues the same sequence — the gap did not consume one.
        val exchangeAfterGap = JSONObject(lines[3])
        assertEquals(2, exchangeAfterGap.getInt("sequence"))
        assertEquals(1, writer.gaps)
    }

    @Test
    fun exceedingMaxBufferBytesSetsLimitReachedWithoutTruncatingWhatWasAlreadyWritten() {
        val (file, writer) = newWriter(maxBufferBytes = 1L) // the header line alone already exceeds this
        writer.writeHeader("s", "fmt", "p", 1L, "tb", 0L)
        assertTrue("the header line already pushed bytesWritten past the 1-byte limit", writer.limitReached)

        // A further call after the limit is reached is a no-op: no new line, no sequence bump —
        // this is the "never by overwriting" guarantee, checked from the writer's own side.
        val before = file.readLines().size
        val sequence = writer.appendExchange("a", 0L, 0L, 0L, 0L, "tb", byteArrayOf(1), "ok")
        writer.close()

        assertEquals(0L, sequence)
        assertEquals("nothing new was appended once the limit was already reached", before, file.readLines().size)
    }

    @Test
    fun aWriteFailureSetsFailedAndStopsAcceptingFurtherAppends() {
        val file = tempFolder.newFile("buffer_failure.jsonl")
        val writer = CxBufferWriter(file.absolutePath, 1_000_000L)
        writer.writeHeader("s", "fmt", "p", 1L, "tb", 0L)
        assertFalse(writer.failed)

        closeUnderlyingStream(writer)

        // Must not throw: writeLine's catch swallows the IOException.
        val sequence = writer.appendExchange("a", 0L, 0L, 0L, 0L, "tb", byteArrayOf(1), "ok")
        assertTrue("a write against the closed stream must flip failed", writer.failed)
        assertEquals(0L, sequence)
    }

    @Test
    fun repairsATornLastLineBeforeAppendingAfterAnAbruptKill() {
        val file = tempFolder.newFile("buffer_torn.jsonl")
        val tornLine = "{\"sequence\":1,\"signalId\":\"a\",\"requestStartedUtcMs\""
        file.writeBytes(tornLine.toByteArray())

        val writer = CxBufferWriter(file.absolutePath, 1_000_000L)
        writer.appendExchange("b", 10L, 20L, 0L, 5L, "tb", byteArrayOf(1), "ok")
        writer.close()

        val lines = file.readLines()
        assertEquals(2, lines.size)
        assertEquals(tornLine, lines[0])
        val resumed = JSONObject(lines[1])
        assertEquals("b", resumed.getString("signalId"))
        // The torn line is untouched, including its own sequence — this writer's own counter
        // starts fresh at 1 regardless, since nothing here re-derives state from a partially
        // written predecessor file (no resume-into-existing-file feature exists).
        assertEquals(1, resumed.getInt("sequence"))
    }

    @Test
    fun readTailStateFindsAnEarlierGapEvenWhenTheRecentTailChunkHasNone() {
        val file = tempFolder.newFile("buffer_large.jsonl")
        val writer = CxBufferWriter(file.absolutePath, Long.MAX_VALUE)
        writer.appendGap("connection lost")
        // Comfortably more than the tail-scan window of plain exchange lines, so the gap above
        // falls out of range for a tail-only scan — exactly the "early dropout, then a long,
        // uneventful recording" shape a chunk-only scan would silently miscount.
        repeat(2000) {
            writer.appendExchange("a", 0L, 0L, 0L, 0L, "tb", byteArrayOf(1), "ok")
        }
        writer.close()
        assertTrue(
            "the generated file must actually exceed the tail-scan window for this test to mean anything",
            file.length() > 65_536L,
        )

        val (lastSequence, lastGap) = CxBufferWriter.readTailState(file)

        assertEquals(2000L, lastSequence)
        assertEquals("the gap near the start of the file must still be found via the full-scan fallback", 1, lastGap)
    }

    private fun closeUnderlyingStream(writer: CxBufferWriter) {
        val field = CxBufferWriter::class.java.getDeclaredField("writer")
        field.isAccessible = true
        (field.get(writer) as java.io.BufferedWriter).close()
    }
}
