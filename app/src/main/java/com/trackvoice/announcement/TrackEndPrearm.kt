package com.trackvoice.announcement

import com.trackvoice.media.PlaybackEvent
import kotlin.math.abs

/**
 * Safe duration-derived preparation timing. This is deliberately not a media-boundary
 * authority: it only says when TrackTalk may warm its own metadata/TTS preparation while the
 * current track is still confirmed playing. It never represents a new track and never grants
 * permission to request focus, duck, pause, resume, or speak.
 */
internal object TrackEndPrearmTiming {
    /** A short preparation window keeps the armed state near the estimated boundary. */
    const val TRACK_END_PREARM_MS = 2_000L

    // Short cues and very long/indefinite sessions are intentionally left on the reactive path.
    internal const val MIN_USABLE_DURATION_MS = 10_000L
    internal const val MAX_USABLE_DURATION_MS = 6L * 60L * 60L * 1_000L
    internal const val RESCHEDULE_TOLERANCE_MS = 750L
    internal const val SPEED_CHANGE_TOLERANCE = 0.01f
}

internal enum class DurationPredictionUnavailableReason {
    NOT_PLAYING,
    DURATION_KEY_MISSING,
    DURATION_INVALID,
    DURATION_TOO_SHORT,
    LIVE_OR_INDEFINITE,
    POSITION_UNAVAILABLE,
    POSITION_UPDATE_TIME_UNAVAILABLE,
    PLAYBACK_SPEED_INVALID,
    POSITION_AT_OR_AFTER_DURATION,
}

internal data class TrackEndPrearmPlan(
    val durationMs: Long,
    val estimatedPositionMs: Long,
    val remainingMs: Long,
    val playbackSpeed: Float,
    val predictedTrackEndElapsedMs: Long,
    val prearmAtElapsedMs: Long,
)

internal sealed interface TrackEndPrearmPlanResult {
    data class Available(val plan: TrackEndPrearmPlan) : TrackEndPrearmPlanResult

    data class Unavailable(
        val reason: DurationPredictionUnavailableReason,
        val durationMetadataPresent: Boolean,
    ) : TrackEndPrearmPlanResult
}

/** Deterministic position extrapolation for a currently playing MediaSession snapshot. */
internal object TrackEndPrearmPlanner {
    fun plan(
        event: PlaybackEvent,
        nowElapsedMs: Long,
        prearmLeadMs: Long = TrackEndPrearmTiming.TRACK_END_PREARM_MS,
    ): TrackEndPrearmPlanResult {
        if (!event.isPlaying) {
            return unavailable(DurationPredictionUnavailableReason.NOT_PLAYING, event)
        }
        if (!event.durationMetadataPresent) {
            return unavailable(DurationPredictionUnavailableReason.DURATION_KEY_MISSING, event)
        }
        val durationMs = event.duration
            ?: return unavailable(DurationPredictionUnavailableReason.DURATION_INVALID, event)
        when {
            durationMs <= 0L -> {
                return unavailable(DurationPredictionUnavailableReason.DURATION_INVALID, event)
            }

            durationMs < TrackEndPrearmTiming.MIN_USABLE_DURATION_MS -> {
                return unavailable(DurationPredictionUnavailableReason.DURATION_TOO_SHORT, event)
            }

            durationMs > TrackEndPrearmTiming.MAX_USABLE_DURATION_MS -> {
                return unavailable(DurationPredictionUnavailableReason.LIVE_OR_INDEFINITE, event)
            }
        }
        val positionMs = event.playbackPosition
            ?: return unavailable(DurationPredictionUnavailableReason.POSITION_UNAVAILABLE, event)
        val positionUpdatedAtElapsedMs = event.playbackStateUpdateElapsedMs
            ?: return unavailable(DurationPredictionUnavailableReason.POSITION_UPDATE_TIME_UNAVAILABLE, event)
        val playbackSpeed = event.playbackSpeed ?: 1f
        if (!playbackSpeed.isFinite() || playbackSpeed <= 0f) {
            return unavailable(DurationPredictionUnavailableReason.PLAYBACK_SPEED_INVALID, event)
        }

        // PlaybackState.lastPositionUpdateTime shares elapsedRealtime's monotonic time base.
        // A paused/buffering event has already returned above, so only confirmed PLAYING time is
        // extrapolated. A future timestamp is treated as zero elapsed time rather than allowing a
        // clock/reporting skew to move the position backwards.
        val elapsedSinceUpdateMs = (nowElapsedMs - positionUpdatedAtElapsedMs).coerceAtLeast(0L)
        val progressedMs = (elapsedSinceUpdateMs.toDouble() * playbackSpeed.toDouble())
            .coerceAtMost(Long.MAX_VALUE.toDouble())
            .toLong()
        val estimatedPositionMs = saturatingAdd(positionMs.coerceAtLeast(0L), progressedMs)
        if (estimatedPositionMs >= durationMs) {
            return unavailable(DurationPredictionUnavailableReason.POSITION_AT_OR_AFTER_DURATION, event)
        }

        val remainingMs = durationMs - estimatedPositionMs
        val predictedTrackEndElapsedMs = saturatingAdd(nowElapsedMs, remainingMs)
        val prearmAtElapsedMs = (predictedTrackEndElapsedMs - prearmLeadMs.coerceAtLeast(0L))
            .coerceAtLeast(nowElapsedMs)
        return TrackEndPrearmPlanResult.Available(
            TrackEndPrearmPlan(
                durationMs = durationMs,
                estimatedPositionMs = estimatedPositionMs,
                remainingMs = remainingMs,
                playbackSpeed = playbackSpeed,
                predictedTrackEndElapsedMs = predictedTrackEndElapsedMs,
                prearmAtElapsedMs = prearmAtElapsedMs,
            ),
        )
    }

    private fun unavailable(
        reason: DurationPredictionUnavailableReason,
        event: PlaybackEvent,
    ) = TrackEndPrearmPlanResult.Unavailable(
        reason = reason,
        durationMetadataPresent = event.durationMetadataPresent,
    )

    private fun saturatingAdd(first: Long, second: Long): Long = when {
        second <= 0L -> first
        first > Long.MAX_VALUE - second -> Long.MAX_VALUE
        else -> first + second
    }
}

/**
 * In-memory identity for one duration prediction. It intentionally includes the monitor and
 * controller generations so a listener reconnect or controller replacement cannot inherit a
 * timer created by an older callback.
 */
internal data class DurationPrearmIdentity(
    val monitorGeneration: Long,
    val logicalSessionGeneration: Long,
    val sessionKey: String?,
    val controllerGeneration: Long?,
    val sourcePackageName: String,
    val trackKey: String,
)

internal data class DurationPrearmPrediction(
    val token: Long,
    val identity: DurationPrearmIdentity,
    val anchorEvent: PlaybackEvent,
    val plan: TrackEndPrearmPlan,
    val prearmedAtElapsedMs: Long? = null,
)

internal data class DurationPrearmCancellation(
    val prediction: DurationPrearmPrediction,
    val reason: String,
)

internal sealed interface DurationPrearmAction {
    object Unchanged : DurationPrearmAction

    data class Schedule(val prediction: DurationPrearmPrediction) : DurationPrearmAction

    data class PrearmNow(val prediction: DurationPrearmPrediction) : DurationPrearmAction

    data class Unavailable(
        val reason: DurationPredictionUnavailableReason,
        val durationMetadataPresent: Boolean,
    ) : DurationPrearmAction
}

internal data class DurationPrearmReconcileResult(
    val cancelled: DurationPrearmCancellation? = null,
    val action: DurationPrearmAction,
)

/**
 * Owns at most one memory-only prediction. The controller owns the coroutine that waits for the
 * returned schedule; this coordinator only makes that coroutine reject stale track/session work.
 */
internal class DurationPrearmCoordinator(
    private val prearmLeadMs: Long = TrackEndPrearmTiming.TRACK_END_PREARM_MS,
) {
    private var nextToken = 0L
    private var activePrediction: DurationPrearmPrediction? = null

    fun reconcile(
        identity: DurationPrearmIdentity,
        event: PlaybackEvent,
        nowElapsedMs: Long,
    ): DurationPrearmReconcileResult {
        val evaluation = TrackEndPrearmPlanner.plan(event, nowElapsedMs, prearmLeadMs)
        if (evaluation is TrackEndPrearmPlanResult.Unavailable) {
            return DurationPrearmReconcileResult(
                cancelled = cancel("DURATION_${evaluation.reason.name}"),
                action = DurationPrearmAction.Unavailable(
                    reason = evaluation.reason,
                    durationMetadataPresent = evaluation.durationMetadataPresent,
                ),
            )
        }

        val plan = (evaluation as TrackEndPrearmPlanResult.Available).plan
        val previous = activePrediction
        if (previous != null && previous.identity == identity) {
            if (samePlan(previous.plan, plan)) {
                return if (previous.prearmedAtElapsedMs == null && plan.prearmAtElapsedMs <= nowElapsedMs) {
                    DurationPrearmReconcileResult(action = DurationPrearmAction.PrearmNow(previous))
                } else {
                    DurationPrearmReconcileResult(action = DurationPrearmAction.Unchanged)
                }
            }
            val cancellation = cancel(replacementReason(previous.plan, plan))
            return newPrediction(identity, event, plan, nowElapsedMs, cancellation)
        }

        val cancellation = previous?.let { cancel(identityChangeReason(it.identity, identity)) }
        return newPrediction(identity, event, plan, nowElapsedMs, cancellation)
    }

    fun cancel(reason: String): DurationPrearmCancellation? {
        val previous = activePrediction ?: return null
        activePrediction = null
        return DurationPrearmCancellation(previous, reason)
    }

    fun markPrearmed(token: Long, nowElapsedMs: Long): DurationPrearmPrediction? {
        val current = activePrediction ?: return null
        if (current.token != token || current.prearmedAtElapsedMs != null) return null
        return current.copy(prearmedAtElapsedMs = nowElapsedMs).also { activePrediction = it }
    }

    fun isCurrent(token: Long, identity: DurationPrearmIdentity): Boolean =
        activePrediction?.let { it.token == token && it.identity == identity } == true

    fun current(): DurationPrearmPrediction? = activePrediction

    private fun newPrediction(
        identity: DurationPrearmIdentity,
        event: PlaybackEvent,
        plan: TrackEndPrearmPlan,
        nowElapsedMs: Long,
        cancelled: DurationPrearmCancellation?,
    ): DurationPrearmReconcileResult {
        val prediction = DurationPrearmPrediction(
            token = ++nextToken,
            identity = identity,
            anchorEvent = event,
            plan = plan,
        )
        activePrediction = prediction
        val action = if (plan.prearmAtElapsedMs <= nowElapsedMs) {
            DurationPrearmAction.PrearmNow(prediction)
        } else {
            DurationPrearmAction.Schedule(prediction)
        }
        return DurationPrearmReconcileResult(cancelled = cancelled, action = action)
    }

    private fun samePlan(first: TrackEndPrearmPlan, second: TrackEndPrearmPlan): Boolean =
        first.durationMs == second.durationMs &&
            abs(first.playbackSpeed - second.playbackSpeed) <= TrackEndPrearmTiming.SPEED_CHANGE_TOLERANCE &&
            abs(first.predictedTrackEndElapsedMs - second.predictedTrackEndElapsedMs) <=
            TrackEndPrearmTiming.RESCHEDULE_TOLERANCE_MS

    private fun replacementReason(
        previous: TrackEndPrearmPlan,
        next: TrackEndPrearmPlan,
    ): String = when {
        previous.durationMs != next.durationMs -> "DURATION_CHANGED"
        abs(previous.playbackSpeed - next.playbackSpeed) > TrackEndPrearmTiming.SPEED_CHANGE_TOLERANCE ->
            "PLAYBACK_SPEED_CHANGED"
        else -> "PLAYBACK_POSITION_CHANGED"
    }

    private fun identityChangeReason(
        previous: DurationPrearmIdentity,
        next: DurationPrearmIdentity,
    ): String = when {
        previous.monitorGeneration != next.monitorGeneration -> "MONITOR_GENERATION_CHANGED"
        previous.logicalSessionGeneration != next.logicalSessionGeneration -> "LOGICAL_SESSION_GENERATION_CHANGED"
        previous.sessionKey != next.sessionKey -> "ACTIVE_SESSION_CHANGED"
        previous.controllerGeneration != next.controllerGeneration -> "CONTROLLER_GENERATION_CHANGED"
        previous.sourcePackageName != next.sourcePackageName -> "SOURCE_CHANGED"
        previous.trackKey != next.trackKey -> "TRACK_IDENTITY_CHANGED"
        else -> "PREDICTION_REPLACED"
    }
}
