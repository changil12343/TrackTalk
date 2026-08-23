package com.trackvoice.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.trackvoice.MainActivity
import com.trackvoice.R
import com.trackvoice.TrackVoiceController
import com.trackvoice.announcement.AnnouncementPlaybackPlanner
import com.trackvoice.announcement.AudioRouteResolution
import com.trackvoice.announcement.AudioRouteState
import com.trackvoice.announcement.ConnectedAudioDevice
import com.trackvoice.data.AnnouncementOutputPolicy
import com.trackvoice.data.AnnouncementReadField
import com.trackvoice.data.AppGuideEnablementPolicy
import com.trackvoice.data.AppLanguage
import com.trackvoice.data.AppSettings
import com.trackvoice.data.DataStoreRepository
import com.trackvoice.data.MusicTreatment
import com.trackvoice.data.UserSettings
import com.trackvoice.localization.localizedString
import com.trackvoice.media.PlaybackCollection
import com.trackvoice.monetization.PremiumState
import com.trackvoice.monetization.forPremiumEntitlement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A deliberately metadata-free view of the active session for the status
 * notification. Track title, artist, and album never enter this model.
 */
internal data class StatusNotificationActiveApp(
    val packageName: String,
    val displayName: String?,
)

internal data class StatusNotificationInput(
    val settings: UserSettings,
    val effectiveEnabled: Boolean,
    val notificationAccessGranted: Boolean,
    val activeApp: StatusNotificationActiveApp?,
    val appSettings: Map<String, AppSettings>,
    val route: AudioRouteResolution,
    val connectedDevices: List<ConnectedAudioDevice>,
)

internal enum class StatusNotificationTitle {
    ON,
    OFF,
    SETUP_REQUIRED,
}

internal enum class StatusNotificationBehavior {
    ANNOUNCE_THEN_PLAY,
    KEEP_MUSIC,
    DUCK_MUSIC,
}

internal sealed interface StatusNotificationDetail {
    data class Active(
        val appName: String,
        val spokenFields: List<AnnouncementReadField>,
        val behavior: StatusNotificationBehavior,
    ) : StatusNotificationDetail

    data object WaitingForMusic : StatusNotificationDetail
    data object WaitingForExternalAudio : StatusNotificationDetail
    data object AnnouncementsPaused : StatusNotificationDetail
    data object MusicDetectionPermissionRequired : StatusNotificationDetail
}

internal data class StatusNotificationExpandedOutput(
    val deviceName: String?,
)

internal data class StatusNotification(
    val visible: Boolean,
    val appLanguage: AppLanguage,
    val title: StatusNotificationTitle,
    val detail: StatusNotificationDetail,
    val expandedOutput: StatusNotificationExpandedOutput? = null,
    /** The persisted UserSettings target carried by the notification action. */
    val actionTargetEnabled: Boolean,
)

/** Pure mapping from existing runtime state to concise notification semantics. */
internal object StatusNotificationMapper {
    fun map(input: StatusNotificationInput): StatusNotification {
        val actionTargetEnabled = !input.settings.enabled
        val base = StatusNotification(
            visible = input.settings.showStatusNotification,
            appLanguage = input.settings.appLanguage,
            title = StatusNotificationTitle.ON,
            detail = StatusNotificationDetail.WaitingForMusic,
            actionTargetEnabled = actionTargetEnabled,
        )
        if (!input.effectiveEnabled) {
            return base.copy(
                title = StatusNotificationTitle.OFF,
                detail = StatusNotificationDetail.AnnouncementsPaused,
            )
        }
        if (!input.notificationAccessGranted) {
            return base.copy(
                title = StatusNotificationTitle.SETUP_REQUIRED,
                detail = StatusNotificationDetail.MusicDetectionPermissionRequired,
            )
        }
        if (
            input.settings.outputPolicy == AnnouncementOutputPolicy.EXTERNAL_ONLY &&
                input.route.state != AudioRouteState.EXTERNAL
        ) {
            return base.copy(detail = StatusNotificationDetail.WaitingForExternalAudio)
        }

        val expandedOutput = input.route.state.takeIf { it == AudioRouteState.EXTERNAL }?.let {
            StatusNotificationExpandedOutput(
                deviceName = input.connectedDevices.singleOrNull()
                    ?.productName
                    ?.trim()
                    ?.takeIf(String::isNotEmpty),
            )
        }
        val appName = input.activeApp?.eligibleDisplayName(input.appSettings)
            ?: return base.copy(expandedOutput = expandedOutput)
        val spokenFields = com.trackvoice.announcement.AnnouncementPolicy
            .resolveConfiguration(input.settings, PlaybackCollection.UNKNOWN)
            .fields
        val playbackPlan = AnnouncementPlaybackPlanner.plan(input.settings)
        val behavior = when {
            playbackPlan.pauseBeforeAnnouncement ->
                StatusNotificationBehavior.ANNOUNCE_THEN_PLAY

            playbackPlan.musicTreatment == MusicTreatment.KEEP ->
                StatusNotificationBehavior.KEEP_MUSIC

            else -> StatusNotificationBehavior.DUCK_MUSIC
        }
        return base.copy(
            detail = StatusNotificationDetail.Active(
                appName = appName,
                spokenFields = spokenFields,
                behavior = behavior,
            ),
            expandedOutput = expandedOutput,
        )
    }

    private fun StatusNotificationActiveApp.eligibleDisplayName(
        settings: Map<String, AppSettings>,
    ): String? {
        val configured = settings[packageName]
        val eligible = configured?.enabled ?: AppGuideEnablementPolicy.defaultEnabled(
            packageName = packageName,
            appName = displayName.orEmpty(),
        )
        if (!eligible) return null

        return sequenceOf(displayName, configured?.appName)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull { it != packageName }
    }
}

/** Keeps repeat delivery of the same explicit notification action idempotent. */
internal object StatusNotificationToggle {
    fun apply(settings: UserSettings, targetEnabled: Boolean): UserSettings =
        if (settings.enabled == targetEnabled) settings else settings.copy(enabled = targetEnabled)
}

internal data class StatusNotificationCopy(
    val title: String,
    val collapsedText: String,
    val expandedText: String?,
    val actionLabel: String,
)

internal class StatusNotificationRenderer(private val context: Context) {
    fun build(status: StatusNotification): Notification {
        val copy = copyFor(status)
        val expanded = copy.expandedText?.let { "${copy.collapsedText}\n$it" }
            ?: copy.collapsedText
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_trackvoice)
            .setContentTitle(copy.title)
            .setContentText(copy.collapsedText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setContentIntent(contentPendingIntent())
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_trackvoice,
                    copy.actionLabel,
                    togglePendingIntent(status.actionTargetEnabled),
                ).build(),
            )
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun copyFor(status: StatusNotification): StatusNotificationCopy {
        val language = status.appLanguage
        return StatusNotificationCopy(
            title = when (status.title) {
                StatusNotificationTitle.ON -> context.localizedString(language, R.string.status_notification_on)
                StatusNotificationTitle.OFF -> context.localizedString(language, R.string.status_notification_off)
                StatusNotificationTitle.SETUP_REQUIRED ->
                    context.localizedString(language, R.string.status_notification_setup_required)
            },
            collapsedText = detailText(status.detail, language),
            expandedText = status.expandedOutput?.let { output ->
                output.deviceName?.let { name ->
                    context.localizedString(
                        language,
                        R.string.status_notification_external_audio_with_device,
                        name,
                    )
                } ?: context.localizedString(language, R.string.status_notification_external_audio)
            },
            actionLabel = context.localizedString(
                language,
                if (status.actionTargetEnabled) {
                    R.string.status_notification_turn_on
                } else {
                    R.string.status_notification_turn_off
                },
            ),
        )
    }

    private fun detailText(
        detail: StatusNotificationDetail,
        language: AppLanguage,
    ): String = when (detail) {
        is StatusNotificationDetail.Active -> context.localizedString(
            language,
            R.string.status_notification_active,
            detail.appName,
            detail.spokenFields
                .mapNotNull { field -> fieldLabel(field, language) }
                .joinToString(
                    separator = " ${context.localizedString(language, R.string.status_notification_field_separator)} ",
                ),
            behaviorLabel(detail.behavior, language),
        )

        StatusNotificationDetail.WaitingForMusic ->
            context.localizedString(language, R.string.status_notification_waiting_for_music)

        StatusNotificationDetail.WaitingForExternalAudio ->
            context.localizedString(language, R.string.status_notification_waiting_for_external_audio)

        StatusNotificationDetail.AnnouncementsPaused ->
            context.localizedString(language, R.string.status_notification_announcements_paused)

        StatusNotificationDetail.MusicDetectionPermissionRequired ->
            context.localizedString(language, R.string.status_notification_music_detection_required)
    }

    private fun fieldLabel(field: AnnouncementReadField, language: AppLanguage): String? = when (field) {
        AnnouncementReadField.TITLE -> context.localizedString(language, R.string.status_notification_field_title)
        AnnouncementReadField.ARTIST -> context.localizedString(language, R.string.status_notification_field_artist)
        AnnouncementReadField.ALBUM -> context.localizedString(language, R.string.status_notification_field_album)
        AnnouncementReadField.TRACK_NUMBER,
        AnnouncementReadField.COLLECTION,
        -> null
    }

    private fun behaviorLabel(
        behavior: StatusNotificationBehavior,
        language: AppLanguage,
    ): String = context.localizedString(
        language,
        when (behavior) {
            StatusNotificationBehavior.ANNOUNCE_THEN_PLAY ->
                R.string.status_notification_behavior_announce_then_play

            StatusNotificationBehavior.KEEP_MUSIC ->
                R.string.status_notification_behavior_keep_music

            StatusNotificationBehavior.DUCK_MUSIC ->
                R.string.status_notification_behavior_duck_music
        },
    )

    private fun contentPendingIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        StatusNotificationIntents.contentIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun togglePendingIntent(targetEnabled: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context,
        if (targetEnabled) REQUEST_TURN_ON else REQUEST_TURN_OFF,
        StatusNotificationIntents.toggleIntent(context, targetEnabled),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_ID = "trackvoice_shortcut"
        const val NOTIFICATION_ID = 2101
        private const val REQUEST_OPEN_APP = 2101
        private const val REQUEST_TURN_ON = 2102
        private const val REQUEST_TURN_OFF = 2103
    }
}

internal object StatusNotificationIntents {
    const val ACTION_TOGGLE = "com.trackvoice.action.TOGGLE_STATUS_NOTIFICATION"
    const val EXTRA_TARGET_ENABLED = "target_enabled"

    fun contentIntent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

    fun toggleIntent(context: Context, targetEnabled: Boolean): Intent =
        Intent(context, TrackVoiceStatusNotificationActionReceiver::class.java)
            .setAction(ACTION_TOGGLE)
            .putExtra(EXTRA_TARGET_ENABLED, targetEnabled)
}

/**
 * Application-scoped owner of the optional status notification. The listener
 * service only updates controller state; it no longer owns another settings
 * observer or notification lifecycle.
 */
class TrackVoiceStatusNotificationManager(
    context: Context,
    private val repository: DataStoreRepository,
    private val controller: TrackVoiceController,
    private val premiumState: kotlinx.coroutines.flow.StateFlow<PremiumState>,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationManager = NotificationManagerCompat.from(appContext)
    private val renderer = StatusNotificationRenderer(appContext)
    private var observerJob: Job? = null
    private var latestStatus: StatusNotification? = null
    private var lastRenderedStatus: StatusNotification? = null

    fun start() {
        if (observerJob?.isActive == true) return
        val effectiveSettings = combine(controller.userSettings, premiumState) { settings, premium ->
            settings.forPremiumEntitlement(premium.isPremium)
        }
        val media = controller.mediaState
            .map { state ->
                StatusNotificationMediaSnapshot(
                    effectiveEnabled = state.effectiveEnabled,
                    activeApp = state.currentEvent?.let { event ->
                        StatusNotificationActiveApp(
                            packageName = event.sourcePackageName,
                            displayName = event.sourceAppName,
                        )
                    },
                )
            }
            .distinctUntilChanged()
        val notificationAccess = controller.diagnostics
            .map { diagnostics -> diagnostics.notificationListenerConnected }
            .distinctUntilChanged()
        val mediaAndAccess = combine(media, notificationAccess) { snapshot, access ->
            StatusNotificationMediaAndAccess(snapshot, access)
        }
        val appsAndDevices = combine(
            controller.appSettings,
            controller.connectedAudioDevices,
        ) { apps, devices ->
            StatusNotificationAppsAndDevices(apps, devices)
        }
        observerJob = scope.launch {
            combine(effectiveSettings, mediaAndAccess, appsAndDevices) { settings, mediaSnapshot, apps ->
                StatusNotificationMapper.map(
                    StatusNotificationInput(
                        settings = settings,
                        effectiveEnabled = mediaSnapshot.media.effectiveEnabled,
                        notificationAccessGranted = mediaSnapshot.notificationAccessGranted,
                        activeApp = mediaSnapshot.media.activeApp,
                        appSettings = apps.appSettings,
                        route = controller.statusNotificationRoute(),
                        connectedDevices = apps.connectedDevices,
                    ),
                )
            }.distinctUntilChanged().collect(::render)
        }
    }

    fun refresh() {
        // Permission or channel state may have changed while the app was in
        // Settings. render() checks those first, then avoids re-posting an
        // otherwise identical status.
        latestStatus?.let(::render)
    }

    fun applyToggleTarget(targetEnabled: Boolean, onComplete: () -> Unit) {
        scope.launch {
            try {
                repository.updateUserSettings { settings ->
                    StatusNotificationToggle.apply(settings, targetEnabled)
                }
            } finally {
                onComplete()
            }
        }
    }

    private fun render(status: StatusNotification) {
        latestStatus = status
        if (!status.visible || !notificationManager.areNotificationsEnabled()) {
            notificationManager.cancel(StatusNotificationRenderer.NOTIFICATION_ID)
            lastRenderedStatus = null
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            notificationManager.cancel(StatusNotificationRenderer.NOTIFICATION_ID)
            lastRenderedStatus = null
            return
        }
        if (status == lastRenderedStatus) return
        ensureChannel(status.appLanguage)
        try {
            notificationManager.notify(StatusNotificationRenderer.NOTIFICATION_ID, renderer.build(status))
            lastRenderedStatus = status
        } catch (_: SecurityException) {
            // The user may revoke POST_NOTIFICATIONS after the check above.
            lastRenderedStatus = null
        }
    }

    private fun ensureChannel(language: AppLanguage) {
        val platformManager = appContext.getSystemService(NotificationManager::class.java)
        platformManager.createNotificationChannel(
            NotificationChannel(
                StatusNotificationRenderer.CHANNEL_ID,
                appContext.localizedString(language, R.string.status_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            },
        )
    }

    private data class StatusNotificationMediaSnapshot(
        val effectiveEnabled: Boolean,
        val activeApp: StatusNotificationActiveApp?,
    )

    private data class StatusNotificationMediaAndAccess(
        val media: StatusNotificationMediaSnapshot,
        val notificationAccessGranted: Boolean,
    )

    private data class StatusNotificationAppsAndDevices(
        val appSettings: Map<String, AppSettings>,
        val connectedDevices: List<ConnectedAudioDevice>,
    )
}
