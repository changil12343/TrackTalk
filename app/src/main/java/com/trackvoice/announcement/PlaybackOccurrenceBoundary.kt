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
    fun allowsSameTrackRestart(
        boundary: PlaybackOccurrenceBoundary?,
        currentSessionKey: String?,
        currentTrack: PlaybackEvent?,
    ): Boolean {
        if (boundary == null || currentSessionKey == null || currentTrack == null) return false
        if (!currentTrack.isPlaying || boundary.sessionKey != currentSessionKey) return false
        return AnnouncementTrackMatcher.matchesForDuplicateSuppression(
            expected = boundary.track,
            current = currentTrack,
            requireSameSource = true,
        )
    }
}
