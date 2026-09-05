package com.trackvoice.announcement

import com.trackvoice.data.MusicTreatment
import com.trackvoice.data.UserSettings

enum class MusicAttenuationStrategy {
    NONE,
    SYSTEM_DUCK,
    MEDIA_PAUSE,
}

data class AnnouncementPlaybackPlan(
    val musicTreatment: MusicTreatment,
    val pauseBeforeAnnouncement: Boolean,
    val requestAudioFocus: Boolean,
    val shouldDuckMusic: Boolean,
    val musicAttenuationStrategy: MusicAttenuationStrategy,
)

object AnnouncementPlaybackPlanner {
    fun plan(settings: UserSettings): AnnouncementPlaybackPlan {
        val treatment = settings.musicTreatment
        return AnnouncementPlaybackPlan(
            musicTreatment = treatment,
            pauseBeforeAnnouncement = treatment == MusicTreatment.PAUSE,
            requestAudioFocus = treatment != MusicTreatment.KEEP,
            shouldDuckMusic = treatment == MusicTreatment.DUCK,
            musicAttenuationStrategy = when (treatment) {
                MusicTreatment.KEEP -> MusicAttenuationStrategy.NONE
                MusicTreatment.DUCK -> MusicAttenuationStrategy.SYSTEM_DUCK
                MusicTreatment.PAUSE -> MusicAttenuationStrategy.MEDIA_PAUSE
            },
        )
    }
}
