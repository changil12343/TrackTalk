package com.trackvoice.media

import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.trackvoice.diagnostics.TrackTalkDebugLog

class TrackMetadataMapper(
    private val appNameForPackage: (String) -> String,
) {
    private val queueHistories = linkedMapOf<String, QueueHistory>()
    private val knownTrackNumbers = linkedMapOf<String, Int>()
    private val lastQueueDiagnosticSignatures = linkedMapOf<String, String>()
    private val lastNextTrackDiagnosticSignatures = linkedMapOf<String, String>()

    fun map(controller: MediaController, observedAt: Long = System.currentTimeMillis()): PlaybackEvent {
        val metadata = controller.metadata
        val state = controller.playbackState
        val rawQueue = controller.queue.orEmpty()
        val queue = rawQueue.map { it.toSnapshot() }
        val metadataMediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).clean()
        // Players do not all populate the same MediaMetadata keys. In
        // particular, some streaming clients expose the spoken track name as
        // DISPLAY_TITLE while leaving TITLE empty. Resolve the canonical
        // fields once so the UI and announcement pipeline receive the same
        // value.
        val canonicalTitle = metadata.firstText(MediaMetadata.METADATA_KEY_TITLE)
        val displayTitle = metadata.firstText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val metadataTitle = canonicalTitle ?: displayTitle
        val canonicalArtist = metadata.firstText(MediaMetadata.METADATA_KEY_ARTIST)
        val displayArtist = metadata.firstText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val metadataArtist = canonicalArtist ?: displayArtist
        val metadataAlbum = metadata.firstText(MediaMetadata.METADATA_KEY_ALBUM)
        val stateActiveQueuePosition = state?.activeQueueItemId
            ?.takeIf { it >= 0L }
            ?.let { queueId -> rawQueue.indexOfFirst { it.queueId == queueId } }
            ?.takeIf { it >= 0 }
        // Several players, including YouTube Music, publish the current
        // metadata while leaving activeQueueItemId at -1. Resolve the item
        // from its media id first, then from title/artist/album so an album
        // queue position can still be used as a track-number fallback.
        val activeQueuePosition = stateActiveQueuePosition ?: resolveActiveQueuePosition(
            queue = queue,
            mediaId = metadataMediaId,
            title = metadataTitle,
            artist = metadataArtist,
            album = metadataAlbum,
        )
        val queueDiagnosticStart = (activeQueuePosition ?: 0).coerceIn(0, rawQueue.size)
        val queueDiagnosticItems = rawQueue
            .drop(queueDiagnosticStart)
            .take(6)
            .mapIndexed { offset, item ->
                val description = item.description
                val extras = description.extras?.keySet()?.sorted()?.joinToString(",").orEmpty()
                "${queueDiagnosticStart + offset}:{" +
                    "queueItemId=${item.queueId}," +
                    "mediaId=${description.mediaId.clean()}," +
                    "title=${description.title?.toString().clean()}," +
                    "subtitle=${description.subtitle?.toString().clean()}," +
                    "description=${description.description?.toString().clean()?.take(80)}," +
                    "mediaUriPresent=${description.mediaUri != null}," +
                    "iconUriPresent=${description.iconUri != null}," +
                    "extras=[$extras]}"
            }
            .joinToString(" || ")
        val queueDiagnosticSignature = listOf(
            controller.packageName,
            controller.queueTitle?.toString().clean().orEmpty(),
            state?.activeQueueItemId,
            queueDiagnosticItems,
        ).joinToString("|")
        if (lastQueueDiagnosticSignatures[controller.packageName] != queueDiagnosticSignature) {
            lastQueueDiagnosticSignatures[controller.packageName] = queueDiagnosticSignature
            while (lastQueueDiagnosticSignatures.size > 32) {
                lastQueueDiagnosticSignatures.remove(lastQueueDiagnosticSignatures.keys.first())
            }
            TrackTalkDebugLog.event(
                "QUEUE_SNAPSHOT",
                "source" to controller.packageName,
                "queueTitle" to controller.queueTitle?.toString().clean(),
                "queueSize" to rawQueue.size,
                "activeQueueItemId" to state?.activeQueueItemId,
                "activeQueuePosition" to activeQueuePosition,
                "durationMs" to metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0L },
                "positionMs" to state?.position?.takeIf { it >= 0L },
                "metadataKeys" to metadata?.keySet()?.joinToString(",", prefix = "[", postfix = "]"),
                "itemsFromActive" to queueDiagnosticItems,
            )
        }
        val nextQueueIndex = activeQueuePosition?.plus(1)
        val nextQueueItem = nextQueueIndex?.let { queue.getOrNull(it) }
        if (nextQueueItem != null) {
            val fields = NextTrackPrefetch.availableFields(nextQueueItem)
            val nextDiagnosticSignature = listOf(
                controller.packageName,
                controller.queueTitle?.toString().clean().orEmpty(),
                nextQueueItem.mediaId.orEmpty(),
                nextQueueItem.title.orEmpty(),
                nextQueueItem.artist.orEmpty(),
                nextQueueItem.album.orEmpty(),
                nextQueueItem.trackNumber,
                NextTrackPrefetch.quality(nextQueueItem),
            ).joinToString("|")
            if (lastNextTrackDiagnosticSignatures[controller.packageName] != nextDiagnosticSignature) {
                lastNextTrackDiagnosticSignatures[controller.packageName] = nextDiagnosticSignature
                while (lastNextTrackDiagnosticSignatures.size > 32) {
                    lastNextTrackDiagnosticSignatures.remove(lastNextTrackDiagnosticSignatures.keys.first())
                }
                TrackTalkDebugLog.event(
                    "NEXT_TRACK_DISCOVERED",
                    "source" to controller.packageName,
                    "queueIndex" to nextQueueIndex,
                    "queueItemId" to nextQueueItem.queueItemId,
                    "quality" to NextTrackPrefetch.quality(nextQueueItem),
                    "titlePresent" to (nextQueueItem.title != null),
                    "artistPresent" to (nextQueueItem.artist != null),
                    "albumPresent" to (nextQueueItem.album != null),
                    "trackNumberPresent" to (nextQueueItem.trackNumber != null),
                    "stableIdentity" to (
                        !nextQueueItem.mediaId.isNullOrBlank() ||
                            (!nextQueueItem.title.isNullOrBlank() && !nextQueueItem.artist.isNullOrBlank())
                        ),
                    "available" to fields.joinToString(","),
                )
            }
        }
        val activeQueueDescription = activeQueuePosition?.let { position ->
            rawQueue.getOrNull(position)?.description
        }
        val activeQueueTrackNumber = activeQueuePosition?.let { position ->
            queue.getOrNull(position)?.trackNumber
        }
        val metadataTrackNumber = metadata.intMetadata(MediaMetadata.METADATA_KEY_TRACK_NUMBER)
        val metadataDiscNumber = metadata.intMetadata(MediaMetadata.METADATA_KEY_DISC_NUMBER)
        val metadataTotalTracks = metadata.intMetadata(MediaMetadata.METADATA_KEY_NUM_TRACKS)
        val mediaId = metadataMediaId ?: activeQueueDescription?.mediaId.clean()
        val trackKey = mediaId?.let { trackKey(controller.packageName, it) }
        val cachedTrackNumber = trackKey?.let(knownTrackNumbers::get)
        val queueOrderChanged = detectQueueOrderChange(controller, rawQueue)
        val shuffleState = state.toShuffleState()
        rememberQueueTrackNumbers(controller.packageName, queue)
        val trackNumberResolution = when {
            activeQueueTrackNumber != null -> activeQueueTrackNumber to TrackNumberSource.QUEUE_ITEM_METADATA
            queueOrderChanged || shuffleState == ShuffleState.ON -> when {
                cachedTrackNumber != null -> cachedTrackNumber to TrackNumberSource.CACHED_QUEUE_ITEM_METADATA
                metadataTrackNumber != null -> metadataTrackNumber to TrackNumberSource.MEDIA_METADATA
                else -> null to TrackNumberSource.UNSPECIFIED
            }
            metadataTrackNumber != null -> metadataTrackNumber to TrackNumberSource.MEDIA_METADATA
            cachedTrackNumber != null -> cachedTrackNumber to TrackNumberSource.CACHED_QUEUE_ITEM_METADATA
            else -> null to TrackNumberSource.UNSPECIFIED
        }
        val resolvedTrackNumber = trackNumberResolution.first
        val trackNumberSource = trackNumberResolution.second
        // Explicit metadata remains valid even when the surrounding
        // recommendation queue is shuffled or reordered. Queue position is
        // never promoted into this value.
        val trackNumberReliable = trackNumberSource != TrackNumberSource.UNSPECIFIED
        if (trackKey != null && resolvedTrackNumber != null && trackNumberReliable) {
            knownTrackNumbers[trackKey] = resolvedTrackNumber
        }
        TrackTalkDebugLog.event(
            "metadata_mapped",
            "source" to controller.packageName,
            "mediaId" to mediaId,
            "metadataRevision" to observedAt,
            "titleSource" to when {
                canonicalTitle != null -> "canonical"
                displayTitle != null -> "display"
                else -> "none"
            },
            "titleAvailable" to (metadataTitle != null || activeQueueDescription?.title != null),
            "artistSource" to when {
                canonicalArtist != null -> "canonical"
                displayArtist != null -> "display"
                else -> "queue_or_none"
            },
            "albumAvailable" to (metadataAlbum != null || activeQueueDescription?.extras?.getString(MediaMetadata.METADATA_KEY_ALBUM) != null),
            "trackNumber" to resolvedTrackNumber,
            "trackNumberSource" to trackNumberSource,
            "queueSize" to queue.size,
            "activeQueuePosition" to activeQueuePosition,
        )
        TrackTalkDebugLog.event(
            "TRACK_NUMBER_SOURCE_SCAN",
            "source" to controller.packageName,
            "mediaId" to mediaId,
            "metadataTrackNumber" to metadataTrackNumber,
            "metadataDiscNumber" to metadataDiscNumber,
            "metadataNumTracks" to metadataTotalTracks,
            "metadataTrackLikeCandidates" to metadata.trackLikeNumericCandidates(),
            "activeQueueDescriptionExtras" to activeQueueDescription?.extras.trackLikeNumericCandidates(),
            "queueItemTrackData" to rawQueue.mapIndexedNotNull { index, item ->
                item.description.extras.trackLikeNumericCandidates()
                    .takeIf { it.isNotBlank() }
                    ?.let { "$index:$it" }
            }.joinToString(";"),
            "sessionExtrasTrackData" to controller.extras.trackLikeNumericCandidates(),
            "playbackStateExtrasTrackData" to state?.extras.trackLikeNumericCandidates(),
        )
        TrackTalkDebugLog.event(
            "TRACK_NUMBER_RESOLUTION",
            "source" to controller.packageName,
            "mediaId" to mediaId,
            "selected" to resolvedTrackNumber,
            "provenance" to trackNumberSource,
            "mediaMetadata" to metadataTrackNumber,
            "activeQueueItem" to activeQueueTrackNumber,
            "cachedQueueItem" to cachedTrackNumber,
            "activeQueuePosition" to activeQueuePosition,
            "queueItemsWithTrackNumbers" to queue.count { it.trackNumber != null },
            "queueTrackNumbers" to queue.mapNotNull { it.trackNumber }.joinToString(",", prefix = "[", postfix = "]"),
        )
        TrackTalkDebugLog.event(
            "PLAYBACK_CONTEXT_EVIDENCE",
            "source" to controller.packageName,
            "mediaId" to mediaId,
            "metadataKeys" to metadata?.keySet()?.joinToString(",", prefix = "[", postfix = "]"),
            "controllerExtras" to controller.extras?.keySet()?.joinToString(",", prefix = "[", postfix = "]"),
            "queueSize" to queue.size,
            "queueAlbumCoverage" to queue.count { !it.album.isNullOrBlank() },
            "queueTrackCoverage" to queue.count { it.trackNumber != null },
            "queueMediaIds" to queue.mapNotNull { it.mediaId }.take(12).joinToString(",", prefix = "[", postfix = "]"),
            "queueTitle" to controller.queueTitle?.toString().clean(),
            "activeQueuePosition" to activeQueuePosition,
            "shuffleState" to shuffleState,
            "queueOrderChanged" to queueOrderChanged,
        )
        return PlaybackEvent(
            sourcePackageName = controller.packageName,
            sourceAppName = appNameForPackage(controller.packageName),
            title = metadataTitle
                ?: activeQueueDescription?.title?.toString().clean()
                ?: activeQueueDescription?.extras?.firstText(
                    MediaMetadata.METADATA_KEY_TITLE,
                    MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                ),
            artist = metadataArtist
                ?: activeQueueDescription?.subtitle?.toString().clean()
                ?: activeQueueDescription?.extras?.firstText(
                    MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                ),
            album = metadataAlbum
                ?: activeQueueDescription?.extras?.getString(MediaMetadata.METADATA_KEY_ALBUM).clean(),
            albumArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).clean(),
            trackNumber = resolvedTrackNumber,
            totalTracks = metadataTotalTracks,
            discNumber = metadataDiscNumber,
            duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0L },
            mediaId = mediaId,
            playbackState = state.toPlaybackStatus(),
            playbackPosition = state?.position?.takeIf { it >= 0L },
            playbackStateUpdateElapsedMs = state?.lastPositionUpdateTime?.takeIf { it > 0L },
            queue = queue,
            observedAt = observedAt,
            queueTitle = controller.queueTitle?.toString().clean(),
            activeQueuePosition = activeQueuePosition,
            queueOrderChanged = queueOrderChanged,
            shuffleState = shuffleState,
            trackNumberReliable = trackNumberReliable,
            trackNumberSource = trackNumberSource,
        )
    }

    /**
     * Finds the current item when a media session omits both the active queue
     * id and a usable media id. A unique title/artist match is intentionally
     * required before falling back to title-only, avoiding false track 1/2
     * readings for repeated song titles.
     */
    internal fun resolveActiveQueuePosition(
        queue: List<QueueItemSnapshot>,
        mediaId: String?,
        title: String?,
        artist: String?,
        album: String?,
    ): Int? {
        mediaId?.let { currentMediaId ->
            queue.mapIndexedNotNull { index, item ->
                index.takeIf { item.mediaId.clean() == currentMediaId }
            }.singleOrNull()?.let { return it }
        }

        val currentTitle = title.normalizedMetadata() ?: return null
        val titleMatches = queue.mapIndexedNotNull { index, item ->
            index.takeIf { item.title.normalizedMetadata() == currentTitle }
        }
        if (titleMatches.isEmpty()) return null

        val artistMatches = if (artist.normalizedMetadata() == null) {
            titleMatches
        } else {
            titleMatches.filter { queue[it].artist.normalizedMetadata() == artist.normalizedMetadata() }
        }
        if (album.normalizedMetadata() != null) {
            val albumMatches = artistMatches.filter {
                queue[it].album.normalizedMetadata() == album.normalizedMetadata()
            }
            if (albumMatches.size == 1) return albumMatches.single()
        }
        if (artistMatches.size == 1) return artistMatches.single()
        return titleMatches.singleOrNull()
    }

    private fun String?.normalizedMetadata(): String? = this
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.replace(Regex("\\s+"), " ")
        ?.lowercase(java.util.Locale.ROOT)

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun MediaMetadata?.firstText(vararg keys: String): String? = keys
        .asSequence()
        .mapNotNull { key -> runCatching { this?.getString(key) }.getOrNull().clean() }
        .firstOrNull()

    private fun android.os.Bundle?.firstText(vararg keys: String): String? = keys
        .asSequence()
        .mapNotNull { key -> runCatching { this?.getString(key) }.getOrNull().clean() }
        .firstOrNull()

    private fun android.os.Bundle?.trackLikeNumericCandidates(): String = this
        ?.keySet()
        ?.asSequence()
        ?.filter(::isTrackLikeKey)
        ?.sorted()
        ?.mapNotNull { key -> numericValue(key)?.let { "$key=$it" } }
        ?.joinToString(",")
        .orEmpty()

    private fun MediaMetadata?.trackLikeNumericCandidates(): String = this
        ?.keySet()
        ?.asSequence()
        ?.filter(::isTrackLikeKey)
        ?.sorted()
        ?.mapNotNull { key ->
            val longValue = runCatching { getLong(key) }.getOrNull()
                ?.takeIf { it > 0L }
            val stringValue = runCatching { getString(key)?.trim()?.toLongOrNull() }
                .getOrNull()
                ?.takeIf { it > 0L }
            (longValue ?: stringValue)?.let { "$key=$it" }
        }
        ?.joinToString(",")
        .orEmpty()

    private fun android.os.Bundle.numericValue(key: String): String? = runCatching {
        when (val value = get(key)) {
            is Byte, is Short, is Int, is Long, is Float, is Double -> value.toString()
            is String -> value.trim().toLongOrNull()?.toString()
            else -> null
        }
    }.getOrNull()

    private fun isTrackLikeKey(key: String): Boolean {
        val normalized = key.lowercase(java.util.Locale.ROOT)
        return normalized.contains("track") ||
            normalized.contains("disc") ||
            normalized.contains("number") ||
            normalized.contains("position") ||
            normalized.contains("index")
    }

    private fun Long.safeInt(): Int? = toInt().takeIf { it > 0 && it.toLong() == this }

    private fun android.os.Bundle.intMetadata(key: String): Int? {
        if (!containsKey(key)) return null
        val longValue = getLong(key, Long.MIN_VALUE)
        if (longValue != Long.MIN_VALUE) return longValue.safeInt()
        val intValue = getInt(key, Int.MIN_VALUE).takeIf { it > 0 }
        if (intValue != null) return intValue
        return getString(key)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun MediaMetadata?.intMetadata(key: String): Int? {
        if (this == null || !containsKey(key)) return null
        val longValue = runCatching { getLong(key) }.getOrNull()
        if (longValue != null && longValue > 0L) return longValue.safeInt()
        return runCatching { getString(key)?.trim()?.toIntOrNull() }
            .getOrNull()
            ?.takeIf { it > 0 }
    }

    private fun detectQueueOrderChange(
        controller: MediaController,
        rawQueue: List<MediaSession.QueueItem>,
    ): Boolean {
        val order = rawQueue.map { item ->
            item.description.mediaId.clean() ?: "queue:${item.queueId}"
        }
        if (order.size < 2 || order.distinct().size != order.size) return false

        val contextKey = "${controller.packageName}|${controller.queueTitle?.toString().clean().orEmpty()}"
        val previous = queueHistories[contextKey]
        val sameItems = previous != null && previous.order.toSet() == order.toSet()
        val reordered = if (sameItems) {
            previous!!.reordered || previous.order != order
        } else {
            false
        }
        queueHistories[contextKey] = QueueHistory(order, reordered)
        while (queueHistories.size > 32) queueHistories.remove(queueHistories.keys.first())
        return reordered
    }

    private fun rememberQueueTrackNumbers(packageName: String, queue: List<QueueItemSnapshot>) {
        queue.forEach { item ->
            val mediaId = item.mediaId?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            val trackNumber = item.trackNumber ?: return@forEach
            knownTrackNumbers.putIfAbsent(trackKey(packageName, mediaId), trackNumber)
        }
        while (knownTrackNumbers.size > 256) knownTrackNumbers.remove(knownTrackNumbers.keys.first())
    }

    private fun trackKey(packageName: String, mediaId: String): String = "$packageName|$mediaId"

    private fun MediaSession.QueueItem.toSnapshot(): QueueItemSnapshot = QueueItemSnapshot(
        mediaId = description.mediaId,
        title = description.title?.toString().clean()
            ?: description.extras.firstText(
                MediaMetadata.METADATA_KEY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
            ),
        artist = description.subtitle?.toString().clean()
            ?: description.extras.firstText(
                MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
            ),
        album = description.extras?.getString(MediaMetadata.METADATA_KEY_ALBUM).clean(),
        trackNumber = description.extras?.intMetadata(MediaMetadata.METADATA_KEY_TRACK_NUMBER),
        queueItemId = queueId.takeIf { it >= 0L },
    )

    private fun PlaybackState?.toPlaybackStatus(): PlaybackStatus = when (this?.state) {
        PlaybackState.STATE_PLAYING -> PlaybackStatus.PLAYING
        PlaybackState.STATE_BUFFERING -> PlaybackStatus.BUFFERING
        PlaybackState.STATE_PAUSED -> PlaybackStatus.PAUSED
        PlaybackState.STATE_STOPPED -> PlaybackStatus.STOPPED
        else -> PlaybackStatus.NONE
    }

    private fun PlaybackState?.toShuffleState(): ShuffleState {
        val actionName = this?.customActions.orEmpty()
            .mapNotNull { it.name?.toString()?.trim()?.lowercase(java.util.Locale.ROOT) }
            .firstOrNull { it.contains("shuffle") || it.contains("\uC154\uD50C") }
            ?: return ShuffleState.UNKNOWN
        return when {
            actionName.contains("off") || actionName.contains("disable") || actionName.contains("\uC548\uD568") -> ShuffleState.ON
            actionName.contains("on") || actionName.contains("enable") || actionName.contains("\uC0AC\uC6A9") -> ShuffleState.OFF
            else -> ShuffleState.UNKNOWN
        }
    }

    private data class QueueHistory(
        val order: List<String>,
        val reordered: Boolean,
    )
}
