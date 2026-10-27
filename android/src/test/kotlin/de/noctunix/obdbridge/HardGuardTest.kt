package de.noctunix.obdbridge

import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HardGuardTest {
    // The Gradle working directory for this module varies with how the
    // build is invoked (module root vs. example app vs. CI), so the shared
    // fixture is located by walking up from it rather than assuming a
    // fixed relative path.
    private fun locateVectorsFile(): File {
        var dir: File? = File(".").absoluteFile
        repeat(8) {
            val current = dir ?: return@repeat
            val candidate = File(current, "test/fixtures/guard_vectors.json")
            if (candidate.isFile) return candidate
            dir = current.parentFile
        }
        error("test/fixtures/guard_vectors.json not found by walking up from ${File(".").absolutePath}")
    }

    private fun loadVectors(): List<Triple<String, Boolean, String>> {
        val json = JSONArray(locateVectorsFile().readText())
        return (0 until json.length()).map { index ->
            val entry = json.getJSONObject(index)
            Triple(entry.getString("command"), entry.getBoolean("allowed"), entry.getString("reason"))
        }
    }

    @Test
    fun sharedVectors() {
        for ((command, allowed, reason) in loadVectors()) {
            val result = HardGuard.check(command)
            val message = "\"$command\" ($reason): expected ${if (allowed) "Allowed" else "Rejected"}, got $result"
            if (allowed) {
                assertTrue(message, result is GuardResult.Allowed)
            } else {
                assertTrue(message, result is GuardResult.Rejected)
            }
        }
    }
}
