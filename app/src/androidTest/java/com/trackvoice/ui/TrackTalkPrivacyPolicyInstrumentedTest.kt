package com.trackvoice.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackTalkPrivacyPolicyInstrumentedTest {
    @Test
    fun defaultBuildUsesTheCanonicalHttpsPolicyUrl() {
        val intent = TrackTalkPrivacyPolicy.createIntent()

        assertTrue(TrackTalkPrivacyPolicy.isConfigured)
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent?.action)
        assertEquals(
            "https://yiri20.github.io/Amnesiac/tracktalk-privacy.html",
            intent?.dataString,
        )
        assertEquals("https", intent?.data?.scheme)
    }
}
