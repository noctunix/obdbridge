package de.noctunix.obdbridge

import android.content.Context
import org.json.JSONObject

/**
 * The one piece of state that survives a process kill and lets a freshly constructed [ObdApi]
 * tell "a recording was still running when this process died" apart from "nothing was ever
 * recording". [CxRecordingService] never self-restarts (see that class's own doc), but a kill
 * mid-recording still needs to end up [CxRecordingPhase.INTERRUPTED] rather than silently idle,
 * and the buffer file itself still needs a visible end rather than trailing off mid-sequence
 * with no explanation.
 *
 * Written once, when a recording actually starts; cleared on every clean end
 * ([CxRecordingService.endRecordingInternal] runs on every path out, including a write failure
 * or an exhausted reconnect budget) — a marker still present at the next process start is
 * therefore proof the previous process never got that far, i.e. it was killed outright.
 * `commit()`, not `apply()`: this must be readable immediately after a write that might be
 * followed by a kill at any moment.
 *
 * [sharedPreferencesName] is caller-supplied ([ObdBridgeConfigMessage.sharedPreferencesName])
 * rather than a fixed name, so an app embedding this library keeps whatever file name its own
 * previous, non-library recorder used.
 */
object CxRecordingRestartMarker {

    private const val KEY_MARKER = "pending_marker"
    private const val FORMAT_VERSION = 1

    private fun prefs(context: Context, sharedPreferencesName: String) =
        context.getSharedPreferences(sharedPreferencesName, Context.MODE_PRIVATE)

    /** Best-effort: a recording that cannot persist this marker still records, it just cannot be
     * recognized as interrupted after a kill that happens to land before it is cleared again. */
    fun save(
        context: Context,
        sharedPreferencesName: String,
        sessionId: String,
        bufferFilePath: String,
        startedAtEpochMs: Long,
    ) {
        try {
            val json = JSONObject().apply {
                put("v", FORMAT_VERSION)
                put("sessionId", sessionId)
                put("bufferFilePath", bufferFilePath)
                put("startedAtEpochMs", startedAtEpochMs)
            }
            prefs(context, sharedPreferencesName).edit().putString(KEY_MARKER, json.toString()).commit()
        } catch (e: Exception) {
            // Nothing else can be done — see the class doc.
        }
    }

    fun clear(context: Context, sharedPreferencesName: String) {
        try {
            prefs(context, sharedPreferencesName).edit().remove(KEY_MARKER).commit()
        } catch (e: Exception) {
            // A marker that cannot be cleared is wrongly reported as interrupted next process
            // start — a confusing but not unsafe outcome (no silent overwrite of real data), and
            // this method has no other way to recover from a broken SharedPreferences write.
        }
    }

    /**
     * If a marker is present, clears it and returns the [CxRecordingState] a caller should
     * report for it: [CxRecordingPhase.INTERRUPTED], carrying the abandoned recording's
     * [CxRecordingState.sessionId]. [CxRecordingState.resumedAfterRestart] stays `false` — that
     * field means a recording actually continuing after being found, which this package never
     * does; discovering the abandonment is not resuming it, and claiming otherwise would read
     * to a later consumer as "still going" rather than "over".
     *
     * Also best-effort appends a terminal gap line to the buffer file the marker names (only if
     * it still exists — a missing file gets no line, never a freshly created one that would
     * consist of nothing but that single line and no header), seeded with the file's own last
     * `sequence`/`gap` numbers via [CxBufferWriter.readTailState] so the appended line continues
     * the existing numbering instead of mislabeling itself as the file's first line. Returns
     * `null` when nothing is pending, which is the overwhelmingly common case (every ordinary
     * app start).
     */
    fun resolveAbandoned(context: Context, sharedPreferencesName: String): CxRecordingState? {
        val raw = try {
            prefs(context, sharedPreferencesName).getString(KEY_MARKER, null)
        } catch (e: Exception) {
            null
        } ?: return null
        clear(context, sharedPreferencesName)
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return null
        }
        if (json.optInt("v", -1) != FORMAT_VERSION) return null
        val sessionId = json.optString("sessionId", "").ifEmpty { return null }
        val bufferFilePath = json.optString("bufferFilePath", "")
        var lastSequence = 0L
        var lastGapCount = 0
        var bufferBytes = 0L
        if (bufferFilePath.isNotEmpty()) {
            val file = java.io.File(bufferFilePath)
            if (file.exists()) {
                try {
                    val tail = CxBufferWriter.readTailState(file)
                    lastSequence = tail.first
                    lastGapCount = tail.second
                    // `Long.MAX_VALUE` because the original recording's own buffer-size limit is
                    // irrelevant to appending one closing line.
                    CxBufferWriter(bufferFilePath, Long.MAX_VALUE).use { writer ->
                        writer.seedFrom(lastSequence, lastGapCount)
                        writer.appendGap("recording process ended without a clean stop")
                        lastGapCount = writer.gaps
                    }
                } catch (e: Exception) {
                    // Best effort — the marker is cleared either way; an unwritable/unreadable
                    // buffer file has nothing else this method can do for it.
                }
                // Read after the append attempt (successful or not) — the file's actual size on
                // disk either way, never a hardcoded `0` for a file that can be several MiB.
                bufferBytes = try { file.length() } catch (e: Exception) { 0L }
            }
        }
        return CxRecordingState(
            sessionId = sessionId,
            phase = CxRecordingPhase.INTERRUPTED,
            lastFlushedSequence = lastSequence,
            bufferBytes = bufferBytes,
            gapCount = lastGapCount,
            writeError = null,
            resumedAfterRestart = false,
        )
    }
}
