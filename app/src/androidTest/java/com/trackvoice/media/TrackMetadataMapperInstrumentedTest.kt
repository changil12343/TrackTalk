package com.trackvoice.media

import android.media.MediaMetadata
import android.media.MediaDescription
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackvoice.announcement.AnnouncementPolicy
import com.trackvoice.data.AnnouncementOutputPolicy
import com.trackvoice.data.AnnouncementReadField
import com.trackvoice.data.UserSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackMetadataMapperInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var session: MediaSession

    @Before
    fun setUp() {
        session = MediaSession(context, "TrackVoiceInstrumentationTest")
        session.isActive = true
    }

    @After
    fun tearDown() {
        session.release()
    }

    @Test
    fun mapsMediaSessionMetadataPlaybackAndQueue() {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Glass Eyes")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Radiohead")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "A Moon Shaped Pool")
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, "Radiohead")
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "track-3")
                .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, 3L)
                .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, 11L)
                .putLong(MediaMetadata.METADATA_KEY_DISC_NUMBER, 1L)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 180_000L)
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 12_345L, 1f)
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .build(),
        )
        session.setQueue(
            listOf(
                MediaSession.QueueItem(
                    MediaDescription.Builder()
                        .setMediaId("track-3")
                        .setTitle("Glass Eyes")
                        .setSubtitle("Radiohead")
                        .build(),
                    3L,
                ),
            ),
        )

        val controller = MediaController(context, session.sessionToken)
        val event = TrackMetadataMapper { "Test Music" }.map(controller, observedAt = 123L)

        assertEquals("com.trackvoice", event.sourcePackageName)
        assertEquals("Test Music", event.sourceAppName)
        assertEquals("Glass Eyes", event.title)
        assertEquals("Radiohead", event.artist)
        assertEquals("A Moon Shaped Pool", event.album)
        assertEquals(3, event.trackNumber)
        assertEquals(TrackNumberSource.MEDIA_METADATA, event.trackNumberSource)
        assertEquals(11, event.totalTracks)
        assertEquals(1, event.discNumber)
        assertEquals(180_000L, event.duration)
        assertTrue(event.durationMetadataPresent)
        assertEquals("track-3", event.mediaId)
        assertEquals(PlaybackStatus.PLAYING, event.playbackState)
        assertEquals(1f, event.playbackSpeed)
        assertTrue("playing position should not move backwards", event.playbackPosition!! >= 12_345L)
        assertTrue("playing position should remain close to the seeded value", event.playbackPosition!! < 20_000L)
        assertEquals(123L, event.observedAt)
        assertEquals(1, event.queue.size)
        assertEquals("Glass Eyes", event.queue.single().title)
        assertEquals("Radiohead", event.queue.single().artist)
    }

    @Test
    fun mapsDisplayTitleWhenPlayerOmitsCanonicalTitleKeys() {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, "Display-only song")
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, "Display-only artist")
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .build(),
        )

        val controller = MediaController(context, session.sessionToken)
        val event = TrackMetadataMapper { "Test Music" }.map(controller, observedAt = 234L)

        assertEquals("Display-only song", event.title)
        assertEquals("Display-only artist", event.artist)
    }

    @Test
    fun displayTitleFallbackFlowsThroughAlbumAnnouncementPipeline() {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, "Display-only song")
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, "Display-only artist")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Display-only album")
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "display-track-3")
                .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, 3L)
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .build(),
        )

        val event = TrackMetadataMapper { "Test Music" }
            .map(MediaController(context, session.sessionToken), observedAt = 235L)
        val decision = AnnouncementPolicy.decide(
            event = event,
            userSettings = UserSettings(
                outputPolicy = AnnouncementOutputPolicy.ALL_OUTPUTS,
                defaultReadFields = listOf(
                    AnnouncementReadField.ALBUM,
                    AnnouncementReadField.TITLE,
                    AnnouncementReadField.ARTIST,
                ),
            ),
            appSettings = null,
            externalAudioOutput = true,
            collectionOverride = PlaybackCollection.ALBUM,
        )

        assertTrue(decision.shouldAnnounce)
        assertEquals(
            "Display-only album, Display-only song, Display-only artist.",
            decision.text,
        )
    }

    @Test
    fun missingMetadataProducesEmptyOptionalFields() {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, 0L, 0f)
                .build(),
        )

        val controller = MediaController(context, session.sessionToken)
        val event = TrackMetadataMapper { "Test Music" }.map(controller, observedAt = 456L)

        assertEquals("com.trackvoice", event.sourcePackageName)
        assertEquals(PlaybackStatus.PAUSED, event.playbackState)
        assertEquals(456L, event.observedAt)
        assertEquals(null, event.title)
        assertEquals(null, event.artist)
        assertEquals(null, event.trackNumber)
        assertEquals(null, event.duration)
        assertFalse(event.durationMetadataPresent)
        assertNotNull(event.queue)
        assertEquals(0, event.queue.size)
    }

    @Test
    fun publishedZeroDurationRemainsDistinctFromAMissingDurationKey() {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Indefinite stream")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 0L)
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 1_000L, 1f)
                .build(),
        )

        val event = TrackMetadataMapper { "Test Music" }
            .map(MediaController(context, session.sessionToken), observedAt = 457L)

        assertTrue(event.durationMetadataPresent)
        assertEquals(null, event.duration)
    }

    @Test
    fun usesActiveQueueDescriptionWhenMetadataIsMissing() {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, 0L, 0f)
                .setActiveQueueItemId(7L)
                .build(),
        )
        session.setQueue(
            listOf(
                MediaSession.QueueItem(
                    MediaDescription.Builder()
                        .setMediaId("queue-track-7")
                        .setTitle("Queue Song")
                        .setSubtitle("Queue Artist")
                        .build(),
                    7L,
                ),
            ),
        )

        val controller = MediaController(context, session.sessionToken)
        val event = TrackMetadataMapper { "Test Music" }.map(controller, observedAt = 789L)

        assertEquals("Queue Song", event.title)
        assertEquals("Queue Artist", event.artist)
        assertEquals("queue-track-7", event.mediaId)
    }

    @Test
    fun mapsQueueTitleAndActiveQueuePosition() {
        session.setQueueTitle("Morning playlist")
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .setActiveQueueItemId(2L)
                .build(),
        )
        session.setQueue(
            listOf(
                MediaSession.QueueItem(
                    MediaDescription.Builder().setMediaId("first").setTitle("First").build(),
                    1L,
                ),
                MediaSession.QueueItem(
                    MediaDescription.Builder().setMediaId("second").setTitle("Second").build(),
                    2L,
                ),
                MediaSession.QueueItem(
                    MediaDescription.Builder().setMediaId("third").setTitle("Third").build(),
                    3L,
                ),
            ),
        )

        val controller = MediaController(context, session.sessionToken)
        val event = TrackMetadataMapper { "Test Music" }.map(controller, observedAt = 999L)

        assertEquals("Morning playlist", event.queueTitle)
        assertEquals(1, event.activeQueuePosition)
    }

    @Test
    fun derivesActiveQueuePositionFromMetadataMediaIdWhenQueueItemIdIsMissing() {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "second")
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Second")
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .build(),
        )
        session.setQueue(
            listOf(
                MediaSession.QueueItem(
                    MediaDescription.Builder().setMediaId("first").setTitle("First").build(),
                    1L,
                ),
                MediaSession.QueueItem(
                    MediaDescription.Builder().setMediaId("second").setTitle("Second").build(),
                    2L,
                ),
            ),
        )

        val controller = MediaController(context, session.sessionToken)
        val event = TrackMetadataMapper { "Test Music" }.map(controller, observedAt = 1_111L)

        assertEquals(1, event.activeQueuePosition)
    }

    @Test
    fun derivesActiveQueuePositionFromMetadataTitleWhenPlayerOmitsIds() {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Second")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Artist")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Album")
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .setActiveQueueItemId(-1L)
                .build(),
        )
        session.setQueue(
            listOf(
                queueItemWithMetadata("first", "First", "Artist", "Album", 1L),
                queueItemWithMetadata("second", "Second", "Artist", "Album", 2L),
                queueItemWithMetadata("third", "Third", "Artist", "Album", 3L),
            ),
        )

        val controller = MediaController(context, session.sessionToken)
        val event = TrackMetadataMapper { "Test Music" }.map(controller, observedAt = 1_150L)

        assertEquals(1, event.activeQueuePosition)
    }

    @Test
    fun mapsAlbumAndTrackNumberFromQueueItemExtras() {
        val extras = Bundle().apply {
            putString(MediaMetadata.METADATA_KEY_ALBUM, "Queue Album")
            putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, 4L)
        }
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "queue-track-4")
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Queue Song")
                // Some players expose the shuffled queue position here rather
                // than the original album track number.
                .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, 1L)
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .setActiveQueueItemId(4L)
                .build(),
        )
        session.setQueue(
            listOf(
                MediaSession.QueueItem(
                    MediaDescription.Builder()
                        .setMediaId("queue-track-4")
                        .setTitle("Queue Song")
                        .setSubtitle("Queue Artist")
                        .setExtras(extras)
                        .build(),
                    4L,
                ),
            ),
        )

        val controller = MediaController(context, session.sessionToken)
        val event = TrackMetadataMapper { "Test Music" }.map(controller, observedAt = 1_212L)

        assertEquals("Queue Album", event.queue.single().album)
        assertEquals(4, event.queue.single().trackNumber)
        assertEquals(4, event.trackNumber)
        assertEquals(TrackNumberSource.QUEUE_ITEM_METADATA, event.trackNumberSource)
        assertTrue(event.trackNumberReliable)
    }

    @Test
    fun keepsKnownAlbumTrackNumberWhenQueueOrderChanges() {
        val mapper = TrackMetadataMapper { "Test Music" }
        session.setQueueTitle("Album queue")
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "target")
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Target")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Queue Album")
                .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, 4L)
                .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, 11L)
                .build(),
        )
        session.setQueue(
            listOf(
                queueItem("first", "First", 1L),
                queueItem("target", "Target", 2L),
                queueItem("third", "Third", 3L),
            ),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .setActiveQueueItemId(2L)
                .build(),
        )
        assertEquals(4, mapper.map(MediaController(context, session.sessionToken)).trackNumber)

        session.setQueue(
            listOf(
                queueItem("target", "Target", 2L),
                queueItem("first", "First", 1L),
                queueItem("third", "Third", 3L),
            ),
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "target")
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Target")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Queue Album")
                .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, 1L)
                .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, 11L)
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .setActiveQueueItemId(2L)
                .build(),
        )

        val event = mapper.map(MediaController(context, session.sessionToken))
        assertTrue(event.queueOrderChanged)
        assertEquals(4, event.trackNumber)
        assertEquals(TrackNumberSource.CACHED_QUEUE_ITEM_METADATA, event.trackNumberSource)
        assertTrue(event.trackNumberReliable)
    }

    @Test
    fun usesCachedQueueItemTrackNumberWhenCurrentQueuePositionCannotBeResolved() {
        val mapper = TrackMetadataMapper { "Test Music" }
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "target")
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Target")
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .setActiveQueueItemId(7L)
                .build(),
        )
        session.setQueue(
            listOf(
                MediaSession.QueueItem(
                    MediaDescription.Builder()
                        .setMediaId("target")
                        .setTitle("Target")
                        .setExtras(Bundle().apply {
                            putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, 4L)
                        })
                        .build(),
                    7L,
                ),
            ),
        )
        mapper.map(MediaController(context, session.sessionToken), observedAt = 1_300L)

        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .setActiveQueueItemId(-1L)
                .build(),
        )
        session.setQueue(
            listOf(
                MediaSession.QueueItem(
                    MediaDescription.Builder()
                        .setMediaId(null)
                        .setTitle("Unmatched queue item")
                        .build(),
                    8L,
                ),
            ),
        )

        val event = mapper.map(MediaController(context, session.sessionToken), observedAt = 1_301L)
        assertEquals(4, event.trackNumber)
        assertEquals(TrackNumberSource.CACHED_QUEUE_ITEM_METADATA, event.trackNumberSource)
    }

    private fun queueItem(mediaId: String, title: String, queueId: Long) = MediaSession.QueueItem(
        MediaDescription.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .build(),
        queueId,
    )

    private fun queueItemWithMetadata(
        mediaId: String,
        title: String,
        artist: String,
        album: String,
        trackNumber: Long,
    ) = MediaSession.QueueItem(
        MediaDescription.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .setSubtitle(artist)
            .setExtras(Bundle().apply {
                putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, trackNumber)
            })
            .build(),
        trackNumber,
    )
}
