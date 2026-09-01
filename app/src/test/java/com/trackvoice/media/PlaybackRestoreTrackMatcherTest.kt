package com.trackvoice.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            PlaybackRestoreTrackMatch.METADATA_ENRICHMENT,
            PlaybackRestoreTrackMatcher.classify(
                initial.copy(
                    album = "Album B",
                    activeQueuePosition = 1,
                ),
                token,
            ),
        )
    }

    @Test
    fun samsungAlbumOnlyMixedFrameCorrectionIsMetadataEnrichment() {
        val mixedFrame = event(
            title = "So Cruel",
            artist = "U2",
            album = "Room On Fire",
            mediaId = null,
        )
        val token = token().copy(
            fingerprint = TrackFingerprint.core(mixedFrame),
            mediaId = null,
            title = mixedFrame.title,
            artist = mixedFrame.artist,
            album = mixedFrame.album,
        )
        val corrected = mixedFrame.copy(album = "Achtung Baby")

        assertEquals(TrackFingerprint.core(mixedFrame), TrackFingerprint.core(corrected))
        assertNotEquals(TrackFingerprint.announcement(mixedFrame), TrackFingerprint.announcement(corrected))
        assertEquals(
            PlaybackRestoreTrackMatch.METADATA_ENRICHMENT,
            PlaybackRestoreTrackMatcher.classify(corrected, token),
        )
        assertTrue(PlaybackRestoreTrackMatcher.matches(corrected, token))
    }

    @Test
    fun samsungTransientPreviousQueueIdCannotInvalidateStableCoreTrack() {
        val current = event(
            title = "Sulk",
            artist = "Radiohead",
            album = "The Bends",
            mediaId = null,
            queue = listOf(
                QueueItemSnapshot(
                    mediaId = null,
                    title = "Altogether",
                    artist = "Slowdive",
                    queueItemId = 108L,
                ),
                QueueItemSnapshot(
                    mediaId = null,
                    title = "Sulk",
                    artist = "Radiohead",
                    queueItemId = 133L,
                ),
            ),
            activeQueuePosition = 1,
        )
        val token = token().copy(
            fingerprint = TrackFingerprint.core(current),
            mediaId = null,
            title = current.title,
            artist = current.artist,
            album = current.album,
            queueItemId = PlaybackRestoreTrackMatcher.queueItemIdForPause(current),
        )
        val transientPreviousQueueProjection = current.copy(activeQueuePosition = 0)

        assertEquals(133L, token.queueItemId)
        assertNull(PlaybackRestoreTrackMatcher.queueItemIdForPause(transientPreviousQueueProjection))
        assertEquals(
            PlaybackRestoreTrackMatch.MATCH,
            PlaybackRestoreTrackMatcher.classify(transientPreviousQueueProjection, token),
        )
        assertEquals(PlaybackRestoreTrackMatch.MATCH, PlaybackRestoreTrackMatcher.classify(current, token))
    }

    @Test
    fun isolatedCoherentQueueIdMismatchCannotOutvoteExactCoreIdentity() {
        val current = event(
            title = "Sulk",
            artist = "Radiohead",
            album = "The Bends",
            mediaId = null,
            queue = listOf(
                QueueItemSnapshot(null, "Sulk", "Radiohead", queueItemId = 108L),
                QueueItemSnapshot(null, "Sulk", "Radiohead", queueItemId = 133L),
            ),
            activeQueuePosition = 1,
        )
        val token = token().copy(
            fingerprint = TrackFingerprint.core(current),
            mediaId = null,
            title = current.title,
            artist = current.artist,
            album = current.album,
            queueItemId = PlaybackRestoreTrackMatcher.queueItemIdForPause(current),
        )

        assertEquals(
            PlaybackRestoreTrackMatch.MATCH,
            PlaybackRestoreTrackMatcher.classify(current.copy(activeQueuePosition = 0), token),
        )
    }

    @Test
    fun coherentQueueChangeStillRejectsProviderIdentityReplacement() {
        val current = event(
            title = "Sulk",
            artist = "Radiohead",
            album = "The Bends",
            mediaId = "provider-sulk-1",
            queue = listOf(
                QueueItemSnapshot("provider-sulk-1", "Sulk", "Radiohead", queueItemId = 133L),
                QueueItemSnapshot("provider-sulk-2", "Sulk", "Radiohead", queueItemId = 134L),
            ),
            activeQueuePosition = 0,
        )
        val token = token().copy(
            fingerprint = TrackFingerprint.core(current),
            mediaId = current.mediaId,
            title = current.title,
            artist = current.artist,
            album = current.album,
            queueItemId = PlaybackRestoreTrackMatcher.queueItemIdForPause(current),
        )
        val replacementOccurrence = current.copy(
            mediaId = "provider-sulk-2",
            activeQueuePosition = 1,
        )

        assertEquals(
            PlaybackRestoreTrackMatch.MISMATCH,
            PlaybackRestoreTrackMatcher.classify(replacementOccurrence, token),
        )
    }

    @Test
    fun trueTitleArtistAndMediaIdTransitionInvalidatesOldTrack() {
        val trackB = event(
            title = "Track B",
            artist = "Artist B",
            album = "Album B",
            mediaId = "track-b",
        )
        val token = token().copy(
            fingerprint = TrackFingerprint.core(trackB),
            mediaId = trackB.mediaId,
            title = trackB.title,
            artist = trackB.artist,
            album = trackB.album,
        )
        val trackC = trackB.copy(
            title = "Track C",
            artist = "Artist C",
            album = "Album C",
            mediaId = "track-c",
        )

        assertNotEquals(TrackFingerprint.core(trackB), TrackFingerprint.core(trackC))
        assertEquals(
            PlaybackRestoreTrackMatch.MISMATCH,
            PlaybackRestoreTrackMatcher.classify(trackC, token),
        )
    }

    @Test
    fun sameMediaIdCannotHideATrueTitleChange() {
        assertEquals(
            PlaybackRestoreTrackMatch.MISMATCH,
            PlaybackRestoreTrackMatcher.classify(
                event(title = "Replacement title"),
                token(),
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
