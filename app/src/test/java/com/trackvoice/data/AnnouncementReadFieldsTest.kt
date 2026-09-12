package com.trackvoice.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AnnouncementReadFieldsTest {
    private val albumFields = listOf(
        AnnouncementReadField.ALBUM,
        AnnouncementReadField.TITLE,
        AnnouncementReadField.ARTIST,
    )

    @Test
    fun newSettingsEnableOnlyTitle() {
        assertEquals(
            listOf(AnnouncementReadField.TITLE),
            UserSettings().defaultReadFields,
        )
    }

    @Test
    fun canonicalGlobalDefaultOrderKeepsInactiveFieldsAfterTitle() {
        assertEquals(
            listOf(
                AnnouncementReadField.TITLE,
                AnnouncementReadField.ARTIST,
                AnnouncementReadField.ALBUM,
            ),
            DEFAULT_GLOBAL_READ_FIELDS,
        )
        assertEquals(
            listOf(AnnouncementReadField.TITLE),
            orderedFieldsFromStorage(
                storedOrder = null,
                legacyFields = null,
                allowedFields = GLOBAL_ANNOUNCEMENT_READ_FIELDS,
                fallbackFields = DEFAULT_GLOBAL_ENABLED_READ_FIELDS,
                legacyOrder = AnnouncementOrder.DEFAULT,
            ),
        )
    }

    @Test
    fun explicitSavedSelectionIsNotReplacedByNewDefault() {
        val saved = listOf(AnnouncementReadField.ALBUM, AnnouncementReadField.TITLE)
        assertEquals(
            saved,
            normalizeAnnouncementReadFields(
                fields = saved,
                allowedFields = GLOBAL_ANNOUNCEMENT_READ_FIELDS,
                fallbackFields = DEFAULT_GLOBAL_ENABLED_READ_FIELDS,
            ),
        )
    }

    @Test
    fun untouchedStorageMigratesToTitleOnlyWithCanonicalOrder() {
        assertEquals(
            GlobalReadDefaultsMigration(
                fields = setOf(AnnouncementReadField.TITLE.name),
                order = "TITLE",
            ),
            migrateUntouchedGlobalReadDefaults(
                storedFields = null,
                storedOrder = null,
                announcementOrder = AnnouncementOrder.DEFAULT,
            ),
        )
    }

    @Test
    fun explicitSavedOrderIsNotReplacedByTheNewDefaultMigration() {
        assertEquals(
            GlobalReadDefaultsMigration(fields = null, order = null),
            migrateUntouchedGlobalReadDefaults(
                storedFields = setOf(
                    AnnouncementReadField.TITLE.name,
                    AnnouncementReadField.ARTIST.name,
                ),
                storedOrder = "ARTIST,TITLE",
                announcementOrder = AnnouncementOrder.DEFAULT,
            ),
        )
    }

    @Test
    fun emptyOrInvalidSelectionFallsBackToTheContentDefault() {
        assertEquals(
            albumFields,
            normalizeAnnouncementReadFields(
                fields = emptyList(),
                allowedFields = albumFields,
                fallbackFields = albumFields,
            ),
        )
    }

    @Test
    fun disablingTheLastActiveFieldKeepsOneFieldActive() {
        assertEquals(
            listOf(AnnouncementReadField.TITLE),
            toggleAnnouncementReadField(
                fields = listOf(AnnouncementReadField.TITLE),
                field = AnnouncementReadField.TITLE,
                enabled = false,
                allowedFields = listOf(AnnouncementReadField.TITLE, AnnouncementReadField.ARTIST),
            ),
        )
    }

    @Test
    fun disablingPreservesActiveOrderAndReenablingAppends() {
        val fields = listOf(
            AnnouncementReadField.ALBUM,
            AnnouncementReadField.TITLE,
            AnnouncementReadField.ARTIST,
        )
        val withoutAlbum = toggleAnnouncementReadField(
            fields = fields,
            field = AnnouncementReadField.ALBUM,
            enabled = false,
            allowedFields = fields,
        )
        assertEquals(
            listOf(
                AnnouncementReadField.TITLE,
                AnnouncementReadField.ARTIST,
            ),
            withoutAlbum,
        )
        assertEquals(
            listOf(
                AnnouncementReadField.TITLE,
                AnnouncementReadField.ARTIST,
                AnnouncementReadField.ALBUM,
            ),
            toggleAnnouncementReadField(
                fields = withoutAlbum,
                field = AnnouncementReadField.ALBUM,
                enabled = true,
                allowedFields = fields,
            ),
        )
    }

    @Test
    fun deselectionSemanticsApplyToEveryContentTypeGroup() {
        listOf(
            DEFAULT_GLOBAL_READ_FIELDS,
            DEFAULT_ALBUM_READ_FIELDS,
            DEFAULT_PLAYLIST_READ_FIELDS,
            DEFAULT_ALGORITHMIC_READ_FIELDS,
        ).forEach { group ->
            val first = group.first()
            val remaining = toggleAnnouncementReadField(
                fields = group,
                field = first,
                enabled = false,
                allowedFields = group,
            )
            assertEquals(group.drop(1), remaining)
            assertEquals(
                group.drop(1) + first,
                toggleAnnouncementReadField(
                    fields = remaining,
                    field = first,
                    enabled = true,
                    allowedFields = group,
                ),
            )
        }
    }

    @Test
    fun dragReorderingChangesOnlyTheActiveOrder() {
        val fields = listOf(
            AnnouncementReadField.TITLE,
            AnnouncementReadField.ARTIST,
            AnnouncementReadField.ALBUM,
        )

        assertEquals(
            listOf(
                AnnouncementReadField.ALBUM,
                AnnouncementReadField.TITLE,
                AnnouncementReadField.ARTIST,
            ),
            reorderAnnouncementReadField(
                fields = fields,
                field = AnnouncementReadField.ALBUM,
                targetIndex = 0,
                allowedFields = fields,
            ),
        )
    }

    @Test
    fun leftMostActiveFieldCanBeMovedAcrossSeveralPositionsInOneGesture() {
        val fields = listOf(
            AnnouncementReadField.TITLE,
            AnnouncementReadField.ARTIST,
            AnnouncementReadField.ALBUM,
        )

        assertEquals(
            listOf(
                AnnouncementReadField.ARTIST,
                AnnouncementReadField.ALBUM,
                AnnouncementReadField.TITLE,
            ),
            reorderAnnouncementReadField(
                fields = fields,
                field = AnnouncementReadField.TITLE,
                targetIndex = 2,
                allowedFields = fields,
            ),
        )
    }

    @Test
    fun legacyDefaultSetRecoversTheCanonicalDefaultOrder() {
        assertEquals(
            DEFAULT_ALBUM_READ_FIELDS,
            orderedFieldsFromStorage(
                storedOrder = null,
                legacyFields = DEFAULT_ALBUM_READ_FIELDS.map(AnnouncementReadField::name).toSet(),
                allowedFields = DEFAULT_ALBUM_READ_FIELDS,
                fallbackFields = DEFAULT_ALBUM_READ_FIELDS,
                legacyOrder = AnnouncementOrder.DEFAULT,
            ),
        )
    }

    @Test
    fun globalReadingConfigurationExcludesUnreliablePlaybackContextFields() {
        assertEquals(
            listOf(AnnouncementReadField.TITLE, AnnouncementReadField.ALBUM),
            normalizeAnnouncementReadFields(
                fields = listOf(
                    AnnouncementReadField.COLLECTION,
                    AnnouncementReadField.TITLE,
                    AnnouncementReadField.ALBUM,
                ),
                allowedFields = GLOBAL_ANNOUNCEMENT_READ_FIELDS,
                fallbackFields = DEFAULT_GLOBAL_READ_FIELDS,
            ),
        )
    }

    @Test
    fun v1ReadFieldsExcludeRetiredTrackNumber() {
        assertEquals(
            listOf(
                AnnouncementReadField.TITLE,
                AnnouncementReadField.ARTIST,
                AnnouncementReadField.ALBUM,
            ),
            BETA_VISIBLE_ANNOUNCEMENT_READ_FIELDS,
        )
        assertEquals(
            listOf(
                AnnouncementReadField.ALBUM,
                AnnouncementReadField.TITLE,
                AnnouncementReadField.ARTIST,
            ),
            normalizeAnnouncementReadFields(
                fields = listOf(
                    AnnouncementReadField.ALBUM,
                    AnnouncementReadField.TRACK_NUMBER,
                    AnnouncementReadField.TITLE,
                    AnnouncementReadField.ARTIST,
                ),
                allowedFields = BETA_VISIBLE_ANNOUNCEMENT_READ_FIELDS,
                fallbackFields = DEFAULT_GLOBAL_READ_FIELDS,
            ),
        )
    }

    @Test
    fun retiredTrackNumberOnlyStorageFallsBackToTitle() {
        assertEquals(
            listOf(AnnouncementReadField.TITLE),
            orderedFieldsFromStorage(
                storedOrder = AnnouncementReadField.TRACK_NUMBER.name,
                legacyFields = setOf(AnnouncementReadField.TRACK_NUMBER.name),
                allowedFields = GLOBAL_ANNOUNCEMENT_READ_FIELDS,
                fallbackFields = DEFAULT_GLOBAL_ENABLED_READ_FIELDS,
                legacyOrder = AnnouncementOrder.TRACK_NUMBER_FIRST,
            ),
        )
        assertEquals(
            listOf(AnnouncementReadField.ALBUM, AnnouncementReadField.TITLE),
            orderedFieldsFromStorage(
                storedOrder = "ALBUM,TRACK_NUMBER,TITLE",
                legacyFields = null,
                allowedFields = GLOBAL_ANNOUNCEMENT_READ_FIELDS,
                fallbackFields = DEFAULT_GLOBAL_ENABLED_READ_FIELDS,
                legacyOrder = AnnouncementOrder.DEFAULT,
            ),
        )
    }
}
