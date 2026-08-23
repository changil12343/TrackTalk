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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Emulator-only integration harness for the transition-critical path.
 *
 * It drives a real framework MediaSession, exposes the next queue item before
 * each transition, and records when the player receives TrackTalk's PAUSE.
 * The production TrackTalk.Validation events provide the precise monotonic
 * T0..T8 breakdown; this harness verifies the external command ordering and
 * supplies a repeatable end-to-end latency sample.
 */
@RunWith(AndroidJUnit4::class)
class AnnounceThenPlayLatencyInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as TrackVoiceApplication
    private val mainHandler = Handler(Looper.getMainLooper())
    private val transitionStartedAtNanos = AtomicLong(0L)
    private val pauseCount = AtomicInteger(0)
    private val pauseLatenciesMs = CopyOnWriteArrayList<Double>()

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
                val receivedAtNanos = SystemClock.elapsedRealtimeNanos()
                transitionStartedAtNanos.get().takeIf { it > 0L }?.let { startedAt ->
                    pauseLatenciesMs += (receivedAtNanos - startedAt).coerceAtLeast(0L) / 1_000_000.0
                }
                pauseCount.incrementAndGet()
                setPlaybackState(PlaybackState.STATE_PAUSED, currentQueueId())
            }

            override fun onPlay() {
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
        app.repository.updateUserSettings { it.copy(enabled = true) }
        waitUntil {
            controller.userSettings.value.enabled &&
                controller.userSettings.value.trackStartBehavior == TrackStartBehavior.ANNOUNCE_THEN_PLAY
        }
        SystemClock.sleep(250L)
    }

    @After
    fun tearDown() = runBlocking {
        transitionStartedAtNanos.set(0L)
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
    fun prefetchedTransitionsPauseBeforeSpeechWithoutDuplicateCommands() {
        val playerController = MediaController(context, session.sessionToken)

        for (index in 1 until TRACKS.size) {
            val expectedPauseCount = index
            transitionStartedAtNanos.set(SystemClock.elapsedRealtimeNanos())
            publishTrack(index)

            waitUntil(timeoutMs = 3_000L) { pauseCount.get() >= expectedPauseCount }
            assertEquals("one PAUSE command per logical transition", expectedPauseCount, pauseCount.get())
            // The callback increments pauseCount immediately before the new
            // framework state becomes visible through MediaController. Observe
            // PAUSED first so a stale PLAYING snapshot cannot advance the loop
            // while the preceding announcement still owns the pause token.
            waitUntil(timeoutMs = 3_000L) {
                playerController.playbackState?.state == PlaybackState.STATE_PAUSED
            }
            waitUntil(timeoutMs = 8_000L) {
                playerController.playbackState?.state == PlaybackState.STATE_PLAYING
            }
            waitUntil(timeoutMs = 3_000L) {
                controller.mediaState.value.currentEvent?.mediaId == TRACKS[index].mediaId
            }
            SystemClock.sleep(120L)
        }

        assertEquals(TRANSITION_COUNT, pauseLatenciesMs.size)
        val sorted = pauseLatenciesMs.sorted()
        val median = percentile(sorted, 0.50)
        val p95 = percentile(sorted, 0.95)
        Log.i(
            LATENCY_TAG,
            "ANNOUNCE_THEN_PLAY_LATENCY transitions=$TRANSITION_COUNT " +
                "medianPauseCallbackMs=$median p95PauseCallbackMs=$p95 samples=${sorted.joinToString(",")}",
        )
        assertTrue("PAUSE callback p95 should remain bounded on the emulator: $p95 ms", p95 < 1_000.0)
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

    private fun percentile(sorted: List<Double>, percentile: Double): Double {
        val index = ((sorted.size - 1) * percentile).toInt().coerceIn(sorted.indices)
        return sorted[index]
    }

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
