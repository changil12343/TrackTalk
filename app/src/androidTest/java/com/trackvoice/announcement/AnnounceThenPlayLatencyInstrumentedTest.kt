package com.trackvoice.announcement

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
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackvoice.TrackVoiceController
import com.trackvoice.TrackVoiceApplication
import com.trackvoice.data.AnnouncementOutputPolicy
import com.trackvoice.data.AnnouncementReadField
import com.trackvoice.data.AnnouncementTiming
import com.trackvoice.data.AppSettings
import com.trackvoice.data.MusicTreatment
import com.trackvoice.data.PersistedAnnouncement
import com.trackvoice.data.TrackStartBehavior
import com.trackvoice.data.UserSettings
import com.trackvoice.monetization.PremiumState
import com.trackvoice.service.TrackVoiceNotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Emulator-only integration harness for the v1 automatic-announcement path.
 *
 * It drives a real framework MediaSession, exposes the next queue item before
 * each transition, and verifies that even legacy pause settings cannot make
 * TrackTalk send transport commands in the expected mode-specific way. Owned-pause
 * restoration remains covered independently by MediaSessionMonitor and
 * PlaybackRestoreObligation tests.
 */
@RunWith(AndroidJUnit4::class)
class AnnounceThenPlayLatencyInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as TrackVoiceApplication
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pauseCount = AtomicInteger(0)
    private val playCount = AtomicInteger(0)

    private lateinit var session: MediaSession
    private lateinit var controller: TrackVoiceController
    private lateinit var originalSettings: UserSettings
    private var originalAppSettings: AppSettings? = null
    private var originalPersistedAnnouncement: PersistedAnnouncement? = null
    private lateinit var premiumStateFlow: MutableStateFlow<PremiumState>
    private lateinit var originalPremiumState: PremiumState

    @Before
    fun setUp() = runBlocking {
        val listenerComponent = ComponentName(
            context,
            TrackVoiceNotificationListenerService::class.java,
        ).flattenToString()
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        assumeTrue(
            "TrackTalk notification access is required for the latency harness",
            enabledListeners.split(':').any { it == listenerComponent },
        )

        originalSettings = app.repository.currentUserSettings()
        originalAppSettings = app.repository.currentAppSettings()[context.packageName]
        originalPersistedAnnouncement = app.repository.currentPersistedAnnouncement()
        @Suppress("UNCHECKED_CAST")
        premiumStateFlow = app.billingManager.javaClass.getDeclaredField("_state").run {
            isAccessible = true
            get(app.billingManager) as MutableStateFlow<PremiumState>
        }
        originalPremiumState = premiumStateFlow.value
        premiumStateFlow.value = originalPremiumState.copy(isPremium = true)
        controller = app.controller

        app.repository.updateUserSettings { current ->
            current.copy(
                enabled = false,
                outputPolicy = AnnouncementOutputPolicy.ALL_OUTPUTS,
                trackStartBehavior = TrackStartBehavior.ANNOUNCE_THEN_PLAY,
                musicTreatment = MusicTreatment.KEEP,
                timing = AnnouncementTiming.IMMEDIATE,
                delaySeconds = 0,
                minimumPlaybackSeconds = 0,
                defaultReadFields = listOf(AnnouncementReadField.TITLE),
                allowRepeatAnnouncements = false,
            )
        }
        app.repository.updateAppSettings(
            AppSettings(
                packageName = context.packageName,
                appName = "TrackTalk latency player",
                enabled = true,
                enabledOverride = true,
            ),
        )
        waitUntil { !app.controller.userSettings.value.enabled }
        waitUntil { app.controller.appSettings.value[context.packageName]?.enabled == true }

        session = MediaSession(context, "TrackTalkAnnounceThenPlayLatency")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPause() {
                pauseCount.incrementAndGet()
                setPlaybackState(PlaybackState.STATE_PAUSED, currentQueueId())
            }

            override fun onPlay() {
                playCount.incrementAndGet()
                setPlaybackState(PlaybackState.STATE_PLAYING, currentQueueId())
            }
        }, mainHandler)
        session.setQueue(TRACKS.mapIndexed { index, track -> track.toQueueItem(index.toLong()) })
        session.setQueueTitle("Up next")
        publishTrack(0)
        session.isActive = true

        controller.attachNotificationListener()
        controller.attachMediaSessionMonitor(context)
        waitUntil(timeoutMs = 8_000L) {
            controller.mediaState.value.currentEvent?.mediaId == TRACKS[0].mediaId
        }
        waitUntil(timeoutMs = 10_000L) {
            controller.ttsState.value.status == TtsStatus.READY
        }
        SystemClock.sleep(250L)
    }

    @After
    fun tearDown() = runBlocking {
        if (::session.isInitialized) {
            session.isActive = false
            session.release()
        }
        mainHandler.removeCallbacksAndMessages(null)
        // JUnit still invokes @After when the notification-access assumption
        // skips setUp. Restore only state that was actually captured.
        if (::originalSettings.isInitialized) {
            app.repository.updateUserSettings { originalSettings }
            originalAppSettings?.let { app.repository.updateAppSettings(it) }
                ?: app.repository.removeApp(context.packageName)
            originalPersistedAnnouncement?.let { app.repository.savePersistedAnnouncement(it) }
                ?: app.repository.clearPersistedAnnouncement()
        }
        if (::premiumStateFlow.isInitialized) premiumStateFlow.value = originalPremiumState
        app.controller.attachNotificationListener()
        app.controller.attachMediaSessionMonitor(context)
    }

    @Test
    fun automaticAnnouncementsKeepPlayingDoesNotIssueTransportCommands() {
        val expectedPausePerTransition = 0
        val expectedPlayPerTransition = 0
        configureAndEnableAnnouncementMode(MusicTreatment.KEEP)
        val playerController = MediaController(context, session.sessionToken)
        var previousAnnouncementAt = controller.diagnostics.value.lastAnnouncementAt ?: 0L
        var completedAnnouncements = 0

        for (index in 1 until TRACKS.size) {
            publishTrack(index)

            waitUntil(timeoutMs = 3_000L) {
                controller.mediaState.value.currentEvent?.mediaId == TRACKS[index].mediaId
            }
            waitUntil(timeoutMs = 8_000L) {
                val diagnostics = controller.diagnostics.value
                diagnostics.lastAnnouncementAt?.let { it > previousAnnouncementAt } == true &&
                    diagnostics.lastAnnouncementSucceeded == true
            }
            previousAnnouncementAt = requireNotNull(controller.diagnostics.value.lastAnnouncementAt)
            completedAnnouncements += 1

            assertEquals(
                "keep playing should never pause",
                expectedPausePerTransition * completedAnnouncements,
                pauseCount.get(),
            )
            assertEquals(
                "keep playing should never play",
                expectedPlayPerTransition * completedAnnouncements,
                playCount.get(),
            )
            assertEquals(PlaybackState.STATE_PLAYING, playerController.playbackState?.state)
        }

        assertEquals(TRANSITION_COUNT, completedAnnouncements)
        assertEquals(
            "keep playing should emit zero pause commands",
            0,
            pauseCount.get(),
        )
        assertEquals(
            "keep playing should emit zero play commands",
            0,
            playCount.get(),
        )
        Log.i(
            LATENCY_TAG,
            "KEEP_PLAYING transitions=$TRANSITION_COUNT " +
                "ttsCompleted=$completedAnnouncements pause=${pauseCount.get()} play=${playCount.get()} " +
                "finalState=${playerController.playbackState?.state}",
        )
    }

    @Test
    fun automaticAnnouncementsSystemDuckWithoutTransportCommands() {
        val expectedPausePerTransition = 0
        val expectedPlayPerTransition = 0
        configureAndEnableAnnouncementMode(MusicTreatment.DUCK)
        val playerController = MediaController(context, session.sessionToken)
        var previousAnnouncementAt = controller.diagnostics.value.lastAnnouncementAt ?: 0L
        var completedAnnouncements = 0

        for (index in 1 until TRACKS.size) {
            publishTrack(index)

            waitUntil(timeoutMs = 3_000L) {
                controller.mediaState.value.currentEvent?.mediaId == TRACKS[index].mediaId
            }
            waitUntil(timeoutMs = 8_000L) {
                val diagnostics = controller.diagnostics.value
                diagnostics.lastAnnouncementAt?.let { it > previousAnnouncementAt } == true &&
                    diagnostics.lastAnnouncementSucceeded == true
            }
            previousAnnouncementAt = requireNotNull(controller.diagnostics.value.lastAnnouncementAt)
            completedAnnouncements += 1

            assertEquals(
                "system duck should never pause",
                expectedPausePerTransition * completedAnnouncements,
                pauseCount.get(),
            )
            assertEquals(
                "system duck should never play",
                expectedPlayPerTransition * completedAnnouncements,
                playCount.get(),
            )
            assertEquals(PlaybackState.STATE_PLAYING, playerController.playbackState?.state)
        }

        assertEquals(TRANSITION_COUNT, completedAnnouncements)
        assertEquals(
            "system duck should emit zero pause commands",
            0,
            pauseCount.get(),
        )
        assertEquals(
            "system duck should emit zero play commands",
            0,
            playCount.get(),
        )
        Log.i(
            LATENCY_TAG,
            "SYSTEM_DUCK transitions=$TRANSITION_COUNT " +
                "ttsCompleted=$completedAnnouncements pause=${pauseCount.get()} play=${playCount.get()} " +
                "finalState=${playerController.playbackState?.state}",
        )
    }

    @Test
    fun automaticAnnouncementsPauseThenPlayIssuesCommandsExactlyOnce() {
        val expectedPausePerTransition = 1
        val expectedPlayPerTransition = 1
        configureAndEnableAnnouncementMode(MusicTreatment.PAUSE)
        val playerController = MediaController(context, session.sessionToken)
        var previousAnnouncementAt = controller.diagnostics.value.lastAnnouncementAt ?: 0L
        var completedAnnouncements = 0

        for (index in 1 until TRACKS.size) {
            publishTrack(index)

            waitUntil(timeoutMs = 3_000L) {
                controller.mediaState.value.currentEvent?.mediaId == TRACKS[index].mediaId
            }
            waitUntil(timeoutMs = 8_000L) {
                val diagnostics = controller.diagnostics.value
                // TTS completion is not an acknowledgement that the framework
                // has published the state set by the asynchronous onPlay callback.
                diagnostics.lastAnnouncementAt?.let { it > previousAnnouncementAt } == true &&
                    diagnostics.lastAnnouncementSucceeded == true &&
                    playCount.get() == expectedPlayPerTransition * (completedAnnouncements + 1) &&
                    playerController.playbackState?.state == PlaybackState.STATE_PLAYING
            }
            previousAnnouncementAt = requireNotNull(controller.diagnostics.value.lastAnnouncementAt)
            completedAnnouncements += 1

            assertEquals(
                "pause mode should pause once per announcement",
                expectedPausePerTransition * completedAnnouncements,
                pauseCount.get(),
            )
            assertEquals(
                "pause mode should play once per announcement",
                expectedPlayPerTransition * completedAnnouncements,
                playCount.get(),
            )
            assertEquals(PlaybackState.STATE_PLAYING, playerController.playbackState?.state)
        }

        assertEquals(TRANSITION_COUNT, completedAnnouncements)
        assertEquals(
            "pause mode should issue one pause per transition",
            TRANSITION_COUNT * expectedPausePerTransition,
            pauseCount.get(),
        )
        assertEquals(
            "pause mode should issue one play per transition",
            TRANSITION_COUNT * expectedPlayPerTransition,
            playCount.get(),
        )
        Log.i(
            LATENCY_TAG,
            "PAUSE_AND_RESTORE transitions=$TRANSITION_COUNT " +
                "ttsCompleted=$completedAnnouncements pause=${pauseCount.get()} play=${playCount.get()} " +
                "finalState=${playerController.playbackState?.state}",
        )
    }

    private fun configureAndEnableAnnouncementMode(musicTreatment: MusicTreatment) {
        runBlocking {
            app.repository.updateUserSettings { current ->
                current.copy(
                    enabled = false,
                    musicTreatment = musicTreatment,
                    trackStartBehavior = TrackStartBehavior.ANNOUNCE_THEN_PLAY,
                    outputPolicy = AnnouncementOutputPolicy.ALL_OUTPUTS,
                    timing = AnnouncementTiming.IMMEDIATE,
                    delaySeconds = 0,
                    minimumPlaybackSeconds = 0,
                    defaultReadFields = listOf(AnnouncementReadField.TITLE),
                    allowRepeatAnnouncements = false,
                )
            }
        }
        waitUntil {
            !controller.userSettings.value.enabled &&
                controller.userSettings.value.trackStartBehavior == TrackStartBehavior.ANNOUNCE_THEN_PLAY &&
                controller.userSettings.value.musicTreatment == musicTreatment
        }
        pauseCount.set(0)
        playCount.set(0)

        runBlocking {
            app.repository.updateUserSettings { it.copy(enabled = true) }
        }
        waitUntil {
            controller.userSettings.value.enabled &&
                controller.userSettings.value.trackStartBehavior == TrackStartBehavior.ANNOUNCE_THEN_PLAY &&
                controller.userSettings.value.musicTreatment == musicTreatment
        }
    }

    private fun publishTrack(index: Int) {
        val track = TRACKS[index]
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, track.mediaId)
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "TrackTalk")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Latency Album")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 180_000L)
                .build(),
        )
        setPlaybackState(PlaybackState.STATE_PLAYING, index.toLong())
    }

    private fun setPlaybackState(state: Int, activeQueueItemId: Long) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(state, 0L, 1f)
                .setActiveQueueItemId(activeQueueItemId)
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .build(),
        )
    }

    private fun currentQueueId(): Long = MediaController(context, session.sessionToken)
        .playbackState
        ?.activeQueueItemId
        ?.takeUnless { it == MediaSession.QueueItem.UNKNOWN_ID.toLong() }
        ?: 0L

    private fun Track.toQueueItem(queueId: Long): MediaSession.QueueItem = MediaSession.QueueItem(
        MediaDescription.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .setSubtitle("TrackTalk")
            .setDescription("Latency Album")
            .build(),
        queueId,
    )

    private fun waitUntil(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(25L)
        }
        assertTrue("Condition was not met within ${timeoutMs}ms", condition())
    }

    private data class Track(val mediaId: String, val title: String)

    private companion object {
        const val TRANSITION_COUNT = 20
        const val LATENCY_TAG = "TrackTalk.LatencyTest"
        val TRACKS = (0..TRANSITION_COUNT).map { index ->
            Track(
                mediaId = "latency-${index.toString().padStart(2, '0')}",
                title = "Latency ${index.toString().padStart(2, '0')}",
            )
        }
    }
}
