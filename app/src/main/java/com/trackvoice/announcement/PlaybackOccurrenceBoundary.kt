package com.trackvoice.announcement

import com.trackvoice.media.PlaybackEvent

/**
 * A confirmed STOPPED state may make the same track eligible when that exact
 * MediaSession starts it again. This is playback evidence, not infrastructure
 * identity: listener/controller generations must never create or satisfy it.
 */
internal data class PlaybackOccurrenceBoundary(
    val sessionKey: String,
    val track: PlaybackEvent,
)

internal object PlaybackOccurrenceBoundaryPolicy {
    /**
     * A same-track occurrence can restart only at the beginning of a new
     * playback run. A later-position PLAYING snapshot after STOPPED may be a
     * provider's stale previous-track frame while a direct selection settles.
     */
    fun hasRestartStartPosition(playbackPositionMs: Long?): Boolean =
        playbackPositionMs != null &&
            playbackPositionMs in 0L..SAME_TRACK_RESTART_START_POSITION_WINDOW_MS

    fun allowsSameTrackRestart(
        boundary: PlaybackOccurrenceBoundary?,
        currentSessionKey: String?,
        currentTrack: PlaybackEvent?,
    ): Boolean {
        if (boundary == null || currentSessionKey == null || currentTrack == null) return false
        if (!currentTrack.isPlaying || boundary.sessionKey != currentSessionKey) return false
        if (!hasRestartStartPosition(currentTrack.playbackPosition)) return false
        return AnnouncementTrackMatcher.matchesForDuplicateSuppression(
            expected = boundary.track,
            current = currentTrack,
            requireSameSource = true,
        )
    }

    private const val SAME_TRACK_RESTART_START_POSITION_WINDOW_MS = 2_000L
}
