package com.trackvoice.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumTrackNumberResolverTest {
    @Test
    fun playerMetadataTrackNumberIsNotRejectedByShortProviderQueue() {
        val event = event(
            trackNumber = 4,
            totalTracks = null,
            queue = listOf(
                QueueItemSnapshot(mediaId = "current", title = "Track 4", artist = "Artist"),
                QueueItemSnapshot(mediaId = "next", title = "Track 9", artist = "Artist"),
            ),
            trackNumberSource = TrackNumberSource.MEDIA_METADATA,
        )

        assertEquals(4, AlbumTrackNumberResolver.resolve(event))
    }

    @Test
    fun missingCanonicalTrackNumberIsNotDerivedFromQueuePosition() {
        val event = event(
            trackNumber = null,
            queue = listOf(
                QueueItemSnapshot(mediaId = "current", title = "Track 1", artist = "Artist"),
                QueueItemSnapshot(mediaId = "next", title = "Track 2", artist = "Artist"),
            ),
            activeQueuePosition = 1,
        )

        assertNull(AlbumTrackNumberResolver.resolve(event))
    }

    private fun event(
        trackNumber: Int?,
        totalTracks: Int? = null,
        queue: List<QueueItemSnapshot> = emptyList(),
        activeQueuePosition: Int? = null,
        trackNumberSource: TrackNumberSource = TrackNumberSource.UNSPECIFIED,
    ) = PlaybackEvent(
        sourcePackageName = "com.example.player",
        sourceAppName = "Player",
        title = "Track",
        artist = "Artist",
        album = "Album",
        albumArtist = "Artist",
        trackNumber = trackNumber,
        totalTracks = totalTracks,
        discNumber = 1,
        duration = 180_000L,
        mediaId = "track",
        playbackState = PlaybackStatus.PLAYING,
        playbackPosition = 0L,
        queue = queue,
        observedAt = 1L,
        activeQueuePosition = activeQueuePosition,
        trackNumberSource = trackNumberSource,
    )
}
