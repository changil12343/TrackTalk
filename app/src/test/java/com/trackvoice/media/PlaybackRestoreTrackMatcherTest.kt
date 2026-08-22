package com.trackvoice.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRestoreTrackMatcherTest {
    @Test
    fun replacementSessionWithoutTitleIsInsufficientRatherThanTrackChange() {
        assertEquals(
            PlaybackRestoreTrackMatch.INSUFFICIENT,
            PlaybackRestoreTrackMatcher.classify(
                event(title = null, artist = null, album = null, mediaId = null),
                token(),
            ),
        )
    }

    @Test
    fun replacementSessionWithSameTrackAndNewMediaIdMatches() {
        assertEquals(
            PlaybackRestoreTrackMatch.MATCH,
            PlaybackRestoreTrackMatcher.classify(
                event(mediaId = "provider-recreated-id"),
                token(),
            ),
        )
    }

    @Test
    fun replacementSessionWithDifferentTitleIsRejected() {
        assertEquals(
            PlaybackRestoreTrackMatch.MISMATCH,
            PlaybackRestoreTrackMatcher.classify(
                event(title = "Another song", mediaId = "another-id"),
                token(),
            ),
        )
    }

    @Test
    fun laggingQueuePositionIsNotCapturedAndMetadataSettlementStillMatches() {
        val initial = event(
            title = "Song B",
            artist = "Artist B",
            album = null,
            mediaId = null,
            queue = listOf(
                QueueItemSnapshot(
                    mediaId = null,
                    title = "Song A",
                    artist = "Artist A",
                    queueItemId = 1L,
                ),
                QueueItemSnapshot(
                    mediaId = null,
                    title = "Song B",
                    artist = "Artist B",
                    queueItemId = 2L,
                ),
            ),
            activeQueuePosition = 0,
        )
        val token = token().copy(
            fingerprint = TrackFingerprint.announcement(initial),
            mediaId = null,
            title = initial.title,
            artist = initial.artist,
            album = initial.album,
            queueItemId = PlaybackRestoreTrackMatcher.queueItemIdForPause(initial),
        )

        assertNull(token.queueItemId)
        assertEquals(
            PlaybackRestoreTrackMatch.MATCH,
            PlaybackRestoreTrackMatcher.classify(
                initial.copy(
                    album = "Album B",
                    activeQueuePosition = 1,
                ),
                token,
            ),
        )
    }

    private fun token() = PlaybackPauseToken(
        sessionKey = "music.app:old-session",
        fingerprint = "old-fingerprint",
        sourcePackageName = "music.app",
        mediaId = "provider-old-id",
        title = "Song A",
        artist = "Artist",
        album = "Album",
    )

    private fun event(
        title: String? = "Song A",
        artist: String? = "Artist",
        album: String? = "Album",
        mediaId: String? = "provider-old-id",
        queue: List<QueueItemSnapshot> = emptyList(),
        activeQueuePosition: Int? = null,
    ) = PlaybackEvent(
        sourcePackageName = "music.app",
        sourceAppName = "Music",
        title = title,
        artist = artist,
        album = album,
        albumArtist = null,
        trackNumber = null,
        totalTracks = null,
        discNumber = null,
        duration = 180_000L,
        mediaId = mediaId,
        playbackState = PlaybackStatus.STOPPED,
        playbackPosition = 0L,
        queue = queue,
        observedAt = 1L,
        activeQueuePosition = activeQueuePosition,
    )
}
