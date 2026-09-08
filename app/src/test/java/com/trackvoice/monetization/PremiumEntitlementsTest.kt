package com.trackvoice.monetization

import com.trackvoice.data.UserSettings
import com.trackvoice.data.MusicTreatment
import com.trackvoice.data.TrackStartBehavior
import com.trackvoice.data.AnnouncementMode
import com.trackvoice.data.AnnouncementOrder
import com.trackvoice.data.AnnouncementVolumeMode
import com.trackvoice.data.DEFAULT_TTS_VOLUME
import com.trackvoice.data.DEFAULT_TTS_VOLUME_PERCENT
import com.trackvoice.data.AnnouncementReadField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PremiumEntitlementsTest {
    @Test
    fun customVolumeFallbackRemainsEightyPercent() {
        assertEquals(80, DEFAULT_TTS_VOLUME_PERCENT)
        assertEquals(0.80f, DEFAULT_TTS_VOLUME, 0f)
    }

    @Test
    fun freeUserGetsBasicVoiceAndAutomationDefaults() {
        val effective = UserSettings(
            autoEnableOnScreenOff = true,
            speechRate = 1.5f,
            pitch = 0.7f,
            volume = 0.4f,
            raiseDeviceVolume = false,
            deviceVolumePercent = 55,
            defaultReadFields = listOf(AnnouncementReadField.TITLE, AnnouncementReadField.ALBUM),
        ).forPremiumEntitlement(isPremium = false)

        assertFalse(effective.autoEnableOnScreenOff)
        assertEquals(1f, effective.speechRate)
        assertEquals(1f, effective.pitch)
        assertEquals(DEFAULT_TTS_VOLUME, effective.volume)
        assertEquals(AnnouncementVolumeMode.FOLLOW_MEDIA, effective.announcementVolumeMode)
        assertEquals(MusicTreatment.DUCK, effective.musicTreatment)
        assertEquals(TrackStartBehavior.PLAY_IMMEDIATELY, effective.trackStartBehavior)
        assertEquals(AnnouncementMode.TITLE_AND_ARTIST, effective.algorithmMode)
        assertEquals(AnnouncementOrder.DEFAULT, effective.announcementOrder)
        assertFalse(effective.albumNameFirstTrackOnly)
        assertFalse(effective.raiseDeviceVolume)
        assertEquals(90, effective.deviceVolumePercent)
        assertEquals(
            listOf(AnnouncementReadField.TITLE, AnnouncementReadField.ALBUM),
            effective.defaultReadFields,
        )
    }

    @Test
    fun premiumUserKeepsAdvancedSettings() {
        val settings = UserSettings(
            autoEnableOnScreenOff = true,
            speechRate = 1.5f,
            pitch = 0.7f,
            volume = 0.4f,
            raiseDeviceVolume = false,
            deviceVolumePercent = 55,
        )

        assertEquals(settings, settings.forPremiumEntitlement(isPremium = true))
    }

    @Test
    fun freeEntitlementDoesNotOverwriteSavedVolumeMode() {
        val effective = UserSettings(
            announcementVolumeMode = AnnouncementVolumeMode.CUSTOM,
            volume = 0.81f,
        ).forPremiumEntitlement(isPremium = false)

        assertEquals(AnnouncementVolumeMode.CUSTOM, effective.announcementVolumeMode)
        assertEquals(DEFAULT_TTS_VOLUME, effective.volume)
    }

}
