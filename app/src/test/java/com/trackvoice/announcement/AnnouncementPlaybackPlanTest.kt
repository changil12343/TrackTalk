package com.trackvoice.announcement

import com.trackvoice.data.MusicTreatment
import com.trackvoice.data.TrackStartBehavior
import com.trackvoice.data.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementPlaybackPlanTest {
    @Test
    fun playImmediatelyCanPauseWhenPAUSEModeIsConfigured() {
        val plan = AnnouncementPlaybackPlanner.plan(
            UserSettings(
                trackStartBehavior = TrackStartBehavior.PLAY_IMMEDIATELY,
                musicTreatment = MusicTreatment.PAUSE,
            ),
        )

        assertEquals(MusicTreatment.PAUSE, plan.musicTreatment)
        assertTrue(plan.pauseBeforeAnnouncement)
        assertTrue(plan.requestAudioFocus)
        assertFalse(plan.shouldDuckMusic)
        assertEquals(MusicAttenuationStrategy.MEDIA_PAUSE, plan.musicAttenuationStrategy)
    }

    @Test
    fun announceThenPlayConfigurationNoLongerForcesDuck() {
        val plan = AnnouncementPlaybackPlanner.plan(
            UserSettings(
                trackStartBehavior = TrackStartBehavior.ANNOUNCE_THEN_PLAY,
                musicTreatment = MusicTreatment.KEEP,
            ),
        )

        assertEquals(MusicTreatment.KEEP, plan.musicTreatment)
        assertFalse(plan.pauseBeforeAnnouncement)
        assertFalse(plan.requestAudioFocus)
        assertFalse(plan.shouldDuckMusic)
        assertEquals(MusicAttenuationStrategy.NONE, plan.musicAttenuationStrategy)
    }

    @Test
    fun playImmediatelyLowerMusicUsesSystemDuckWithoutManualStreamMutation() {
        val plan = AnnouncementPlaybackPlanner.plan(
            UserSettings(
                trackStartBehavior = TrackStartBehavior.PLAY_IMMEDIATELY,
                musicTreatment = MusicTreatment.DUCK,
                musicDuckPercent = 50,
            ),
        )

        assertEquals(MusicAttenuationStrategy.SYSTEM_DUCK, plan.musicAttenuationStrategy)
        assertTrue(plan.shouldDuckMusic)
        assertTrue(plan.requestAudioFocus)
    }

    @Test
    fun explicitKeepStillUsesNoFocusAndNoTransportCommand() {
        val plan = AnnouncementPlaybackPlanner.plan(
            UserSettings(
                trackStartBehavior = TrackStartBehavior.PLAY_IMMEDIATELY,
                musicTreatment = MusicTreatment.KEEP,
            ),
        )

        assertEquals(MusicTreatment.KEEP, plan.musicTreatment)
        assertFalse(plan.pauseBeforeAnnouncement)
        assertFalse(plan.requestAudioFocus)
        assertFalse(plan.shouldDuckMusic)
        assertEquals(MusicAttenuationStrategy.NONE, plan.musicAttenuationStrategy)
    }
}
