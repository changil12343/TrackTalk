package com.trackvoice.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackvoice.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class BackupRulesInstrumentedTest {
    @Test
    fun transientPlaybackStateIsExcludedFromEverySupportedBackupRuleSet() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertTrue(exclusions(context, R.xml.backup_rules).contains(PRIVATE_STATE_EXCLUSION))
        assertTrue(exclusions(context, R.xml.data_extraction_rules).contains(PRIVATE_STATE_EXCLUSION))
    }

    private fun exclusions(context: Context, resourceId: Int): Set<Pair<String, String>> {
        val parser = context.resources.getXml(resourceId)
        return buildSet {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude") {
                    val domain = parser.getAttributeValue(null, "domain")
                    val path = parser.getAttributeValue(null, "path")
                    if (domain != null && path != null) add(domain to path)
                }
                parser.next()
            }
        }
    }

    private companion object {
        val PRIVATE_STATE_EXCLUSION = "file" to "datastore/trackvoice_private_state.preferences_pb"
    }
}
