package com.trackvoice.ui

import android.content.Intent
import androidx.core.net.toUri
import com.trackvoice.BuildConfig

/**
 * Keeps the release policy address explicit: an empty or malformed build
 * value renders a visible setup state instead of a broken production link.
 */
internal object TrackTalkPrivacyPolicy {
    private val configuredUri = BuildConfig.PRIVACY_POLICY_URL
        .trim()
        .takeIf(String::isNotEmpty)
        ?.toUri()
        ?.takeIf { it.scheme == "https" && !it.host.isNullOrBlank() }

    val isConfigured: Boolean
        get() = configuredUri != null

    fun createIntent(): Intent? = configuredUri?.let { uri ->
        Intent(Intent.ACTION_VIEW, uri)
    }
}
