package com.trackvoice.announcement

import com.trackvoice.media.PlaybackEvent
import com.trackvoice.media.PlaybackPauseToken
import com.trackvoice.media.PlaybackRestoreTrackMatcher
import com.trackvoice.media.PlaybackStatus

internal enum class PlaybackRestoreCycleState {
    ARMED,
    RESTORE_REQUESTED,
}

internal enum class PlaybackRestoreReadiness {
    WAITING_FOR_BOTH,
    WAITING_FOR_TTS,
    WAITING_FOR_PAUSE_ACK,
    READY,
}

internal enum class PlaybackRestorePlayerObservation {
    IGNORED,
    OWNED_PAUSE_CONFIRMED,
    DUPLICATE_OR_STALE,
    INTERVENING_PLAYBACK,
    NEWER_PAUSE_OR_STOP_INTENT,
}

internal enum class PlaybackPauseAcknowledgementEvidence {
    STATE_TIMESTAMP_AFTER_COMMAND,
    ORDERED_CALLBACK_FROM_PLAYING_BASELINE,
    LOCAL_POST_COMMAND_CALLBACK,
}

internal enum class PlaybackPauseAcknowledgementRejectReason {
    NOT_PLAYBACK_STATE_CALLBACK,
    CALLBACK_NOT_AFTER_PAUSE_COMMAND,
    PRE_COMMAND_LOCAL_EVENT,
    SOURCE_TIMESTAMP_BEFORE_PLAYING_BASELINE,
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

/**
 * Memory-only authority to undo one pause that TrackTalk actually issued.
 *
 * This object is deliberately not serializable and is never written to the
 * repository. A process, listener, monitor, controller, session, or track
 * replacement must create a new observation baseline, never recover this
 * authority.
 */
internal data class PlaybackRestoreLease(
    val id: Long,
    val pauseToken: PlaybackPauseToken,
    val track: PlaybackEvent?,
    val monitorGeneration: Long,
    val sessionGeneration: Long,
    val controllerGeneration: Long,
    val sessionIdentity: String,
    val trackIdentity: String,
    val armedAtElapsedNanos: Long,
    val transitionAtElapsedNanos: Long?,
    val trackTalkActuallyPausedPlayback: Boolean = true,
    var state: PlaybackRestoreCycleState = PlaybackRestoreCycleState.ARMED,
    var speechGeneration: Long? = null,
    /** A current TTS success/error/interruption has reached this transaction. */
    var ttsCompleted: Boolean = false,
    /** A matching post-command PAUSED update acknowledged TrackTalk's pause. */
    var pauseAcknowledged: Boolean = false,
    var pauseAcknowledgementEvidence: PlaybackPauseAcknowledgementEvidence? = null,
    var pauseAcknowledgementRejectReason: PlaybackPauseAcknowledgementRejectReason? = null,
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
    private var active: PlaybackRestoreLease? = null

    fun reserveCycleId(): Long = ++nextCycleId

    fun activeLease(): PlaybackRestoreLease? = active

    fun arm(
        cycleId: Long,
        pauseToken: PlaybackPauseToken,
        track: PlaybackEvent?,
        monitorGeneration: Long,
        sessionGeneration: Long,
        controllerGeneration: Long,
        armedAtElapsedNanos: Long,
        transitionAtElapsedNanos: Long?,
    ): PlaybackRestoreLease {
        check(active == null) { "A playback restore obligation is already active" }
        check(pauseToken.controllerGeneration == controllerGeneration) {
            "The restore lease must use the controller that accepted TrackTalk's pause"
        }
        return PlaybackRestoreLease(
            id = cycleId,
            pauseToken = pauseToken,
            track = track,
            monitorGeneration = monitorGeneration,
            sessionGeneration = sessionGeneration,
            controllerGeneration = controllerGeneration,
            sessionIdentity = pauseToken.sessionKey,
            trackIdentity = pauseToken.fingerprint,
            armedAtElapsedNanos = armedAtElapsedNanos,
            transitionAtElapsedNanos = transitionAtElapsedNanos,
        ).also { active = it }
    }

    fun bindSpeech(cycleId: Long, generation: Long): Boolean {
        val cycle = active?.takeIf { it.id == cycleId } ?: return false
        if (cycle.state != PlaybackRestoreCycleState.ARMED) return false
        if (cycle.ttsCompleted) return false
        if (cycle.speechGeneration != null && cycle.speechGeneration != generation) return false
        cycle.speechGeneration = generation
        return true
    }

    fun newerPlaybackIntentReason(cycleId: Long): String? =
        active?.takeIf { it.id == cycleId }?.newerPlaybackIntentReason

    /**
     * Classifies ordered player states while TrackTalk owns a pause.
     *
     * The first matching, locally ordered PAUSED callback acknowledges
     * TrackTalk's command. A later PLAYING state, or a genuinely newer
     * PAUSED/STOPPED state, is newer player/user intent. Provider timestamps
     * are diagnostic/auxiliary stale evidence, not command provenance.
     */
    fun observePlayerState(
        cycleId: Long,
        playbackStatus: PlaybackStatus,
        observedAtElapsedNanos: Long,
        stateUpdatedAtElapsedNanos: Long?,
        isPlaybackStateCallback: Boolean = false,
        eventSequenceNumber: Long? = null,
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
                isPlaybackStateCallback = isPlaybackStateCallback,
                eventSequenceNumber = eventSequenceNumber,
            )

            PlaybackStatus.PLAYING -> observePlaying(
                cycle = cycle,
                observedAtElapsedNanos = observedAtElapsedNanos,
                stateUpdatedAtElapsedNanos = stateUpdatedAt,
            )

            PlaybackStatus.STOPPED -> {
                markNewerIntent(cycle, observedAtElapsedNanos, "STOPPED_DURING_RESTORE_LEASE")
                PlaybackRestorePlayerObservation.NEWER_PAUSE_OR_STOP_INTENT
            }

            PlaybackStatus.NONE -> {
                markNewerIntent(cycle, observedAtElapsedNanos, "NONE_DURING_RESTORE_LEASE")
                PlaybackRestorePlayerObservation.NEWER_PAUSE_OR_STOP_INTENT
            }

            PlaybackStatus.BUFFERING -> PlaybackRestorePlayerObservation.DUPLICATE_OR_STALE
        }
    }

    private fun observePaused(
        cycle: PlaybackRestoreLease,
        observedAtElapsedNanos: Long,
        stateUpdatedAtElapsedNanos: Long?,
        isPlaybackStateCallback: Boolean,
        eventSequenceNumber: Long?,
    ): PlaybackRestorePlayerObservation {
        if (cycle.pausedObservedAtElapsedNanos == null) {
            val pauseRequestedAt = cycle.pauseToken.pauseRequestedAtElapsedNanos
            val playingBaseline = cycle.pauseToken.playbackStateUpdatedAtPauseElapsedNanos
            val rejection = when {
                !isPlaybackStateCallback ->
                    PlaybackPauseAcknowledgementRejectReason.NOT_PLAYBACK_STATE_CALLBACK
                observedAtElapsedNanos <= pauseRequestedAt ->
                    PlaybackPauseAcknowledgementRejectReason.CALLBACK_NOT_AFTER_PAUSE_COMMAND
                eventSequenceNumber == null ||
                    eventSequenceNumber <= cycle.pauseToken.eventSequenceNumberAtPauseCommand ->
                    PlaybackPauseAcknowledgementRejectReason.PRE_COMMAND_LOCAL_EVENT
                stateUpdatedAtElapsedNanos != null &&
                    playingBaseline != null &&
                    stateUpdatedAtElapsedNanos < playingBaseline ->
                    PlaybackPauseAcknowledgementRejectReason.SOURCE_TIMESTAMP_BEFORE_PLAYING_BASELINE
                else -> null
            }
            if (rejection != null) {
                cycle.pauseAcknowledgementRejectReason = rejection
                return PlaybackRestorePlayerObservation.DUPLICATE_OR_STALE
            }
            val evidence = when {
                stateUpdatedAtElapsedNanos != null && stateUpdatedAtElapsedNanos >= pauseRequestedAt ->
                    PlaybackPauseAcknowledgementEvidence.STATE_TIMESTAMP_AFTER_COMMAND
                stateUpdatedAtElapsedNanos != null && playingBaseline != null ->
                    PlaybackPauseAcknowledgementEvidence.ORDERED_CALLBACK_FROM_PLAYING_BASELINE
                else -> PlaybackPauseAcknowledgementEvidence.LOCAL_POST_COMMAND_CALLBACK
            }
            cycle.pausedObservedAtElapsedNanos = observedAtElapsedNanos
            cycle.pausedStateUpdatedAtElapsedNanos = stateUpdatedAtElapsedNanos
            cycle.pauseAcknowledged = true
            cycle.pauseAcknowledgementEvidence = evidence
            cycle.pauseAcknowledgementRejectReason = null
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
        cycle: PlaybackRestoreLease,
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
        cycle: PlaybackRestoreLease,
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

    fun markTtsCompleted(
        cycleId: Long,
        trigger: PlaybackRestoreTrigger,
        speechGeneration: Long? = null,
    ): PlaybackRestoreLease? {
        val cycle = active?.takeIf { it.id == cycleId } ?: return null
        if (!cycle.trackTalkActuallyPausedPlayback) return null
        if (trigger !in RESTORE_COMPLETION_TRIGGERS) return null
        if (speechGeneration != null && cycle.speechGeneration != speechGeneration) return null
        if (cycle.state != PlaybackRestoreCycleState.ARMED) return null
        if (cycle.newerPlaybackIntentReason != null) return null
        if (cycle.ttsCompleted) return null
        cycle.ttsCompleted = true
        cycle.restoreTrigger = trigger
        return cycle
    }

    fun readiness(cycleId: Long): PlaybackRestoreReadiness? {
        val cycle = active?.takeIf { it.id == cycleId } ?: return null
        if (cycle.state != PlaybackRestoreCycleState.ARMED) return null
        if (cycle.newerPlaybackIntentReason != null) return null
        return when {
            cycle.ttsCompleted && cycle.pauseAcknowledged -> PlaybackRestoreReadiness.READY
            cycle.ttsCompleted -> PlaybackRestoreReadiness.WAITING_FOR_PAUSE_ACK
            cycle.pauseAcknowledged -> PlaybackRestoreReadiness.WAITING_FOR_TTS
            else -> PlaybackRestoreReadiness.WAITING_FOR_BOTH
        }
    }

    fun claimRestoreIfReady(cycleId: Long): PlaybackRestoreLease? {
        val cycle = active?.takeIf { it.id == cycleId } ?: return null
        if (readiness(cycleId) != PlaybackRestoreReadiness.READY) return null
        cycle.state = PlaybackRestoreCycleState.RESTORE_REQUESTED
        return cycle
    }

    fun validateLease(
        cycleId: Long,
        monitorGeneration: Long,
        sessionGeneration: Long,
        sessionKey: String?,
        controllerGeneration: Long?,
        event: PlaybackEvent?,
    ): String? {
        val lease = active?.takeIf { it.id == cycleId } ?: return "NO_ACTIVE_LEASE"
        if (!lease.trackTalkActuallyPausedPlayback) return "PAUSE_NOT_ISSUED_BY_TRACKTALK"
        if (lease.monitorGeneration != monitorGeneration) return "MONITOR_GENERATION_CHANGED"
        if (lease.sessionGeneration != sessionGeneration) return "SESSION_GENERATION_CHANGED"
        if (sessionKey == null || lease.sessionIdentity != sessionKey) return "SESSION_IDENTITY_CHANGED"
        if (controllerGeneration == null || lease.controllerGeneration != controllerGeneration) {
            return "CONTROLLER_GENERATION_CHANGED"
        }
        val current = event ?: return "ACTIVE_SESSION_UNAVAILABLE"
        if (!PlaybackRestoreTrackMatcher.matches(current, lease.pauseToken)) return "TRACK_IDENTITY_CHANGED"
        return when (current.playbackState) {
            PlaybackStatus.STOPPED -> "PLAYBACK_STOPPED"
            PlaybackStatus.NONE -> "PLAYBACK_NONE"
            PlaybackStatus.BUFFERING -> "PLAYBACK_STATE_AMBIGUOUS"
            PlaybackStatus.PLAYING,
            PlaybackStatus.PAUSED,
            -> null
        }
    }

    fun complete(cycleId: Long): PlaybackRestoreLease? {
        val cycle = active?.takeIf { it.id == cycleId } ?: return null
        active = null
        return cycle
    }

    fun cancel(cycleId: Long): PlaybackRestoreLease? = complete(cycleId)

    fun matchesActiveTrack(event: PlaybackEvent): Boolean =
        active?.let { PlaybackRestoreTrackMatcher.matches(event, it.pauseToken) } == true

    private companion object {
        val RESTORE_COMPLETION_TRIGGERS = setOf(
            PlaybackRestoreTrigger.TTS_COMPLETED,
            PlaybackRestoreTrigger.TTS_ERROR,
            PlaybackRestoreTrigger.TTS_INTERRUPTED,
            PlaybackRestoreTrigger.AUDIO_FOCUS_FAILED,
        )
    }

}

internal object PlaybackRestoreWatchdogPolicy {
    const val MIN_TIMEOUT_MS = 8_000L
    const val MAX_TIMEOUT_MS = 30_000L
    /** Safety expiry only: expiration discards authority and never sends PLAY. */
    const val PAUSE_ACKNOWLEDGEMENT_EXPIRY_MS = 8_000L
    private const val MILLIS_PER_CHARACTER = 90L
    private const val COMPLETION_MARGIN_MS = 5_000L

    fun timeoutMs(textLength: Int, speechRate: Float): Long {
        val safeRate = speechRate.coerceIn(0.5f, 2f)
        val estimatedSpeechMs = (textLength.coerceAtLeast(1) * MILLIS_PER_CHARACTER / safeRate).toLong()
        return (estimatedSpeechMs + COMPLETION_MARGIN_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }
}
