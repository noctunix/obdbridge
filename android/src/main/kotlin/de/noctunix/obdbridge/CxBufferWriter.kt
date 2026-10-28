package de.noctunix.obdbridge

import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile

/**
 * Append-only JSONL writer for one recording's buffer file. One JSON object per line, flushed
 * immediately after every write — a process kill can corrupt at most the last, unflushed line,
 * never more, and [repairTornTail] protects a fresh append after exactly that kind of kill.
 *
 * Field names are deliberately full words (`sequence`, `requestStartedUtcMs`, `receivedUtcMs`,
 * `monotonicStartNs`, `durationNs`, `timebaseId`, `raw`, `transport`), not an abbreviated set —
 * the buffer is meant to be read by a later importer, not just this file. The header carries
 * [ObdPlanMessage]'s own identity as `planId`/`planVersion`, and each line identifies its step by
 * `signalId` ([ObdStepMessage.signalId]).
 *
 * [appendExchange]'s `raw` is the literal bytes [CxCommandResult.rawBytes] carried for that
 * attempt — the complete frame before the prompt on success, or whatever partial bytes had
 * already arrived before a timeout/overflow/disconnect/cancellation — encoded as lowercase hex,
 * never the decoded response text. `transport` is one of the literal strings `ok`, `timeout`,
 * `overflow`, `disconnected`, `rejectedByGuard`, `writeFailed`, `cancelled` (the same spelling
 * [ObdTransportStatusMessage]'s Dart enum uses), not a Kotlin enum name.
 *
 * [appendExchange]'s `oneShot` field is present, always `true`, only on a line from
 * [CxPoller.enqueueOneShot]'s queue; omitted (never `false`) on every ordinary, planned exchange,
 * so an existing reader that does not know the field yet keeps working unchanged.
 *
 * [maxBufferBytes] is enforced after the fact, not by refusing to write: [limitReached] is set
 * once this writer's own running byte count would exceed it, and the caller ([CxPoller]/
 * [CxRecordingService]) is the one that stops polling and ends the recording visibly once it
 * sees that flag — this class never overwrites or truncates anything to stay under the limit.
 */
class CxBufferWriter(path: String, private val maxBufferBytes: Long) : AutoCloseable {
    private val writer: BufferedWriter = run {
        repairTornTail(File(path))
        BufferedWriter(FileWriter(File(path), /* append = */ true))
    }

    @Volatile
    var failed: Boolean = false
        private set

    @Volatile
    var limitReached: Boolean = false
        private set

    private var bytesWritten: Long = 0L
    val bufferBytes: Long get() = bytesWritten

    private var sequence: Long = 0L
    val lastSequence: Long get() = sequence

    private var gapCount: Int = 0
    val gaps: Int get() = gapCount

    /** Seeds [lastSequence]/[gaps] from numbers a caller already knows (typically
     * [readTailState] on the same file) — for continuing an existing file's numbering instead
     * of starting a fresh writer instance back at zero and mislabeling every `afterSequence`/
     * `gap` it writes from here on. Only meaningful before the first [appendExchange]/
     * [appendGap] call on this instance. */
    @Synchronized
    fun seedFrom(lastSequence: Long, lastGapCount: Int) {
        sequence = lastSequence
        gapCount = lastGapCount
    }

    /** `{"format":<bufferFormatId>,"formatVersion":1,"sessionId":…,"planId":…,
     * "planVersion":…,"timebaseId":…,"createdUtcMs":…}`. The top-level `formatVersion` is this
     * JSONL shape's own version (always [FORMAT_VERSION] today), unrelated to [ObdPlanMessage]'s
     * own `formatVersion` (the wire-format version of the plan itself), which this header does
     * not carry at all. [bufferFormatId] is caller-supplied
     * ([ObdBridgeConfigMessage.bufferFormatId]) rather than a fixed string, so a caller that
     * already shipped buffers under a different identifier (e.g. an app moving its own recorder
     * onto this library) keeps using it and previously recorded buffers still import correctly. */
    @Synchronized
    fun writeHeader(
        sessionId: String,
        bufferFormatId: String,
        planId: String,
        planVersion: Long,
        timebaseId: String,
        createdUtcMs: Long,
    ) {
        writeLine(
            JSONObject().apply {
                put("format", bufferFormatId)
                put("formatVersion", FORMAT_VERSION)
                put("sessionId", sessionId)
                put("planId", planId)
                put("planVersion", planVersion)
                put("timebaseId", timebaseId)
                put("createdUtcMs", createdUtcMs)
            },
        )
    }

    /** `{"sequence":…,"signalId":…,"requestStartedUtcMs":…,"receivedUtcMs":…,
     * "monotonicStartNs":…,"durationNs":…,"timebaseId":…,"raw":…,"transport":…}`
     *
     * Returns the `sequence` just assigned — gapless by construction, this writer is the one
     * place that hands sequence numbers out. A no-op (returns the previous [lastSequence]
     * unchanged) once [failed] or [limitReached] is already set, so a caller that keeps calling
     * past either flag cannot silently keep extending the file. */
    @Synchronized
    fun appendExchange(
        signalId: String,
        requestStartedUtcMs: Long,
        receivedUtcMs: Long?,
        monotonicStartNs: Long,
        durationNs: Long,
        timebaseId: String,
        rawBytes: ByteArray,
        transport: String,
        oneShot: Boolean = false,
    ): Long {
        if (failed || limitReached) return sequence
        val nextSequence = sequence + 1
        writeLine(
            JSONObject().apply {
                put("sequence", nextSequence)
                put("signalId", signalId)
                put("requestStartedUtcMs", requestStartedUtcMs)
                receivedUtcMs?.let { put("receivedUtcMs", it) }
                put("monotonicStartNs", monotonicStartNs)
                put("durationNs", durationNs)
                put("timebaseId", timebaseId)
                put("raw", toHex(rawBytes))
                put("transport", transport)
                if (oneShot) put("oneShot", true)
            },
        )
        // A failure inside writeLine above must not bump the sequence counter — committing a
        // number to a line that was never actually written would make the very next, genuinely
        // successful append look gapless to a reader when a line is in fact missing.
        if (!failed) sequence = nextSequence
        return sequence
    }

    /** `{"gap":…,"afterSequence":…,"reason":…}` — [afterSequence] is the last `sequence` actually
     * written before the gap, carried so a reader can place it without having to count lines.
     * Does not itself consume a sequence number.
     *
     * [force] writes even if [limitReached] is already set — a recording ending *because* the
     * limit was just reached still needs exactly this one closing line, which the ordinary
     * limit guard would otherwise refuse. Never bypasses [failed]: a writer whose last write
     * already threw is not trusted with one more. */
    @Synchronized
    fun appendGap(reason: String, force: Boolean = false) {
        if (failed || (limitReached && !force)) return
        gapCount++
        writeLine(
            JSONObject().apply {
                put("gap", gapCount)
                put("afterSequence", sequence)
                put("reason", reason)
            },
        )
    }

    private fun writeLine(json: JSONObject) {
        try {
            val text = json.toString()
            writer.write(text)
            writer.newLine()
            writer.flush()
            // +1 for the newline this writer always adds; close enough for the limit this
            // guards — being off by the platform's own line-ending convention is not worth a
            // second file stat per line.
            bytesWritten += text.toByteArray(Charsets.UTF_8).size + 1
            if (bytesWritten > maxBufferBytes) limitReached = true
        } catch (e: Exception) {
            // Total by design: a write failure (disk full,
            // …) must never crash the recording. The caller reads [failed] out afterwards and
            // ends the recording visibly rather than pretending nothing happened.
            failed = true
        }
    }

    @Synchronized
    override fun close() {
        try {
            writer.close()
        } catch (e: Exception) {
            failed = true
        }
    }

    companion object {
        private const val FORMAT_VERSION = 1

        private val hexChars = "0123456789abcdef".toCharArray()

        /** Lowercase hex, no separators — e.g. `6f6b`. */
        fun toHex(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val value = bytes[i].toInt() and 0xFF
                out[i * 2] = hexChars[value ushr 4]
                out[i * 2 + 1] = hexChars[value and 0x0F]
            }
            return String(out)
        }

        /** The last `sequence` an exchange line carried and the last `gap` number a gap line
         * carried, scanned from whatever is already in [file] — `0` to `0` for a missing or
         * empty file. Tolerates a torn last line (and any other line that fails to parse as
         * JSON) by simply skipping it, the same tolerance [repairTornTail] itself assumes a
         * reader downstream already has. Used by [CxRecordingRestartMarker] to seed a writer
         * that continues an existing file rather than mislabeling it as empty, on the main
         * thread right after a process restart — so it tries [TAIL_SCAN_BYTES] from the end
         * first, rather than a file that can be [maxBufferBytes]-sized (several MiB), but that
         * is only the fast path, not a bound on what actually runs: the `gap` number found this
         * way is trustworthy on its own whenever the chunk contains one at all (gap numbers only
         * ever increase by exactly one, so the highest one visible anywhere already implies
         * every earlier one), but *not* when the chunk contains none — a gap can sit arbitrarily
         * far before an otherwise gap-free tail (an early dropout, then a long, uneventful
         * recording later truncated by a kill), which is exactly the ordinary shape of a
         * successful, gap-free recording once it runs past a few hundred exchanges. That case —
         * not a rare one — falls back to scanning the whole file, parsing one `JSONObject` per
         * line, still on the calling (main) thread. */
        fun readTailState(file: File): Pair<Long, Int> {
            if (!file.exists()) return 0L to 0
            val length = file.length()
            if (length == 0L) return 0L to 0
            val chunkSize = minOf(length, TAIL_SCAN_BYTES)
            val tail = ByteArray(chunkSize.toInt())
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(length - chunkSize)
                raf.readFully(tail)
            }
            val (chunkSequence, chunkGap) = scanLinesForTailState(String(tail, Charsets.UTF_8).lineSequence())
            if (chunkGap > 0 || chunkSize >= length) return chunkSequence to chunkGap
            // `scanLinesForTailState` must run *inside* this block — `useLines` closes the
            // underlying reader the moment it returns, and the sequence it hands out is lazy,
            // so consuming it afterward would read from an already-closed stream.
            return file.useLines { scanLinesForTailState(it) }
        }

        private fun scanLinesForTailState(lines: Sequence<String>): Pair<Long, Int> {
            var lastSequence = 0L
            var lastGap = 0
            for (line in lines) {
                val json = try {
                    JSONObject(line)
                } catch (e: Exception) {
                    continue
                }
                if (json.has("sequence")) lastSequence = json.optLong("sequence", lastSequence)
                if (json.has("gap")) lastGap = json.optInt("gap", lastGap)
            }
            return lastSequence to lastGap
        }

        /** Comfortably more than enough recent lines to find the last `sequence`/`gap` in any
         * realistic buffer — a starting value, not measured against a real recording's actual
         * line size. */
        private const val TAIL_SCAN_BYTES = 65_536L

        /** A process kill mid-write can
         * leave the last line without its trailing newline; appending straight onto that tail
         * would fuse the torn bytes and the next line into one unparsable blob. */
        private fun repairTornTail(file: File) {
            val length = file.length()
            if (length == 0L) return
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(length - 1)
                if (raf.read() != '\n'.code) {
                    raf.seek(length)
                    raf.write('\n'.code)
                }
            }
        }
    }
}
