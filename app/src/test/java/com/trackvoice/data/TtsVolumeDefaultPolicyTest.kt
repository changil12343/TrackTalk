package com.trackvoice.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AnnouncementVolumeMigrationPolicyTest {
    @Test
    fun freshConfigurationFollowsMediaAtNeutralGain() {
        assertEquals(AnnouncementVolumeMode.FOLLOW_MEDIA, UserSettings().announcementVolumeMode)
        assertEquals(
            AnnouncementVolumeMode.FOLLOW_MEDIA,
            AnnouncementVolumeMigrationPolicy.modeFor(
                storedMode = null,
                storedCustomVolume = null,
            ),
        )
    }

    @Test
    fun everyLegacyStoredVolumeBecomesCustomWithoutChangingItsValue() {
        listOf(0f, 0.4f, 0.8f, 0.85f, 1f).forEach { storedVolume ->
            assertEquals(
                AnnouncementVolumeMode.CUSTOM,
                AnnouncementVolumeMigrationPolicy.modeFor(
                    storedMode = null,
                    storedCustomVolume = storedVolume,
                ),
            )
        }
    }

    @Test
    fun storedModeWinsOverLegacyVolumePresence() {
        assertEquals(
            AnnouncementVolumeMode.FOLLOW_MEDIA,
            AnnouncementVolumeMigrationPolicy.modeFor(
                storedMode = AnnouncementVolumeMode.FOLLOW_MEDIA.name,
                storedCustomVolume = 0.81f,
            ),
        )
        assertEquals(
            AnnouncementVolumeMode.CUSTOM,
            AnnouncementVolumeMigrationPolicy.modeFor(
                storedMode = AnnouncementVolumeMode.CUSTOM.name,
                storedCustomVolume = null,
            ),
        )
    }
}
