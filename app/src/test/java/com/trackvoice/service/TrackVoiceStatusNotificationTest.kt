package com.trackvoice.service

import com.trackvoice.announcement.AudioRouteResolution
import com.trackvoice.announcement.AudioRouteState
import com.trackvoice.data.AnnouncementOutputPolicy
import com.trackvoice.data.AnnouncementReadField
import com.trackvoice.data.AppSettings
import com.trackvoice.data.MusicTreatment
import com.trackvoice.data.TrackStartBehavior
import com.trackvoice.data.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackVoiceStatusNotificationTest {
    @Test
    fun onCollapsedStateUsesEligibleAppFieldOrderAndAnnounceThenPlay() {
        val status = status(
            settings = settings(
                defaultReadFields = listOf(
                    AnnouncementReadField.TITLE,
                    AnnouncementReadField.ARTIST,
                ),
                trackStartBehavior = TrackStartBehavior.ANNOUNCE_THEN_PLAY,
            ),
        )

        assertEquals(StatusNotificationTitle.ON, status.title)
        assertEquals(
            StatusNotificationDetail.Active(
                appName = "YouTube Music",
                spokenFields = listOf(
                    AnnouncementReadField.TITLE,
                    AnnouncementReadField.ARTIST,
                ),
                behavior = StatusNotificationBehavior.ANNOUNCE_THEN_PLAY,
            ),
            status.detail,
        )
        assertFalse(status.actionTargetEnabled)
    }

    @Test
    fun offStateIsTruthfulAndOffersTurnOn() {
        val status = status(
            settings = settings(enabled = false),
            effectiveEnabled = false,
        )

        assertEquals(StatusNotificationTitle.OFF, status.title)
        assertEquals(StatusNotificationDetail.AnnouncementsPaused, status.detail)
        assertTrue(status.actionTargetEnabled)
    }

    @Test
    fun waitingForMusicDoesNotInventAnActiveApp() {
        val status = status(activeApp = null)

        assertEquals(StatusNotificationTitle.ON, status.title)
        assertEquals(StatusNotificationDetail.WaitingForMusic, status.detail)
    }

    @Test
    fun externalOnlyOnSpeakerWaitsForExternalAudio() {
        val status = status(
            settings = settings(outputPolicy = AnnouncementOutputPolicy.EXTERNAL_ONLY),
            route = AudioRouteResolution(AudioRouteState.SPEAKER, "TEST_SPEAKER"),
        )

        assertEquals(StatusNotificationDetail.WaitingForExternalAudio, status.detail)
        assertNull(status.expandedOutput)
    }

    @Test
    fun missingMusicDetectionAccessShowsSetupState() {
        val status = status(notificationAccessGranted = false)

        assertEquals(StatusNotificationTitle.SETUP_REQUIRED, status.title)
        assertEquals(StatusNotificationDetail.MusicDetectionPermissionRequired, status.detail)
    }

    @Test
    fun spokenFieldSummaryUsesFormatterConfigurationOrder() {
        val titleOnly = status(
            settings = settings(defaultReadFields = listOf(AnnouncementReadField.TITLE)),
        ).active()
        assertEquals(listOf(AnnouncementReadField.TITLE), titleOnly.spokenFields)

        val multiple = status(
            settings = settings(
                defaultReadFields = listOf(
                    AnnouncementReadField.ARTIST,
                    AnnouncementReadField.TITLE,
                    AnnouncementReadField.ALBUM,
                ),
            ),
        ).active()
        assertEquals(
            listOf(
                AnnouncementReadField.ARTIST,
                AnnouncementReadField.TITLE,
                AnnouncementReadField.ALBUM,
            ),
            multiple.spokenFields,
        )
    }

    @Test
    fun announcementBehaviorReflectsTheEffectivePlaybackPlan() {
        assertEquals(
            StatusNotificationBehavior.KEEP_MUSIC,
            status(settings = settings(musicTreatment = MusicTreatment.KEEP)).active().behavior,
        )
        assertEquals(
            StatusNotificationBehavior.DUCK_MUSIC,
            status(settings = settings(musicTreatment = MusicTreatment.DUCK)).active().behavior,
        )
        assertEquals(
            StatusNotificationBehavior.ANNOUNCE_THEN_PLAY,
            status(
                settings = settings(trackStartBehavior = TrackStartBehavior.ANNOUNCE_THEN_PLAY),
            ).active().behavior,
        )
    }

    @Test
    fun unknownActiveAppFallsBackInsteadOfShowingAnUnverifiedPackage() {
        val status = status(
            activeApp = StatusNotificationActiveApp(
                packageName = "example.unknown.player",
                displayName = null,
            ),
            appSettings = emptyMap(),
        )

        assertEquals(StatusNotificationDetail.WaitingForMusic, status.detail)
    }

    @Test
    fun externalRouteShowsOneTruthfulExpandedOutputLine() {
        val status = status(
            connectedDevices = listOf(
                com.trackvoice.announcement.ConnectedAudioDevice(
                    key = "test-device",
                    productName = "soundcore Space One Pro",
                    kind = com.trackvoice.announcement.AudioDeviceKind.BLUETOOTH,
                ),
            ),
        )

        assertEquals(
            StatusNotificationExpandedOutput("soundcore Space One Pro"),
            status.expandedOutput,
        )
    }

    @Test
    fun toggleActionUsesTargetStateAndIsIdempotent() {
        val enabled = settings(enabled = true)
        val turnOff = StatusNotificationToggle.apply(enabled, targetEnabled = false)
        assertFalse(turnOff.enabled)
        assertEquals(turnOff, StatusNotificationToggle.apply(turnOff, targetEnabled = false))

        val turnOn = StatusNotificationToggle.apply(turnOff, targetEnabled = true)
        assertTrue(turnOn.enabled)
        assertEquals(turnOn, StatusNotificationToggle.apply(turnOn, targetEnabled = true))
    }

    @Test
    fun disabledStatusNotificationStaysHidden() {
        val status = status(settings = settings(showStatusNotification = false))

        assertFalse(status.visible)
    }

    private fun StatusNotification.active(): StatusNotificationDetail.Active =
        detail as StatusNotificationDetail.Active

    private fun status(
        settings: UserSettings = settings(),
        effectiveEnabled: Boolean = settings.enabled,
        notificationAccessGranted: Boolean = true,
        activeApp: StatusNotificationActiveApp? = StatusNotificationActiveApp(
            packageName = MUSIC_PACKAGE,
            displayName = "YouTube Music",
        ),
        appSettings: Map<String, AppSettings> = mapOf(
            MUSIC_PACKAGE to AppSettings(
                packageName = MUSIC_PACKAGE,
                appName = "YouTube Music",
                enabled = true,
            ),
        ),
        route: AudioRouteResolution = AudioRouteResolution(AudioRouteState.EXTERNAL, "TEST_EXTERNAL"),
        connectedDevices: List<com.trackvoice.announcement.ConnectedAudioDevice> = emptyList(),
    ): StatusNotification = StatusNotificationMapper.map(
        StatusNotificationInput(
            settings = settings,
            effectiveEnabled = effectiveEnabled,
            notificationAccessGranted = notificationAccessGranted,
            activeApp = activeApp,
            appSettings = appSettings,
            route = route,
            connectedDevices = connectedDevices,
        ),
    )

    private fun settings(
        enabled: Boolean = true,
        outputPolicy: AnnouncementOutputPolicy = AnnouncementOutputPolicy.ALL_OUTPUTS,
        defaultReadFields: List<AnnouncementReadField> = listOf(AnnouncementReadField.TITLE),
        musicTreatment: MusicTreatment = MusicTreatment.DUCK,
        trackStartBehavior: TrackStartBehavior = TrackStartBehavior.PLAY_IMMEDIATELY,
        showStatusNotification: Boolean = true,
    ): UserSettings = UserSettings(
        enabled = enabled,
        outputPolicy = outputPolicy,
        defaultReadFields = defaultReadFields,
        musicTreatment = musicTreatment,
        trackStartBehavior = trackStartBehavior,
        showStatusNotification = showStatusNotification,
    )

    private companion object {
        const val MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
    }
}
