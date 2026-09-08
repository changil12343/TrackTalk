package com.trackvoice.announcement

import com.trackvoice.data.AnnouncementVolumeMode
import com.trackvoice.data.MusicTreatment
import com.trackvoice.data.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsVolumeMappingTest {
    @Test
    fun followMediaUsesNeutralTtsGainWithoutSystemVolumeScaling() {
        listOf(0f, 0.1f, 0.5f, 1f).forEach { hypotheticalSystemVolumeRatio ->
            assertEquals(
                "system ratio $hypotheticalSystemVolumeRatio must not affect TrackTalk gain",
                1f,
                TtsVolumeMapping.parameterFor(
                    UserSettings(
                        announcementVolumeMode = AnnouncementVolumeMode.FOLLOW_MEDIA,
                        volume = 0.25f,
                    ),
                ),
                0f,
            )
        }
    }

    @Test
    fun customVolumeMapsDirectlyToTtsGain() {
        assertEquals(
            0.81f,
            TtsVolumeMapping.parameterFor(
                UserSettings(
                    announcementVolumeMode = AnnouncementVolumeMode.CUSTOM,
                    volume = 0.81f,
                ),
            ),
            0f,
        )
    }

    @Test
    fun announcementGainIsIndependentOfKeepDuckAndPausePlans() {
        MusicTreatment.values().forEach { treatment ->
            assertEquals(
                1f,
                TtsVolumeMapping.parameterFor(
                    UserSettings(
                        musicTreatment = treatment,
                        announcementVolumeMode = AnnouncementVolumeMode.FOLLOW_MEDIA,
                        volume = 0.3f,
                    ),
                ),
                0f,
            )
        }
    }

    @Test
    fun customGainIsClampedOnlyAtTheTtsBoundary() {
        fun gainFor(customVolume: Float): Float = TtsVolumeMapping.parameterFor(
            UserSettings(
                announcementVolumeMode = AnnouncementVolumeMode.CUSTOM,
                volume = customVolume,
            ),
        )

        assertEquals(0f, gainFor(-0.1f), 0f)
        assertEquals(0.85f, gainFor(0.85f), 0f)
        assertEquals(1f, gainFor(1.1f), 0f)
    }
}
