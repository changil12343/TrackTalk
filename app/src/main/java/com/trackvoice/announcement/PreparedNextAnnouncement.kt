package com.trackvoice.announcement

import com.trackvoice.data.AnnouncementReadField
import com.trackvoice.data.AppSettings
import com.trackvoice.data.UserSettings
import com.trackvoice.media.AlbumTrackNumberResolver
import com.trackvoice.media.NextTrackPrefetch
import com.trackvoice.media.PlaybackCollection
import com.trackvoice.media.PlaybackEvent
import com.trackvoice.media.PlaybackStatus
import com.trackvoice.media.PreparedNextTrack
import com.trackvoice.media.TrackNumberSource
import java.util.Locale

/**
 * Pure work prepared while the preceding track is still playing. It never
 * represents current playback and cannot authorize pause, focus, or speech.
 */
data class PreparedNextAnnouncement(
    val candidate: PreparedNextTrack,
    val settingsSignature: String,
    val spokenMetadataSignature: String,
    val text: String,
)

object NextTrackAnnouncementPreparation {
    fun prepare(
        candidate: PreparedNextTrack,
        settings: UserSettings,
    ): PreparedNextAnnouncement? {
        val event = candidate.toPlaybackEvent()
        val decision = AnnouncementPolicy.decide(
            event = event,
            userSettings = settings,
            appSettings = AppSettings(
                packageName = candidate.sourcePackageName,
                appName = candidate.sourcePackageName,
                enabled = true,
                enabledOverride = true,
            ),
            effectiveEnabled = true,
            externalAudioOutput = true,
            collectionOverride = PlaybackCollection.UNKNOWN,
        )
        val text = decision.text ?: return null
        return PreparedNextAnnouncement(
            candidate = candidate,
            settingsSignature = settings.signature(),
            spokenMetadataSignature = event.spokenMetadataSignature(settings),
            text = text,
        )
    }

    /**
     * Returns prepared text only when the predicted item, effective settings,
     * and every spoken metadata value still describe the confirmed track.
     */
    fun reusableText(
        prepared: PreparedNextAnnouncement,
        actual: PlaybackEvent,
        sessionKey: String?,
        settings: UserSettings,
    ): String? {
        if (!NextTrackPrefetch.matches(prepared.candidate, actual, sessionKey)) return null
        if (prepared.settingsSignature != settings.signature()) return null
        if (prepared.spokenMetadataSignature != actual.spokenMetadataSignature(settings)) return null
        return prepared.text
    }

    private fun PreparedNextTrack.toPlaybackEvent(): PlaybackEvent = PlaybackEvent(
        sourcePackageName = sourcePackageName,
        sourceAppName = sourcePackageName,
        title = title,
        artist = artist,
        album = album,
        albumArtist = null,
        trackNumber = trackNumber,
        totalTracks = null,
        discNumber = null,
        duration = null,
        mediaId = predicted.mediaId,
        playbackState = PlaybackStatus.PLAYING,
        playbackPosition = 0L,
        observedAt = preparedAt,
        queueTitle = queueTitle,
        trackNumberReliable = trackNumber != null,
        trackNumberSource = if (trackNumber != null) {
            TrackNumberSource.QUEUE_ITEM_METADATA
        } else {
            TrackNumberSource.UNSPECIFIED
        },
    )

    private fun UserSettings.signature(): String {
        val configuration = AnnouncementPolicy.resolveConfiguration(this, PlaybackCollection.UNKNOWN)
        return buildString {
            append(configuration.fields.joinToString(",") { it.name })
            append('|')
            append(voiceLanguage.name)
            append('|')
            append(Locale.getDefault().toLanguageTag())
        }
    }

    private fun PlaybackEvent.spokenMetadataSignature(settings: UserSettings): String {
        val fields = AnnouncementPolicy.resolveConfiguration(settings, PlaybackCollection.UNKNOWN).fields
        return fields.joinToString("|") { field ->
            val value = when (field) {
                AnnouncementReadField.TITLE -> title
                AnnouncementReadField.ARTIST -> artist
                AnnouncementReadField.ALBUM -> album
                AnnouncementReadField.TRACK_NUMBER -> AlbumTrackNumberResolver.resolve(this)?.toString()
                AnnouncementReadField.COLLECTION -> queueTitle
            }
            "${field.name}:${value.normalized()}"
        }
    }

    private fun String?.normalized(): String = this
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("\\s+"), " ")
        .orEmpty()
}

/** Keeps an already validated prepared value off the slow fallback path. */
internal object PreparedAnnouncementTextResolver {
    fun resolve(preparedText: String?, fallback: () -> String?): String? =
        preparedText ?: fallback()
}
