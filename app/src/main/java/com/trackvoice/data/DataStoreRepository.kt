package com.trackvoice.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.trackVoiceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "trackvoice_settings",
)

/** State that should remain on this device rather than participating in Android Auto Backup. */
private val Context.trackVoicePrivateStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "trackvoice_private_state",
)

private fun appKey(packageName: String, suffix: String): Preferences.Key<String> =
    stringPreferencesKey("app.$packageName.$suffix")

private const val CONTENT_READ_DEFAULT_VERSION = 2
private const val CONTENT_READ_ORDER_VERSION = 2
private const val AUDIO_OUTPUT_POLICY_VERSION = 1
private const val APP_ANNOUNCEMENT_SOURCE_VERSION = 1
private const val PLAYBACK_CONTEXT_SETTINGS_VERSION = 1
private const val RETIRED_TRACK_NUMBER_MIGRATION_VERSION = 1
private const val PRIVATE_PLAYBACK_STATE_MIGRATION_VERSION = 1

data class PersistedAnnouncement(
    val sourcePackageName: String,
    val sourceAppName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val duration: Long?,
    val mediaId: String?,
    val trackNumberReliable: Boolean,
    val trackNumberSource: String,
    val announcedAt: Long,
)

class DataStoreRepository(private val context: Context) {
    private val dataStore = context.trackVoiceDataStore
    private val privateStateDataStore = context.trackVoicePrivateStateDataStore

    val userSettings: Flow<UserSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map { preferences -> preferences.toUserSettings() }

    val appSettings: Flow<Map<String, AppSettings>> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map { preferences -> preferences.toAppSettings() }

    val audioDeviceSettings: Flow<Map<String, AudioDeviceSettings>> = combine(
        dataStore.data.catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        },
        privateStateDataStore.data.catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        },
    ) { publicPreferences, privatePreferences ->
        if ((publicPreferences[Keys.privatePlaybackStateMigrationVersion] ?: 0) >=
                PRIVATE_PLAYBACK_STATE_MIGRATION_VERSION ||
            privatePreferences[Keys.privatePlaybackStateInitialized] == true
        ) {
            privatePreferences.toAudioDeviceSettings()
        } else {
            publicPreferences.toAudioDeviceSettings()
        }
    }

    suspend fun currentUserSettings(): UserSettings = userSettings.first()
    suspend fun currentAppSettings(): Map<String, AppSettings> = appSettings.first()
    suspend fun currentAudioDeviceSettings(): Map<String, AudioDeviceSettings> = audioDeviceSettings.first()

    suspend fun currentPersistedAnnouncement(): PersistedAnnouncement? {
        val publicPreferences = dataStore.data.first()
        val privatePreferences = privateStateDataStore.data.first()
        return if ((publicPreferences[Keys.privatePlaybackStateMigrationVersion] ?: 0) >=
                PRIVATE_PLAYBACK_STATE_MIGRATION_VERSION ||
            privatePreferences[Keys.privatePlaybackStateInitialized] == true
        ) {
            privatePreferences.toPersistedAnnouncement()
        } else {
            publicPreferences.toPersistedAnnouncement()
        }
    }

    suspend fun savePersistedAnnouncement(announcement: PersistedAnnouncement) {
        privateStateDataStore.edit { preferences ->
            preferences[Keys.lastAnnouncementPackage] = announcement.sourcePackageName
            preferences[Keys.lastAnnouncementAppName] = announcement.sourceAppName
            setOptional(preferences, Keys.lastAnnouncementTitle, announcement.title)
            setOptional(preferences, Keys.lastAnnouncementArtist, announcement.artist)
            setOptional(preferences, Keys.lastAnnouncementAlbum, announcement.album)
            setOptional(preferences, Keys.lastAnnouncementMediaId, announcement.mediaId)
            announcement.trackNumber?.let { preferences[Keys.lastAnnouncementTrackNumber] = it }
                ?: preferences.remove(Keys.lastAnnouncementTrackNumber)
            announcement.discNumber?.let { preferences[Keys.lastAnnouncementDiscNumber] = it }
                ?: preferences.remove(Keys.lastAnnouncementDiscNumber)
            announcement.duration?.let { preferences[Keys.lastAnnouncementDuration] = it }
                ?: preferences.remove(Keys.lastAnnouncementDuration)
            preferences[Keys.lastAnnouncementTrackNumberReliable] = announcement.trackNumberReliable
            preferences[Keys.lastAnnouncementTrackNumberSource] = announcement.trackNumberSource
            preferences[Keys.lastAnnouncementAt] = announcement.announcedAt
            preferences[Keys.privatePlaybackStateInitialized] = true
        }
    }

    suspend fun clearPersistedAnnouncement() {
        privateStateDataStore.edit { preferences ->
            preferences.remove(Keys.lastAnnouncementPackage)
            preferences.remove(Keys.lastAnnouncementAppName)
            preferences.remove(Keys.lastAnnouncementTitle)
            preferences.remove(Keys.lastAnnouncementArtist)
            preferences.remove(Keys.lastAnnouncementAlbum)
            preferences.remove(Keys.lastAnnouncementTrackNumber)
            preferences.remove(Keys.lastAnnouncementDiscNumber)
            preferences.remove(Keys.lastAnnouncementDuration)
            preferences.remove(Keys.lastAnnouncementMediaId)
            preferences.remove(Keys.lastAnnouncementTrackNumberReliable)
            preferences.remove(Keys.lastAnnouncementTrackNumberSource)
            preferences.remove(Keys.lastAnnouncementAt)
            preferences[Keys.privatePlaybackStateInitialized] = true
        }
    }

    suspend fun migrateAnnouncementVolumeMode() {
        dataStore.edit { preferences ->
            val storedVolume = preferences[Keys.volume]
            val mode = AnnouncementVolumeMigrationPolicy.modeFor(
                storedMode = preferences[Keys.announcementVolumeMode],
                storedCustomVolume = storedVolume,
            )
            if (preferences[Keys.announcementVolumeMode] != mode.name) {
                preferences[Keys.announcementVolumeMode] = mode.name
            }
        }
    }

    /**
     * Retires the hidden Track Number configuration that previously enabled an
     * external catalog request. Existing choices retain every supported local
     * field, and a Track Number-only choice falls back to Title.
     */
    suspend fun migrateRetiredTrackNumberState() {
        dataStore.edit { preferences ->
            if ((preferences[Keys.retiredTrackNumberMigrationVersion] ?: 0) >=
                RETIRED_TRACK_NUMBER_MIGRATION_VERSION
            ) {
                return@edit
            }
            val legacyOrder = enumOrDefault(
                preferences[Keys.announcementOrder],
                AnnouncementOrder.DEFAULT,
            )
            val migratedFields = orderedFieldsFromStorage(
                storedOrder = preferences[Keys.defaultReadOrder],
                legacyFields = preferences[Keys.defaultReadFields],
                allowedFields = GLOBAL_ANNOUNCEMENT_READ_FIELDS,
                fallbackFields = DEFAULT_GLOBAL_ENABLED_READ_FIELDS,
                legacyOrder = legacyOrder,
            )
            preferences[Keys.defaultReadFields] = migratedFields.map(AnnouncementReadField::name).toSet()
            preferences[Keys.defaultReadOrder] = migratedFields.encodeReadFields()
            val albumFields = orderedFieldsFromStorage(
                storedOrder = preferences[Keys.albumReadOrder],
                legacyFields = preferences[Keys.albumReadFields],
                allowedFields = DEFAULT_ALBUM_READ_FIELDS,
                fallbackFields = DEFAULT_ALBUM_READ_FIELDS,
                legacyOrder = legacyOrder,
            )
            preferences[Keys.albumReadFields] = albumFields.map(AnnouncementReadField::name).toSet()
            preferences[Keys.albumReadOrder] = albumFields.encodeReadFields()
            val playlistFields = orderedFieldsFromStorage(
                storedOrder = preferences[Keys.playlistReadOrder],
                legacyFields = preferences[Keys.playlistReadFields],
                allowedFields = DEFAULT_PLAYLIST_READ_FIELDS,
                fallbackFields = DEFAULT_PLAYLIST_READ_FIELDS,
                legacyOrder = legacyOrder,
            )
            preferences[Keys.playlistReadFields] = playlistFields.map(AnnouncementReadField::name).toSet()
            preferences[Keys.playlistReadOrder] = playlistFields.encodeReadFields()
            val algorithmFields = orderedFieldsFromStorage(
                storedOrder = preferences[Keys.algorithmReadOrder],
                legacyFields = preferences[Keys.algorithmReadFields],
                allowedFields = DEFAULT_ALGORITHMIC_READ_FIELDS,
                fallbackFields = DEFAULT_ALGORITHMIC_READ_FIELDS,
                legacyOrder = legacyOrder,
            )
            preferences[Keys.algorithmReadFields] = algorithmFields.map(AnnouncementReadField::name).toSet()
            preferences[Keys.algorithmReadOrder] = algorithmFields.encodeReadFields()
            preferences[Keys.announcementOrder] = AnnouncementOrder.DEFAULT.name
            preferences.asMap().keys
                .map { it.name }
                .filter { it.startsWith(EXTERNAL_METADATA_KEY_PREFIX) }
                .forEach { preferences.remove(stringPreferencesKey(it)) }
            preferences[Keys.retiredTrackNumberMigrationVersion] = RETIRED_TRACK_NUMBER_MIGRATION_VERSION
        }
    }

    /**
     * Moves recent media and stable output-device state into a separately
     * excluded backup file. Copying first keeps an interrupted upgrade from
     * losing duplicate-suppression or device choices.
     */
    suspend fun migratePrivatePlaybackStateForBackup() {
        val publicPreferences = dataStore.data.first()
        if ((publicPreferences[Keys.privatePlaybackStateMigrationVersion] ?: 0) >=
            PRIVATE_PLAYBACK_STATE_MIGRATION_VERSION
        ) {
            return
        }
        privateStateDataStore.edit { privatePreferences ->
            if (privatePreferences[Keys.privatePlaybackStateInitialized] != true) {
                privatePreferences.copyPrivatePlaybackStateFrom(publicPreferences)
                privatePreferences[Keys.privatePlaybackStateInitialized] = true
            }
        }
        dataStore.edit { preferences ->
            if ((preferences[Keys.privatePlaybackStateMigrationVersion] ?: 0) >=
                PRIVATE_PLAYBACK_STATE_MIGRATION_VERSION
            ) {
                return@edit
            }
            preferences.removePrivatePlaybackState()
            preferences[Keys.privatePlaybackStateMigrationVersion] = PRIVATE_PLAYBACK_STATE_MIGRATION_VERSION
        }
    }

    suspend fun migrateContentReadDefaults() {
        dataStore.edit { preferences ->
            if ((preferences[Keys.contentReadDefaultVersion] ?: 0) >= CONTENT_READ_DEFAULT_VERSION) {
                return@edit
            }

            // Only migrate values that can be identified as an untouched
            // default. Any other set/order is a user choice and must remain
            // intact. The exact three-field order is the default written by
            // the previous beta migration, so it is safe to move that one
            // known legacy state to the new Title-only default.
            migrateUntouchedGlobalReadDefaults(
                storedFields = preferences[Keys.defaultReadFields],
                storedOrder = preferences[Keys.defaultReadOrder],
                announcementOrder = enumOrDefault(
                    preferences[Keys.announcementOrder],
                    AnnouncementOrder.DEFAULT,
                ),
            ).let { migration ->
                migration.fields?.let { preferences[Keys.defaultReadFields] = it }
                migration.order?.let { preferences[Keys.defaultReadOrder] = it }
            }

            val oldPlaylistDefaults = setOf(
                AnnouncementReadField.COLLECTION.name,
                AnnouncementReadField.TITLE.name,
                AnnouncementReadField.ARTIST.name,
            )
            if (preferences[Keys.playlistReadFields] == oldPlaylistDefaults) {
                preferences[Keys.playlistReadFields] = DEFAULT_PLAYLIST_READ_FIELDS.map(AnnouncementReadField::name).toSet()
            }
            if (preferences[Keys.algorithmReadFields] == setOf(
                    AnnouncementReadField.TITLE.name,
                    AnnouncementReadField.ARTIST.name,
                )) {
                preferences[Keys.algorithmReadFields] = DEFAULT_ALGORITHMIC_READ_FIELDS.map(AnnouncementReadField::name).toSet()
            }
            preferences[Keys.contentReadDefaultVersion] = CONTENT_READ_DEFAULT_VERSION
        }
    }

    /**
     * Migrate the old unordered string sets to the canonical ordered-list
     * representation. The old first-field preference is folded into the list
     * once, then reset so it cannot override later drag ordering.
     */
    suspend fun migrateContentReadOrder() {
        dataStore.edit { preferences ->
            if ((preferences[Keys.contentReadOrderVersion] ?: 0) >= CONTENT_READ_ORDER_VERSION) {
                return@edit
            }

            val legacyOrder = enumOrDefault(
                preferences[Keys.announcementOrder],
                AnnouncementOrder.DEFAULT,
            )
            val defaultFields = orderedFieldsFromStorage(
                storedOrder = preferences[Keys.defaultReadOrder],
                legacyFields = preferences[Keys.defaultReadFields],
                allowedFields = GLOBAL_ANNOUNCEMENT_READ_FIELDS,
                fallbackFields = DEFAULT_GLOBAL_ENABLED_READ_FIELDS,
                legacyOrder = legacyOrder,
            )
            val albumFields = orderedFieldsFromStorage(
                storedOrder = preferences[Keys.albumReadOrder],
                legacyFields = preferences[Keys.albumReadFields],
                allowedFields = DEFAULT_ALBUM_READ_FIELDS,
                fallbackFields = DEFAULT_ALBUM_READ_FIELDS,
                legacyOrder = legacyOrder,
            )
            val playlistFields = orderedFieldsFromStorage(
                storedOrder = preferences[Keys.playlistReadOrder],
                legacyFields = preferences[Keys.playlistReadFields],
                allowedFields = DEFAULT_PLAYLIST_READ_FIELDS,
                fallbackFields = DEFAULT_PLAYLIST_READ_FIELDS,
                legacyOrder = legacyOrder,
            )
            val algorithmFields = orderedFieldsFromStorage(
                storedOrder = preferences[Keys.algorithmReadOrder],
                legacyFields = preferences[Keys.algorithmReadFields],
                allowedFields = DEFAULT_ALGORITHMIC_READ_FIELDS,
                fallbackFields = DEFAULT_ALGORITHMIC_READ_FIELDS,
                legacyOrder = legacyOrder,
            )

            preferences[Keys.defaultReadOrder] = defaultFields.encodeReadFields()
            preferences[Keys.albumReadOrder] = albumFields.encodeReadFields()
            preferences[Keys.playlistReadOrder] = playlistFields.encodeReadFields()
            preferences[Keys.algorithmReadOrder] = algorithmFields.encodeReadFields()
            preferences[Keys.announcementOrder] = AnnouncementOrder.DEFAULT.name
            preferences[Keys.contentReadOrderVersion] = CONTENT_READ_ORDER_VERSION
        }
    }

    /**
     * Per-app announcement fields/modes were removed from the product model.
     * Keep the app enablement override, but delete the old policy keys so an
     * upgrade can never resurrect an app-specific announcement source.
     */
    suspend fun migrateLegacyAppAnnouncementSettings() {
        dataStore.edit { preferences ->
            if ((preferences[Keys.appAnnouncementSourceVersion] ?: 0) >= APP_ANNOUNCEMENT_SOURCE_VERSION) {
                return@edit
            }

            preferences[Keys.knownPackages].orEmpty().forEach { packageName ->
                if (preferences[AppKeys.alwaysExclude(packageName)] == true) {
                    preferences[AppKeys.enabledOverride(packageName)] = false
                    preferences[AppKeys.enabled(packageName)] = false
                }
                removeLegacyAppAnnouncementKeys(preferences, packageName)
            }
            preferences[Keys.appAnnouncementSourceVersion] = APP_ANNOUNCEMENT_SOURCE_VERSION
        }
    }

    /** Legacy content-type values stay stored for migration, but are no longer runtime policy. */
    suspend fun migratePlaybackContextSettings() {
        dataStore.edit { preferences ->
            if ((preferences[Keys.playbackContextSettingsVersion] ?: 0) >= PLAYBACK_CONTEXT_SETTINGS_VERSION) {
                return@edit
            }
            preferences[Keys.useContentTypeSettings] = false
            preferences[Keys.playbackContextSettingsVersion] = PLAYBACK_CONTEXT_SETTINGS_VERSION
        }
    }

    suspend fun migrateAudioOutputPolicy() {
        dataStore.edit { preferences ->
            if ((preferences[Keys.audioOutputPolicyVersion] ?: 0) >= AUDIO_OUTPUT_POLICY_VERSION) {
                return@edit
            }

            if (preferences[Keys.outputPolicy] == null) {
                preferences[Keys.outputPolicy] = AnnouncementOutputPolicy.fromLegacy(
                    headphonesOnly = preferences[Keys.headphonesOnly],
                    suppressDuringSpeakerPlayback = preferences[Keys.suppressDuringSpeakerPlayback],
                ).name
            }
            // Once the canonical value is present, the two old switches must
            // not remain writable sources of truth.
            preferences.remove(Keys.headphonesOnly)
            preferences.remove(Keys.suppressDuringSpeakerPlayback)
            preferences[Keys.audioOutputPolicyVersion] = AUDIO_OUTPUT_POLICY_VERSION
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.enabled] = enabled }
    }

    suspend fun updateUserSettings(transform: (UserSettings) -> UserSettings) {
        dataStore.edit { preferences ->
            val updated = preferences.toUserSettings().let(transform)
            preferences.writeUserSettings(updated)
        }
    }

    suspend fun ensureApp(packageName: String, appName: String) {
        if (packageName.isBlank()) return
        dataStore.edit { preferences ->
            val packages = preferences[Keys.knownPackages].orEmpty().toMutableSet()
            val displayName = appName.ifBlank { packageName }
            if (packageName !in packages) {
                packages += packageName
                preferences[Keys.knownPackages] = packages
            }
            if (preferences[appKey(packageName, "name")] != displayName) {
                preferences[appKey(packageName, "name")] = displayName
            }
        }
    }

    suspend fun updateAppSettings(settings: AppSettings) {
        dataStore.edit { preferences ->
            val packages = preferences[Keys.knownPackages].orEmpty().toMutableSet()
            packages += settings.packageName
            preferences[Keys.knownPackages] = packages
            preferences.writeAppSettings(settings)
        }
    }

    suspend fun updateAudioDeviceSettings(settings: AudioDeviceSettings) {
        privateStateDataStore.edit { preferences ->
            val keys = preferences[Keys.knownDeviceKeys].orEmpty().toMutableSet()
            keys += settings.deviceKey
            preferences[Keys.knownDeviceKeys] = keys
            preferences.writeAudioDeviceSettings(settings)
            preferences[Keys.privatePlaybackStateInitialized] = true
        }
    }

    suspend fun reconcileAudioDeviceSettings(
        canonicalKey: String,
        displayName: String,
        legacyKeys: Set<String>,
    ): AudioDeviceSettings {
        var reconciled = AudioDeviceSettings(canonicalKey, displayName)
        privateStateDataStore.edit { preferences ->
            val knownKeys = preferences[Keys.knownDeviceKeys].orEmpty().toMutableSet()
            val aliases = legacyKeys - canonicalKey
            val candidateKeys = buildSet {
                add(canonicalKey)
                addAll(aliases)
            }
            val candidates = candidateKeys
                .filter { it in knownKeys }
                .map(preferences::readAudioDeviceSettings)
            reconciled = mergeAudioDeviceSettings(
                canonicalKey = canonicalKey,
                displayName = displayName,
                candidates = candidates,
            )

            aliases.forEach { alias ->
                preferences.removeAudioDeviceSettings(alias)
                knownKeys -= alias
            }
            knownKeys += canonicalKey
            preferences[Keys.knownDeviceKeys] = knownKeys
            preferences.writeAudioDeviceSettings(reconciled)
            preferences[Keys.privatePlaybackStateInitialized] = true
        }
        return reconciled
    }

    suspend fun removeAudioDeviceSettings(deviceKeys: Set<String>) {
        if (deviceKeys.isEmpty()) return
        privateStateDataStore.edit { preferences ->
            val knownKeys = preferences[Keys.knownDeviceKeys].orEmpty().toMutableSet()
            deviceKeys.forEach { key ->
                preferences.removeAudioDeviceSettings(key)
                knownKeys -= key
            }
            preferences[Keys.knownDeviceKeys] = knownKeys
            preferences[Keys.privatePlaybackStateInitialized] = true
        }
    }

    suspend fun removeApp(packageName: String) {
        dataStore.edit { preferences ->
            val packages = preferences[Keys.knownPackages].orEmpty().toMutableSet()
            packages -= packageName
            preferences[Keys.knownPackages] = packages
            preferences.remove(AppKeys.name(packageName))
            preferences.remove(AppKeys.enabled(packageName))
            preferences.remove(AppKeys.enabledOverride(packageName))
            preferences.remove(AppKeys.useCustomGuideSettings(packageName))
            preferences.remove(AppKeys.mode(packageName))
            preferences.remove(AppKeys.collectionFallback(packageName))
            preferences.remove(AppKeys.readTitle(packageName))
            preferences.remove(AppKeys.readArtist(packageName))
            preferences.remove(AppKeys.readTrackNumber(packageName))
            preferences.remove(AppKeys.readAlbum(packageName))
            preferences.remove(AppKeys.readCollection(packageName))
            preferences.remove(AppKeys.timing(packageName))
            preferences.remove(AppKeys.alwaysExclude(packageName))
        }
    }

    internal object Keys {
        val enabled = booleanPreferencesKey("enabled")
        val appLanguage = stringPreferencesKey("app_language")
        val autoEnableOnScreenOff = booleanPreferencesKey("auto_enable_screen_off")
        val restoreEnabledWhenScreenOn = booleanPreferencesKey("restore_on_screen_on")
        val outputPolicy = stringPreferencesKey("announcement_output_policy")
        val audioOutputPolicyVersion = intPreferencesKey("audio_output_policy_version")
        // Legacy keys are read only during migration/compatibility parsing.
        val headphonesOnly = booleanPreferencesKey("headphones_only")
        val bluetoothOnlyForAutoEnable = booleanPreferencesKey("bluetooth_only_auto")
        val suppressDuringSpeakerPlayback = booleanPreferencesKey("suppress_speaker")
        val pauseMusicDuringAnnouncement = booleanPreferencesKey("pause_music_during_announcement")
        val musicTreatment = stringPreferencesKey("music_treatment")
        val musicDuckPercent = intPreferencesKey("music_duck_percent")
        val trackStartBehavior = stringPreferencesKey("track_start_behavior")
        val showStatusNotification = booleanPreferencesKey("show_status_notification")
        val timing = stringPreferencesKey("timing")
        val delaySeconds = intPreferencesKey("delay_seconds")
        val defaultMode = stringPreferencesKey("default_mode")
        val useContentTypeSettings = booleanPreferencesKey("use_content_type_settings")
        val defaultReadFields = stringSetPreferencesKey("default_read_fields")
        val defaultReadOrder = stringPreferencesKey("default_read_order_v2")
        val announcementOrder = stringPreferencesKey("announcement_order")
        val allowRepeatAnnouncements = booleanPreferencesKey("allow_repeat")
        val minimumPlaybackSeconds = intPreferencesKey("minimum_playback_seconds")
        val albumMode = stringPreferencesKey("album_mode")
        val playlistMode = stringPreferencesKey("playlist_mode")
        val algorithmMode = stringPreferencesKey("algorithm_mode")
        val albumReadFields = stringSetPreferencesKey("album_read_fields")
        val albumReadOrder = stringPreferencesKey("album_read_order_v2")
        val albumNameFirstTrackOnly = booleanPreferencesKey("album_name_first_track_only")
        val playlistReadFields = stringSetPreferencesKey("playlist_read_fields")
        val playlistReadOrder = stringPreferencesKey("playlist_read_order_v2")
        val algorithmReadFields = stringSetPreferencesKey("algorithm_read_fields")
        val algorithmReadOrder = stringPreferencesKey("algorithm_read_order_v2")
        val voiceLanguage = stringPreferencesKey("voice_language")
        val voiceName = stringPreferencesKey("voice_name")
        val genderFilter = stringPreferencesKey("gender_filter")
        val speechRate = floatPreferencesKey("speech_rate")
        val pitch = floatPreferencesKey("pitch")
        val announcementVolumeMode = stringPreferencesKey("announcement_volume_mode")
        val volume = floatPreferencesKey("volume")
        val contentReadDefaultVersion = intPreferencesKey("content_read_default_version")
        val contentReadOrderVersion = intPreferencesKey("content_read_order_version")
        val playbackContextSettingsVersion = intPreferencesKey("playback_context_settings_version")
        val appAnnouncementSourceVersion = intPreferencesKey("app_announcement_source_version")
        val retiredTrackNumberMigrationVersion = intPreferencesKey("retired_track_number_migration_version")
        val privatePlaybackStateMigrationVersion = intPreferencesKey("private_playback_state_migration_version")
        val privatePlaybackStateInitialized = booleanPreferencesKey("private_playback_state_initialized")
        val raiseDeviceVolume = booleanPreferencesKey("raise_device_volume")
        val deviceVolumePercent = intPreferencesKey("device_volume_percent")
        val knownPackages = stringSetPreferencesKey("known_app_packages")
        val knownDeviceKeys = stringSetPreferencesKey("known_audio_devices")
        val lastAnnouncementPackage = stringPreferencesKey("last_announcement_package")
        val lastAnnouncementAppName = stringPreferencesKey("last_announcement_app_name")
        val lastAnnouncementTitle = stringPreferencesKey("last_announcement_title")
        val lastAnnouncementArtist = stringPreferencesKey("last_announcement_artist")
        val lastAnnouncementAlbum = stringPreferencesKey("last_announcement_album")
        val lastAnnouncementMediaId = stringPreferencesKey("last_announcement_media_id")
        val lastAnnouncementTrackNumber = intPreferencesKey("last_announcement_track_number")
        val lastAnnouncementDiscNumber = intPreferencesKey("last_announcement_disc_number")
        val lastAnnouncementDuration = longPreferencesKey("last_announcement_duration")
        val lastAnnouncementTrackNumberReliable = booleanPreferencesKey("last_announcement_track_number_reliable")
        val lastAnnouncementTrackNumberSource = stringPreferencesKey("last_announcement_track_number_source")
        val lastAnnouncementAt = longPreferencesKey("last_announcement_at")
    }

    private object AppKeys {
        fun name(packageName: String) = appKey(packageName, "name")
        fun enabled(packageName: String) = appBooleanKey(packageName, "enabled")
        fun enabledOverride(packageName: String) = appBooleanKey(packageName, "enabled_override")
        fun useCustomGuideSettings(packageName: String) = appBooleanKey(packageName, "custom_guide_settings")
        fun mode(packageName: String) = appKey(packageName, "mode")
        fun collectionFallback(packageName: String) = appKey(packageName, "collection_fallback")
        fun readTitle(packageName: String) = appBooleanKey(packageName, "read_title")
        fun readArtist(packageName: String) = appBooleanKey(packageName, "read_artist")
        fun readTrackNumber(packageName: String) = appBooleanKey(packageName, "read_track_number")
        fun readAlbum(packageName: String) = appBooleanKey(packageName, "read_album")
        fun readCollection(packageName: String) = appBooleanKey(packageName, "read_collection")
        fun timing(packageName: String) = appKey(packageName, "timing")
        fun alwaysExclude(packageName: String) = appBooleanKey(packageName, "always_exclude")
    }

    private fun Preferences.toUserSettings(): UserSettings {
        val storedTrackStartBehavior = enumOrDefault(
            this[Keys.trackStartBehavior],
            TrackStartBehavior.PLAY_IMMEDIATELY,
        )
        val storedMusicTreatment = this[Keys.musicTreatment]?.let { enumOrDefault(it, MusicTreatment.DUCK) }
            ?: if (this[Keys.pauseMusicDuringAnnouncement] ?: false) MusicTreatment.PAUSE else MusicTreatment.DUCK
        val albumMode = enumOrDefault(this[Keys.albumMode], AnnouncementMode.ALBUM)
        val playlistMode = enumOrDefault(this[Keys.playlistMode], AnnouncementMode.PLAYLIST)
        val algorithmMode = enumOrDefault(this[Keys.algorithmMode], AnnouncementMode.TITLE_AND_ARTIST)
        val announcementOrder = enumOrDefault(this[Keys.announcementOrder], AnnouncementOrder.DEFAULT)
        val timing = enumOrDefault(this[Keys.timing], AnnouncementTiming.IMMEDIATE).normalizedForSettings()
        val storedVolume = this[Keys.volume]
        val announcementVolumeMode = AnnouncementVolumeMigrationPolicy.modeFor(
            storedMode = this[Keys.announcementVolumeMode],
            storedCustomVolume = storedVolume,
        )
        return UserSettings(
            appLanguage = enumOrDefault(this[Keys.appLanguage], AppLanguage.SYSTEM),
            enabled = this[Keys.enabled] ?: true,
            autoEnableOnScreenOff = this[Keys.autoEnableOnScreenOff] ?: false,
            restoreEnabledWhenScreenOn = this[Keys.restoreEnabledWhenScreenOn] ?: true,
            outputPolicy = enumOrDefault(
                this[Keys.outputPolicy],
                AnnouncementOutputPolicy.fromLegacy(
                    headphonesOnly = this[Keys.headphonesOnly],
                    suppressDuringSpeakerPlayback = this[Keys.suppressDuringSpeakerPlayback],
                ),
            ),
            bluetoothOnlyForAutoEnable = this[Keys.bluetoothOnlyForAutoEnable] ?: false,
            musicTreatment = storedMusicTreatment,
            musicDuckPercent = (this[Keys.musicDuckPercent] ?: DEFAULT_MUSIC_DUCK_PERCENT)
                .coerceIn(MIN_MUSIC_DUCK_PERCENT, MAX_MUSIC_DUCK_PERCENT),
            trackStartBehavior = storedTrackStartBehavior,
            showStatusNotification = this[Keys.showStatusNotification] ?: true,
            timing = timing,
            delaySeconds = AnnouncementTimingPolicy.normalizeStoredDelaySeconds(
                timing,
                this[Keys.delaySeconds] ?: 0,
            ),
            defaultMode = enumOrDefault(this[Keys.defaultMode], AnnouncementMode.SMART),
            // The legacy switch is retained only so old preferences can be
            // read safely. Playback source/context is not a reliable input,
            // so the runtime always uses the global ordered fields.
            useContentTypeSettings = false,
            defaultReadFields = orderedFieldsFromStorage(
                storedOrder = this[Keys.defaultReadOrder],
                legacyFields = this[Keys.defaultReadFields],
                allowedFields = GLOBAL_ANNOUNCEMENT_READ_FIELDS,
                fallbackFields = DEFAULT_GLOBAL_ENABLED_READ_FIELDS,
                legacyOrder = announcementOrder,
            ),
            announcementOrder = announcementOrder,
            allowRepeatAnnouncements = this[Keys.allowRepeatAnnouncements] ?: false,
            minimumPlaybackSeconds = (this[Keys.minimumPlaybackSeconds] ?: 0).coerceIn(0, 120),
            albumMode = albumMode,
            playlistMode = playlistMode,
            algorithmMode = algorithmMode,
            albumReadFields = orderedFieldsFromStorage(
                storedOrder = this[Keys.albumReadOrder],
                legacyFields = this[Keys.albumReadFields],
                allowedFields = DEFAULT_ALBUM_READ_FIELDS,
                fallbackFields = readFieldsForMode(albumMode, DEFAULT_ALBUM_READ_FIELDS),
                legacyOrder = announcementOrder,
            ),
            albumNameFirstTrackOnly = this[Keys.albumNameFirstTrackOnly] ?: false,
            playlistReadFields = orderedFieldsFromStorage(
                storedOrder = this[Keys.playlistReadOrder],
                legacyFields = this[Keys.playlistReadFields],
                allowedFields = DEFAULT_PLAYLIST_READ_FIELDS,
                fallbackFields = readFieldsForMode(playlistMode, DEFAULT_PLAYLIST_READ_FIELDS),
                legacyOrder = announcementOrder,
            ),
            algorithmReadFields = orderedFieldsFromStorage(
                storedOrder = this[Keys.algorithmReadOrder],
                legacyFields = this[Keys.algorithmReadFields],
                allowedFields = DEFAULT_ALGORITHMIC_READ_FIELDS,
                fallbackFields = readFieldsForMode(algorithmMode, DEFAULT_ALGORITHMIC_READ_FIELDS),
                legacyOrder = announcementOrder,
            ),
            voiceLanguage = enumOrDefault(this[Keys.voiceLanguage], VoiceLanguage.AUTO),
            voiceName = this[Keys.voiceName],
            genderFilter = enumOrDefault(this[Keys.genderFilter], GenderFilter.ANY),
            speechRate = (this[Keys.speechRate] ?: 1f).coerceIn(0.5f, 2f),
            pitch = (this[Keys.pitch] ?: 1f).coerceIn(0.5f, 2f),
            announcementVolumeMode = announcementVolumeMode,
            volume = (storedVolume ?: DEFAULT_TTS_VOLUME).coerceIn(0f, 1f),
            raiseDeviceVolume = this[Keys.raiseDeviceVolume] ?: false,
            deviceVolumePercent = (this[Keys.deviceVolumePercent] ?: 90).coerceIn(10, 100),
        )
    }

    private fun Preferences.toAudioDeviceSettings(): Map<String, AudioDeviceSettings> =
        this[Keys.knownDeviceKeys].orEmpty().associateWith { key ->
            readAudioDeviceSettings(key)
        }

    private fun Preferences.toAppSettings(): Map<String, AppSettings> = this[Keys.knownPackages]
        .orEmpty()
        .associateWith { packageName ->
            val appName = this[AppKeys.name(packageName)] ?: packageName
            // The new override key is canonical. The legacy enabled key is
            // treated as explicit when present because older versions only
            // wrote it after a user changed the Apps switch; apps without
            // either key continue following the category default.
            val explicitEnabled = this[AppKeys.enabledOverride(packageName)]
                ?: this[AppKeys.enabled(packageName)]
            val legacyExcluded = this[AppKeys.alwaysExclude(packageName)] ?: false
            AppSettings(
                packageName = packageName,
                appName = appName,
                enabled = AppGuideEnablementPolicy.effectiveEnabled(
                    packageName = packageName,
                    appName = appName,
                    explicitOverride = explicitEnabled,
                ) && !legacyExcluded,
                enabledOverride = explicitEnabled,
            )
        }

    private fun MutablePreferences.writeUserSettings(settings: UserSettings) {
        this[Keys.appLanguage] = settings.appLanguage.name
        this[Keys.enabled] = settings.enabled
        this[Keys.autoEnableOnScreenOff] = settings.autoEnableOnScreenOff
        this[Keys.restoreEnabledWhenScreenOn] = settings.restoreEnabledWhenScreenOn
        this[Keys.outputPolicy] = settings.outputPolicy.name
        this[Keys.audioOutputPolicyVersion] = AUDIO_OUTPUT_POLICY_VERSION
        remove(Keys.headphonesOnly)
        this[Keys.bluetoothOnlyForAutoEnable] = settings.bluetoothOnlyForAutoEnable
        remove(Keys.suppressDuringSpeakerPlayback)
        this[Keys.musicTreatment] = settings.musicTreatment.name
        this[Keys.musicDuckPercent] = settings.musicDuckPercent.coerceIn(MIN_MUSIC_DUCK_PERCENT, MAX_MUSIC_DUCK_PERCENT)
        this[Keys.trackStartBehavior] = settings.trackStartBehavior.name
        this[Keys.showStatusNotification] = settings.showStatusNotification
        this[Keys.timing] = settings.timing.name
        this[Keys.delaySeconds] = AnnouncementTimingPolicy.normalizeStoredDelaySeconds(
            settings.timing,
            settings.delaySeconds,
        )
        this[Keys.defaultMode] = settings.defaultMode.name
        this[Keys.useContentTypeSettings] = false
        val defaultFields = normalizeAnnouncementReadFields(
            settings.defaultReadFields,
            GLOBAL_ANNOUNCEMENT_READ_FIELDS,
            DEFAULT_GLOBAL_ENABLED_READ_FIELDS,
        )
        val albumFields = normalizeAnnouncementReadFields(
            settings.albumReadFields,
            DEFAULT_ALBUM_READ_FIELDS,
            DEFAULT_ALBUM_READ_FIELDS,
        )
        val playlistFields = normalizeAnnouncementReadFields(
            settings.playlistReadFields,
            DEFAULT_PLAYLIST_READ_FIELDS,
            DEFAULT_PLAYLIST_READ_FIELDS,
        )
        val algorithmFields = normalizeAnnouncementReadFields(
            settings.algorithmReadFields,
            DEFAULT_ALGORITHMIC_READ_FIELDS,
            DEFAULT_ALGORITHMIC_READ_FIELDS,
        )
        this[Keys.defaultReadFields] = defaultFields.map(AnnouncementReadField::name).toSet()
        this[Keys.defaultReadOrder] = defaultFields.encodeReadFields()
        this[Keys.contentReadOrderVersion] = CONTENT_READ_ORDER_VERSION
        this[Keys.announcementOrder] = settings.announcementOrder.name
        this[Keys.allowRepeatAnnouncements] = settings.allowRepeatAnnouncements
        this[Keys.minimumPlaybackSeconds] = settings.minimumPlaybackSeconds.coerceIn(0, 120)
        this[Keys.albumMode] = settings.albumMode.name
        this[Keys.playlistMode] = settings.playlistMode.name
        this[Keys.algorithmMode] = settings.algorithmMode.name
        this[Keys.albumReadFields] = albumFields.map(AnnouncementReadField::name).toSet()
        this[Keys.albumReadOrder] = albumFields.encodeReadFields()
        this[Keys.albumNameFirstTrackOnly] = settings.albumNameFirstTrackOnly
        this[Keys.playlistReadFields] = playlistFields.map(AnnouncementReadField::name).toSet()
        this[Keys.playlistReadOrder] = playlistFields.encodeReadFields()
        this[Keys.algorithmReadFields] = algorithmFields.map(AnnouncementReadField::name).toSet()
        this[Keys.algorithmReadOrder] = algorithmFields.encodeReadFields()
        this[Keys.voiceLanguage] = settings.voiceLanguage.name
        if (settings.voiceName == null) remove(Keys.voiceName) else this[Keys.voiceName] = settings.voiceName
        this[Keys.genderFilter] = settings.genderFilter.name
        this[Keys.speechRate] = settings.speechRate.coerceIn(0.5f, 2f)
        this[Keys.pitch] = settings.pitch.coerceIn(0.5f, 2f)
        this[Keys.announcementVolumeMode] = settings.announcementVolumeMode.name
        this[Keys.volume] = settings.volume.coerceIn(0f, 1f)
        this[Keys.raiseDeviceVolume] = settings.raiseDeviceVolume
        this[Keys.deviceVolumePercent] = settings.deviceVolumePercent.coerceIn(10, 100)
    }

    private fun MutablePreferences.writeAppSettings(settings: AppSettings) {
        this[AppKeys.name(settings.packageName)] = settings.appName
        if (settings.enabledOverride == null) {
            remove(AppKeys.enabledOverride(settings.packageName))
            remove(AppKeys.enabled(settings.packageName))
        } else {
            val explicitEnabled = settings.enabledOverride
            this[AppKeys.enabledOverride(settings.packageName)] = explicitEnabled
            this[AppKeys.enabled(settings.packageName)] = explicitEnabled
        }
        removeLegacyAppAnnouncementKeys(this, settings.packageName)
    }

    private fun removeLegacyAppAnnouncementKeys(preferences: MutablePreferences, packageName: String) {
        preferences.remove(AppKeys.useCustomGuideSettings(packageName))
        preferences.remove(AppKeys.mode(packageName))
        preferences.remove(AppKeys.collectionFallback(packageName))
        preferences.remove(AppKeys.readTitle(packageName))
        preferences.remove(AppKeys.readArtist(packageName))
        preferences.remove(AppKeys.readTrackNumber(packageName))
        preferences.remove(AppKeys.readAlbum(packageName))
        preferences.remove(AppKeys.readCollection(packageName))
        preferences.remove(AppKeys.timing(packageName))
        preferences.remove(AppKeys.alwaysExclude(packageName))
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

}

private fun Preferences.toPersistedAnnouncement(): PersistedAnnouncement? {
    val sourcePackageName = this[DataStoreRepository.Keys.lastAnnouncementPackage]
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val announcedAt = this[DataStoreRepository.Keys.lastAnnouncementAt] ?: return null
    return PersistedAnnouncement(
        sourcePackageName = sourcePackageName,
        sourceAppName = this[DataStoreRepository.Keys.lastAnnouncementAppName].orEmpty(),
        title = this[DataStoreRepository.Keys.lastAnnouncementTitle],
        artist = this[DataStoreRepository.Keys.lastAnnouncementArtist],
        album = this[DataStoreRepository.Keys.lastAnnouncementAlbum],
        trackNumber = this[DataStoreRepository.Keys.lastAnnouncementTrackNumber],
        discNumber = this[DataStoreRepository.Keys.lastAnnouncementDiscNumber],
        duration = this[DataStoreRepository.Keys.lastAnnouncementDuration],
        mediaId = this[DataStoreRepository.Keys.lastAnnouncementMediaId],
        trackNumberReliable = this[DataStoreRepository.Keys.lastAnnouncementTrackNumberReliable] ?: true,
        trackNumberSource = this[DataStoreRepository.Keys.lastAnnouncementTrackNumberSource] ?: "UNSPECIFIED",
        announcedAt = announcedAt,
    )
}

private fun setOptional(
    preferences: MutablePreferences,
    key: Preferences.Key<String>,
    value: String?,
) {
    if (value.isNullOrBlank()) preferences.remove(key) else preferences[key] = value
}

private fun AnnouncementTiming.normalizedForSettings(): AnnouncementTiming = when (this) {
    // The old "between tracks" option used the same post-detection delay as
    // DELAYED. Keep old saved settings understandable in the new two-option UI.
    AnnouncementTiming.BETWEEN_TRACKS -> AnnouncementTiming.DELAYED
    AnnouncementTiming.IMMEDIATE,
    AnnouncementTiming.DELAYED,
    -> this
}

internal fun orderedFieldsFromStorage(
    storedOrder: String?,
    legacyFields: Set<String>?,
    allowedFields: List<AnnouncementReadField>,
    fallbackFields: List<AnnouncementReadField>,
    legacyOrder: AnnouncementOrder,
): List<AnnouncementReadField> {
    val stored = storedOrder
        ?.split(',')
        ?.mapNotNull { value -> runCatching { AnnouncementReadField.valueOf(value.trim()) }.getOrNull() }
        .orEmpty()
    if (stored.isNotEmpty()) {
        return normalizeAnnouncementReadFields(stored, allowedFields, fallbackFields)
    }

    val legacy = legacyFields.orEmpty()
        .mapNotNull { runCatching { AnnouncementReadField.valueOf(it) }.getOrNull() }
    // The old preferences were sets, so they lost ordering. When the set is
    // exactly the untouched default, restore the documented default order;
    // otherwise use the allowed-field order as a deterministic migration for
    // a user-selected legacy set.
    val migrationOrder = if (legacy.toSet() == fallbackFields.toSet()) {
        fallbackFields
    } else {
        allowedFields
    }
    val orderedLegacy = migrationOrder.filter { it in legacy }
    return normalizeAnnouncementReadFields(orderedLegacy, allowedFields, fallbackFields)
        .withLegacyAnnouncementOrder(legacyOrder)
}

internal data class GlobalReadDefaultsMigration(
    val fields: Set<String>?,
    val order: String?,
)

/**
 * Applies the Title-only default only to fresh storage or a known untouched
 * default from an earlier beta. Any other persisted selection/order is user
 * data and must remain unchanged.
 */
internal fun migrateUntouchedGlobalReadDefaults(
    storedFields: Set<String>?,
    storedOrder: String?,
    announcementOrder: AnnouncementOrder,
): GlobalReadDefaultsMigration {
    val oldGlobalDefaults = setOf(
        AnnouncementReadField.TITLE.name,
        AnnouncementReadField.ARTIST.name,
    )
    val previousBetaGlobalDefaults = setOf(
        AnnouncementReadField.TITLE.name,
        AnnouncementReadField.ARTIST.name,
        AnnouncementReadField.ALBUM.name,
    )
    val previousBetaGlobalOrder = listOf(
        AnnouncementReadField.ALBUM,
        AnnouncementReadField.TITLE,
        AnnouncementReadField.ARTIST,
    ).joinToString(",") { it.name }
    val untouched = (storedFields == null && storedOrder == null) ||
        (storedFields == oldGlobalDefaults && storedOrder == null) ||
        (storedFields == previousBetaGlobalDefaults &&
            storedOrder == previousBetaGlobalOrder &&
            announcementOrder == AnnouncementOrder.DEFAULT)
    return if (untouched) {
        GlobalReadDefaultsMigration(
            fields = DEFAULT_GLOBAL_ENABLED_READ_FIELDS.map(AnnouncementReadField::name).toSet(),
            order = DEFAULT_GLOBAL_ENABLED_READ_FIELDS.joinToString(",") { it.name },
        )
    } else {
        GlobalReadDefaultsMigration(fields = null, order = null)
    }
}

private fun List<AnnouncementReadField>.encodeReadFields(): String =
    joinToString(",") { it.name }

private fun readFieldsForMode(
    mode: AnnouncementMode,
    defaultFields: List<AnnouncementReadField>,
): List<AnnouncementReadField> = when (mode) {
    AnnouncementMode.ALBUM,
    AnnouncementMode.PLAYLIST,
    AnnouncementMode.SMART,
    -> defaultFields
    AnnouncementMode.TITLE_ONLY -> listOf(AnnouncementReadField.TITLE)
    AnnouncementMode.TITLE_AND_ARTIST -> listOf(
        AnnouncementReadField.TITLE,
        AnnouncementReadField.ARTIST,
    )
}

private fun appBooleanKey(packageName: String, suffix: String): Preferences.Key<Boolean> =
    booleanPreferencesKey("app.$packageName.$suffix")

private fun deviceKey(key: String, suffix: String): Preferences.Key<String> =
    stringPreferencesKey("device.${key.hashCode()}.$suffix")

private fun deviceBooleanKey(key: String, suffix: String): Preferences.Key<Boolean> =
    booleanPreferencesKey("device.${key.hashCode()}.$suffix")

private fun Preferences.readAudioDeviceSettings(key: String): AudioDeviceSettings = AudioDeviceSettings(
    deviceKey = key,
    displayName = this[deviceKey(key, "name")] ?: key,
    autoEnable = this[deviceBooleanKey(key, "auto_enable")] ?: false,
    enabled = this[deviceBooleanKey(key, "enabled")] ?: true,
)

private fun MutablePreferences.writeAudioDeviceSettings(settings: AudioDeviceSettings) {
    this[deviceKey(settings.deviceKey, "name")] = settings.displayName
    this[deviceBooleanKey(settings.deviceKey, "auto_enable")] = settings.autoEnable
    this[deviceBooleanKey(settings.deviceKey, "enabled")] = settings.enabled
}

private fun MutablePreferences.removeAudioDeviceSettings(key: String) {
    remove(deviceKey(key, "name"))
    remove(deviceBooleanKey(key, "auto_enable"))
    remove(deviceBooleanKey(key, "enabled"))
}

private const val EXTERNAL_METADATA_KEY_PREFIX = "external_metadata."

private fun MutablePreferences.copyPrivatePlaybackStateFrom(source: Preferences) {
    source[DataStoreRepository.Keys.knownDeviceKeys]?.let { deviceKeys ->
        this[DataStoreRepository.Keys.knownDeviceKeys] = deviceKeys
        deviceKeys.forEach { key ->
            copyPreference(source, deviceKey(key, "name"))
            copyPreference(source, deviceBooleanKey(key, "auto_enable"))
            copyPreference(source, deviceBooleanKey(key, "enabled"))
        }
    }
    copyPreference(source, DataStoreRepository.Keys.lastAnnouncementPackage)
    copyPreference(source, DataStoreRepository.Keys.lastAnnouncementAppName)
    copyPreference(source, DataStoreRepository.Keys.lastAnnouncementTitle)
    copyPreference(source, DataStoreRepository.Keys.lastAnnouncementArtist)
    copyPreference(source, DataStoreRepository.Keys.lastAnnouncementAlbum)
    copyPreference(source, DataStoreRepository.Keys.lastAnnouncementTrackNumber)
    copyPreference(source, DataStoreRepository.Keys.lastAnnouncementDiscNumber)
    copyPreference(source, DataStoreRepository.Keys.lastAnnouncementDuration)
    copyPreference(source, DataStoreRepository.Keys.lastAnnouncementMediaId)
    copyPreference(source, DataStoreRepository.Keys.lastAnnouncementTrackNumberReliable)
    copyPreference(source, DataStoreRepository.Keys.lastAnnouncementTrackNumberSource)
    copyPreference(source, DataStoreRepository.Keys.lastAnnouncementAt)
}

private fun MutablePreferences.removePrivatePlaybackState() {
    this[DataStoreRepository.Keys.knownDeviceKeys].orEmpty().forEach(::removeAudioDeviceSettings)
    remove(DataStoreRepository.Keys.knownDeviceKeys)
    remove(DataStoreRepository.Keys.lastAnnouncementPackage)
    remove(DataStoreRepository.Keys.lastAnnouncementAppName)
    remove(DataStoreRepository.Keys.lastAnnouncementTitle)
    remove(DataStoreRepository.Keys.lastAnnouncementArtist)
    remove(DataStoreRepository.Keys.lastAnnouncementAlbum)
    remove(DataStoreRepository.Keys.lastAnnouncementTrackNumber)
    remove(DataStoreRepository.Keys.lastAnnouncementDiscNumber)
    remove(DataStoreRepository.Keys.lastAnnouncementDuration)
    remove(DataStoreRepository.Keys.lastAnnouncementMediaId)
    remove(DataStoreRepository.Keys.lastAnnouncementTrackNumberReliable)
    remove(DataStoreRepository.Keys.lastAnnouncementTrackNumberSource)
    remove(DataStoreRepository.Keys.lastAnnouncementAt)
}

private fun <T> MutablePreferences.copyPreference(
    source: Preferences,
    key: Preferences.Key<T>,
) {
    source[key]?.let { this[key] = it }
}
