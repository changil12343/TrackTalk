package com.trackvoice.announcement

import com.trackvoice.media.PlaybackEvent
import com.trackvoice.media.PlaybackPauseToken
import com.trackvoice.media.PlaybackRestoreTrackMatcher
import com.trackvoice.media.PlaybackStatus

internal enum class PlaybackRestoreCycleState {
    ARMED,
    RESTORE_REQUESTED,
}

internal enum class PlaybackRestorePlayerObservation {
    IGNORED,
    OWNED_PAUSE_CONFIRMED,
    DUPLICATE_OR_STALE,
    INTERVENING_PLAYBACK,
    NEWER_PAUSE_OR_STOP_INTENT,
}

internal enum class PlaybackRestoreTrigger {
    TTS_COMPLETED,
    TTS_ERROR,
    TTS_INTERRUPTED,
    WATCHDOG_TIMEOUT,
    ANNOUNCEMENT_CANCELLED,
    AUDIO_FOCUS_FAILED,
    NON_PAUSE_MODE,
    CONTROLLER_DETACH,
    CONTROLLER_CLOSE,
}

internal data class PlaybackRestoreCycle(
    val id: Long,
    val pauseToken: PlaybackPauseToken,
    val track: PlaybackEvent?,
    val sessionGeneration: Long,
    val armedAtElapsedNanos: Long,
    val transitionAtElapsedNanos: Long?,
    var state: PlaybackRestoreCycleState = PlaybackRestoreCycleState.ARMED,
    var speechGeneration: Long? = null,
    var pausedObservedAtElapsedNanos: Long? = null,
    var pausedStateUpdatedAtElapsedNanos: Long? = null,
    var playingObservedAfterOwnedPauseAtElapsedNanos: Long? = null,
    var playingStateUpdatedAtElapsedNanos: Long? = null,
    var newerPlaybackIntentObservedAtElapsedNanos: Long? = null,
    var newerPlaybackIntentReason: String? = null,
    var restoreTrigger: PlaybackRestoreTrigger? = null,
)

/**
 * Owns exactly one temporary playback-restore obligation.
 *
 * The state is intentionally independent from pending metadata and TTS jobs:
 * once TrackTalk successfully issues PAUSE, harmless callback churn must not
 * erase the obligation to restore the same logical playback.
 */
internal class PlaybackRestoreObligation {
    private var nextCycleId = 0L
    private var active: PlaybackRestoreCycle? = null

    fun reserveCycleId(): Long = ++nextCycleId

    fun activeCycle(): PlaybackRestoreCycle? = active

    fun arm(
        cycleId: Long,
        pauseToken: PlaybackPauseToken,
        track: PlaybackEvent?,
        sessionGeneration: Long,
        armedAtElapsedNanos: Long,
        transitionAtElapsedNanos: Long?,
    ): PlaybackRestoreCycle {
        check(active == null) { "A playback restore obligation is already active" }
        return PlaybackRestoreCycle(
            id = cycleId,
            pauseToken = pauseToken,
            track = track,
            sessionGeneration = sessionGeneration,
            armedAtElapsedNanos = armedAtElapsedNanos,
            transitionAtElapsedNanos = transitionAtElapsedNanos,
        ).also { active = it }
    }

    fun bindSpeech(cycleId: Long, generation: Long): Boolean {
        val cycle = active?.takeIf { it.id == cycleId } ?: return false
        if (cycle.state != PlaybackRestoreCycleState.ARMED) return false
        cycle.speechGeneration = generation
        return true
    }

    fun markPlayerPaused(cycleId: Long, observedAtElapsedNanos: Long): Boolean {
        return observePlayerState(
            cycleId = cycleId,
            playbackStatus = PlaybackStatus.PAUSED,
            observedAtElapsedNanos = observedAtElapsedNanos,
            stateUpdatedAtElapsedNanos = null,
        ) == PlaybackRestorePlayerObservation.OWNED_PAUSE_CONFIRMED
    }

    fun markPlayerPlayingAfterOwnedPause(cycleId: Long, observedAtElapsedNanos: Long): Boolean {
        return observePlayerState(
            cycleId = cycleId,
            playbackStatus = PlaybackStatus.PLAYING,
            observedAtElapsedNanos = observedAtElapsedNanos,
            stateUpdatedAtElapsedNanos = null,
        ) == PlaybackRestorePlayerObservation.INTERVENING_PLAYBACK
    }

    fun hasInterveningPlaybackIntent(cycleId: Long): Boolean =
        active?.takeIf { it.id == cycleId }
            ?.newerPlaybackIntentReason != null

    fun newerPlaybackIntentReason(cycleId: Long): String? =
        active?.takeIf { it.id == cycleId }?.newerPlaybackIntentReason

    /**
     * Classifies ordered player states while TrackTalk owns a pause.
     *
     * The first matching PAUSED state acknowledges TrackTalk's command. A
     * later PLAYING state, or a genuinely newer PAUSED/STOPPED state, is newer
     * player/user intent. Duplicate callbacks and controller recreation often
     * replay the same PlaybackState timestamp, so those remain harmless.
     */
    fun observePlayerState(
        cycleId: Long,
        playbackStatus: PlaybackStatus,
        observedAtElapsedNanos: Long,
        stateUpdatedAtElapsedNanos: Long?,
    ): PlaybackRestorePlayerObservation {
        val cycle = active?.takeIf { it.id == cycleId }
            ?: return PlaybackRestorePlayerObservation.IGNORED
        if (cycle.state != PlaybackRestoreCycleState.ARMED) {
            return PlaybackRestorePlayerObservation.IGNORED
        }
        val stateUpdatedAt = stateUpdatedAtElapsedNanos?.takeIf { it > 0L }
        return when (playbackStatus) {
            PlaybackStatus.PAUSED -> observePaused(
                cycle = cycle,
                observedAtElapsedNanos = observedAtElapsedNanos,
                stateUpdatedAtElapsedNanos = stateUpdatedAt,
            )

            PlaybackStatus.PLAYING -> observePlaying(
                cycle = cycle,
                observedAtElapsedNanos = observedAtElapsedNanos,
                stateUpdatedAtElapsedNanos = stateUpdatedAt,
            )

            PlaybackStatus.STOPPED -> {
                val baseline = cycle.playingStateUpdatedAtElapsedNanos
                    ?: cycle.pausedStateUpdatedAtElapsedNanos
                    ?: cycle.pauseToken.pauseRequestedAtElapsedNanos.takeIf { it > 0L }
                if (!isNewerState(stateUpdatedAt, baseline, observedAtElapsedNanos, cycle.armedAtElapsedNanos)) {
                    PlaybackRestorePlayerObservation.DUPLICATE_OR_STALE
                } else {
                    markNewerIntent(cycle, observedAtElapsedNanos, "STOPPED_AFTER_OWNED_PAUSE")
                    PlaybackRestorePlayerObservation.NEWER_PAUSE_OR_STOP_INTENT
                }
            }

            PlaybackStatus.BUFFERING,
            PlaybackStatus.NONE,
            -> PlaybackRestorePlayerObservation.DUPLICATE_OR_STALE
        }
    }

    private fun observePaused(
        cycle: PlaybackRestoreCycle,
        observedAtElapsedNanos: Long,
        stateUpdatedAtElapsedNanos: Long?,
    ): PlaybackRestorePlayerObservation {
        if (cycle.pausedObservedAtElapsedNanos == null) {
            cycle.pausedObservedAtElapsedNanos = observedAtElapsedNanos
            cycle.pausedStateUpdatedAtElapsedNanos = stateUpdatedAtElapsedNanos
            return PlaybackRestorePlayerObservation.OWNED_PAUSE_CONFIRMED
        }

        val baseline = cycle.playingStateUpdatedAtElapsedNanos
            ?: cycle.pausedStateUpdatedAtElapsedNanos
        val fallbackBaseline = cycle.playingObservedAfterOwnedPauseAtElapsedNanos
            ?: cycle.pausedObservedAtElapsedNanos
            ?: return PlaybackRestorePlayerObservation.DUPLICATE_OR_STALE
        if (stateUpdatedAtElapsedNanos == null && cycle.playingObservedAfterOwnedPauseAtElapsedNanos == null) {
            return PlaybackRestorePlayerObservation.DUPLICATE_OR_STALE
        }
        if (!isNewerState(stateUpdatedAtElapsedNanos, baseline, observedAtElapsedNanos, fallbackBaseline)) {
            return PlaybackRestorePlayerObservation.DUPLICATE_OR_STALE
        }

        val reason = if (cycle.playingObservedAfterOwnedPauseAtElapsedNanos != null) {
            "PAUSED_AFTER_INTERVENING_PLAYBACK"
        } else {
            "NEWER_PAUSED_STATE_AFTER_OWNED_PAUSE"
        }
        markNewerIntent(cycle, observedAtElapsedNanos, reason)
        return PlaybackRestorePlayerObservation.NEWER_PAUSE_OR_STOP_INTENT
    }

    private fun observePlaying(
        cycle: PlaybackRestoreCycle,
        observedAtElapsedNanos: Long,
        stateUpdatedAtElapsedNanos: Long?,
    ): PlaybackRestorePlayerObservation {
        val pausedObservedAt = cycle.pausedObservedAtElapsedNanos
            ?: return PlaybackRestorePlayerObservation.DUPLICATE_OR_STALE
        if (cycle.playingObservedAfterOwnedPauseAtElapsedNanos != null) {
            return PlaybackRestorePlayerObservation.DUPLICATE_OR_STALE
        }
        if (!isNewerState(
                currentStateUpdatedAt = stateUpdatedAtElapsedNanos,
                baselineStateUpdatedAt = cycle.pausedStateUpdatedAtElapsedNanos,
                currentObservedAt = observedAtElapsedNanos,
                fallbackBaselineObservedAt = pausedObservedAt,
            )
        ) {
            return PlaybackRestorePlayerObservation.DUPLICATE_OR_STALE
        }
        cycle.playingObservedAfterOwnedPauseAtElapsedNanos = observedAtElapsedNanos
        cycle.playingStateUpdatedAtElapsedNanos = stateUpdatedAtElapsedNanos
        markNewerIntent(cycle, observedAtElapsedNanos, "PLAYING_AFTER_OWNED_PAUSE")
        return PlaybackRestorePlayerObservation.INTERVENING_PLAYBACK
    }

    private fun markNewerIntent(
        cycle: PlaybackRestoreCycle,
        observedAtElapsedNanos: Long,
        reason: String,
    ) {
        if (cycle.newerPlaybackIntentReason != null) return
        cycle.newerPlaybackIntentObservedAtElapsedNanos = observedAtElapsedNanos
        cycle.newerPlaybackIntentReason = reason
    }

    private fun isNewerState(
        currentStateUpdatedAt: Long?,
        baselineStateUpdatedAt: Long?,
        currentObservedAt: Long,
        fallbackBaselineObservedAt: Long,
    ): Boolean = when {
        currentStateUpdatedAt != null && baselineStateUpdatedAt != null ->
            currentStateUpdatedAt > baselineStateUpdatedAt
        currentStateUpdatedAt != null ->
            currentStateUpdatedAt > fallbackBaselineObservedAt
        else -> currentObservedAt > fallbackBaselineObservedAt
    }

    fun requestRestore(
        cycleId: Long,
        trigger: PlaybackRestoreTrigger,
        speechGeneration: Long? = null,
    ): PlaybackRestoreCycle? {
        val cycle = active?.takeIf { it.id == cycleId } ?: return null
        if (speechGeneration != null && cycle.speechGeneration != speechGeneration) return null
        if (cycle.state != PlaybackRestoreCycleState.ARMED) return null
        if (cycle.newerPlaybackIntentReason != null) return null
        cycle.state = PlaybackRestoreCycleState.RESTORE_REQUESTED
        cycle.restoreTrigger = trigger
        return cycle
    }

    fun complete(cycleId: Long): PlaybackRestoreCycle? {
        val cycle = active?.takeIf { it.id == cycleId } ?: return null
        active = null
        return cycle
    }

    fun cancel(cycleId: Long): PlaybackRestoreCycle? = complete(cycleId)

    fun matchesActiveTrack(event: PlaybackEvent): Boolean =
        active?.let { PlaybackRestoreTrackMatcher.matches(event, it.pauseToken) } == true

}

internal object PlaybackRestoreWatchdogPolicy {
    const val MIN_TIMEOUT_MS = 8_000L
    const val MAX_TIMEOUT_MS = 30_000L
    private const val MILLIS_PER_CHARACTER = 90L
    private const val COMPLETION_MARGIN_MS = 5_000L

    fun timeoutMs(textLength: Int, speechRate: Float): Long {
        val safeRate = speechRate.coerceIn(0.5f, 2f)
        val estimatedSpeechMs = (textLength.coerceAtLeast(1) * MILLIS_PER_CHARACTER / safeRate).toLong()
        return (estimatedSpeechMs + COMPLETION_MARGIN_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }
}
