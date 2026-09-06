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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
    private val pauseCallbackDelayMs = AtomicLong(350L)
    private val pauseStateUpdateTimeOverrideMs = AtomicLong(-1L)
    private val publishPauseAcknowledgement = AtomicBoolean(true)
    private lateinit var session: MediaSession

    @Before
    fun setUp() {
        pauseCommandCount.set(0)
        playCommandCount.set(0)
        ignoredPlayCommandsRemaining.set(0)
        pauseCallbackDelayMs.set(350L)
        pauseStateUpdateTimeOverrideMs.set(-1L)
        publishPauseAcknowledgement.set(true)
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
    fun ttsCompletionBeforeLatePauseWithAlbumCorrectionRestoresExactlyOnce() {
        pauseCallbackDelayMs.set(1_800L)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "So Cruel")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "U2")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Room On Fire")
                .build(),
        )
        val restoreEvents = CopyOnWriteArrayList<PlaybackRestoreEvent>()
        val playbackCallbackCount = AtomicInteger(0)
        val monitor = MediaSessionMonitor(context) { update ->
            if (update.eventType == MediaEventType.PLAYBACK_STATE) {
                playbackCallbackCount.incrementAndGet()
            }
        }
        monitor.start()
        try {
            waitUntil { monitor.isSelectedPlaybackPlaying() == true }
            val callbacksBeforeBaseline = playbackCallbackCount.get()
            val reusedPlayingStateTimestampMs = SystemClock.elapsedRealtime()
            setState(
                state = PlaybackState.STATE_PLAYING,
                stateUpdatedAtElapsedMs = reusedPlayingStateTimestampMs,
            )
            waitUntil { playbackCallbackCount.get() > callbacksBeforeBaseline }
            pauseStateUpdateTimeOverrideMs.set(reusedPlayingStateTimestampMs)
            val token = monitor.pauseSelectedIfPlaying(announcementCycleId = 1_800L)
            assertNotNull(token)

            // Reproduce the YouTube Music mixed frame: title/artist already
            // identify Track B, while album still belongs to Track A and is
            // corrected about 400 ms later.
            SystemClock.sleep(400L)
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "So Cruel")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "U2")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "Achtung Baby")
                    .build(),
            )

            // TTS completes at about 1.2 s while the provider still exposes
            // PLAYING. This must remain pending instead of consuming authority.
            SystemClock.sleep(800L)
            monitor.resumePlayback(
                token = token!!,
                announcementCycleId = 1_800L,
                onEvent = restoreEvents::add,
            )
            assertEquals(0, playCommandCount.get())
            assertTrue(restoreEvents.none { it.type == PlaybackRestoreEventType.PLAYING_CONFIRMED })
            SystemClock.sleep(300L)
            assertEquals(0, playCommandCount.get())

            // The matching post-command PAUSED callback arrives at about
            // 1.8 s. Only then may TrackTalk send exactly one PLAY.
            waitUntil(timeoutMs = 2_500L) {
                playCommandCount.get() == 1 &&
                    MediaController(context, session.sessionToken).playbackState?.state ==
                    PlaybackState.STATE_PLAYING
            }
            waitUntil(timeoutMs = 1_000L) {
                restoreEvents.any { it.type == PlaybackRestoreEventType.PLAYING_CONFIRMED }
            }
            SystemClock.sleep(500L)
            assertEquals(1, pauseCommandCount.get())
            assertEquals(1, playCommandCount.get())
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun postCommandPauseWithoutSameTrackPlayingCallbackAcceptsOlderSourceTimestamp() {
        pauseCallbackDelayMs.set(1_800L)
        val latest = AtomicReference<MediaMonitorUpdate>()
        val restoreEvents = CopyOnWriteArrayList<PlaybackRestoreEvent>()
        val monitor = MediaSessionMonitor(context, latest::set)
        monitor.start()
        try {
            waitUntil { latest.get()?.selected?.event?.title == "Delayed Resume Test" }
            // INITIAL selection can precede queued bootstrap PLAYING callbacks.
            // Establish and drain the Track-A callback baseline before emitting
            // the metadata-only Track-B transition required by this scenario.
            setState(PlaybackState.STATE_PLAYING)
            waitUntil {
                latest.get()?.let { update ->
                    update.eventType == MediaEventType.PLAYBACK_STATE &&
                        update.selected?.event?.title == "Delayed Resume Test"
                } == true
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            // Track B arrives through metadata while the controller's current
            // snapshot is already PLAYING. No Track-B PLAYING_STATE callback is
            // emitted, matching the Samsung + YouTube Music failure.
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Hide Your Eyes")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "elricfd")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "Slowdive into")
                    .build(),
            )
            waitUntil {
                latest.get()?.selected?.event?.let { event ->
                    event.title == "Hide Your Eyes" && event.isPlaying
                } == true
            }
            val selected = latest.get()!!.selected!!
            pauseStateUpdateTimeOverrideMs.set(SystemClock.elapsedRealtime() - 189L)

            val token = monitor.pauseSelectedIfPlaying(
                expectedEvent = selected.event,
                expectedSessionKey = selected.sessionKey,
                announcementCycleId = 1_794L,
            )
            assertNotNull(token)
            val pauseToken = token!!
            assertNull(
                "Track B deliberately has no same-track PLAYING callback baseline",
                pauseToken.playbackStateUpdatedAtPauseElapsedNanos,
            )

            // Model TTS completing while the provider still exposes PLAYING.
            monitor.resumePlayback(
                token = pauseToken,
                announcementCycleId = 1_794L,
                onEvent = restoreEvents::add,
            )
            assertEquals(0, playCommandCount.get())

            waitUntil(timeoutMs = 3_500L) {
                playCommandCount.get() == 1 &&
                    MediaController(context, session.sessionToken).playbackState?.state ==
                    PlaybackState.STATE_PLAYING
            }
            waitUntil(timeoutMs = 1_000L) {
                restoreEvents.any { it.type == PlaybackRestoreEventType.PLAYING_CONFIRMED }
            }
            SystemClock.sleep(400L)
            assertEquals(1, pauseCommandCount.get())
            assertEquals(1, playCommandCount.get())
            assertEquals(
                1,
                restoreEvents.count { it.type == PlaybackRestoreEventType.PLAY_REQUESTED },
            )
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun missingPauseAcknowledgementExpiresWithoutPlay() {
        publishPauseAcknowledgement.set(false)
        val restoreEvents = CopyOnWriteArrayList<PlaybackRestoreEvent>()
        val monitor = MediaSessionMonitor(
            context = context,
            onUpdate = {},
            pauseAcknowledgementExpiryMs = 300L,
        )
        monitor.start()
        try {
            waitUntil { monitor.isSelectedPlaybackPlaying() == true }
            val token = monitor.pauseSelectedIfPlaying(announcementCycleId = 301L)
            assertNotNull(token)
            monitor.resumePlayback(
                token = token!!,
                announcementCycleId = 301L,
                onEvent = restoreEvents::add,
            )

            waitUntil(timeoutMs = 1_500L) {
                restoreEvents.any {
                    it.type == PlaybackRestoreEventType.CANCELLED &&
                        it.reason == "PAUSE_ACKNOWLEDGEMENT_EXPIRED"
                }
            }
            assertEquals(1, pauseCommandCount.get())
            assertEquals(0, playCommandCount.get())
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
    fun sameFrameworkSessionWrapperRefreshRetainsGenerationAndRestoreLease() {
        val latest = AtomicReference<MediaMonitorUpdate>()
        val restoreEvents = CopyOnWriteArrayList<PlaybackRestoreEvent>()
        val monitor = MediaSessionMonitor(context, latest::set)
        monitor.start()
        try {
            waitUntil { latest.get()?.selected?.event?.mediaId == "delayed-resume-test" }
            val generationBeforeRefresh = latest.get()?.selectedControllerGeneration
            assertNotNull(generationBeforeRefresh)
            val token = monitor.pauseSelectedIfPlaying(announcementCycleId = 304L)
            assertNotNull(token)
            waitUntil(timeoutMs = 2_000L) {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PAUSED
            }

            // MediaSessionManager returns an equivalent MediaController wrapper
            // for the same exact framework token. This is reconciliation, not
            // a controller/session boundary, so the callback generation and
            // owned-pause lease must remain intact.
            monitor.refresh()
            assertEquals(generationBeforeRefresh, latest.get()?.selectedControllerGeneration)
            monitor.resumePlayback(
                token = token!!,
                announcementCycleId = 304L,
                ownedPauseAcknowledged = true,
                onEvent = restoreEvents::add,
            )

            waitUntil(timeoutMs = 2_500L) {
                playCommandCount.get() == 1 &&
                    MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PLAYING
            }
            waitUntil {
                restoreEvents.any { it.type == PlaybackRestoreEventType.PLAYING_CONFIRMED }
            }
            assertEquals(1, pauseCommandCount.get())
            assertEquals(1, playCommandCount.get())
            assertTrue(restoreEvents.none { it.type == PlaybackRestoreEventType.CANCELLED })
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun mediaNotificationHintReconcilesFreshTrackSnapshot() {
        val updates = CopyOnWriteArrayList<MediaMonitorUpdate>()
        val monitor = MediaSessionMonitor(context, updates::add)
        monitor.start()
        try {
            waitUntil { updates.any { it.selected?.event?.mediaId == "delayed-resume-test" } }

            // The normal callback may arrive too, but this assertion is specifically about the
            // hint-triggered snapshot: it must read the authoritative controller state rather
            // than use notification content as track metadata.
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Reconciled Track")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "TrackTalk")
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "reconciled-track")
                    .build(),
            )
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                monitor.reconcileFromMediaNotificationHint(context.packageName)
            }

            waitUntil {
                updates.any { update ->
                    update.eventType == MediaEventType.MEDIA_NOTIFICATION_RECONCILE &&
                        update.selected?.event?.mediaId == "reconciled-track"
                }
            }
            assertEquals(0, pauseCommandCount.get())
            assertEquals(0, playCommandCount.get())
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun sameTrackMediaNotificationBurstCoalescesAndUnrelatedPackageIsIgnored() {
        val updates = CopyOnWriteArrayList<MediaMonitorUpdate>()
        val monitor = MediaSessionMonitor(context, updates::add)
        monitor.start()
        try {
            waitUntil { updates.any { it.selected?.event?.mediaId == "delayed-resume-test" } }
            val generationBeforeHint = requireNotNull(
                updates.last { it.selected?.event?.mediaId == "delayed-resume-test" }
                    .selectedControllerGeneration,
            )

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                repeat(10) {
                    monitor.reconcileFromMediaNotificationHint(context.packageName)
                }
            }
            waitUntil {
                updates.count { it.eventType == MediaEventType.MEDIA_NOTIFICATION_RECONCILE } == 1
            }
            val reconciliations = updates.filter {
                it.eventType == MediaEventType.MEDIA_NOTIFICATION_RECONCILE
            }
            assertEquals(1, reconciliations.size)
            assertEquals("delayed-resume-test", reconciliations.single().selected?.event?.mediaId)
            assertEquals(
                "A same-token reconciliation is not a controller-generation boundary",
                generationBeforeHint,
                reconciliations.single().selectedControllerGeneration,
            )
            assertEquals(0, pauseCommandCount.get())
            assertEquals(0, playCommandCount.get())

            monitor.reconcileFromMediaNotificationHint("com.android.settings")
            assertEquals(
                "A non-selected package must not produce a reconciliation update",
                1,
                updates.count { it.eventType == MediaEventType.MEDIA_NOTIFICATION_RECONCILE },
            )
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
    fun sessionRecreationInvalidatesOldPauseToken() {
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
            // Make the provider timestamp unambiguously newer than the PLAY
            // request even when elapsedRealtimeNanos() and PlaybackState's
            // millisecond timestamp land in the same clock millisecond.
            setState(
                PlaybackState.STATE_PAUSED,
                stateUpdatedAtElapsedMs = SystemClock.elapsedRealtime() + 1L,
            )
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

            monitor.resumePlayback(
                token = token!!,
                onEvent = restoreEvents::add,
            )

            waitUntil { restoreEvents.any { it.type == PlaybackRestoreEventType.CANCELLED } }
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
    fun destroyedSelectedSessionReconcilesToReplacementWithoutManualRefresh() {
        val latest = AtomicReference<MediaMonitorUpdate>()
        val monitor = MediaSessionMonitor(context, latest::set)
        monitor.start()
        try {
            waitUntil { latest.get()?.selected?.event?.mediaId == "delayed-resume-test" }
            val destroyedSession = session

            // Keep the replacement paused so the old PLAYING session remains selected until it is
            // destroyed. The monitor must then refresh active sessions itself; this test performs
            // no monitor.refresh() after the destruction callback.
            session = MediaSession(context, "TrackTalkMonitorSessionDestroyedReplacement")
            installSessionCallback(session)
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Recovered Session")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "TrackTalk")
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "recovered-session")
                    .build(),
            )
            setState(PlaybackState.STATE_PAUSED)
            session.isActive = true
            destroyedSession.release()

            waitUntil(timeoutMs = 2_500L) {
                latest.get()?.selected?.event?.mediaId == "recovered-session"
            }
            assertEquals("recovered-session", latest.get()?.selected?.event?.mediaId)
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun monitorRestartRestoresCurrentSessionWithoutUiRefresh() {
        val latest = AtomicReference<MediaMonitorUpdate>()
        val monitor = MediaSessionMonitor(context, latest::set)
        monitor.start()
        try {
            waitUntil { latest.get()?.selected?.event?.mediaId == "delayed-resume-test" }

            monitor.stop()
            monitor.start()

            waitUntil(timeoutMs = 2_500L) {
                latest.get()?.selected?.event?.mediaId == "delayed-resume-test"
            }
            assertEquals("delayed-resume-test", latest.get()?.selected?.event?.mediaId)
            assertEquals(0, playCommandCount.get())
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun providerSessionGapInvalidatesRestoreAndCannotResumeReplacement() {
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

            // A removed/destroyed MediaSession ends TrackTalk's restore authority.
            session.release()
            monitor.refresh()
            monitor.resumePlayback(
                token = token!!,
                announcementCycleId = 73L,
                onEvent = { restoreEvents.add(it) },
            )
            waitUntil { restoreEvents.any { it.type == PlaybackRestoreEventType.CANCELLED } }
            assertEquals(0, playCommandCount.get())

            // Even a replacement publishing the same visible track cannot inherit
            // the old controller's lease.
            session = MediaSession(context, "TrackTalkMonitorDelayedRecreatedSession")
            installSessionCallback(session)
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Delayed Resume Test")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "TrackTalk")
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "recreated-after-gap")
                    .build(),
            )
            setState(PlaybackState.STATE_PAUSED)
            session.isActive = true
            monitor.refresh()
            SystemClock.sleep(500L)
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
    fun ignoredPlayCommandIsNeverRetried() {
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
            SystemClock.sleep(600L)
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
    fun newerPausedStateAfterPlayRequestCannotTriggerRetry() {
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

            monitor.resumePlayback(token!!, onEvent = restoreEvents::add)
            waitUntil { playCommandCount.get() == 1 }
            setState(
                state = PlaybackState.STATE_PAUSED,
                stateUpdatedAtElapsedMs = SystemClock.elapsedRealtime() + 1L,
            )

            waitUntil {
                restoreEvents.any {
                    it.type == PlaybackRestoreEventType.CANCELLED &&
                        it.reason == "PAUSED_AFTER_PLAY_REQUEST"
                }
            }
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
    fun stalePausedAfterPlayThenPlayingConfirmsWithoutSecondCommand() {
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

            monitor.resumePlayback(token!!, onEvent = restoreEvents::add)
            waitUntil { playCommandCount.get() == 1 }
            setState(
                state = PlaybackState.STATE_PAUSED,
                stateUpdatedAtElapsedMs = SystemClock.elapsedRealtime() - 10_000L,
            )
            mainHandler.postDelayed({ setState(PlaybackState.STATE_PLAYING) }, 40L)

            waitUntil {
                restoreEvents.any { it.type == PlaybackRestoreEventType.PLAYING_CONFIRMED }
            }
            assertEquals(1, playCommandCount.get())
            assertTrue(restoreEvents.none { it.type == PlaybackRestoreEventType.CANCELLED })
            assertEquals(
                PlaybackState.STATE_PLAYING,
                MediaController(context, session.sessionToken).playbackState?.state,
            )
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun newerPausedThenFinalPlayingIsDiagnosticOnlyForBoundedDeviceValidation() {
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
            setState(
                state = PlaybackState.STATE_PAUSED,
                stateUpdatedAtElapsedMs = SystemClock.elapsedRealtime() + 1L,
            )
            mainHandler.postDelayed({ setState(PlaybackState.STATE_PLAYING) }, 40L)

            waitUntil { restoreEvents.any { it.type == PlaybackRestoreEventType.CANCELLED } }
            waitUntil {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PLAYING
            }
            assertEquals(1, playCommandCount.get())
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
    fun stoppedPlaybackAfterTrackTalkPauseIsNeverResumed() {
        val restoreEvents = CopyOnWriteArrayList<PlaybackRestoreEvent>()
        val monitor = MediaSessionMonitor(context) {}
        monitor.start()
        try {
            waitUntil { monitor.isSelectedPlaybackPlaying() == true }
            val token = monitor.pauseSelectedIfPlaying()
            assertNotNull(token)
            waitUntil {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PAUSED
            }

            setState(PlaybackState.STATE_STOPPED)
            monitor.resumePlayback(
                token = token!!,
                announcementCycleId = 76L,
                ownedPauseAcknowledged = true,
                onEvent = restoreEvents::add,
            )

            waitUntil { restoreEvents.any { it.type == PlaybackRestoreEventType.CANCELLED } }
            assertEquals(0, playCommandCount.get())
            assertEquals(
                PlaybackState.STATE_STOPPED,
                MediaController(context, session.sessionToken).playbackState?.state,
            )
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun activeSessionChangeCannotResumeThePreviouslyPausedSession() {
        val restoreEvents = CopyOnWriteArrayList<PlaybackRestoreEvent>()
        val latest = AtomicReference<MediaMonitorUpdate>()
        val monitor = MediaSessionMonitor(context, latest::set)
        monitor.start()
        val originalSession = session
        try {
            waitUntil { latest.get()?.selected?.event?.mediaId == "delayed-resume-test" }
            val token = monitor.pauseSelectedIfPlaying()
            assertNotNull(token)
            waitUntil {
                MediaController(context, originalSession.sessionToken).playbackState?.state == PlaybackState.STATE_PAUSED
            }

            session = MediaSession(context, "TrackTalkMonitorNewActiveSession")
            installSessionCallback(session)
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "New Active Track")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "TrackTalk")
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "new-active-track")
                    .build(),
            )
            setState(PlaybackState.STATE_PLAYING)
            session.isActive = true
            monitor.refresh()
            waitUntil { latest.get()?.selected?.event?.mediaId == "new-active-track" }

            monitor.resumePlayback(
                token = token!!,
                announcementCycleId = 78L,
                ownedPauseAcknowledged = true,
                onEvent = restoreEvents::add,
            )

            waitUntil { restoreEvents.any { it.type == PlaybackRestoreEventType.CANCELLED } }
            assertEquals(0, playCommandCount.get())
            assertEquals(
                PlaybackState.STATE_PAUSED,
                MediaController(context, originalSession.sessionToken).playbackState?.state,
            )
        } finally {
            originalSession.release()
            monitor.stop()
        }
    }

    @Test
    fun monitorReconnectCannotUseLeaseFromPreviousLifecycle() {
        val restoreEvents = CopyOnWriteArrayList<PlaybackRestoreEvent>()
        val monitor = MediaSessionMonitor(context) {}
        monitor.start()
        try {
            waitUntil { monitor.isSelectedPlaybackPlaying() == true }
            val token = monitor.pauseSelectedIfPlaying()
            assertNotNull(token)
            waitUntil {
                MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PAUSED
            }

            monitor.stop()
            monitor.start()
            waitUntil { monitor.activeSessionCount >= 1 }
            monitor.resumePlayback(
                token = token!!,
                announcementCycleId = 77L,
                ownedPauseAcknowledged = true,
                onEvent = restoreEvents::add,
            )

            waitUntil { restoreEvents.any { it.type == PlaybackRestoreEventType.CANCELLED } }
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
    fun inactiveProviderSessionCannotUseDetachedControllerForRestore() {
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

            // Once the provider removes the session from the active set, the
            // controller that accepted PAUSE no longer carries restore authority.
            session.isActive = false
            monitor.refresh()
            monitor.resumePlayback(
                token = token!!,
                announcementCycleId = 75L,
                onEvent = { restoreEvents.add(it) },
            )

            waitUntil { restoreEvents.any { it.type == PlaybackRestoreEventType.CANCELLED } }
            assertEquals(0, playCommandCount.get())
            assertTrue(
                restoreEvents.any {
                    it.type == PlaybackRestoreEventType.CANCELLED &&
                        it.reason.contains("LEASE_SESSION_INVALID")
                },
            )
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun explicitUserToggleMayStillStartTheCurrentlySelectedPausedSession() {
        setState(PlaybackState.STATE_PAUSED)
        val monitor = MediaSessionMonitor(context) {}
        monitor.start()
        try {
            waitUntil { monitor.isSelectedPlaybackPlaying() == false }

            assertEquals(true, monitor.toggleSelectedPlayback())

            waitUntil {
                playCommandCount.get() == 1 &&
                    MediaController(context, session.sessionToken).playbackState?.state == PlaybackState.STATE_PLAYING
            }
            assertEquals(1, playCommandCount.get())
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
        stateUpdatedAtElapsedMs: Long? = null,
    ) {
        val builder = PlaybackState.Builder()
        if (stateUpdatedAtElapsedMs == null) {
            builder.setState(state, 0L, 1f)
        } else {
            builder.setState(state, 0L, 1f, stateUpdatedAtElapsedMs)
        }
        session.setPlaybackState(
            builder
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
                if (publishPauseAcknowledgement.get()) {
                    val stateUpdatedAtElapsedMs = pauseStateUpdateTimeOverrideMs.get()
                        .takeIf { it >= 0L }
                    mainHandler.postDelayed(
                        {
                            setState(
                                state = PlaybackState.STATE_PAUSED,
                                stateUpdatedAtElapsedMs = stateUpdatedAtElapsedMs,
                            )
                        },
                        pauseCallbackDelayMs.get(),
                    )
                }
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
