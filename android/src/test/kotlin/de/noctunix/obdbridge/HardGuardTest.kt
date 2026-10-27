package de.noctunix.obdbridge

import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
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

    @TestFactory
    fun sharedVectors(): List<DynamicTest> =
        loadVectors().map { (command, allowed, reason) ->
            DynamicTest.dynamicTest("${if (allowed) "allows" else "rejects"} \"$command\" ($reason)") {
                val result = HardGuard.check(command)
                if (allowed) {
                    assertTrue(result is GuardResult.Allowed, "expected \"$command\" to be allowed, got $result")
                } else {
                    assertTrue(result is GuardResult.Rejected, "expected \"$command\" to be rejected, got $result")
                }
            }
        }
}
