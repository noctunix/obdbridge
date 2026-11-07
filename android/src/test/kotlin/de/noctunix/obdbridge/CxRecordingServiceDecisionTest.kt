package de.noctunix.obdbridge

import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises [CxRecordingService.consecutiveFailuresOutcome] directly — a pure function, so
 * none of [CxRecordingServiceTest]'s Robolectric/wake-lock machinery is needed to cover the
 * actual escalate-or-keep-resyncing decision. */
class CxRecordingServiceDecisionTest {

    @Test
    fun endRecordingAlwaysEscalatesRegardlessOfWhetherThePollerHadAlreadyStopped() {
        val outcome = CxRecordingService.consecutiveFailuresOutcome(
            ObdConsecutiveFailuresPolicyMessage.END_RECORDING,
            pollerStopped = true,
        )

        assertEquals(true, outcome.escalate)
        assertEquals("consecutive vehicle-read failures exceeded the configured limit", outcome.gapReason)
    }

    @Test
    fun resyncAndContinueWithTheStillRunningPollerOnlyLogsAndNeverEscalates() {
        val outcome = CxRecordingService.consecutiveFailuresOutcome(
            ObdConsecutiveFailuresPolicyMessage.RESYNC_AND_CONTINUE,
            pollerStopped = false,
        )

        assertEquals(false, outcome.escalate)
        assertEquals(
            "consecutive vehicle-read failures exceeded the configured limit, resyncing",
            outcome.gapReason,
        )
    }

    @Test
    fun resyncAndContinueOnceThePollerGaveUpEscalatesWithItsOwnDistinctReason() {
        val outcome = CxRecordingService.consecutiveFailuresOutcome(
            ObdConsecutiveFailuresPolicyMessage.RESYNC_AND_CONTINUE,
            pollerStopped = true,
        )

        assertEquals(true, outcome.escalate)
        assertEquals(
            "consecutive vehicle-read failures exceeded the configured limit, resync alone " +
                "never recovered the connection, forcing a reconnect",
            outcome.gapReason,
        )
    }
}
