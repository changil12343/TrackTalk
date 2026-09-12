package com.trackvoice.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataStoreRepositoryInstrumentedTest {
    @Test
    fun legacyBluetoothProfilesMergeIntoOnePersistentLogicalDevice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = DataStoreRepository(context)
        val suffix = System.nanoTime().toString()
        val canonicalKey = "bluetooth:test-$suffix"
        val a2dpKey = "8:AA:BB:CC:$suffix"
        val scoKey = "7:AA:BB:CC:$suffix"
        val allKeys = setOf(canonicalKey, a2dpKey, scoKey)

        try {
            repository.removeAudioDeviceSettings(allKeys)
            repository.updateAudioDeviceSettings(
                AudioDeviceSettings(
                    deviceKey = a2dpKey,
                    displayName = "Space One Pro",
                    autoEnable = true,
                    enabled = true,
                ),
            )
            repository.updateAudioDeviceSettings(
                AudioDeviceSettings(
                    deviceKey = scoKey,
                    displayName = "Space One Pro",
                    autoEnable = false,
                    enabled = false,
                ),
            )

            repository.reconcileAudioDeviceSettings(
                canonicalKey = canonicalKey,
                displayName = "Space One Pro",
                legacyKeys = setOf(a2dpKey, scoKey),
            )

            val recreated = DataStoreRepository(context).currentAudioDeviceSettings()
            assertFalse(a2dpKey in recreated)
            assertFalse(scoKey in recreated)
            assertEquals(setOf(canonicalKey), recreated.keys.intersect(allKeys))
            assertTrue(recreated.getValue(canonicalKey).autoEnable)
            assertFalse(recreated.getValue(canonicalKey).enabled)

            // A reconnect is idempotent and retains the merged choices.
            repository.reconcileAudioDeviceSettings(
                canonicalKey = canonicalKey,
                displayName = "Space One Pro",
                legacyKeys = setOf(a2dpKey, scoKey),
            )
            val reconnected = DataStoreRepository(context).currentAudioDeviceSettings().getValue(canonicalKey)
            assertTrue(reconnected.autoEnable)
            assertFalse(reconnected.enabled)
        } finally {
            repository.removeAudioDeviceSettings(allKeys)
        }
    }

    @Test
    fun explicitAppLanguagePersistsWithoutChangingSpeechOrReadingPreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = DataStoreRepository(context)
        val original = repository.currentUserSettings()
        val readFields = listOf(AnnouncementReadField.ALBUM, AnnouncementReadField.TITLE)

        try {
            repository.updateUserSettings { current ->
                current.copy(
                    appLanguage = AppLanguage.ENGLISH,
                    voiceLanguage = VoiceLanguage.KOREAN,
                    defaultReadFields = readFields,
                )
            }
            repository.migrateAnnouncementVolumeMode()
            repository.migrateContentReadDefaults()
            repository.migrateContentReadOrder()
            repository.migratePlaybackContextSettings()
            repository.migrateAudioOutputPolicy()

            val english = DataStoreRepository(context).currentUserSettings()
            assertEquals(AppLanguage.ENGLISH, english.appLanguage)
            assertEquals(VoiceLanguage.KOREAN, english.voiceLanguage)
            assertEquals(readFields, english.defaultReadFields)

            repository.updateUserSettings { it.copy(appLanguage = AppLanguage.KOREAN) }
            val korean = DataStoreRepository(context).currentUserSettings()
            assertEquals(AppLanguage.KOREAN, korean.appLanguage)
            assertEquals(VoiceLanguage.KOREAN, korean.voiceLanguage)
            assertEquals(readFields, korean.defaultReadFields)
        } finally {
            repository.updateUserSettings { original }
        }
    }

    @Test
    fun announcementVolumeModeAndCustomValueSurviveRepositoryRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = DataStoreRepository(context)
        val original = repository.currentUserSettings()

        try {
            repository.updateUserSettings {
                it.copy(
                    announcementVolumeMode = AnnouncementVolumeMode.CUSTOM,
                    volume = 0.81f,
                )
            }

            val custom = DataStoreRepository(context).currentUserSettings()
            assertEquals(AnnouncementVolumeMode.CUSTOM, custom.announcementVolumeMode)
            assertEquals(0.81f, custom.volume, 0f)

            repository.updateUserSettings {
                it.copy(announcementVolumeMode = AnnouncementVolumeMode.FOLLOW_MEDIA)
            }
            val follow = DataStoreRepository(context).currentUserSettings()
            assertEquals(AnnouncementVolumeMode.FOLLOW_MEDIA, follow.announcementVolumeMode)
            assertEquals(0.81f, follow.volume, 0f)

            repository.updateUserSettings {
                it.copy(announcementVolumeMode = AnnouncementVolumeMode.CUSTOM)
            }
            val restoredCustom = DataStoreRepository(context).currentUserSettings()
            assertEquals(AnnouncementVolumeMode.CUSTOM, restoredCustom.announcementVolumeMode)
            assertEquals(0.81f, restoredCustom.volume, 0f)
        } finally {
            repository.updateUserSettings { original }
        }
    }

    @Test
    fun statusNotificationPreferenceSurvivesRepositoryRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = DataStoreRepository(context)
        val original = repository.currentUserSettings()

        try {
            repository.updateUserSettings { it.copy(showStatusNotification = false) }
            assertFalse(DataStoreRepository(context).currentUserSettings().showStatusNotification)

            repository.updateUserSettings { it.copy(showStatusNotification = true) }
            assertTrue(DataStoreRepository(context).currentUserSettings().showStatusNotification)
        } finally {
            repository.updateUserSettings { original }
        }
    }

    @Test
    fun appEnablementDefaultsAndExplicitChoicesSurviveRepositoryRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = DataStoreRepository(context)
        val musicPackage = "com.trackvoice.test.category.music"
        val videoPackage = "com.trackvoice.test.category.video"
        val unknownPackage = "com.trackvoice.test.category.unknown"

        try {
            repository.removeApp(musicPackage)
            repository.removeApp(videoPackage)
            repository.removeApp(unknownPackage)

            repository.ensureApp(musicPackage, "Spotify")
            repository.ensureApp(videoPackage, "YouTube")
            repository.ensureApp(unknownPackage, "Unknown Player")

            val discovered = repository.currentAppSettings()
            assertTrue(discovered[musicPackage]!!.enabled)
            assertNull(discovered[musicPackage]!!.enabledOverride)
            assertFalse(discovered[videoPackage]!!.enabled)
            assertNull(discovered[videoPackage]!!.enabledOverride)
            assertFalse(discovered[unknownPackage]!!.enabled)

            // Updating another app setting must not turn an unset default into
            // an explicit false value.
            repository.updateAppSettings(discovered[unknownPackage]!!.copy(enabledOverride = null))
            repository.ensureApp(unknownPackage, "Spotify")
            assertTrue(repository.currentAppSettings()[unknownPackage]!!.enabled)

            repository.updateAppSettings(
                repository.currentAppSettings()[videoPackage]!!.copy(
                    enabled = true,
                    enabledOverride = true,
                ),
            )
            repository.updateAppSettings(
                repository.currentAppSettings()[musicPackage]!!.copy(
                    enabled = false,
                    enabledOverride = false,
                ),
            )

            val recreatedRepository = DataStoreRepository(context)
            val recreated = recreatedRepository.currentAppSettings()
            assertTrue(recreated[videoPackage]!!.enabled)
            assertEquals(true, recreated[videoPackage]!!.enabledOverride)
            assertFalse(recreated[musicPackage]!!.enabled)
            assertEquals(false, recreated[musicPackage]!!.enabledOverride)

            // An explicit choice remains authoritative even if the displayed
            // app/category evidence changes later.
            repository.ensureApp(musicPackage, "YouTube")
            assertFalse(repository.currentAppSettings()[musicPackage]!!.enabled)
            assertEquals(false, repository.currentAppSettings()[musicPackage]!!.enabledOverride)
        } finally {
            repository.removeApp(musicPackage)
            repository.removeApp(videoPackage)
            repository.removeApp(unknownPackage)
        }
    }

    @Test
    fun outputPolicySurvivesRepositoryRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = DataStoreRepository(context)
        val original = repository.currentUserSettings()

        try {
            repository.updateUserSettings { current ->
                current.copy(outputPolicy = AnnouncementOutputPolicy.ALL_OUTPUTS)
            }

            val recreatedRepository = DataStoreRepository(context)
            assertEquals(
                AnnouncementOutputPolicy.ALL_OUTPUTS,
                recreatedRepository.currentUserSettings().outputPolicy,
            )
        } finally {
            repository.updateUserSettings { original }
        }
    }

    @Test
    fun orderedContentReadFieldsSurviveRepositoryRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = DataStoreRepository(context)
        val original = repository.currentUserSettings()
        val selectedOrder = listOf(
            AnnouncementReadField.ALBUM,
            AnnouncementReadField.TITLE,
        )

        try {
            repository.updateUserSettings { current ->
                current.copy(
                    albumReadFields = selectedOrder,
                    announcementOrder = AnnouncementOrder.DEFAULT,
                )
            }

            val recreatedRepository = DataStoreRepository(context)
            assertEquals(selectedOrder, recreatedRepository.currentUserSettings().albumReadFields)
        } finally {
            repository.updateUserSettings { original }
        }
    }

    @Test
    fun delayedReadingPersistsAUsableDelayAcrossRepositoryRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = DataStoreRepository(context)
        val original = repository.currentUserSettings()

        try {
            repository.updateUserSettings { current ->
                current.copy(
                    timing = AnnouncementTiming.DELAYED,
                    delaySeconds = 0,
                )
            }

            val recreated = DataStoreRepository(context).currentUserSettings()
            assertEquals(AnnouncementTiming.DELAYED, recreated.timing)
            assertEquals(AnnouncementTimingPolicy.MIN_DELAY_SECONDS, recreated.delaySeconds)
        } finally {
            repository.updateUserSettings { original }
        }
    }

    @Test
    fun explicitPauseSettingsArePersistedWithoutNormalization() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = DataStoreRepository(context)
        val original = repository.currentUserSettings()

        try {
            repository.updateUserSettings { current ->
                current.copy(
                    trackStartBehavior = TrackStartBehavior.ANNOUNCE_THEN_PLAY,
                    musicTreatment = MusicTreatment.PAUSE,
                )
            }

            val recreated = DataStoreRepository(context).currentUserSettings()
            assertEquals(TrackStartBehavior.ANNOUNCE_THEN_PLAY, recreated.trackStartBehavior)
            assertEquals(MusicTreatment.PAUSE, recreated.musicTreatment)
        } finally {
            repository.updateUserSettings { original }
        }
    }

    @Test
    fun legacyAppAnnouncementMigrationKeepsTheAppEligibilityOverride() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = DataStoreRepository(context)
        val packageName = "com.trackvoice.test.legacy.app"

        try {
            repository.removeApp(packageName)
            repository.ensureApp(packageName, "Legacy Player")
            repository.updateAppSettings(
                repository.currentAppSettings()[packageName]!!.copy(
                    enabled = false,
                    enabledOverride = false,
                ),
            )

            repository.migrateLegacyAppAnnouncementSettings()
            repository.migrateLegacyAppAnnouncementSettings()

            val migrated = DataStoreRepository(context).currentAppSettings()[packageName]!!
            assertFalse(migrated.enabled)
            assertEquals(false, migrated.enabledOverride)
        } finally {
            repository.removeApp(packageName)
        }
    }

    @Test
    fun persistedAnnouncementSurvivesRepositoryRecreationAndCanBeCleared() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = DataStoreRepository(context)
        val announcement = PersistedAnnouncement(
            sourcePackageName = "com.example.player",
            sourceAppName = "Example Player",
            title = "Track A",
            artist = "Artist A",
            album = "Album A",
            trackNumber = 3,
            discNumber = 1,
            duration = 180_000L,
            mediaId = "track-a",
            trackNumberReliable = true,
            trackNumberSource = "MEDIA_METADATA",
            announcedAt = 123_456L,
        )

        try {
            repository.savePersistedAnnouncement(announcement)

            assertEquals(announcement, DataStoreRepository(context).currentPersistedAnnouncement())

            repository.clearPersistedAnnouncement()
            assertNull(DataStoreRepository(context).currentPersistedAnnouncement())
        } finally {
            repository.clearPersistedAnnouncement()
        }
    }

}
