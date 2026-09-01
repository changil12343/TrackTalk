package com.trackvoice.announcement

import com.trackvoice.media.PlaybackEvent
import com.trackvoice.media.PlaybackStatus
import com.trackvoice.media.QueueItemSnapshot
import com.trackvoice.media.RepeatMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateSuppressorTest {
    @Test
    fun sameMetadataEventIsSuppressed() {
        val suppressor = DuplicateSuppressor()
        val event = event()
        assertTrue(suppressor.shouldAnnounce(event, allowRepeat = false, now = 1_000L))
        suppressor.markAnnounced(event, 1_000L)
        assertFalse(suppressor.shouldAnnounce(event.copy(observedAt = 2_000L), false, 2_000L))
    }

    @Test
    fun metadataEnrichmentForSameMediaIdIsSuppressed() {
        val suppressor = DuplicateSuppressor()
        val early = event().copy(
            title = null,
            artist = null,
            album = null,
            trackNumber = null,
            totalTracks = null,
            discNumber = null,
        )
        val enriched = event()

        assertTrue(suppressor.shouldAnnounce(early, allowRepeat = false, now = 1_000L))
        suppressor.markAnnounced(early, 1_000L)
        assertFalse(suppressor.shouldAnnounce(enriched, allowRepeat = false, now = 2_000L))
    }

    @Test
    fun metadataEnrichmentDoesNotReadTheSameTrackAgain() {
        val suppressor = DuplicateSuppressor()
        val early = event().copy(artist = null)
        val enriched = event()

        assertTrue(suppressor.shouldAnnounce(early, false, 1_000L))
        suppressor.markAnnounced(early, 1_000L)
        assertFalse(suppressor.shouldAnnounce(enriched, false, 2_000L))
    }

    @Test
    fun albumOnlyMixedFrameCorrectionDoesNotCreateAnotherAnnouncement() {
        val suppressor = DuplicateSuppressor()
        val mixedFrame = event().copy(
            mediaId = null,
            title = "So Cruel",
            artist = "U2",
            album = "Room On Fire",
        )
        val corrected = mixedFrame.copy(album = "Achtung Baby")

        suppressor.markAnnounced(mixedFrame, 1_000L)

        assertTrue(AnnouncementTrackMatcher.matchesForDuplicateSuppression(mixedFrame, corrected))
        assertFalse(suppressor.shouldAnnounce(corrected, false, 1_400L))
    }

    @Test
    fun transientPreviousQueueIdForSameCoreTrackDoesNotCreateAnotherAnnouncement() {
        val suppressor = DuplicateSuppressor()
        val queue = listOf(
            QueueItemSnapshot(null, "Previous", "Other artist", queueItemId = 108L),
            QueueItemSnapshot(null, "Sulk", "Radiohead", queueItemId = 133L),
        )
        val announced = event().copy(
            mediaId = null,
            title = "Sulk",
            artist = "Radiohead",
            queue = queue,
            activeQueuePosition = 1,
        )
        val transientPreviousQueueProjection = announced.copy(
            activeQueuePosition = 0,
            observedAt = 2_000L,
        )

        suppressor.markAnnounced(announced, 1_000L)

        assertFalse(suppressor.shouldAnnounce(transientPreviousQueueProjection, false, 2_000L))
        assertFalse(suppressor.shouldAnnounce(announced.copy(observedAt = 2_001L), false, 2_001L))
    }

    @Test
    fun pendingTrackWithMissingTitleMatchesItsLaterEnrichment() {
        val early = event().copy(title = null)
        val enriched = event()

        assertTrue(AnnouncementTrackMatcher.matches(early, enriched))
        assertTrue(AnnouncementTrackMatcher.matches(enriched, early))
    }

    @Test
    fun skippedTrackDoesNotMatchPendingAlbumTrack() {
        val early = event().copy(title = null)
        val next = event().copy(
            title = "Next song",
            mediaId = "track-4",
            trackNumber = 4,
        )

        assertFalse(AnnouncementTrackMatcher.matches(early, next))
    }

    @Test
    fun trackNumberCorrectionForSameMediaIdDoesNotReadAgain() {
        val suppressor = DuplicateSuppressor()
        val early = event().copy(trackNumber = null)
        val corrected = event().copy(trackNumber = 3)

        suppressor.markAnnounced(early, 1_000L)

        assertFalse(suppressor.shouldAnnounce(corrected, false, 2_000L))
    }

    @Test
    fun changedMediaIdWithSameTrackMetadataDoesNotReadAgain() {
        val suppressor = DuplicateSuppressor()
        val early = event()
        val refreshed = event().copy(mediaId = "provider://track-3")

        suppressor.markAnnounced(early, 1_000L)

        assertFalse(suppressor.shouldAnnounce(refreshed, false, 2_000L))
    }

    @Test
    fun changedMediaIdAndCorrectedTrackNumberDoesNotReadAgain() {
        val suppressor = DuplicateSuppressor()
        val early = event().copy(trackNumber = 0)
        val corrected = event().copy(
            mediaId = "provider://canonical-track-3",
            trackNumber = 3,
        )

        suppressor.markAnnounced(early, 1_000L, announcementText = "Glass Eyes, Radiohead.")

        assertFalse(
            suppressor.shouldAnnounce(
                corrected,
                allowRepeat = false,
                now = 20_000L,
                announcementText = "트랙 3번, Glass Eyes, Radiohead.",
            ),
        )
    }

    @Test
    fun sameSongAfterControllerRecreationDoesNotReadAgain() {
        val suppressor = DuplicateSuppressor()
        val announced = event().copy(mediaId = "session-a-track-3")
        val refreshedController = announced.copy(mediaId = "session-b-track-3")

        suppressor.markAnnounced(announced, 1_000L, announcementText = "Glass Eyes, Radiohead.")

        assertFalse(
            suppressor.shouldAnnounce(
                refreshedController,
                allowRepeat = false,
                now = 2_000L,
                announcementText = "Glass Eyes, Radiohead.",
            ),
        )
    }

    @Test
    fun nextTrackAfterControllerRecreationRemainsAnnounceable() {
        val suppressor = DuplicateSuppressor()
        val announced = event().copy(mediaId = "session-a-track-3")
        val nextTrack = announced.copy(
            mediaId = "session-b-track-4",
            title = "Next song",
            trackNumber = 4,
        )

        suppressor.markAnnounced(announced, 1_000L)

        assertTrue(suppressor.shouldAnnounce(nextTrack, allowRepeat = false, now = 2_000L))
    }

    @Test
    fun restoredProcessHistorySuppressesSameTrack() {
        val firstProcess = DuplicateSuppressor()
        val secondProcess = DuplicateSuppressor()
        val announced = event().copy(mediaId = "provider://track-3")
        val refreshedAfterRestart = announced.copy(
            mediaId = "provider://session-refresh-3",
            observedAt = 2_000L,
        )

        firstProcess.markAnnounced(announced, 1_000L, announcementText = "Glass Eyes, Radiohead.")
        secondProcess.restoreAnnounced(announced, 1_000L)

        assertFalse(
            secondProcess.shouldAnnounce(
                refreshedAfterRestart,
                allowRepeat = false,
                now = 2_000L,
                announcementText = "Glass Eyes, Radiohead.",
            ),
        )
    }

    @Test
    fun restoredTrackCanBeAnnouncedAfterAConfirmedNewPlaybackOccurrence() {
        val suppressor = DuplicateSuppressor()
        val restoredTrack = event().copy(mediaId = "provider://track-a")

        suppressor.restoreAnnounced(restoredTrack, 1_000L)

        assertTrue(
            suppressor.shouldAnnounce(
                restoredTrack.copy(observedAt = 2_000L),
                allowRepeat = false,
                now = 2_000L,
                isNewPlaybackOccurrence = true,
            ),
        )
    }

    @Test
    fun adversarialRefreshBeforeHistoryTransferStillSuppressesTrack() {
        val suppressor = DuplicateSuppressor()
        val track = event()
        val metadataRefresh = track.copy(artist = null, album = null, trackNumber = null)

        assertTrue(suppressor.shouldAnnounce(track, allowRepeat = false, now = 1_000L))
        suppressor.markAnnounced(track, 1_000L)
        assertFalse(suppressor.shouldAnnounce(metadataRefresh, false, 1_001L))

        val restored = DuplicateSuppressor()
        restored.restoreAnnounced(track, 1_000L)
        assertFalse(restored.shouldAnnounce(metadataRefresh, false, 1_002L))
    }

    @Test
    fun repeatedMetadataAndPlaybackCallbacksDoNotRepeat() {
        val suppressor = DuplicateSuppressor()
        val track = event()

        assertTrue(suppressor.shouldAnnounce(track, false, 1_000L))
        suppressor.markAnnounced(track, 1_000L)
        repeat(20) { index ->
            assertFalse(
                suppressor.shouldAnnounce(
                    track.copy(observedAt = 1_001L + index),
                    allowRepeat = false,
                    now = 1_001L + index,
                ),
            )
        }
    }

    @Test
    fun announcedTrackThenNextTrackAllowsOnlyNextTrack() {
        val suppressor = DuplicateSuppressor()
        val first = event()
        val next = first.copy(title = "Next song", mediaId = "track-4", trackNumber = 4)

        assertTrue(suppressor.shouldAnnounce(first, false, 1_000L))
        suppressor.markAnnounced(first, 1_000L)
        assertFalse(suppressor.shouldAnnounce(first.copy(observedAt = 1_001L), false, 1_001L))
        assertTrue(suppressor.shouldAnnounce(next, false, 1_002L))
        suppressor.markAnnounced(next, 1_002L)
        assertFalse(suppressor.shouldAnnounce(next.copy(observedAt = 1_003L), false, 1_003L))
    }

    @Test
    fun twoCloseCallbacksOnlyFirstCanBeAccepted() {
        val suppressor = DuplicateSuppressor()
        val first = event().copy(mediaId = "queue-item-3")
        val second = first.copy(mediaId = "canonical-item-3", album = "Updated album")

        assertTrue(suppressor.shouldAnnounce(first, false, 1_000L))
        suppressor.markAnnounced(first, 1_000L)
        assertFalse(suppressor.shouldAnnounce(second, false, 1_001L))
    }

    @Test
    fun aAfterBIsEligibleNotAHistoryDuplicate() {
        val suppressor = DuplicateSuppressor()
        val first = event().copy(mediaId = "controller-a-track-3")
        val next = first.copy(title = "Next song", mediaId = "controller-b-track-4", trackNumber = 4)
        val returnedToFirst = first.copy(mediaId = "controller-a-refresh-track-3")

        suppressor.markAnnounced(first, 1_000L)
        assertTrue(suppressor.shouldAnnounce(next, false, 1_001L))
        suppressor.markAnnounced(next, 1_001L)
        assertTrue(suppressor.shouldAnnounce(returnedToFirst, false, 1_002L))
    }

    @Test
    fun aThenBThenAAnnouncesAgainWithRepeatAnnouncementsOff() {
        val suppressor = DuplicateSuppressor()
        val first = event().copy(title = "A", mediaId = "track-a")
        val second = event().copy(title = "B", mediaId = "track-b")

        assertTrue(suppressor.shouldAnnounce(first, false, 1_000L))
        suppressor.markAnnounced(first, 1_000L)
        assertTrue(suppressor.shouldAnnounce(second, false, 2_000L))
        suppressor.markAnnounced(second, 2_000L)
        assertTrue(suppressor.shouldAnnounce(first.copy(observedAt = 3_000L), false, 3_000L))
        suppressor.markAnnounced(first.copy(observedAt = 3_000L), 3_000L)
        assertFalse(suppressor.shouldAnnounce(first.copy(observedAt = 3_001L), false, 3_001L))
    }

    @Test
    fun aThenBThenCThenAAnnouncesASecondTime() {
        val suppressor = DuplicateSuppressor()
        val first = event().copy(title = "A", mediaId = "track-a")
        val second = event().copy(title = "B", mediaId = "track-b")
        val third = event().copy(title = "C", mediaId = "track-c")

        listOf(first, second, third).forEachIndexed { index, track ->
            assertTrue(suppressor.shouldAnnounce(track, false, (index + 1) * 1_000L))
            suppressor.markAnnounced(track, (index + 1) * 1_000L)
        }
        assertTrue(suppressor.shouldAnnounce(first.copy(observedAt = 4_000L), false, 4_000L))
    }

    @Test
    fun previousFromBBackToAAnnouncesAAgain() {
        val suppressor = DuplicateSuppressor()
        val first = event().copy(title = "A", mediaId = "track-a")
        val second = event().copy(title = "B", mediaId = "track-b")

        suppressor.markAnnounced(first, 1_000L)
        suppressor.markAnnounced(second, 2_000L)

        assertTrue(suppressor.shouldAnnounce(first.copy(observedAt = 3_000L), false, 3_000L))
    }

    @Test
    fun skippedPredictedTrackDoesNotBlockItsLaterGenuinePlayback() {
        val suppressor = DuplicateSuppressor()
        val first = event().copy(title = "A", mediaId = "track-a")
        val skipped = event().copy(title = "B", mediaId = "track-b")
        val later = event().copy(title = "C", mediaId = "track-c")

        assertTrue(suppressor.shouldAnnounce(first, false, 1_000L))
        suppressor.markAnnounced(first, 1_000L)

        // B was prefetched/predicted but never became the actual current track.
        assertTrue(suppressor.shouldAnnounce(skipped, false, 2_000L))
        assertTrue(suppressor.shouldAnnounce(later, false, 3_000L))
        suppressor.markAnnounced(later, 3_000L)

        // A later real transition to B remains eligible; an unspoken
        // prediction must not become historical duplicate state.
        assertTrue(suppressor.shouldAnnounce(skipped, false, 4_000L))
        suppressor.markAnnounced(skipped, 4_000L)
        assertFalse(suppressor.shouldAnnounce(skipped.copy(observedAt = 4_001L), false, 4_001L))
    }

    @Test
    fun deterministicRefreshStressNeverRepeatsAndNextTrackSpeaksOnce() {
        repeat(500) { seed ->
            val suppressor = DuplicateSuppressor()
            val first = event().copy(mediaId = "track-a-$seed")
            val next = first.copy(
                title = "Next song",
                mediaId = "track-b-$seed",
                trackNumber = 4,
            )
            val refreshes = listOf(
                first.copy(artist = null, album = null, trackNumber = null),
                first.copy(mediaId = "canonical-a-$seed", album = "Updated album"),
                first.copy(mediaId = "queue-a-$seed", activeQueuePosition = 2, queueTitle = "A Moon Shaped Pool"),
                first.copy(observedAt = seed.toLong() + 2L),
            )
            val rotation = seed % refreshes.size
            val ordered = refreshes.drop(rotation) + refreshes.take(rotation)

            assertTrue(suppressor.shouldAnnounce(first, false, 1_000L))
            suppressor.markAnnounced(first, 1_000L)
            ordered.forEachIndexed { index, refresh ->
                assertFalse(suppressor.shouldAnnounce(refresh, false, 1_001L + index))
            }
            assertTrue(suppressor.shouldAnnounce(next, false, 1_010L))
            suppressor.markAnnounced(next, 1_010L)
            assertFalse(suppressor.shouldAnnounce(next.copy(observedAt = 1_011L), false, 1_011L))
        }
    }

    @Test
    fun queueDescriptionChangingToCanonicalTitleDoesNotReadAgain() {
        val suppressor = DuplicateSuppressor()
        val queueDescription = event().copy(
            title = "Up next",
            mediaId = "queue-item-3",
            activeQueuePosition = 2,
            queueTitle = "A Moon Shaped Pool",
        )
        val canonical = event().copy(
            mediaId = "provider://canonical-track-3",
            activeQueuePosition = 2,
            queueTitle = "A Moon Shaped Pool",
        )

        suppressor.markAnnounced(queueDescription, 1_000L)

        assertFalse(suppressor.shouldAnnounce(canonical, false, 2_000L))
    }

    @Test
    fun albumCorrectionWithSameTitleAndArtistDoesNotReadAgain() {
        val suppressor = DuplicateSuppressor()
        val first = event().copy(album = "Original album")
        val corrected = event().copy(
            album = "Compilation album",
            mediaId = "provider://refreshed-track-3",
        )

        suppressor.markAnnounced(first, 1_000L)

        assertFalse(suppressor.shouldAnnounce(corrected, false, 20_000L))
    }

    @Test
    fun sameTrackFromDifferentAppsIsASeparateEvent() {
        val suppressor = DuplicateSuppressor()
        val spotify = event("com.spotify.music")
        val youtube = event("com.google.android.apps.youtube.music")
        suppressor.markAnnounced(spotify, 1_000L)
        assertTrue(suppressor.shouldAnnounce(youtube, false, 2_000L))
    }

    @Test
    fun pauseAndResumeDoesNotAnnounceAgain() {
        val suppressor = DuplicateSuppressor()
        val playing = event().copy(playbackState = PlaybackStatus.PLAYING, playbackPosition = 10_000L)
        val paused = playing.copy(playbackState = PlaybackStatus.PAUSED, playbackPosition = 20_000L)
        suppressor.markAnnounced(playing, 1_000L)
        assertFalse(suppressor.shouldAnnounce(paused, false, 2_000L))
        assertFalse(suppressor.shouldAnnounce(playing, false, 3_000L))
    }

    @Test
    fun repeatOneCycleIsSuppressedWhenRepeatAnnouncementsAreOff() {
        val suppressor = DuplicateSuppressor()
        val event = event().copy(repeatMode = RepeatMode.ONE, playbackPosition = 0L)
        suppressor.markAnnounced(event.copy(playbackPosition = 179_000L), 1_000L)

        assertFalse(
            suppressor.shouldAnnounce(
                event,
                allowRepeat = false,
                now = 2_000L,
                isNewRepeatCycle = true,
            ),
        )
    }

    @Test
    fun repeatOneCycleIsAnnouncedWhenRepeatAnnouncementsAreOn() {
        val suppressor = DuplicateSuppressor()
        val event = event().copy(repeatMode = RepeatMode.ONE, playbackPosition = 0L)
        suppressor.markAnnounced(event.copy(playbackPosition = 179_000L), 1_000L)

        assertTrue(
            suppressor.shouldAnnounce(
                event,
                allowRepeat = true,
                now = 2_000L,
                isNewRepeatCycle = true,
            ),
        )
    }

    @Test
    fun repeatFlagWithoutExplicitNewCycleDoesNotRepeat() {
        val suppressor = DuplicateSuppressor()
        val event = event().copy(repeatMode = RepeatMode.ONE)
        suppressor.markAnnounced(event, 1_000L)

        assertFalse(suppressor.shouldAnnounce(event.copy(observedAt = 2_000L), true, 2_000L))
    }

    @Test
    fun identicalSpokenTextFromASecondEventIsSuppressedBriefly() {
        val suppressor = DuplicateSuppressor()
        val first = event().copy(title = "First song", mediaId = "track-1")
        val second = event().copy(title = "Second song", mediaId = "track-2")

        suppressor.markAnnounced(first, 1_000L, announcementText = "Now playing.")

        assertFalse(suppressor.shouldAnnounce(second, false, 2_000L, "Now playing."))
        assertFalse(suppressor.shouldAnnounce(second, false, 7_000L, "Now playing."))
        assertTrue(suppressor.shouldAnnounce(second, false, 13_001L, "Now playing."))
    }

    @Test
    fun sameTrackFromAnotherMediaSessionIsSuppressedWhileSpeechIsActive() {
        val suppressor = DuplicateSuppressor()
        val first = event("com.google.android.apps.youtube.music")
        val second = first.copy(
            sourcePackageName = "com.google.android.youtube",
            mediaId = "different-session-id",
            album = "Updated album metadata",
        )

        suppressor.markAnnounced(first, 1_000L, announcementText = "Glass Eyes, Radiohead.")

        assertFalse(
            suppressor.shouldAnnounce(
                second,
                allowRepeat = false,
                now = 4_000L,
                announcementText = "Glass Eyes, Radiohead, Album Updated album metadata.",
            ),
        )
    }

    @Test
    fun differentDiscOrTrackNumberIsNotTheSameFingerprint() {
        val suppressor = DuplicateSuppressor()
        val first = event().copy(mediaId = null, trackNumber = 1, discNumber = 1)
        val second = event().copy(mediaId = null, trackNumber = 1, discNumber = 2)

        suppressor.markAnnounced(first, 1_000L)

        assertTrue(suppressor.shouldAnnounce(second, false, 2_000L))
    }

    private fun event(packageName: String = "com.spotify.music") = PlaybackEvent(
        sourcePackageName = packageName,
        sourceAppName = packageName,
        title = "Glass Eyes",
        artist = "Radiohead",
        album = "A Moon Shaped Pool",
        albumArtist = "Radiohead",
        trackNumber = 3,
        totalTracks = 11,
        discNumber = 1,
        duration = 180_000L,
        mediaId = "track-3",
        playbackState = PlaybackStatus.PLAYING,
        playbackPosition = 0L,
        observedAt = 1L,
    )
}
