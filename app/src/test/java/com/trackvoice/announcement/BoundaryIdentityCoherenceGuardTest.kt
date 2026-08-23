package com.trackvoice.announcement

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundaryIdentityCoherenceGuardTest {
    @Test
    fun stalePreviousAIsDeferredUntilBMetadataCanSettle() {
        val guard = BoundaryIdentityCoherenceGuard(confirmationWindowNanos = 600_000_000L)

        val first = guard.evaluate(
            hardBoundaryPending = true,
            sameSessionRestartAllowed = true,
            sameTrackAsAccepted = true,
            currentPositionMs = 0L,
            boundaryKey = "session|A|B",
            observedAtElapsedNanos = 1_000_000_000L,
        )
        val seventyOneMsLater = guard.evaluate(
            hardBoundaryPending = true,
            sameSessionRestartAllowed = true,
            sameTrackAsAccepted = true,
            currentPositionMs = 71L,
            boundaryKey = "session|A|B",
            observedAtElapsedNanos = 1_071_000_000L,
        )

        assertEquals(BoundaryIdentityDecision.DEFER_STALE_PREVIOUS, first)
        assertEquals(BoundaryIdentityDecision.DEFER_STALE_PREVIOUS, seventyOneMsLater)
    }

    @Test
    fun persistentSameAEventuallyBecomesAGenuineRestart() {
        val guard = BoundaryIdentityCoherenceGuard(confirmationWindowNanos = 600_000_000L)
        val args = { observed: Long ->
            guard.evaluate(
                hardBoundaryPending = true,
                sameSessionRestartAllowed = true,
                sameTrackAsAccepted = true,
                currentPositionMs = 500L,
                boundaryKey = "session|A|B",
                observedAtElapsedNanos = observed,
            )
        }

        assertEquals(BoundaryIdentityDecision.DEFER_STALE_PREVIOUS, args(1_000_000_000L))
        assertEquals(BoundaryIdentityDecision.CONFIRMED_SAME_TRACK_RESTART, args(1_600_000_000L))
    }

    @Test
    fun actualBImmediatelyClearsThePendingAConfirmation() {
        val guard = BoundaryIdentityCoherenceGuard()
        guard.evaluate(
            hardBoundaryPending = true,
            sameSessionRestartAllowed = true,
            sameTrackAsAccepted = true,
            currentPositionMs = 0L,
            boundaryKey = "session|A|B",
            observedAtElapsedNanos = 1_000_000_000L,
        )

        val result = guard.evaluate(
            hardBoundaryPending = true,
            sameSessionRestartAllowed = true,
            sameTrackAsAccepted = false,
            currentPositionMs = 0L,
            boundaryKey = "session|B|C",
            observedAtElapsedNanos = 1_071_000_000L,
        )

        assertEquals(BoundaryIdentityDecision.NOT_APPLICABLE, result)
        assertEquals(null, guard.pendingKey())
    }
}
