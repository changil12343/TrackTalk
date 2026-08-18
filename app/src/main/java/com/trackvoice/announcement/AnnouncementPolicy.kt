package com.trackvoice.announcement

import com.trackvoice.data.AnnouncementMode
import com.trackvoice.data.AnnouncementOrder
import com.trackvoice.data.AnnouncementReadField
import com.trackvoice.data.AppSettings
import com.trackvoice.data.UserSettings
import com.trackvoice.data.DEFAULT_ALBUM_READ_FIELDS
import com.trackvoice.data.DEFAULT_ALGORITHMIC_READ_FIELDS
import com.trackvoice.data.DEFAULT_GLOBAL_ENABLED_READ_FIELDS
import com.trackvoice.data.DEFAULT_PLAYLIST_READ_FIELDS
import com.trackvoice.data.ALL_ANNOUNCEMENT_READ_FIELDS
import com.trackvoice.data.BETA_VISIBLE_ANNOUNCEMENT_READ_FIELDS
import com.trackvoice.data.AnnouncementTimingPolicy
import com.trackvoice.data.normalizeAnnouncementReadFields
import com.trackvoice.data.withLegacyAnnouncementOrder
import com.trackvoice.media.PlaybackEvent
import com.trackvoice.media.PlaybackCollection
import com.trackvoice.media.PlaybackCollectionResolver

enum class AnnouncementSkipReason {
    DISABLED,
    APP_DISABLED,
    NO_TITLE,
    SPEAKER_OUTPUT,
    TOO_EARLY,
    NO_TEXT,
}

enum class AnnouncementConfigurationSource {
    DEFAULT,
    CONTENT_SPECIFIC,
}

data class EffectiveAnnouncementConfiguration(
    val source: AnnouncementConfigurationSource,
    val collection: PlaybackCollection,
    val fields: List<AnnouncementReadField>,
    val typeSpecificSettingsEnabled: Boolean,
)

data class AnnouncementDecision(
    val shouldAnnounce: Boolean,
    val text: String? = null,
    val mode: AnnouncementMode,
    val delayMs: Long,
    val formatOptions: AnnouncementFormatOptions = AnnouncementFormatOptions(),
    val collection: PlaybackCollection = PlaybackCollection.UNKNOWN,
    val skipReason: AnnouncementSkipReason? = null,
)

object AnnouncementPolicy {
    fun decide(
        event: PlaybackEvent,
        userSettings: UserSettings,
        appSettings: AppSettings?,
        effectiveEnabled: Boolean = userSettings.enabled,
        externalAudioOutput: Boolean = true,
        collectionOverride: PlaybackCollection? = null,
        preparedText: String? = null,
    ): AnnouncementDecision {
        val detectedCollection = collectionOverride ?: PlaybackCollectionResolver.resolve(event)
        val collection = PlaybackCollectionResolver.applyFallback(
            detected = detectedCollection,
            fallback = com.trackvoice.data.CollectionFallback.AUTO,
        )
        val configuration = resolveConfiguration(userSettings, collection)
        val mode = resolveMode(collection, userSettings)
        val announceBeforePlayback = userSettings.trackStartBehavior == com.trackvoice.data.TrackStartBehavior.ANNOUNCE_THEN_PLAY
        val configuredDelayMs = AnnouncementTimingPolicy.effectiveDelayMs(userSettings)

        // Immediate reading (and announce-before-playback) has no meaningful
        // elapsed-playback threshold. Do not let a stale minimum value delay
        // an announcement after the user selects "Read now".
        val minimumPosition = if (userSettings.timing == com.trackvoice.data.AnnouncementTiming.IMMEDIATE || announceBeforePlayback) {
            0L
        } else {
            userSettings.minimumPlaybackSeconds.coerceIn(0, 120) * 1_000L
        }
        val minimumRemainingMs = (minimumPosition - (event.playbackPosition ?: 0L)).coerceAtLeast(0L)
        val delayMs = maxOf(configuredDelayMs, minimumRemainingMs)

        if (!effectiveEnabled) return skipped(mode, delayMs, AnnouncementSkipReason.DISABLED)
        if (appSettings?.enabled == false) return skipped(mode, delayMs, AnnouncementSkipReason.APP_DISABLED)
        // MediaSession metadata is often delivered in more than one callback.
        // A track with album/queue identity but no title is still a valid
        // pending candidate; the controller settles it before committing TTS.
        // Truly empty sessions are still rejected instead of producing a
        // misleading announcement.
        if (!event.hasTrackMetadata) return skipped(mode, delayMs, AnnouncementSkipReason.NO_TITLE)
        if (!userSettings.outputPolicy.allows(externalAudioOutput)) {
            return skipped(mode, delayMs, AnnouncementSkipReason.SPEAKER_OUTPUT)
        }

        val formatOptions = configuration.fields.toFormatOptions(
            // Album/playlist source intent is not reliably exposed by a
            // normal MediaSession. Do not apply a source-specific shortcut.
            albumNameFirstTrackOnly = false,
            announcementOrder = AnnouncementOrder.DEFAULT,
        )

        val text = PreparedAnnouncementTextResolver.resolve(preparedText) {
            AnnouncementFormatter.format(
                event = event,
                mode = mode,
                options = formatOptions,
                collection = collection,
                voiceLanguage = userSettings.voiceLanguage,
            )
        }
        return if (text == null) {
            skipped(mode, delayMs, AnnouncementSkipReason.NO_TEXT)
        } else {
            AnnouncementDecision(
                shouldAnnounce = true,
                text = text,
                mode = mode,
                delayMs = delayMs,
                formatOptions = formatOptions,
                collection = collection,
            )
        }
    }

    private fun skipped(
        mode: AnnouncementMode,
        delayMs: Long,
        reason: AnnouncementSkipReason,
    ) = AnnouncementDecision(
        shouldAnnounce = false,
        mode = mode,
        delayMs = delayMs,
        skipReason = reason,
    )

    fun resolveMode(
        collection: PlaybackCollection,
        userSettings: UserSettings,
    ): AnnouncementMode {
        val configuration = resolveConfiguration(userSettings, collection)
        // The ordered global field list is the sole source of truth. The
        // collection argument remains for internal diagnostics only.
        return configuration.fields.toAnnouncementMode()
    }

    fun resolveConfiguration(
        userSettings: UserSettings,
        collection: PlaybackCollection,
    ): EffectiveAnnouncementConfiguration {
        return EffectiveAnnouncementConfiguration(
            source = AnnouncementConfigurationSource.DEFAULT,
            collection = PlaybackCollection.UNKNOWN,
            fields = normalizeAnnouncementReadFields(
                userSettings.defaultReadFields,
                BETA_VISIBLE_ANNOUNCEMENT_READ_FIELDS,
                DEFAULT_GLOBAL_ENABLED_READ_FIELDS,
            ),
            typeSpecificSettingsEnabled = false,
        )
    }
}

private fun List<AnnouncementReadField>.toFormatOptions(
    albumNameFirstTrackOnly: Boolean = false,
    announcementOrder: AnnouncementOrder = AnnouncementOrder.DEFAULT,
) = AnnouncementFormatOptions(
    readTitle = AnnouncementReadField.TITLE in this,
    readArtist = AnnouncementReadField.ARTIST in this,
    readTrackNumber = AnnouncementReadField.TRACK_NUMBER in this,
    readAlbum = AnnouncementReadField.ALBUM in this,
    readCollection = AnnouncementReadField.COLLECTION in this,
    albumNameFirstTrackOnly = albumNameFirstTrackOnly,
    announcementOrder = announcementOrder,
    orderedFields = withLegacyAnnouncementOrder(announcementOrder),
)

private fun List<AnnouncementReadField>.toAnnouncementMode(): AnnouncementMode = when {
    AnnouncementReadField.ALBUM in this || AnnouncementReadField.TRACK_NUMBER in this -> AnnouncementMode.ALBUM
    AnnouncementReadField.COLLECTION in this -> AnnouncementMode.PLAYLIST
    AnnouncementReadField.TITLE in this && AnnouncementReadField.ARTIST in this -> AnnouncementMode.TITLE_AND_ARTIST
    AnnouncementReadField.TITLE in this -> AnnouncementMode.TITLE_ONLY
    else -> AnnouncementMode.TITLE_AND_ARTIST
}

private fun List<AnnouncementReadField>.toConfiguredMode(configuredMode: AnnouncementMode): AnnouncementMode {
    // The checklist is the source of truth for metadata fields. Older saved
    // settings may still contain TITLE_AND_ARTIST in the legacy mode field;
    // do not let that stale value hide a newly checked album or track number.
    return when {
        AnnouncementReadField.ALBUM in this || AnnouncementReadField.TRACK_NUMBER in this -> AnnouncementMode.ALBUM
        AnnouncementReadField.COLLECTION in this -> AnnouncementMode.PLAYLIST
        configuredMode != AnnouncementMode.SMART -> configuredMode
        else -> toAnnouncementMode()
    }
}

private fun List<AnnouncementReadField>.toAlbumMode(): AnnouncementMode = when {
    AnnouncementReadField.ALBUM in this || AnnouncementReadField.TRACK_NUMBER in this -> AnnouncementMode.ALBUM
    AnnouncementReadField.TITLE in this && AnnouncementReadField.ARTIST in this -> AnnouncementMode.TITLE_AND_ARTIST
    AnnouncementReadField.TITLE in this -> AnnouncementMode.TITLE_ONLY
    else -> AnnouncementMode.TITLE_AND_ARTIST
}

private fun List<AnnouncementReadField>.toPlaylistMode(): AnnouncementMode = when {
    AnnouncementReadField.COLLECTION in this ||
        AnnouncementReadField.ALBUM in this ||
        AnnouncementReadField.TRACK_NUMBER in this -> AnnouncementMode.PLAYLIST
    AnnouncementReadField.TITLE in this && AnnouncementReadField.ARTIST in this -> AnnouncementMode.TITLE_AND_ARTIST
    AnnouncementReadField.TITLE in this -> AnnouncementMode.TITLE_ONLY
    else -> AnnouncementMode.TITLE_AND_ARTIST
}
