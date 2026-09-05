package com.trackvoice.service

import android.app.Notification
import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackvoice.MainActivity
import com.trackvoice.R
import com.trackvoice.announcement.AudioDeviceKind
import com.trackvoice.announcement.AudioRouteResolution
import com.trackvoice.announcement.AudioRouteState
import com.trackvoice.announcement.ConnectedAudioDevice
import com.trackvoice.data.AnnouncementOutputPolicy
import com.trackvoice.data.AnnouncementReadField
import com.trackvoice.data.AppLanguage
import com.trackvoice.data.AppSettings
import com.trackvoice.data.MusicTreatment
import com.trackvoice.data.TrackStartBehavior
import com.trackvoice.data.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackVoiceStatusNotificationInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("DEPRECATION")
    @Test
    fun koreanCollapsedAndExpandedNotificationAreConciseAndMetadataFree() {
        val status = status(
            settings = settings(
                appLanguage = AppLanguage.KOREAN,
                defaultReadFields = listOf(
                    AnnouncementReadField.TITLE,
                    AnnouncementReadField.ARTIST,
                ),
                trackStartBehavior = TrackStartBehavior.ANNOUNCE_THEN_PLAY,
            ),
            devices = listOf(
                ConnectedAudioDevice(
                    key = "soundcore",
                    productName = "soundcore Space One Pro",
                    kind = AudioDeviceKind.BLUETOOTH,
                ),
            ),
        )
        val copy = StatusNotificationRenderer(context).copyFor(status)
        val notification = StatusNotificationRenderer(context).build(status)

        assertEquals("TrackTalk 켜짐", copy.title)
        assertEquals("YouTube Music · 곡명 → 아티스트 · 음량을 줄이고 안내", copy.collapsedText)
        assertEquals("soundcore Space One Pro · 외부 오디오", copy.expandedText)
        assertEquals("안내 끄기", copy.actionLabel)
        assertFalse(copy.collapsedText.contains("Never show this song title"))
        assertFalse(copy.expandedText.orEmpty().contains("Never show this song title"))
        assertEquals(copy.title, notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(copy.collapsedText, notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(
            "${copy.collapsedText}\n${copy.expandedText}",
            notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
        )
        assertEquals(1, notification.actions?.size)
        assertEquals(R.drawable.ic_trackvoice, notification.actions?.single()?.icon)
        assertEquals("안내 끄기", notification.actions?.single()?.title?.toString())
        assertNotNull(notification.contentIntent)
        assertNotNull(notification.actions?.single()?.actionIntent)
    }

    @Test
    fun koreanCollapsedStateUsesPauseModeWordingWhenPauseEnabled() {
        val status = status(
            settings = settings(
                appLanguage = AppLanguage.KOREAN,
                musicTreatment = MusicTreatment.PAUSE,
                defaultReadFields = listOf(
                    AnnouncementReadField.TITLE,
                    AnnouncementReadField.ARTIST,
                ),
            ),
        )
        val copy = StatusNotificationRenderer(context).copyFor(status)

        assertEquals("YouTube Music · 곡명 → 아티스트 · 안내 후 재생", copy.collapsedText)
    }

    @Test
    fun englishCollapsedStateUsesEffectiveFieldsAndDuckBehavior() {
        val status = status(
            settings = settings(
                appLanguage = AppLanguage.ENGLISH,
                defaultReadFields = listOf(
                    AnnouncementReadField.TITLE,
                    AnnouncementReadField.ARTIST,
                    AnnouncementReadField.ALBUM,
                ),
                musicTreatment = MusicTreatment.DUCK,
            ),
        )
        val copy = StatusNotificationRenderer(context).copyFor(status)

        assertEquals("TrackTalk is on", copy.title)
        assertEquals(
            "YouTube Music · Title → Artist → Album · Lower music for announcements",
            copy.collapsedText,
        )
        assertEquals("Turn off", copy.actionLabel)
        assertTrue(copy.collapsedText.length < 100)
    }

    @Test
    fun offAndPermissionRequiredStatesHaveLocalizedTruthfulFallbacks() {
        val renderer = StatusNotificationRenderer(context)
        val off = renderer.copyFor(
            status(
                settings = settings(appLanguage = AppLanguage.ENGLISH, enabled = false),
                effectiveEnabled = false,
            ),
        )
        assertEquals("TrackTalk is off", off.title)
        assertEquals("Voice announcements are paused", off.collapsedText)
        assertEquals("Turn on", off.actionLabel)

        val permission = renderer.copyFor(
            status(
                settings = settings(appLanguage = AppLanguage.KOREAN),
                notificationAccessGranted = false,
            ),
        )
        assertEquals("TrackTalk 설정 필요", permission.title)
        assertEquals("음악 감지 권한을 확인해 주세요", permission.collapsedText)
    }

    @Test
    fun notificationIntentsKeepTapAndToggleActionsSeparate() {
        val contentIntent = StatusNotificationIntents.contentIntent(context)
        assertEquals(
            ComponentName(context, MainActivity::class.java),
            contentIntent.component,
        )

        val turnOff = StatusNotificationIntents.toggleIntent(context, targetEnabled = false)
        assertEquals(StatusNotificationIntents.ACTION_TOGGLE, turnOff.action)
        assertFalse(turnOff.getBooleanExtra(StatusNotificationIntents.EXTRA_TARGET_ENABLED, true))

        val turnOn = StatusNotificationIntents.toggleIntent(context, targetEnabled = true)
        assertTrue(turnOn.getBooleanExtra(StatusNotificationIntents.EXTRA_TARGET_ENABLED, false))
    }

    private fun status(
        settings: UserSettings,
        effectiveEnabled: Boolean = settings.enabled,
        notificationAccessGranted: Boolean = true,
        devices: List<ConnectedAudioDevice> = emptyList(),
    ): StatusNotification = StatusNotificationMapper.map(
        StatusNotificationInput(
            settings = settings,
            effectiveEnabled = effectiveEnabled,
            notificationAccessGranted = notificationAccessGranted,
            activeApp = StatusNotificationActiveApp(
                packageName = MUSIC_PACKAGE,
                displayName = "YouTube Music",
            ),
            appSettings = mapOf(
                MUSIC_PACKAGE to AppSettings(
                    packageName = MUSIC_PACKAGE,
                    appName = "YouTube Music",
                    enabled = true,
                ),
            ),
            route = AudioRouteResolution(AudioRouteState.EXTERNAL, "TEST_EXTERNAL"),
            connectedDevices = devices,
        ),
    )

    private fun settings(
        appLanguage: AppLanguage,
        enabled: Boolean = true,
        defaultReadFields: List<AnnouncementReadField> = listOf(AnnouncementReadField.TITLE),
        musicTreatment: MusicTreatment = MusicTreatment.DUCK,
        trackStartBehavior: TrackStartBehavior = TrackStartBehavior.PLAY_IMMEDIATELY,
    ): UserSettings = UserSettings(
        appLanguage = appLanguage,
        enabled = enabled,
        outputPolicy = AnnouncementOutputPolicy.ALL_OUTPUTS,
        defaultReadFields = defaultReadFields,
        musicTreatment = musicTreatment,
        trackStartBehavior = trackStartBehavior,
    )

    private companion object {
        const val MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
    }
}
