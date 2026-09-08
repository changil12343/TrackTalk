package com.trackvoice.announcement

import com.trackvoice.data.UserSettings

/** Maps TrackTalk's own announcement gain directly to TextToSpeech. */
object TtsVolumeMapping {
    fun parameterFor(settings: UserSettings): Float =
        settings.announcementVolumeMode.effectiveTtsGain(settings.volume)
}
