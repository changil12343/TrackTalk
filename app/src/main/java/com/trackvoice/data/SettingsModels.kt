package com.trackvoice.data

const val DEFAULT_TTS_VOLUME_PERCENT = 80
const val DEFAULT_TTS_VOLUME = 0.80f
const val DEFAULT_MUSIC_DUCK_PERCENT = 50
const val MIN_MUSIC_DUCK_PERCENT = 10
const val MAX_MUSIC_DUCK_PERCENT = 80
const val YOUTUBE_PACKAGE_NAME = "com.google.android.youtube"

/**
 * The default is applied only when storage has no voice-volume value yet.
 * Once a value exists, including a value written by an older build, it is
 * treated as an explicit user preference and must not be replaced.
 */
object TtsVolumeDefaultPolicy {
    fun valueFor(storedVolume: Float?): Float = storedVolume ?: DEFAULT_TTS_VOLUME

    fun shouldWriteDefault(storedVolume: Float?): Boolean = storedVolume == null
}

fun defaultAppGuideEnabled(packageName: String, appName: String = ""): Boolean =
    AppGuideEnablementPolicy.defaultEnabled(packageName, appName)

/**
 * The single source of truth for which media routes may receive announcements.
 * Built-in speaker and earpiece are never considered external routes.
 */
enum class AnnouncementOutputPolicy {
    ALL_OUTPUTS,
    EXTERNAL_ONLY,
    ;

    fun allows(externalAudioOutput: Boolean): Boolean =
        this == ALL_OUTPUTS || externalAudioOutput

    companion object {
        /**
         * Preserve the old behavior while migrating the two legacy switches:
         * either old switch enabled meant speaker suppression, while both false
         * explicitly meant that all routes were allowed. Missing old values use
         * the previous safe default, which suppressed the built-in speaker.
         */
        fun fromLegacy(
            headphonesOnly: Boolean?,
            suppressDuringSpeakerPlayback: Boolean?,
        ): AnnouncementOutputPolicy = if (
            headphonesOnly == true || suppressDuringSpeakerPlayback != false
        ) {
            EXTERNAL_ONLY
        } else {
            ALL_OUTPUTS
        }
    }
}

enum class AnnouncementMode(val label: String) {
    SMART("Smart"),
    ALBUM("앨범 정보"),
    PLAYLIST("재생목록 정보"),
    TITLE_AND_ARTIST("제목 + 아티스트"),
    TITLE_ONLY("제목만"),
}

/**
 * The first item spoken in a multi-field announcement.
 *
 * DEFAULT keeps the content-aware order (for example, album -> track ->
 * title -> artist for albums). The other values move the selected field to
 * the front and keep the remaining checked fields in that stable order.
 */
enum class AnnouncementOrder {
    DEFAULT,
    TITLE_FIRST,
    ALBUM_FIRST,
    TRACK_NUMBER_FIRST,
    ARTIST_FIRST,
    COLLECTION_FIRST,
}

enum class CollectionFallback {
    AUTO,
    ALBUM,
    PLAYLIST,
    ALGORITHMIC,
}

enum class AnnouncementReadField {
    TITLE,
    ARTIST,
    TRACK_NUMBER,
    ALBUM,
    COLLECTION,
}

/** Every field that can be selected in the global reading configuration. */
val ALL_ANNOUNCEMENT_READ_FIELDS = listOf(
    AnnouncementReadField.TITLE,
    AnnouncementReadField.ARTIST,
    AnnouncementReadField.ALBUM,
    AnnouncementReadField.TRACK_NUMBER,
    AnnouncementReadField.COLLECTION,
)

/**
 * The product-level reading configuration. Playback source/context is not a
 * reliable MediaSession fact, so this is intentionally the only user-facing
 * field set used by the runtime.
 */
val GLOBAL_ANNOUNCEMENT_READ_FIELDS = listOf(
    AnnouncementReadField.TITLE,
    AnnouncementReadField.ARTIST,
    AnnouncementReadField.ALBUM,
    AnnouncementReadField.TRACK_NUMBER,
)

/**
 * Fields exposed by the beta product surface. Track number remains in the
 * internal/persisted model for a future metadata-backed release, but is not
 * currently reliable enough to expose or speak by default.
 */
val BETA_VISIBLE_ANNOUNCEMENT_READ_FIELDS = GLOBAL_ANNOUNCEMENT_READ_FIELDS
    .filterNot { it == AnnouncementReadField.TRACK_NUMBER }

/** The canonical default order for album announcements. */
val DEFAULT_ALBUM_READ_FIELDS = listOf(
    AnnouncementReadField.ALBUM,
    AnnouncementReadField.TRACK_NUMBER,
    AnnouncementReadField.TITLE,
    AnnouncementReadField.ARTIST,
)

/** The canonical default order for playlist announcements. */
val DEFAULT_PLAYLIST_READ_FIELDS = listOf(
    AnnouncementReadField.COLLECTION,
    AnnouncementReadField.ALBUM,
    AnnouncementReadField.TRACK_NUMBER,
    AnnouncementReadField.TITLE,
    AnnouncementReadField.ARTIST,
)

/** The canonical default order for recommendation/shuffle announcements. */
val DEFAULT_ALGORITHMIC_READ_FIELDS = listOf(
    AnnouncementReadField.ALBUM,
    AnnouncementReadField.TRACK_NUMBER,
    AnnouncementReadField.TITLE,
    AnnouncementReadField.ARTIST,
)

// The canonical order is also the order used to place inactive chips after
// the active selection. A new user starts with only the title active, while
// Artist and Album remain available immediately after it.
val DEFAULT_GLOBAL_READ_FIELDS = listOf(
    AnnouncementReadField.TITLE,
    AnnouncementReadField.ARTIST,
    AnnouncementReadField.ALBUM,
)

/** Fields enabled for a genuinely new/untouched global configuration. */
val DEFAULT_GLOBAL_ENABLED_READ_FIELDS = listOf(
    AnnouncementReadField.TITLE,
)

/**
 * Removes duplicates/unsupported values while preserving the user's order.
 * A reading configuration must never become empty: old or corrupt storage is
 * normalized to its content-specific default, then to the first supported
 * field as a final safety net.
 */
fun normalizeAnnouncementReadFields(
    fields: Iterable<AnnouncementReadField>,
    allowedFields: List<AnnouncementReadField>,
    fallbackFields: List<AnnouncementReadField>,
): List<AnnouncementReadField> {
    val allowed = allowedFields.toSet()
    val normalized = fields
        .filter { it in allowed }
        .distinct()
    if (normalized.isNotEmpty()) return normalized

    val fallback = fallbackFields
        .filter { it in allowed }
        .distinct()
    return fallback.ifEmpty { allowedFields.distinct().take(1) }
}

/**
 * Normalizes the beta-visible order without dropping hidden internal fields
 * from persisted settings. This lets the future track-number feature return
 * without treating a UI refresh as a destructive migration.
 */
fun mergeBetaVisibleAnnouncementReadFields(
    storedFields: List<AnnouncementReadField>,
    visibleFields: Iterable<AnnouncementReadField>,
): List<AnnouncementReadField> {
    val visible = normalizeAnnouncementReadFields(
        fields = visibleFields,
        allowedFields = BETA_VISIBLE_ANNOUNCEMENT_READ_FIELDS,
        fallbackFields = DEFAULT_GLOBAL_ENABLED_READ_FIELDS,
    )
    val hidden = storedFields
        .filter { it in GLOBAL_ANNOUNCEMENT_READ_FIELDS && it !in BETA_VISIBLE_ANNOUNCEMENT_READ_FIELDS }
        .distinct()
    if (hidden.isEmpty()) return visible

    val visibleSet = visible.toSet()
    val merged = mutableListOf<AnnouncementReadField>()
    var visibleIndex = 0
    storedFields.forEach { field ->
        when {
            field in hidden -> merged += field
            field in visibleSet -> {
                if (visibleIndex < visible.size) merged += visible[visibleIndex++]
            }
        }
    }
    merged += visible.drop(visibleIndex)
    return merged.distinct()
}

/**
 * Toggles a field in the canonical ordered selection. The final active field
 * cannot be removed, preventing a silent no-text configuration in the UI.
 */
fun toggleAnnouncementReadField(
    fields: List<AnnouncementReadField>,
    field: AnnouncementReadField,
    enabled: Boolean,
    allowedFields: List<AnnouncementReadField>,
    fallbackFields: List<AnnouncementReadField> = allowedFields,
): List<AnnouncementReadField> {
    val normalized = normalizeAnnouncementReadFields(fields, allowedFields, fallbackFields)
    return when {
        enabled && field !in normalized && field in allowedFields -> normalized + field
        !enabled && field in normalized && normalized.size > 1 -> normalized - field
        else -> normalized
    }
}

/** Reorders one active field without changing the active/inactive selection. */
fun reorderAnnouncementReadField(
    fields: List<AnnouncementReadField>,
    field: AnnouncementReadField,
    targetIndex: Int,
    allowedFields: List<AnnouncementReadField>,
    fallbackFields: List<AnnouncementReadField> = allowedFields,
): List<AnnouncementReadField> {
    val normalized = normalizeAnnouncementReadFields(fields, allowedFields, fallbackFields)
    val currentIndex = normalized.indexOf(field)
    if (currentIndex < 0 || normalized.size < 2) return normalized
    val mutable = normalized.toMutableList()
    val item = mutable.removeAt(currentIndex)
    mutable.add(targetIndex.coerceIn(0, mutable.size), item)
    return mutable
}

/**
 * Applies the old single-first-field setting while migrating legacy data.
 * New UI changes store the full order and reset this compatibility value.
 */
fun List<AnnouncementReadField>.withLegacyAnnouncementOrder(
    order: AnnouncementOrder,
): List<AnnouncementReadField> {
    val first = when (order) {
        AnnouncementOrder.DEFAULT -> null
        AnnouncementOrder.TITLE_FIRST -> AnnouncementReadField.TITLE
        AnnouncementOrder.ALBUM_FIRST -> AnnouncementReadField.ALBUM
        AnnouncementOrder.TRACK_NUMBER_FIRST -> AnnouncementReadField.TRACK_NUMBER
        AnnouncementOrder.ARTIST_FIRST -> AnnouncementReadField.ARTIST
        AnnouncementOrder.COLLECTION_FIRST -> AnnouncementReadField.COLLECTION
    } ?: return this
    if (first !in this) return this
    return listOf(first) + filterNot { it == first }
}

enum class AnnouncementTiming(val label: String) {
    IMMEDIATE("즉시"),
    DELAYED("잠시 후"),
    BETWEEN_TRACKS("곡 사이"),
}

enum class VoiceLanguage(val label: String) {
    AUTO("제목에 맞게 자동"),
    SYSTEM("시스템 언어"),
    KOREAN("한국어"),
    ENGLISH("영어"),
}

enum class MusicTreatment(val label: String) {
    KEEP("그대로 재생"),
    DUCK("음량 줄이기"),
    PAUSE("일시정지"),
}

enum class TrackStartBehavior(val label: String) {
    PLAY_IMMEDIATELY("음악과 함께 안내"),
    ANNOUNCE_THEN_PLAY("곡명 안내 후 재생"),
}

enum class GenderFilter(val label: String) {
    ANY("자동 선택"),
    FEMALE("여성 음성"),
    MALE("남성 음성"),
    UNSPECIFIED("자동 선택"),
}

enum class AppLanguage {
    SYSTEM,
    KOREAN,
    ENGLISH,
}

val APP_LANGUAGE_OPTIONS = listOf(
    AppLanguage.SYSTEM,
    AppLanguage.ENGLISH,
    AppLanguage.KOREAN,
)

fun AppLanguage.resolve(systemLanguage: String): AppLanguage = when (this) {
    AppLanguage.SYSTEM -> if (systemLanguage.startsWith("ko", ignoreCase = true)) {
        AppLanguage.KOREAN
    } else {
        AppLanguage.ENGLISH
    }
    AppLanguage.KOREAN,
    AppLanguage.ENGLISH,
    -> this
}

data class UserSettings(
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val enabled: Boolean = true,
    val autoEnableOnScreenOff: Boolean = false,
    val restoreEnabledWhenScreenOn: Boolean = true,
    val outputPolicy: AnnouncementOutputPolicy = AnnouncementOutputPolicy.EXTERNAL_ONLY,
    val bluetoothOnlyForAutoEnable: Boolean = false,
    val musicTreatment: MusicTreatment = MusicTreatment.DUCK,
    val musicDuckPercent: Int = DEFAULT_MUSIC_DUCK_PERCENT,
    val trackStartBehavior: TrackStartBehavior = TrackStartBehavior.PLAY_IMMEDIATELY,
    val showStatusNotification: Boolean = true,
    val timing: AnnouncementTiming = AnnouncementTiming.IMMEDIATE,
    val delaySeconds: Int = 0,
    val defaultMode: AnnouncementMode = AnnouncementMode.SMART,
    // Playback source context is not a reliable MediaSession fact. The
    // runtime and UI therefore use one global field order.
    val useContentTypeSettings: Boolean = false,
    val defaultReadFields: List<AnnouncementReadField> = DEFAULT_GLOBAL_ENABLED_READ_FIELDS,
    val announcementOrder: AnnouncementOrder = AnnouncementOrder.DEFAULT,
    val allowRepeatAnnouncements: Boolean = false,
    val minimumPlaybackSeconds: Int = 0,
    val albumMode: AnnouncementMode = AnnouncementMode.ALBUM,
    val playlistMode: AnnouncementMode = AnnouncementMode.PLAYLIST,
    val algorithmMode: AnnouncementMode = AnnouncementMode.TITLE_AND_ARTIST,
    val albumReadFields: List<AnnouncementReadField> = DEFAULT_ALBUM_READ_FIELDS,
    val albumNameFirstTrackOnly: Boolean = false,
    val playlistReadFields: List<AnnouncementReadField> = DEFAULT_PLAYLIST_READ_FIELDS,
    val algorithmReadFields: List<AnnouncementReadField> = DEFAULT_ALGORITHMIC_READ_FIELDS,
    val voiceLanguage: VoiceLanguage = VoiceLanguage.AUTO,
    val voiceName: String? = null,
    val genderFilter: GenderFilter = GenderFilter.ANY,
    val speechRate: Float = 1f,
    val pitch: Float = 1f,
    val volume: Float = DEFAULT_TTS_VOLUME,
    val raiseDeviceVolume: Boolean = false,
    val deviceVolumePercent: Int = 90,
)

/**
 * v1 automatic announcements never take transport ownership from the source player.
 * Keep all explicit values readable so legacy `PAUSE`/`TRACK` settings are
 * restored exactly as configured by users. Transport ownership is controlled by
 * the playback planner, not by this persistence transform.
 */
internal fun UserSettings.normalizedForAutomaticAnnouncementPlayback(): UserSettings {
    return this
}

data class AudioDeviceSettings(
    val deviceKey: String,
    val displayName: String,
    val autoEnable: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * Merges settings formerly stored under profile-specific keys.
 *
 * The old schema did not persist explicit/default provenance. Its defaults
 * were enabled=true and autoEnable=false, so the opposite values are the only
 * choices known to be intentional and therefore win conflicts.
 */
internal fun mergeAudioDeviceSettings(
    canonicalKey: String,
    displayName: String,
    candidates: List<AudioDeviceSettings>,
): AudioDeviceSettings = AudioDeviceSettings(
    deviceKey = canonicalKey,
    displayName = displayName,
    enabled = candidates.all(AudioDeviceSettings::enabled),
    autoEnable = candidates.any(AudioDeviceSettings::autoEnable),
)

data class AppSettings(
    val packageName: String,
    val appName: String,
    val enabled: Boolean = true,
    /** Null means the app follows its category default; non-null is explicit. */
    val enabledOverride: Boolean? = null,
)
