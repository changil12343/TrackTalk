package com.trackvoice.media

import android.content.ComponentName
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackvoice.TrackVoiceApplication
import com.trackvoice.service.TrackVoiceNotificationListenerService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MediaSessionMonitorInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val application: TrackVoiceApplication
        get() = context.applicationContext as TrackVoiceApplication
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pauseCommandCount = AtomicInteger(0)
    private val playCommandCount = AtomicInteger(0)
    private val ignoredPlayCommandsRemaining = AtomicInteger(0)
    private lateinit var session: MediaSession

    @Before
    fun setUp() {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        val trackTalkListener = ComponentName(
            context,
            TrackVoiceNotificationListenerService::class.java,
        ).flattenToString()
        assumeTrue(
            "TrackTalk notification access is required for MediaSessionMonitor integration tests",
            enabledListeners.split(':').any { it == trackTalkListener },
        )

        // The target Application is alive during instrumentation and its normal
        // notification-listener monitor would also observe this fake session.
        // Isolate transport-command assertions to the monitor under test.
        application.controller.detachNotificationListener(preservePlaybackHistory = true)
        session = MediaSession(context, "TrackTalkMonitorInstrumentationTest")
        installSessionCallback(session)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Delayed Resume Test")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "TrackTalk")
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "delayed-resume-test")
                .build(),
        )
        session.setQueueTitle("Test playlist")
        setState(PlaybackState.STATE_PLAYING)
        session.isActive = true
    }

    @After
    fun tearDown() {
        if (::session.isInitialized) session.release()
        mainHandler.removeCallbacksAndMessages(null)
        application.controller.attachNotificationListener()
        application.controller.attachMediaSessionMonitor(context)
    }

    @Test
    fun resumesWhenMediaAppPublishesPauseAfterResumeWasRequested() {
        val monitor = MediaSessionMonitor(context) {}
        monitor.start()
        try {
            waitUntil { monitor.isSelectedPlaybackPlaying() == true }

            val token = monitor.pauseSelectedIfPlaying()
            assertNotNull("The active test session should be paused", token)
            monitor.resumePlayback(token!!)

            waitUntil(timeoutMs = 2_500L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PLAYING
            }
            assertTrue(monitor.isSelectedPlaybackPlaying() == true)
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun resumesWhenMediaIdTemporarilyDisappearsDuringMetadataRefresh() {
        val monitor = MediaSessionMonitor(context) {}
        monitor.start()
        try {
            waitUntil { monitor.isSelectedPlaybackPlaying() == true }

            val token = monitor.pauseSelectedIfPlaying()
            assertNotNull("The active test session should be paused", token)
            // Several real media apps clear MEDIA_ID for one callback while
            // keeping the visible title/artist unchanged.
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Delayed Resume Test")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "TrackTalk")
                    .build(),
            )
            monitor.resumePlayback(token!!)

            waitUntil(timeoutMs = 2_500L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PLAYING
            }
            assertTrue(monitor.isSelectedPlaybackPlaying() == true)
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun laggingQueuePositionDuringTransitionDoesNotCancelRestore() {
        session.setQueue(
            listOf(
                queueItem(id = 1L, title = "Previous Track", artist = "Previous Artist"),
                queueItem(id = 2L, title = "Delayed Resume Test", artist = "TrackTalk"),
            ),
        )
        // Reproduce YouTube Music's mixed transition snapshot: metadata has
        // advanced to Track B while PlaybackState still points at Track A.
        setState(PlaybackState.STATE_PLAYING, activeQueueItemId = 1L)

        val monitor = MediaSessionMonitor(context) {}
        monitor.start()
        try {
            waitUntil { monitor.isSelectedPlaybackPlaying() == true }

            val token = monitor.pauseSelectedIfPlaying()
            assertNotNull(token)
            assertNull("A stale active queue item must not enter the pause token", token!!.queueItemId)
            waitUntil(timeoutMs = 2_000L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PAUSED
            }

            // Queue position settles to Track B while title/artist remain the
            // same. Restoration must treat this as enrichment, not a skip.
            setState(PlaybackState.STATE_PAUSED, activeQueueItemId = 2L)
            monitor.resumePlayback(token)

            waitUntil(timeoutMs = 2_500L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PLAYING
            }
            assertEquals(1, playCommandCount.get())
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun resumeCommandIsIssuedBeforeImmediateMonitorStop() {
        val monitor = MediaSessionMonitor(context) {}
        monitor.start()
        try {
            waitUntil { monitor.isSelectedPlaybackPlaying() == true }
            val token = monitor.pauseSelectedIfPlaying()
            assertNotNull(token)
            waitUntil(timeoutMs = 2_000L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PAUSED
            }

            // Reproduce notification-listener/controller teardown on the same
            // main-loop turn as TTS completion. A zero-delay posted PLAY is
            // cancelled by stop(); the first restore command must be issued
            // before resumePlayback() returns.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                monitor.resumePlayback(token!!)
                monitor.stop()
            }

            waitUntil(timeoutMs = 1_000L) { playCommandCount.get() == 1 }
            assertEquals(1, playCommandCount.get())
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun resumesWhenProviderChangesMediaIdForSameLogicalTrack() {
        val monitor = MediaSessionMonitor(context) {}
        monitor.start()
        try {
            waitUntil { monitor.isSelectedPlaybackPlaying() == true }
            val token = monitor.pauseSelectedIfPlaying()
            assertNotNull(token)
            waitUntil(timeoutMs = 2_000L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PAUSED
            }
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Delayed Resume Test")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "TrackTalk")
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "canonical-delayed-resume-test")
                    .build(),
            )

            monitor.resumePlayback(token!!)

            waitUntil(timeoutMs = 2_500L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PLAYING
            }
            assertEquals(1, playCommandCount.get())
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun harmlessSessionRecreationRestoresSameLogicalTrack() {
        val monitor = MediaSessionMonitor(context) {}
        monitor.start()
        try {
            waitUntil { monitor.isSelectedPlaybackPlaying() == true }
            val token = monitor.pauseSelectedIfPlaying()
            assertNotNull(token)
            waitUntil(timeoutMs = 2_000L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PAUSED
            }

            session.release()
            session = MediaSession(context, "TrackTalkMonitorRecreatedSession")
            installSessionCallback(session)
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Delayed Resume Test")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "TrackTalk")
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "recreated-session-id")
                    .build(),
            )
            setState(PlaybackState.STATE_PAUSED)
            session.isActive = true
            monitor.refresh()
            // A real device may also retain a paused YouTube Music session.
            // refresh() is monitor-thread synchronous; assert the recreated
            // test session itself instead of assuming it is the only session.
            assertTrue(monitor.activeSessionCount >= 1)
            assertEquals(
                PlaybackState.STATE_PAUSED,
                MediaController(context, session.sessionToken).playbackState?.state,
            )

            monitor.resumePlayback(token!!)

            waitUntil(timeoutMs = 2_500L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PLAYING
            }
            assertEquals(1, playCommandCount.get())
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun restoreSurvivesProviderSessionGapAndWaitsForTrackIdentity() {
        val restoreEvents = CopyOnWriteArrayList<PlaybackRestoreEvent>()
        val monitor = MediaSessionMonitor(context) {}
        monitor.start()
        try {
            waitUntil { monitor.isSelectedPlaybackPlaying() == true }
            val token = monitor.pauseSelectedIfPlaying()
            assertNotNull(token)
            waitUntil(timeoutMs = 2_000L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PAUSED
            }

            // Reproduce YouTube Music destroying its MediaSession while TTS is
            // active. The old implementation finalized the restore after
            // about one second and never sent PLAY when the replacement
            // session appeared several seconds later.
            session.release()
            repeat(3) {
                monitor.refresh()
                SystemClock.sleep(80L)
            }
            monitor.resumePlayback(
                token = token!!,
                announcementCycleId = 73L,
                onEvent = { restoreEvents.add(it) },
            )
            waitUntil { restoreEvents.any { it.type == PlaybackRestoreEventType.WAITING_FOR_SESSION } }
            SystemClock.sleep(1_300L)
            assertEquals(0, playCommandCount.get())

            // A newly registered provider session may initially have no title.
            // Do not call it a different track or issue PLAY until metadata
            // identifies the same logical track.
            session = MediaSession(context, "TrackTalkMonitorDelayedRecreatedSession")
            installSessionCallback(session)
            setState(PlaybackState.STATE_STOPPED)
            session.isActive = true
            monitor.refresh()
            SystemClock.sleep(300L)
            assertEquals(0, playCommandCount.get())

            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Delayed Resume Test")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "TrackTalk")
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "recreated-after-gap")
                    .build(),
            )
            monitor.refresh()

            waitUntil(timeoutMs = 2_500L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PLAYING &&
                    restoreEvents.any { it.type == PlaybackRestoreEventType.PLAYING_CONFIRMED }
            }
            assertEquals(1, playCommandCount.get())
            assertEquals(
                1,
                restoreEvents.count { it.type == PlaybackRestoreEventType.PLAY_REQUESTED },
            )
            assertTrue(restoreEvents.any { it.type == PlaybackRestoreEventType.PLAYING_CONFIRMED })
            assertTrue(restoreEvents.none { it.type == PlaybackRestoreEventType.FAILED })
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun oneBoundedRetryRecoversAnIgnoredPlayCommand() {
        val monitor = MediaSessionMonitor(context) {}
        monitor.start()
        try {
            waitUntil { monitor.isSelectedPlaybackPlaying() == true }
            val token = monitor.pauseSelectedIfPlaying()
            assertNotNull(token)
            waitUntil(timeoutMs = 2_000L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PAUSED
            }
            ignoredPlayCommandsRemaining.set(1)

            monitor.resumePlayback(token!!)

            waitUntil(timeoutMs = 2_500L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PLAYING
            }
            assertEquals(2, playCommandCount.get())
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun stalePausedStateCreatedBeforePlayDoesNotCancelSafeRetry() {
        val monitor = MediaSessionMonitor(context) {}
        monitor.start()
        try {
            waitUntil { monitor.isSelectedPlaybackPlaying() == true }
            val token = monitor.pauseSelectedIfPlaying()
            assertNotNull(token)
            waitUntil(timeoutMs = 2_000L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PAUSED
            }
            ignoredPlayCommandsRemaining.set(1)

            monitor.resumePlayback(token!!)
            waitUntil { playCommandCount.get() == 1 }
            // Re-deliver TrackTalk's old PAUSED state after PLAY. Its
            // elapsedRealtime update timestamp predates the PLAY request, so
            // it is not a newer user pause and must not cancel the sole retry.
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setState(
                        PlaybackState.STATE_PAUSED,
                        0L,
                        0f,
                        token.pauseRequestedAtElapsedNanos / 1_000_000L,
                    )
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                    .build(),
            )

            waitUntil(timeoutMs = 2_500L) {
                playCommandCount.get() == 2 &&
                    MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PLAYING
            }
            assertEquals(2, playCommandCount.get())
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun providerPausedTransitionImmediatelyFollowedByPlayingConfirmsRestore() {
        val restoreEvents = CopyOnWriteArrayList<PlaybackRestoreEvent>()
        val monitor = MediaSessionMonitor(context) {}
        monitor.start()
        try {
            waitUntil { monitor.isSelectedPlaybackPlaying() == true }
            val token = monitor.pauseSelectedIfPlaying()
            assertNotNull(token)
            waitUntil(timeoutMs = 2_000L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PAUSED
            }
            ignoredPlayCommandsRemaining.set(1)

            monitor.resumePlayback(
                token = token!!,
                announcementCycleId = 74L,
                onEvent = { restoreEvents.add(it) },
            )
            waitUntil { playCommandCount.get() == 1 }

            // YouTube Music can publish a newly timestamped PAUSED transition
            // and then PLAYING in the same callback burst. Give that burst a
            // bounded chance to settle before treating PAUSED as user intent.
            setState(PlaybackState.STATE_PAUSED)
            mainHandler.postDelayed({ setState(PlaybackState.STATE_PLAYING) }, 40L)

            waitUntil(timeoutMs = 1_500L) {
                restoreEvents.any { it.type == PlaybackRestoreEventType.PLAYING_CONFIRMED }
            }
            assertEquals(1, playCommandCount.get())
            assertTrue(restoreEvents.none { it.type == PlaybackRestoreEventType.CANCELLED })
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun alreadyPlayingAfterAcknowledgedOwnedPauseDoesNotReceiveRedundantPlay() {
        val restoreEvents = CopyOnWriteArrayList<PlaybackRestoreEvent>()
        val monitor = MediaSessionMonitor(context) {}
        monitor.start()
        try {
            waitUntil { monitor.isSelectedPlaybackPlaying() == true }
            val token = monitor.pauseSelectedIfPlaying(announcementCycleId = 75L)
            assertNotNull(token)
            waitUntil(timeoutMs = 2_000L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PAUSED
            }

            // Simulate a newer actor (notification/focus owner/player) already
            // restoring the exact track before TrackTalk reaches TTS completion.
            setState(PlaybackState.STATE_PLAYING)
            waitUntil {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PLAYING
            }

            monitor.resumePlayback(
                token = token!!,
                announcementCycleId = 75L,
                ownedPauseAcknowledged = true,
                onEvent = { restoreEvents.add(it) },
            )
            waitUntil {
                restoreEvents.any { it.type == PlaybackRestoreEventType.PLAYING_CONFIRMED }
            }

            assertEquals(0, playCommandCount.get())
            assertTrue(
                restoreEvents.any {
                    it.type == PlaybackRestoreEventType.PLAYING_CONFIRMED &&
                        it.reason == "ALREADY_PLAYING_AFTER_OWNED_PAUSE" &&
                        it.attempt == 0
                },
            )
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun manualPauseAfterPlayingConfirmationIsNotOverridden() {
        val monitor = MediaSessionMonitor(context) {}
        monitor.start()
        try {
            waitUntil { monitor.isSelectedPlaybackPlaying() == true }
            val token = monitor.pauseSelectedIfPlaying()
            assertNotNull(token)
            waitUntil(timeoutMs = 2_000L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PAUSED
            }

            monitor.resumePlayback(token!!)
            waitUntil(timeoutMs = 1_000L) {
                playCommandCount.get() == 1 &&
                    MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PLAYING
            }
            setState(PlaybackState.STATE_PAUSED)
            SystemClock.sleep(1_300L)

            assertEquals(1, playCommandCount.get())
            assertEquals(
                PlaybackState.STATE_PAUSED,
                MediaController(context, session.sessionToken).playbackState?.state,
            )
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun replacementTrackIsNeverResumedByStalePauseToken() {
        val monitor = MediaSessionMonitor(context) {}
        monitor.start()
        try {
            waitUntil { monitor.isSelectedPlaybackPlaying() == true }
            val token = monitor.pauseSelectedIfPlaying()
            assertNotNull(token)
            waitUntil(timeoutMs = 2_000L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PAUSED
            }
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Replacement Track")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Another Artist")
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "replacement-track")
                    .build(),
            )
            setState(PlaybackState.STATE_PAUSED)

            monitor.resumePlayback(token!!)
            SystemClock.sleep(1_300L)

            assertEquals(0, playCommandCount.get())
            assertEquals(
                PlaybackState.STATE_PAUSED,
                MediaController(context, session.sessionToken).playbackState?.state,
            )
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun inactiveProviderSessionUsesRetainedControllerForRestore() {
        val restoreEvents = CopyOnWriteArrayList<PlaybackRestoreEvent>()
        val monitor = MediaSessionMonitor(context) {}
        monitor.start()
        try {
            waitUntil { monitor.isSelectedPlaybackPlaying() == true }
            val token = monitor.pauseSelectedIfPlaying()
            assertNotNull(token)
            waitUntil(timeoutMs = 2_000L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PAUSED
            }

            // YouTube Music can temporarily drop its paused session from the
            // active-session list without destroying the controller that
            // accepted TrackTalk's PAUSE.
            session.isActive = false
            monitor.refresh()
            monitor.resumePlayback(
                token = token!!,
                announcementCycleId = 75L,
                onEvent = { restoreEvents.add(it) },
            )

            waitUntil(timeoutMs = 2_000L) {
                restoreEvents.any { it.type == PlaybackRestoreEventType.PLAYING_CONFIRMED }
            }
            assertEquals(1, playCommandCount.get())
            assertTrue(
                restoreEvents.any {
                    it.type == PlaybackRestoreEventType.PLAY_REQUESTED &&
                        it.reason == "COMMAND_ISSUED_RETAINED_CONTROLLER"
                },
            )
            assertTrue(restoreEvents.none { it.type == PlaybackRestoreEventType.FAILED })
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun confirmedTrackFastPathPausesTheExpectedSessionOnce() {
        val latest = AtomicReference<MediaMonitorUpdate>()
        val monitor = MediaSessionMonitor(context, latest::set)
        monitor.start()
        try {
            waitUntil { latest.get()?.selected?.event?.mediaId == "delayed-resume-test" }
            val selected = latest.get().selected!!

            val token = monitor.pauseSelectedIfPlaying(
                expectedEvent = selected.event,
                expectedSessionKey = selected.sessionKey,
            )

            assertNotNull(token)
            waitUntil { pauseCommandCount.get() == 1 }
            assertEquals(1, pauseCommandCount.get())
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun diagnosticCallbackFailureDoesNotLosePauseToken() {
        val latest = AtomicReference<MediaMonitorUpdate>()
        val monitor = MediaSessionMonitor(context, latest::set)
        monitor.start()
        try {
            waitUntil { latest.get()?.selected?.event?.mediaId == "delayed-resume-test" }
            val selected = latest.get().selected!!

            val token = monitor.pauseSelectedIfPlaying(
                expectedEvent = selected.event,
                expectedSessionKey = selected.sessionKey,
                onPauseRequested = { error("diagnostic callback failure") },
            )

            assertNotNull(token)
            waitUntil { pauseCommandCount.get() == 1 }
            assertEquals(1, pauseCommandCount.get())
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun staleConfirmedTrackNeverPausesAReplacementTrack() {
        val latest = AtomicReference<MediaMonitorUpdate>()
        val monitor = MediaSessionMonitor(context, latest::set)
        monitor.start()
        try {
            waitUntil { latest.get()?.selected?.event?.mediaId == "delayed-resume-test" }
            val selected = latest.get().selected!!
            val staleEvent = selected.event.copy(
                mediaId = "stale-media-id",
                title = "Stale title",
            )

            val token = monitor.pauseSelectedIfPlaying(
                expectedEvent = staleEvent,
                expectedSessionKey = selected.sessionKey,
            )

            assertNull(token)
            SystemClock.sleep(200L)
            assertEquals(0, pauseCommandCount.get())
            assertTrue(monitor.isSelectedPlaybackPlaying() == true)
        } finally {
            monitor.stop()
        }
    }

    private fun setState(
        state: Int,
        activeQueueItemId: Long = MediaSession.QueueItem.UNKNOWN_ID.toLong(),
    ) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(state, 0L, 1f)
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .setActiveQueueItemId(activeQueueItemId)
                .build(),
        )
    }

    private fun queueItem(id: Long, title: String, artist: String): MediaSession.QueueItem =
        MediaSession.QueueItem(
            MediaDescription.Builder()
                .setTitle(title)
                .setSubtitle(artist)
                .build(),
            id,
        )

    private fun installSessionCallback(target: MediaSession) {
        target.setCallback(object : MediaSession.Callback() {
            override fun onPause() {
                pauseCommandCount.incrementAndGet()
                // Simulate a media app that publishes PAUSED after its command
                // callback. This is the race that used to lose auto-resume.
                mainHandler.postDelayed({ setState(PlaybackState.STATE_PAUSED) }, 350L)
            }

            override fun onPlay() {
                playCommandCount.incrementAndGet()
                if (ignoredPlayCommandsRemaining.getAndUpdate { value -> (value - 1).coerceAtLeast(0) } > 0) {
                    return
                }
                setState(PlaybackState.STATE_PLAYING)
            }
        }, mainHandler)
    }

    private fun waitUntil(timeoutMs: Long = 1_500L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(40L)
        }
        assertTrue("Condition was not met within ${timeoutMs}ms", condition())
    }
}
