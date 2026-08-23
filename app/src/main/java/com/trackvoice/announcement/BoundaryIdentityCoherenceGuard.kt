package com.trackvoice.announcement

internal enum class BoundaryIdentityDecision {
    NOT_APPLICABLE,
    DEFER_STALE_PREVIOUS,
    CONFIRMED_SAME_TRACK_RESTART,
}

/**
 * Prevents a playback-position reset with stale A metadata from becoming a
 * second A occurrence before the provider has had a bounded chance to publish
 * the real post-boundary identity.
 */
internal class BoundaryIdentityCoherenceGuard(
    private val confirmationWindowNanos: Long = DEFAULT_CONFIRMATION_WINDOW_NANOS,
) {
    private var pending: Pending? = null

    fun evaluate(
        hardBoundaryPending: Boolean,
        sameSessionRestartAllowed: Boolean,
        sameTrackAsAccepted: Boolean,
        currentPositionMs: Long?,
        boundaryKey: String,
        observedAtElapsedNanos: Long,
    ): BoundaryIdentityDecision {
        val applicable = hardBoundaryPending &&
            sameSessionRestartAllowed &&
            sameTrackAsAccepted &&
            (currentPositionMs == null || currentPositionMs <= START_POSITION_WINDOW_MS)
        if (!applicable) {
            pending = null
            return BoundaryIdentityDecision.NOT_APPLICABLE
        }

        val existing = pending
        if (existing == null || existing.boundaryKey != boundaryKey) {
            pending = Pending(boundaryKey, observedAtElapsedNanos)
            return BoundaryIdentityDecision.DEFER_STALE_PREVIOUS
        }
        if (observedAtElapsedNanos - existing.firstObservedAtElapsedNanos < confirmationWindowNanos) {
            return BoundaryIdentityDecision.DEFER_STALE_PREVIOUS
        }
        pending = null
        return BoundaryIdentityDecision.CONFIRMED_SAME_TRACK_RESTART
    }

    fun reset() {
        pending = null
    }

    fun pendingKey(): String? = pending?.boundaryKey

    private data class Pending(
        val boundaryKey: String,
        val firstObservedAtElapsedNanos: Long,
    )

    private companion object {
        const val START_POSITION_WINDOW_MS = 2_000L
        const val DEFAULT_CONFIRMATION_WINDOW_NANOS = 600L * 1_000_000L
    }
}
