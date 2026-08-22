package com.trackvoice.announcement

import com.trackvoice.media.PlaybackEvent
import com.trackvoice.media.PlaybackPauseToken
import com.trackvoice.media.PlaybackRestoreTrackMatcher

internal enum class PlaybackRestoreCycleState {
    ARMED,
    RESTORE_REQUESTED,
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
    var playingObservedAfterOwnedPauseAtElapsedNanos: Long? = null,
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
        val cycle = active?.takeIf { it.id == cycleId } ?: return false
        if (cycle.pausedObservedAtElapsedNanos != null) return false
        cycle.pausedObservedAtElapsedNanos = observedAtElapsedNanos
        return true
    }

    fun markPlayerPlayingAfterOwnedPause(cycleId: Long, observedAtElapsedNanos: Long): Boolean {
        val cycle = active?.takeIf { it.id == cycleId } ?: return false
        if (cycle.state != PlaybackRestoreCycleState.ARMED) return false
        if (cycle.pausedObservedAtElapsedNanos == null) return false
        if (cycle.playingObservedAfterOwnedPauseAtElapsedNanos != null) return false
        cycle.playingObservedAfterOwnedPauseAtElapsedNanos = observedAtElapsedNanos
        return true
    }

    fun hasInterveningPlaybackIntent(cycleId: Long): Boolean =
        active?.takeIf { it.id == cycleId }
            ?.playingObservedAfterOwnedPauseAtElapsedNanos != null

    fun requestRestore(
        cycleId: Long,
        trigger: PlaybackRestoreTrigger,
        speechGeneration: Long? = null,
    ): PlaybackRestoreCycle? {
        val cycle = active?.takeIf { it.id == cycleId } ?: return null
        if (speechGeneration != null && cycle.speechGeneration != speechGeneration) return null
        if (cycle.state != PlaybackRestoreCycleState.ARMED) return null
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
