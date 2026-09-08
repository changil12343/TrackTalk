package com.trackvoice.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.core.graphics.drawable.toBitmap
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackvoice.BuildConfig
import com.trackvoice.TrackVoiceViewModel
import com.trackvoice.announcement.AnnouncementPolicy
import com.trackvoice.announcement.InstalledVoice
import com.trackvoice.announcement.TtsStatus
import com.trackvoice.announcement.VoiceCatalogPolicy
import com.trackvoice.announcement.VoiceMetadataPolicy
import com.trackvoice.data.AnnouncementMode
import com.trackvoice.data.AnnouncementOrder
import com.trackvoice.data.AnnouncementOutputPolicy
import com.trackvoice.data.AnnouncementReadField
import com.trackvoice.data.AnnouncementTiming
import com.trackvoice.data.AnnouncementTimingPolicy
import com.trackvoice.data.AnnouncementVolumeMode
import com.trackvoice.data.AppSettings
import com.trackvoice.data.AppCategory
import com.trackvoice.data.AppLanguage
import com.trackvoice.data.APP_LANGUAGE_OPTIONS
import com.trackvoice.data.categorizeApp
import com.trackvoice.data.GenderFilter
import com.trackvoice.data.UserSettings
import com.trackvoice.data.VoiceLanguage
import com.trackvoice.data.MusicTreatment
import com.trackvoice.data.TrackStartBehavior
import com.trackvoice.data.AudioDeviceSettings
import com.trackvoice.data.BETA_VISIBLE_ANNOUNCEMENT_READ_FIELDS
import com.trackvoice.data.mergeBetaVisibleAnnouncementReadFields
import com.trackvoice.data.normalizeAnnouncementReadFields
import com.trackvoice.data.reorderAnnouncementReadField
import com.trackvoice.data.toggleAnnouncementReadField
import com.trackvoice.announcement.ConnectedAudioDevice
import com.trackvoice.announcement.EffectiveAnnouncementConfiguration
import com.trackvoice.media.PlaybackEvent
import com.trackvoice.media.PlaybackCollection
import com.trackvoice.monetization.PremiumState
import com.trackvoice.monetization.forPremiumEntitlement
import com.trackvoice.monetization.promoCodeRedeemUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppSection {
    HOME,
    GENERAL,
    APPS,
    DEVICES,
    DIAGNOSTICS,
}

internal enum class GuideSettingsPane {
    GUIDE,
    VOICE,
}

private const val LEGACY_VOICE_SECTION_NAME = "VOICE"
private const val CURRENT_PLAYBACK_ANNOUNCEMENT_ROW_TEST_TAG = "currentPlaybackAnnouncementRow"
private const val CURRENT_PLAYBACK_READING_ROW_TEST_TAG = "currentPlaybackReadingRow"

private val TrackVoiceCardShape = RoundedCornerShape(14.dp)
private val HomeCardContentHorizontalInset = 24.dp
private val TopLevelHeaderAdditionalTopPadding = 14.dp

private object CurrentPlaybackCardSpacing {
    val cardHorizontal = HomeCardContentHorizontalInset
    // The header's 48dp icon target centers the title; this outer inset keeps
    // the title visually clear of the card border without changing the target.
    val cardTop = 16.dp
    val cardBottom = 16.dp
    val headingToFirstRow = 20.dp
    val metadataRowGap = 12.dp
    val metadataToDivider = 20.dp
    // Keep the divider-to-action break distinct while the action rows retain
    // their independent 48dp touch targets.
    val dividerToSettings = 16.dp
    val settingsRowGap = 0.dp
    val labelWidth = 96.dp
    val labelToValue = 12.dp
    val settingsChevronSize = 18.dp
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackVoiceApp(viewModel: TrackVoiceViewModel, activity: Activity) {
    val settings by viewModel.userSettings.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val mediaState by viewModel.mediaState.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val voices by viewModel.installedVoices.collectAsStateWithLifecycle()
    val connectedDevices by viewModel.connectedAudioDevices.collectAsStateWithLifecycle()
    val deviceSettings by viewModel.audioDeviceSettings.collectAsStateWithLifecycle()
    val premiumState by viewModel.premiumState.collectAsStateWithLifecycle()
    val effectiveSettings = settings.forPremiumEntitlement(premiumState.isPremium)
    val strings = TrackTalkStrings.forLanguage(settings.appLanguage, Locale.getDefault().language)
    val effectiveAnnouncementConfiguration = AnnouncementPolicy.resolveConfiguration(
        userSettings = effectiveSettings,
        collection = PlaybackCollection.UNKNOWN,
    )
    var selectedSectionName by rememberSaveable { mutableStateOf(AppSection.HOME.name) }
    var guidePaneName by rememberSaveable { mutableStateOf(GuideSettingsPane.GUIDE.name) }
    var showPremiumDialog by rememberSaveable { mutableStateOf(false) }
    val selectedSection = when (selectedSectionName) {
        LEGACY_VOICE_SECTION_NAME -> AppSection.GENERAL
        else -> runCatching { AppSection.valueOf(selectedSectionName) }.getOrDefault(AppSection.HOME)
    }
    LaunchedEffect(selectedSectionName) {
        if (selectedSectionName == LEGACY_VOICE_SECTION_NAME) {
            selectedSectionName = AppSection.GENERAL.name
            guidePaneName = GuideSettingsPane.VOICE.name
        }
    }
    val context = LocalContext.current
    var notificationPermissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionGranted = granted
        viewModel.refreshStatusNotification()
    }
    val lifecycleOwner = activity as? LifecycleOwner
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                viewModel.refreshStatusNotification()
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    CompositionLocalProvider(LocalTrackTalkStrings provides strings) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopLevelScreenHeader(
                    title = strings.sectionTitle(selectedSection),
                    enabled = mediaState.effectiveEnabled,
                    notificationAccess = diagnostics.notificationListenerConnected,
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    NavigationItem(AppSection.HOME, Icons.Default.Home, selectedSection) {
                        selectedSectionName = it.name
                    }
                    NavigationItem(AppSection.GENERAL, Icons.Default.Tune, selectedSection) {
                        selectedSectionName = it.name
                    }
                    NavigationItem(AppSection.APPS, Icons.Default.Apps, selectedSection) {
                        selectedSectionName = it.name
                    }
                    NavigationItem(AppSection.DEVICES, Icons.Default.Settings, selectedSection) {
                        selectedSectionName = it.name
                    }
                }
            },
        ) { padding ->
            SurfaceContent(padding) {
                when (selectedSection) {
                AppSection.HOME -> HomeScreen(
                    settings = effectiveSettings,
                    mediaEvent = mediaState.currentEvent,
                    announcementConfiguration = effectiveAnnouncementConfiguration,
                    effectiveEnabled = mediaState.effectiveEnabled,
                    notificationAccess = diagnostics.notificationListenerConnected,
                    notificationPermissionGranted = notificationPermissionGranted,
                    premiumState = premiumState,
                    onToggle = viewModel.controller::setEnabled,
                    onTogglePlayback = { viewModel.controller.togglePlayback() },
                    onOpenPermission = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onToggleStatusNotification = { enabled ->
                        viewModel.controller.updateUserSettings { current ->
                            current.copy(showStatusNotification = enabled)
                        }
                    },
                    onOpenAnnouncementSettings = {
                        guidePaneName = GuideSettingsPane.GUIDE.name
                        selectedSectionName = AppSection.GENERAL.name
                    },
                    onOpenPremium = { showPremiumDialog = true },
                )

                AppSection.GENERAL -> GuideSettingsScreen(
                    settings = effectiveSettings,
                    voices = voices,
                    ttsStatus = diagnostics.ttsState,
                    isPremium = premiumState.isPremium,
                    onUpdate = viewModel.controller::updateUserSettings,
                    onTest = viewModel.controller::speakTest,
                    onPreviewVoice = viewModel.controller::speakVoicePreview,
                    onOpenPremium = { showPremiumDialog = true },
                    selectedPaneName = guidePaneName,
                    onPaneSelected = { guidePaneName = it.name },
                )

                AppSection.APPS -> AppSettingsScreen(
                    apps = appSettings.values.sortedBy { it.appName.lowercase(Locale.getDefault()) },
                    onUpdate = viewModel.controller::updateAppSettings,
                    onRefresh = viewModel.controller::refreshSupportedMediaApps,
                )

                AppSection.DEVICES -> DeviceSettingsScreen(
                    settings = effectiveSettings,
                    connectedDevices = connectedDevices,
                    deviceSettings = deviceSettings,
                    isPremium = premiumState.isPremium,
                    onUpdate = viewModel.controller::updateUserSettings,
                    onUpdateDevice = viewModel.controller::updateAudioDeviceSettings,
                    onOpenPremium = { showPremiumDialog = true },
                    onOpenDiagnostics = { selectedSectionName = AppSection.DIAGNOSTICS.name },
                    onFeedback = {
                        openFeedbackEmail(context, strings.noEmailApp)
                    },
                )

                AppSection.DIAGNOSTICS -> DiagnosticsScreen(
                    diagnostics,
                    mediaState.currentEvent,
                    onBack = { selectedSectionName = AppSection.DEVICES.name },
                )
                }
            }
        }
        if (showPremiumDialog) {
            PremiumDialog(
                state = premiumState,
                onDismiss = { showPremiumDialog = false },
                onPurchase = { viewModel.purchasePremium(activity) },
                onRestore = viewModel::restorePremium,
                onRedeemLocalCode = viewModel::redeemLocalPromoCode,
                onOpenPromoCode = { url ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TopLevelScreenHeader(
    title: String,
    enabled: Boolean,
    notificationAccess: Boolean,
) {
    Box(modifier = Modifier.padding(top = TopLevelHeaderAdditionalTopPadding)) {
        TopAppBar(
            title = {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            actions = {
                StatusBadge(
                    enabled = enabled,
                    notificationAccess = notificationAccess,
                )
            },
        )
    }
}

@Composable
private fun RowScope.NavigationItem(
    section: AppSection,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: AppSection,
    onSelected: (AppSection) -> Unit,
) {
    val strings = LocalTrackTalkStrings.current
    NavigationBarItem(
        selected = selected == section,
        onClick = { onSelected(section) },
        icon = { Icon(icon, contentDescription = strings.sectionTitle(section)) },
        label = {
            Text(
                strings.navLabel(section),
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall,
            )
        },
    )
}

@Composable
private fun SurfaceContent(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        content = content,
    )
}

@Composable
internal fun HomeScreen(
    settings: UserSettings,
    mediaEvent: PlaybackEvent?,
    announcementConfiguration: EffectiveAnnouncementConfiguration,
    effectiveEnabled: Boolean,
    notificationAccess: Boolean,
    notificationPermissionGranted: Boolean,
    premiumState: PremiumState,
    onToggle: (Boolean) -> Unit,
    onTogglePlayback: () -> Unit,
    onOpenPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onToggleStatusNotification: (Boolean) -> Unit,
    onOpenAnnouncementSettings: () -> Unit,
    onOpenPremium: () -> Unit,
) {
    val permissionPresentation = resolveHomePermissionPresentation(
        requiredPermissionGranted = notificationAccess,
        isPremium = premiumState.isPremium,
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatusCard(
                enabled = settings.enabled,
                effectiveEnabled = effectiveEnabled,
                onToggle = onToggle,
            )
        }
        if (permissionPresentation.showRequiredPermission) {
            item {
                RequiredPermissionBanner(onOpenPermission)
            }
        }
        if (!permissionPresentation.showRequiredPermission) {
            item {
                StatusNotificationCard(
                    enabled = settings.showStatusNotification,
                    permissionGranted = notificationPermissionGranted,
                    requiresRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                    onToggle = onToggleStatusNotification,
                    onRequestPermission = onRequestNotificationPermission,
                )
            }
        }
        item {
            CurrentTrackCard(
                event = mediaEvent.takeIf { permissionPresentation.showCurrentPlayback },
                corePermissionGranted = permissionPresentation.showCurrentPlayback,
                settings = settings,
                announcementConfiguration = announcementConfiguration,
                onTogglePlayback = onTogglePlayback,
                onOpenAnnouncementSettings = onOpenAnnouncementSettings,
            )
        }
        if (permissionPresentation.showPremiumPromotion) {
            item {
                PremiumCard(premiumState, onOpenPremium)
            }
        }
    }
}

@Composable
private fun PremiumCard(state: PremiumState, onOpenPremium: () -> Unit) {
    val strings = LocalTrackTalkStrings.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = TrackVoiceCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.isPremium) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(strings.premiumTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (state.isPremium) {
                        strings.premiumEnabledSummary
                    } else {
                        strings.premiumLockedSummary
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!state.isPremium) {
                TextButton(onClick = onOpenPremium) { Text(strings.view) }
            }
        }
    }
}

@Composable
private fun PremiumDialog(
    state: PremiumState,
    onDismiss: () -> Unit,
    onPurchase: () -> Unit,
    onRestore: () -> Unit,
    onRedeemLocalCode: (String) -> Boolean,
    onOpenPromoCode: (String) -> Unit,
) {
    val strings = LocalTrackTalkStrings.current
    var promoCode by rememberSaveable { mutableStateOf("") }
    var promoCodeError by rememberSaveable { mutableStateOf(false) }
    var showPromoCode by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Star, contentDescription = null) },
        title = { Text(strings.premiumTitle) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PremiumBenefit(strings.premiumVoiceBenefit)
                PremiumBenefit(strings.premiumDeviceBenefit)
                PremiumBenefit(strings.premiumAdsBenefit)
                PremiumBenefit(strings.premiumFutureBenefit)
                when {
                    state.isPremium -> Text(
                        strings.premiumActive,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    state.price != null -> Text(strings.oneTimePrice(state.price.orEmpty()))
                    else -> Text(
                        state.message?.let(strings::premiumMessage) ?: strings.purchaseUnavailable,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!state.isPremium && state.message != null && state.price != null) {
                    Text(
                        strings.premiumMessage(state.message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!state.isPremium) {
                    HorizontalDivider()
                    TextButton(
                        onClick = { showPromoCode = !showPromoCode },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (showPromoCode) strings.closePromoCode else strings.promoCodeSection)
                    }
                    if (showPromoCode) {
                        Text(
                            strings.promoDescription,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = promoCode,
                            onValueChange = {
                                promoCode = it.take(64)
                                promoCodeError = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(strings.promoCode) },
                            singleLine = true,
                            isError = promoCodeError,
                            supportingText = if (promoCodeError) {
                                { Text(strings.promoCodeFormatError) }
                            } else {
                                null
                            },
                        )
                        OutlinedButton(
                            onClick = {
                                if (onRedeemLocalCode(promoCode)) {
                                    promoCode = ""
                                    promoCodeError = false
                                } else {
                                    promoCodeError = true
                                }
                            },
                            enabled = promoCode.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(strings.applyFriendCode)
                        }
                        TextButton(
                            onClick = {
                                val url = promoCodeRedeemUrl(promoCode)
                                if (url == null) {
                                    promoCodeError = true
                                } else {
                                    onOpenPromoCode(url)
                                }
                            },
                            enabled = promoCode.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(strings.useGooglePlayCode)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state.isPremium) {
                TextButton(onClick = onDismiss) { Text(strings.close) }
            } else {
                Button(
                    onClick = onPurchase,
                    enabled = state.productAvailable && !state.isLoading,
                ) {
                    Text(if (state.isLoading) strings.checking else strings.buyPlus)
                }
            }
        },
        dismissButton = {
            if (!state.isPremium) {
                TextButton(onClick = onRestore, enabled = !state.isLoading) { Text(strings.restorePurchase) }
            }
        },
    )
}

@Composable
private fun PremiumBenefit(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

internal fun statusNotificationNeedsPermission(
    requiresRuntimePermission: Boolean,
    permissionGranted: Boolean,
): Boolean = requiresRuntimePermission && !permissionGranted

internal data class HomePermissionPresentation(
    val showRequiredPermission: Boolean,
    val showPremiumPromotion: Boolean,
    val showCurrentPlayback: Boolean,
)

internal fun resolveHomePermissionPresentation(
    requiredPermissionGranted: Boolean,
    isPremium: Boolean,
): HomePermissionPresentation = HomePermissionPresentation(
    showRequiredPermission = !requiredPermissionGranted,
    showPremiumPromotion = requiredPermissionGranted && !isPremium,
    showCurrentPlayback = requiredPermissionGranted,
)

internal fun homeStatusText(
    enabled: Boolean,
    notificationAccess: Boolean,
    strings: TrackTalkStrings,
): String = when {
    enabled && !notificationAccess -> strings.statusNeedsSetup
    enabled -> strings.on
    else -> strings.off
}

@Composable
internal fun StatusNotificationCard(
    enabled: Boolean,
    permissionGranted: Boolean,
    requiresRuntimePermission: Boolean,
    onToggle: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
) {
    val strings = LocalTrackTalkStrings.current
    val needsPermission = statusNotificationNeedsPermission(
        requiresRuntimePermission = requiresRuntimePermission,
        permissionGranted = permissionGranted,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = TrackVoiceCardShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = HomeCardContentHorizontalInset),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    strings.statusShortcut,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (needsPermission) strings.statusShortcutPermissionSummary else strings.statusShortcutSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (needsPermission) {
                Box(modifier = Modifier.padding(end = 12.dp)) {
                    TextButton(
                        onClick = onRequestPermission,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(strings.permissionSettings)
                    }
                }
            } else {
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.padding(end = 16.dp),
                )
            }
        }
    }
}

@Composable
internal fun StatusCard(enabled: Boolean, effectiveEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    val strings = LocalTrackTalkStrings.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = TrackVoiceCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (effectiveEnabled) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = HomeCardContentHorizontalInset),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(strings.homeVoiceGuide, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(strings.statusSummary(effectiveEnabled, enabled))
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.padding(end = 16.dp),
            )
        }
    }
}

@Composable
internal fun RequiredPermissionBanner(onOpenPermission: () -> Unit) {
    val strings = LocalTrackTalkStrings.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = TrackVoiceCardShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        strings.musicDetectionPermissionTitle,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        strings.requiredPermissionBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Text(
                    strings.musicDetectionPermissionSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onOpenPermission,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(strings.permissionSettings)
            }
        }
    }
}

@Composable
internal fun CurrentTrackCard(
    event: PlaybackEvent?,
    corePermissionGranted: Boolean,
    settings: UserSettings,
    announcementConfiguration: EffectiveAnnouncementConfiguration,
    onTogglePlayback: () -> Unit,
    onOpenAnnouncementSettings: () -> Unit,
) {
    val strings = LocalTrackTalkStrings.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = TrackVoiceCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = CurrentPlaybackCardSpacing.cardHorizontal)
                .padding(
                    top = CurrentPlaybackCardSpacing.cardTop,
                    bottom = CurrentPlaybackCardSpacing.cardBottom,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    strings.currentTrack,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (event != null) {
                    IconButton(onClick = onTogglePlayback) {
                        Icon(
                            if (event.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (event.isPlaying) strings.pauseMusic else strings.playMusic,
                        )
                    }
                }
            }
            Spacer(Modifier.height(CurrentPlaybackCardSpacing.headingToFirstRow))
            if (event == null) {
                if (corePermissionGranted) {
                    Column(verticalArrangement = Arrangement.spacedBy(CurrentPlaybackCardSpacing.metadataRowGap)) {
                        Text(strings.noMusicPlaying)
                        Text(strings.playMusicHint, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(strings.permissionPlaybackSummary)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(CurrentPlaybackCardSpacing.metadataRowGap)) {
                    TrackField(strings.appField, event.sourceAppName)
                    TrackField(strings.trackField, event.title ?: strings.unknownTitle)
                    TrackField(strings.artistField, event.artist ?: strings.unknownArtist)
                    TrackField(strings.albumField, event.album ?: strings.unknownAlbum)
                }
                Spacer(Modifier.height(CurrentPlaybackCardSpacing.metadataToDivider))
                CurrentAnnouncementSummaryRows(
                    configuration = announcementConfiguration,
                    timing = settings.timing,
                    delaySeconds = settings.delaySeconds,
                    trackStartBehavior = settings.trackStartBehavior,
                    onAnnouncementClick = onOpenAnnouncementSettings,
                    onReadingClick = onOpenAnnouncementSettings,
                )
            }
        }
    }
}

@Composable
private fun CurrentAnnouncementSummaryRows(
    configuration: EffectiveAnnouncementConfiguration,
    timing: AnnouncementTiming,
    delaySeconds: Int,
    trackStartBehavior: TrackStartBehavior,
    onAnnouncementClick: () -> Unit,
    onReadingClick: () -> Unit,
) {
    val strings = LocalTrackTalkStrings.current
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(CurrentPlaybackCardSpacing.dividerToSettings))
        AnnouncementSummaryNavigationRow(
            label = strings.announcementLabel,
            value = strings.homeAnnouncementBehavior(trackStartBehavior, timing, delaySeconds),
            testTag = CURRENT_PLAYBACK_ANNOUNCEMENT_ROW_TEST_TAG,
            onClick = onAnnouncementClick,
        )
        Spacer(Modifier.height(CurrentPlaybackCardSpacing.settingsRowGap))
        AnnouncementSummaryNavigationRow(
            label = strings.readingOrderLabel,
            value = strings.announcementFieldsSummary(configuration.fields),
            testTag = CURRENT_PLAYBACK_READING_ROW_TEST_TAG,
            onClick = onReadingClick,
        )
    }
}

@Composable
private fun AnnouncementSummaryNavigationRow(
    label: String,
    value: String,
    testTag: String,
    onClick: () -> Unit,
) {
    val strings = LocalTrackTalkStrings.current
    CurrentPlaybackKeyValueRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(testTag)
            .clickable(role = Role.Button, onClick = onClick),
        label = {
            Text(
                label,
                modifier = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        value = {
            Text(
                value,
                modifier = it,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = strings.openGuideSettings,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(CurrentPlaybackCardSpacing.settingsChevronSize),
            )
        },
        verticalAlignment = Alignment.CenterVertically,
    )
}

@Composable
private fun TrackField(label: String, value: String) {
    CurrentPlaybackKeyValueRow(
        label = {
            Text(
                label,
                modifier = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        value = {
            Text(value, modifier = it, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
    )
}

@Composable
private fun CurrentPlaybackKeyValueRow(
    modifier: Modifier = Modifier.fillMaxWidth(),
    label: @Composable (Modifier) -> Unit,
    value: @Composable RowScope.(Modifier) -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
    verticalAlignment: Alignment.Vertical = Alignment.Top,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CurrentPlaybackCardSpacing.labelToValue),
        verticalAlignment = verticalAlignment,
    ) {
        label(Modifier.width(CurrentPlaybackCardSpacing.labelWidth))
        value(Modifier.weight(1f))
        trailing()
    }
}

@Composable
internal fun GuideSettingsScreen(
    settings: UserSettings,
    voices: List<InstalledVoice>,
    ttsStatus: com.trackvoice.announcement.TtsState,
    isPremium: Boolean,
    onUpdate: ((UserSettings) -> UserSettings) -> Unit,
    onTest: () -> Unit,
    onPreviewVoice: (String) -> Unit,
    onOpenPremium: () -> Unit,
    selectedPaneName: String,
    onPaneSelected: (GuideSettingsPane) -> Unit,
) {
    val selectedPane = runCatching { GuideSettingsPane.valueOf(selectedPaneName) }
        .getOrDefault(GuideSettingsPane.GUIDE)
    val strings = LocalTrackTalkStrings.current
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    RoundedCornerShape(16.dp),
                )
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GuidePaneSegment(
                selected = selectedPane == GuideSettingsPane.GUIDE,
                onClick = { onPaneSelected(GuideSettingsPane.GUIDE) },
                label = strings.announcementPane,
                modifier = Modifier.weight(1f),
            )
            GuidePaneSegment(
                selected = selectedPane == GuideSettingsPane.VOICE,
                onClick = { onPaneSelected(GuideSettingsPane.VOICE) },
                label = strings.voicePane,
                modifier = Modifier.weight(1f),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (selectedPane == GuideSettingsPane.GUIDE) {
                GeneralSettingsScreen(
                    settings = settings,
                    isPremium = isPremium,
                    onUpdate = onUpdate,
                    onOpenPremium = onOpenPremium,
                )
            } else {
                VoiceSettingsScreen(
                    settings = settings,
                    voices = voices,
                    ttsStatus = ttsStatus,
                    isPremium = isPremium,
                    onUpdate = onUpdate,
                    onTest = onTest,
                    onPreviewVoice = onPreviewVoice,
                    onOpenPremium = onOpenPremium,
                )
            }
        }
    }
}

@Composable
private fun GuidePaneSegment(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
internal fun GeneralSettingsScreen(
    settings: UserSettings,
    isPremium: Boolean,
    onUpdate: ((UserSettings) -> UserSettings) -> Unit,
    onOpenPremium: () -> Unit,
) {
    val strings = LocalTrackTalkStrings.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(GENERAL_SETTINGS_SCREEN_TAG),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingCard(strings.basicOperation) {
                SettingSwitchRow(strings.voiceGuide, strings.voiceGuideSummary, settings.enabled) { enabled ->
                    onUpdate { current -> current.copy(enabled = enabled) }
                }
                OptionDropdown(
                    strings.announcementOutput,
                    settings.outputPolicy,
                    AnnouncementOutputPolicy.values().toList(),
                    strings::announcementOutputOption,
                ) { policy ->
                    onUpdate { current -> current.copy(outputPolicy = policy) }
                }
                Text(
                    strings.announcementOutputSummary(settings.outputPolicy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingCard(strings.trackGuide) {
                if (isPremium) {
                    val treatment = settings.musicTreatment
                    val treatmentSummary = when (treatment) {
                        MusicTreatment.PAUSE -> strings.announceThenPlaySummary
                        MusicTreatment.DUCK -> strings.musicVolumeSummary
                        MusicTreatment.KEEP -> strings.musicKeepSummary
                    }
                    OptionDropdown(
                        strings.musicDuringGuide,
                        treatment,
                        listOf(MusicTreatment.KEEP, MusicTreatment.DUCK, MusicTreatment.PAUSE),
                        strings::musicTreatment,
                    ) { value ->
                        onUpdate {
                            it.copy(
                                trackStartBehavior = TrackStartBehavior.PLAY_IMMEDIATELY,
                                musicTreatment = value,
                            )
                        }
                    }
                    Text(
                        treatmentSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val timingOptions = listOf(
                        AnnouncementTiming.IMMEDIATE,
                        AnnouncementTiming.DELAYED,
                    )
                    OptionDropdown(strings.announcementTiming, settings.timing, timingOptions, strings::announcementTiming) { selectedTiming ->
                        onUpdate { current ->
                            current.copy(
                                timing = selectedTiming,
                                delaySeconds = AnnouncementTimingPolicy.normalizeStoredDelaySeconds(
                                    selectedTiming,
                                    current.delaySeconds,
                                ),
                            )
                        }
                    }
                    val delayedTimingActive = settings.timing == AnnouncementTiming.DELAYED
                    if (delayedTimingActive) {
                        SliderSetting(
                            strings.announcementDelay,
                            AnnouncementTimingPolicy.normalizeStoredDelaySeconds(settings.timing, settings.delaySeconds).toFloat(),
                            AnnouncementTimingPolicy.MIN_DELAY_SECONDS.toFloat()..AnnouncementTimingPolicy.MAX_DELAY_SECONDS.toFloat(),
                            { value -> strings.seconds(value.toInt()) },
                        ) { value ->
                            onUpdate { it.copy(delaySeconds = value.toInt()) }
                        }
                        SliderSetting(
                            strings.minimumPlayback,
                            settings.minimumPlaybackSeconds.toFloat(),
                            0f..60f,
                            { value -> strings.seconds(value.toInt()) },
                        ) { value -> onUpdate { it.copy(minimumPlaybackSeconds = value.toInt()) } }
                    }
                    SettingSwitchRow(strings.repeatTrack, strings.repeatTrackSummary, settings.allowRepeatAnnouncements) { enabled ->
                        onUpdate { current -> current.copy(allowRepeatAnnouncements = enabled) }
                    }
                } else {
                    BasicPlaybackDefaults()
                    NavigationEntryContent(
                        title = strings.detailedGuidePlusTitle,
                        summary = strings.freeGuideDetailsSummary,
                        showPlusBadge = true,
                        onClick = onOpenPremium,
                    )
                }
            }
        }
        item {
            SettingCard(strings.readingFieldsSection) {
                Text(
                    strings.readingFieldsSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ContentReadOrderPicker(
                    title = strings.readingFieldsTitle,
                    availableFields = BETA_VISIBLE_ANNOUNCEMENT_READ_FIELDS,
                    selectedFields = settings.defaultReadFields,
                ) { fields ->
                    onUpdate {
                        it.copy(
                            defaultMode = AnnouncementMode.SMART,
                            useContentTypeSettings = false,
                            defaultReadFields = mergeBetaVisibleAnnouncementReadFields(
                                storedFields = it.defaultReadFields,
                                visibleFields = fields,
                            ),
                            announcementOrder = AnnouncementOrder.DEFAULT,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DeviceSettingsScreen(
    settings: UserSettings,
    connectedDevices: List<ConnectedAudioDevice>,
    deviceSettings: Map<String, AudioDeviceSettings>,
    isPremium: Boolean,
    onUpdate: ((UserSettings) -> UserSettings) -> Unit,
    onUpdateDevice: (AudioDeviceSettings) -> Unit,
    onOpenPremium: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onFeedback: () -> Unit,
) {
    val strings = LocalTrackTalkStrings.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    strings.generalSection,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                CompactOptionSetting(
                    title = strings.appLanguageTitle,
                    selected = settings.appLanguage,
                    options = APP_LANGUAGE_OPTIONS,
                    optionLabel = strings::appLanguageOption,
                    modifier = Modifier.testTag(APP_LANGUAGE_SETTING_TAG),
                ) { language ->
                    onUpdate { it.copy(appLanguage = language) }
                }
            }
        }
        if (isPremium) {
            item {
                SettingCard(strings.automationPlusTitle) {
                    Text(strings.deviceAutomationSummary, style = MaterialTheme.typography.bodySmall)
                    if (connectedDevices.isEmpty()) {
                        Text(strings.noConnectedDevices, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    connectedDevices.forEach { device ->
                        val typeLabel = strings.audioDeviceType(device.kind)
                        val displayName = device.productName ?: typeLabel
                        val saved = deviceSettings[device.key] ?: AudioDeviceSettings(device.key, displayName)
                        Text(displayName, fontWeight = FontWeight.SemiBold)
                        Text(typeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SettingSwitchRow(strings.useOnThisDevice, strings.useOnThisDeviceSummary, saved.enabled) {
                            onUpdateDevice(saved.copy(enabled = it))
                        }
                        SettingSwitchRow(strings.autoEnableOnConnect, strings.autoEnableOnConnectSummary, saved.autoEnable) {
                            onUpdateDevice(saved.copy(autoEnable = it))
                        }
                        HorizontalDivider()
                    }
                    Text(strings.screenAutomation, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    SettingSwitchRow(strings.screenOffEnable, strings.screenOffEnableSummary, settings.autoEnableOnScreenOff) { enabled ->
                        onUpdate { it.copy(autoEnableOnScreenOff = enabled) }
                    }
                    SettingSwitchRow(strings.screenOnRestore, strings.screenOnRestoreSummary, settings.restoreEnabledWhenScreenOn) { enabled ->
                        onUpdate { it.copy(restoreEnabledWhenScreenOn = enabled) }
                    }
                    SettingSwitchRow(strings.bluetoothOnly, strings.bluetoothOnlySummary, settings.bluetoothOnlyForAutoEnable) { enabled ->
                        onUpdate { it.copy(bluetoothOnlyForAutoEnable = enabled) }
                    }
                }
            }
        } else {
            item {
                NavigationEntryCard(
                    title = strings.automationPlusTitle,
                    summary = strings.automationPlusSummary,
                    showPlusBadge = true,
                    onClick = onOpenPremium,
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            }
        }
        item {
            SettingCard(strings.sectionTitle(AppSection.DIAGNOSTICS)) {
                Text(
                    strings.diagnosticsSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.BugReport, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(strings.openDiagnostics)
                }
            }
        }
        item {
            AppInfoCard(onFeedback = onFeedback)
        }
    }
}

@Composable
internal fun AppInfoCard(onFeedback: () -> Unit) {
    val strings = LocalTrackTalkStrings.current
    SettingCard(strings.appInfoTitle) {
        InfoRow(strings.versionLabel, "v${BuildConfig.VERSION_NAME}")
        NavigationEntryContent(
            title = strings.feedbackDeveloper,
            summary = strings.feedbackDeveloperSummary,
            showPlusBadge = false,
            onClick = onFeedback,
        )
    }
}

private fun openFeedbackEmail(context: Context, noEmailAppMessage: String) {
    val intent = TrackTalkFeedback.createIntent()
    val canOpenEmail = runCatching {
        intent.resolveActivity(context.packageManager) != null
    }.getOrDefault(false)
    if (!canOpenEmail) {
        Toast.makeText(context, noEmailAppMessage, Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, noEmailAppMessage, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.width(88.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AppSettingsScreen(
    apps: List<AppSettings>,
    onUpdate: (AppSettings) -> Unit,
    onRefresh: () -> Unit,
) {
    val strings = LocalTrackTalkStrings.current
    var selectedCategoryNames by rememberSaveable {
        mutableStateOf(
            listOf(
                AppCategory.MUSIC_STREAMING.name,
                AppCategory.MUSIC_VIDEO.name,
            ),
        )
    }
    val appsByCategory = remember(apps) {
        apps.groupBy { app -> categorizeApp(app.packageName, app.appName) }
    }
    val visibleAppCount = selectedCategoryNames.sumOf { name ->
        appsByCategory[AppCategory.valueOf(name)].orEmpty().size
    }
    val categoryScrollState = rememberScrollState()
    val hasMoreCategories by remember {
        derivedStateOf { categoryScrollState.maxValue > categoryScrollState.value }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = TrackVoiceCardShape,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            strings.appsIntro,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (apps.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            strings.visibleCategories,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        TextButton(
                            onClick = onRefresh,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(strings.refresh)
                        }
                    }
                    Box(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                        .horizontalScroll(categoryScrollState)
                        .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AppCategory.values().forEach { category ->
                                val selected = category.name in selectedCategoryNames
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        selectedCategoryNames = if (selected) {
                                            selectedCategoryNames - category.name
                                        } else {
                                            selectedCategoryNames + category.name
                                        }
                                    },
                                    label = { Text(strings.categoryTitle(category)) },
                                    leadingIcon = if (selected) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null,
                                )
                            }
                            // Keep the final chip clear of the edge affordance
                            // so it reads as scrollable rather than clipped.
                            Spacer(Modifier.width(48.dp))
                        }
                        if (hasMoreCategories) {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = strings.scrollMore,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .size(32.dp)
                                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
                                    .padding(4.dp),
                            )
                        }
                    }
                    Text(
                        strings.appCountSummary(visibleAppCount, selectedCategoryNames.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (apps.isEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings.noSupportedApps)
                    Text(strings.appAutoAddSummary, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else if (visibleAppCount == 0) {
            item {
                Text(
                    strings.noCategorySelected,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        } else {
            AppCategory.values().forEach { category ->
                val categoryApps = appsByCategory[category].orEmpty()
                if (category.name in selectedCategoryNames && categoryApps.isNotEmpty()) {
                    item(key = "category-${category.name}") {
                        AppCategoryHeader(category, categoryApps.size)
                    }
                    items(
                        items = categoryApps,
                        key = { app -> "app-${app.packageName}" },
                    ) { app ->
                        AppSettingsCard(app, onUpdate)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppCategoryHeader(category: AppCategory, appCount: Int) {
    val strings = LocalTrackTalkStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = category.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                strings.categoryTitle(category),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                strings.categoryDescription(category),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            strings.appCategoryCount(appCount),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun AppCategory.icon() = when (this) {
    AppCategory.MUSIC_STREAMING -> Icons.Default.MusicNote
    AppCategory.MUSIC_VIDEO -> Icons.Default.VideoLibrary
    AppCategory.LEARNING -> Icons.Default.School
    AppCategory.PODCAST -> Icons.Default.Podcasts
    AppCategory.OTHER -> Icons.Default.Apps
}

@Composable
private fun AppSettingsCard(
    app: AppSettings,
    onUpdate: (AppSettings) -> Unit,
) {
    val strings = LocalTrackTalkStrings.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = TrackVoiceCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InstalledAppIcon(app.packageName)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(app.appName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (app.enabled) strings.appGuideEnabled else strings.appGuideDisabled,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = app.enabled,
                    onCheckedChange = { enabled ->
                        onUpdate(app.copy(enabled = enabled, enabledOverride = enabled))
                    },
                )
            }
        }
    }
}

@Composable
private fun InstalledAppIcon(packageName: String) {
    val context = LocalContext.current
    val bitmap = androidx.compose.runtime.remember(packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName)
                .toBitmap(width = 96, height = 96)
                .asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun VoiceSettingsScreen(
    settings: UserSettings,
    voices: List<InstalledVoice>,
    ttsStatus: com.trackvoice.announcement.TtsState,
    isPremium: Boolean,
    onUpdate: ((UserSettings) -> UserSettings) -> Unit,
    onTest: () -> Unit,
    onPreviewVoice: (String) -> Unit,
    onOpenPremium: () -> Unit,
) {
    val strings = LocalTrackTalkStrings.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingCard(strings.voiceSelection) {
                OptionDropdown(strings.defaultVoiceLanguage, settings.voiceLanguage, VoiceLanguage.values().toList(), strings::voiceLanguage) { selectedLanguage ->
                    onUpdate { current -> current.copy(voiceLanguage = selectedLanguage, voiceName = null) }
                }
                Text(
                    strings.voiceLanguageHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val genderOptions = listOf(GenderFilter.ANY, GenderFilter.FEMALE, GenderFilter.MALE)
                val visibleGender = settings.genderFilter.takeIf { it in genderOptions } ?: GenderFilter.ANY
                OptionDropdown(strings.gender, visibleGender, genderOptions, strings::genderLabel) { selectedGender ->
                    onUpdate { current -> current.copy(genderFilter = selectedGender, voiceName = null) }
                }
                if (VoiceCatalogPolicy.showsManualVoicePicker(settings.voiceLanguage)) {
                    val filteredVoices = VoiceCatalogPolicy.visibleVoices(
                        voices = voices,
                        language = settings.voiceLanguage,
                        gender = visibleGender,
                    )
                    val selectedVoice = filteredVoices.firstOrNull { it.name == settings.voiceName }
                    VoiceDropdown(
                        label = strings.voice,
                        selected = selectedVoice,
                        voices = filteredVoices,
                        numberByName = VoiceCatalogPolicy.stableDisplayNumbers(
                            voices = voices,
                            language = settings.voiceLanguage,
                        ),
                        english = strings.isEnglish,
                        autoLabel = strings.autoSelect,
                        previewLabel = strings.voicePreview,
                        onSelected = { voice -> onUpdate { it.copy(voiceName = voice?.name) } },
                        onPreview = onPreviewVoice,
                    )
                    Text(
                        if (filteredVoices.isEmpty()) strings.noMatchingVoices
                        else strings.availableVoices(visibleGender, filteredVoices.size),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        strings.automaticVoiceSelection,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ttsStatus.status == TtsStatus.ERROR) {
                    Text(
                        strings.diagnosticMessage(ttsStatus.message),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        item {
            SettingCard(strings.speechControls) {
                if (isPremium) {
                    SliderSetting(strings.speechRate, settings.speechRate, 0.5f..2f, { value -> "${"%.1f".format(Locale.getDefault(), value)}x" }) { value ->
                        onUpdate { current -> current.copy(speechRate = value) }
                    }
                    SliderSetting(strings.pitch, settings.pitch, 0.5f..2f, { value -> "${"%.1f".format(Locale.getDefault(), value)}x" }) { value ->
                        onUpdate { current -> current.copy(pitch = value) }
                    }
                    OptionDropdown(
                        strings.voiceVolumeSeparate,
                        settings.announcementVolumeMode,
                        listOf(AnnouncementVolumeMode.FOLLOW_MEDIA, AnnouncementVolumeMode.CUSTOM),
                        strings::announcementVolumeMode,
                    ) { mode ->
                        onUpdate { current -> current.copy(announcementVolumeMode = mode) }
                    }
                    when (settings.announcementVolumeMode) {
                        AnnouncementVolumeMode.FOLLOW_MEDIA -> Text(
                            strings.followMediaVolumeHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        AnnouncementVolumeMode.CUSTOM -> {
                            SliderSetting(
                                strings.customAnnouncementVolume,
                                settings.volume,
                                0f..1f,
                                { value -> "${(value * 100).toInt()}%" },
                            ) { value ->
                                onUpdate { current -> current.copy(volume = value) }
                            }
                        }
                    }
                } else {
                    NavigationEntryContent(
                        title = strings.voiceControlsPlusTitle,
                        summary = strings.voiceControlsFreeSummary,
                        showPlusBadge = true,
                        onClick = onOpenPremium,
                    )
                }
                Button(onClick = onTest) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(strings.testPlayback)
                }
                Text(strings.testExample, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(
    diagnostics: com.trackvoice.DiagnosticsState,
    event: PlaybackEvent?,
    onBack: () -> Unit,
) {
    val strings = LocalTrackTalkStrings.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = onBack) {
                Text(strings.backToDevices)
            }
        }
        item {
            SettingCard(strings.connectionStatus) {
                DiagnosticRow(strings.notificationAccess, if (diagnostics.notificationListenerConnected) strings.connected else strings.notConnected, diagnostics.notificationListenerConnected)
                DiagnosticRow(strings.activeMediaSessions, diagnostics.activeSessionCount.toString(), diagnostics.activeSessionCount > 0)
                DiagnosticRow(strings.selectedApp, diagnostics.selectedSourcePackage ?: strings.none, diagnostics.selectedSourcePackage != null)
                DiagnosticRow(strings.voiceEngine, strings.diagnosticMessage(diagnostics.ttsState.message), diagnostics.ttsState.status == TtsStatus.READY)
            }
        }
        item {
            SettingCard(strings.recentLog) {
                DiagnosticRow(strings.metadataDetected, formatTimeOrDash(diagnostics.lastMetadataEventAt, strings.none), diagnostics.lastMetadataEventAt != null)
                DiagnosticRow(strings.playbackDetected, formatTimeOrDash(diagnostics.lastPlaybackStateEventAt, strings.none), diagnostics.lastPlaybackStateEventAt != null)
                DiagnosticRow(strings.lastAnnouncement, strings.diagnosticMessage(diagnostics.lastAnnouncementMessage), diagnostics.lastAnnouncementSucceeded == true)
                DiagnosticRow(strings.announcementTime, formatTimeOrDash(diagnostics.lastAnnouncementAt, strings.none), diagnostics.lastAnnouncementAt != null)
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = TrackVoiceCardShape,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.privacy, fontWeight = FontWeight.Bold)
                    Text(strings.privacySummary)
                }
            }
        }
        if (event != null) {
            item {
                Text(strings.currentTrackInfo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${event.sourcePackageName} · ${event.title ?: strings.titleMissing} · ${event.artist ?: strings.artistMissing}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = TrackVoiceCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                content()
            },
        )
    }
}

@Composable
private fun <T> CompactOptionSetting(
    title: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
    onSelected: (T) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = true },
            shape = TrackVoiceCardShape,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    optionLabel(selected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 220.dp, max = 320.dp),
            shape = RoundedCornerShape(12.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun NavigationEntryCard(
    title: String,
    summary: String,
    onClick: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color,
    showPlusBadge: Boolean = false,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        shape = TrackVoiceCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        NavigationEntryContent(
            title = title,
            summary = summary,
            showPlusBadge = showPlusBadge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun NavigationEntryContent(
    title: String,
    summary: String,
    showPlusBadge: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val entryModifier = if (onClick == null) {
        modifier
    } else {
        modifier.clickable(role = Role.Button, onClick = onClick)
    }
    Row(
        modifier = entryModifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showPlusBadge) {
                    PlusBadge()
                }
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlusBadge() {
    val strings = LocalTrackTalkStrings.current
    Text(
        strings.plusBadge,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun BasicPlaybackDefaults() {
    val strings = LocalTrackTalkStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(strings.freeGuideWithMusic)
        Text(strings.freeMusicDuckSummary)
        Text(strings.defaultFollowMediaVolume)
    }
}

@Composable
internal fun ContentReadOrderPicker(
    title: String,
    hint: String? = null,
    availableFields: List<AnnouncementReadField>,
    selectedFields: List<AnnouncementReadField>,
    onUpdate: (List<AnnouncementReadField>) -> Unit,
) {
    val strings = LocalTrackTalkStrings.current
    val normalizedFields = normalizeAnnouncementReadFields(
        fields = selectedFields,
        allowedFields = availableFields,
        fallbackFields = availableFields,
    )
    val visibleFields = normalizedFields + availableFields.filterNot { it in normalizedFields }
    val listState = rememberLazyListState()
    var draggingField by remember { mutableStateOf<AnnouncementReadField?>(null) }
    var draggedPointerCenter by remember { mutableFloatStateOf(0f) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            hint ?: strings.contentReadOrderHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            state = listState,
            userScrollEnabled = draggingField == null,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 4.dp, end = 12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CONTENT_READ_ORDER_PICKER_TAG),
        ) {
            items(
                visibleFields,
                key = { field ->
                    // A checked field moving to the inactive tail is a
                    // selection change, not a drag. Changing the segment in
                    // the key makes LazyRow remove the old item instead of
                    // preserving it as an anchor and scrolling after it.
                    contentReadFieldItemKey(field, field in normalizedFields)
                },
            ) { field ->
                val active = field in normalizedFields
                val currentFields by rememberUpdatedState(normalizedFields)
                val currentActive by rememberUpdatedState(active)
                val currentUpdate by rememberUpdatedState(onUpdate)
                FilterChip(
                    selected = active,
                    onClick = {
                        currentUpdate(
                            toggleAnnouncementReadField(
                                fields = currentFields,
                                field = field,
                                enabled = !currentActive,
                                allowedFields = availableFields,
                                fallbackFields = availableFields,
                            ),
                        )
                    },
                    label = {
                        Text(
                            strings.readField(field),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = if (active) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .testTag("$CONTENT_READ_ORDER_PICKER_TAG:${field.name}")
                        .zIndex(if (draggingField == field) 1f else 0f)
                        .graphicsLayer {
                            if (draggingField == field) {
                                val index = currentFields.indexOf(field)
                                val item = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.index == index }
                                if (item != null) {
                                    translationX = draggedPointerCenter - (item.offset + item.size / 2f)
                                    shadowElevation = 12f
                                }
                            } else {
                                translationX = 0f
                                shadowElevation = 0f
                            }
                        }
                        .pointerInput(field) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                if (currentActive) {
                                    val index = currentFields.indexOf(field)
                                    val item = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.index == index }
                                    if (item == null) return@detectDragGesturesAfterLongPress
                                    draggingField = field
                                    draggedPointerCenter = item.offset + offset.x
                                }
                            },
                                onDragCancel = {
                                    draggingField = null
                                    draggedPointerCenter = 0f
                                },
                                onDragEnd = {
                                    draggingField = null
                                    draggedPointerCenter = 0f
                                },
                                onDrag = { change, amount ->
                                if (!currentActive || draggingField != field) return@detectDragGesturesAfterLongPress
                                change.consume()
                                draggedPointerCenter += amount.x
                                val fields = currentFields
                                val fromIndex = fields.indexOf(field)
                                val visibleItems = listState.layoutInfo.visibleItemsInfo
                                val draggedItem = visibleItems
                                    .firstOrNull { it.index == fromIndex }
                                if (draggedItem == null || fromIndex < 0) return@detectDragGesturesAfterLongPress
                                val currentCenter = draggedItem.offset + draggedItem.size / 2f
                                var targetIndex = fromIndex
                                if (draggedPointerCenter > currentCenter) {
                                    while (targetIndex + 1 < fields.size) {
                                        val next = visibleItems.firstOrNull { it.index == targetIndex + 1 } ?: break
                                        if (draggedPointerCenter < next.offset + next.size / 2f) break
                                        targetIndex += 1
                                    }
                                } else if (draggedPointerCenter < currentCenter) {
                                    while (targetIndex - 1 >= 0) {
                                        val previous = visibleItems.firstOrNull { it.index == targetIndex - 1 } ?: break
                                        if (draggedPointerCenter > previous.offset + previous.size / 2f) break
                                        targetIndex -= 1
                                    }
                                }
                                if (targetIndex != fromIndex) {
                                    val reordered = reorderAnnouncementReadField(
                                        fields = fields,
                                        field = field,
                                        targetIndex = targetIndex,
                                        allowedFields = availableFields,
                                        fallbackFields = availableFields,
                                    )
                                    if (reordered != fields) {
                                        currentUpdate(reordered)
                                    }
                                }
                                },
                            )
                        },
                )
            }
        }
    }
}

internal const val CONTENT_READ_ORDER_PICKER_TAG = "content-read-order-picker"
internal const val GENERAL_SETTINGS_SCREEN_TAG = "general-settings-screen"
internal const val APP_LANGUAGE_SETTING_TAG = "app-language-setting"

internal fun contentReadFieldItemKey(
    field: AnnouncementReadField,
    active: Boolean,
): String = "${if (active) "active" else "inactive"}:${field.name}"

@Composable
private fun SettingSwitchRow(title: String, summary: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun VoiceDropdown(
    label: String,
    selected: InstalledVoice?,
    voices: List<InstalledVoice>,
    numberByName: Map<String, Int>,
    english: Boolean,
    autoLabel: String,
    previewLabel: String,
    onSelected: (InstalledVoice?) -> Unit,
    onPreview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    fun display(voice: InstalledVoice): com.trackvoice.announcement.VoiceDisplayLabels =
        VoiceMetadataPolicy.labels(voice, numberByName[voice.name] ?: 1, english)

    Column(
        modifier = Modifier.fillMaxWidth().then(modifier),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    selected?.let(::display)?.primary ?: autoLabel,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("⌄")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .widthIn(min = 340.dp, max = 380.dp)
                    .heightIn(max = 420.dp),
                shape = RoundedCornerShape(12.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                DropdownMenuItem(
                    text = { Text(autoLabel) },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                    onClick = {
                        onSelected(null)
                        expanded = false
                    },
                )
                voices.forEach { voice ->
                    val labels = display(voice)
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    labels.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    labels.secondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    expanded = false
                                    onPreview(voice.name)
                                },
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "$previewLabel: ${labels.primary}",
                                )
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        onClick = {
                            onSelected(voice)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> OptionDropdown(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
    onSelected: (T) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().then(modifier),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(optionLabel(selected), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("▾")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .heightIn(max = 360.dp),
                shape = RoundedCornerShape(12.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                        onClick = {
                            onSelected(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: (Float) -> String,
    onValueChange: (Float) -> Unit,
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(label)
        Text(valueLabel(sliderValue), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(
        value = sliderValue,
        onValueChange = { sliderValue = it },
        onValueChangeFinished = { onValueChange(sliderValue) },
        valueRange = range,
    )
}

@Composable
private fun DiagnosticRow(label: String, value: String, ok: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(value, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StatusBadge(enabled: Boolean, notificationAccess: Boolean) {
    val strings = LocalTrackTalkStrings.current
    val setupRequired = enabled && !notificationAccess
    val statusText = homeStatusText(enabled, notificationAccess, strings)
    val color = if (enabled && !setupRequired) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (enabled && !setupRequired) Icons.Default.CheckCircle else Icons.Default.Settings,
            contentDescription = statusText,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            statusText,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

private fun formatTime(timestamp: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
private fun formatTimeOrDash(timestamp: Long?, emptyLabel: String): String = timestamp?.let(::formatTime) ?: emptyLabel
