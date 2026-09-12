# Architecture

## Purpose and boundary

TrackTalk is a Kotlin/Compose Android application that observes active media
sessions exposed by other apps and speaks selected metadata using Android TTS.
It does not host a player, stream music, parse general notifications, or own a
music library.

The required observation permission is Notification Listener access. It lets
the app query active `MediaSession` controllers through `MediaSessionManager`.
`POST_NOTIFICATIONS` is separate and only affects TrackTalk's optional
status-bar shortcut.

## Runtime flow

```text
NotificationListenerService
  → MediaSessionMonitor / ActiveSessionSelector
  → TrackMetadataMapper
  → TrackVoiceController serial media-update queue
  → memory-only duration pre-arm (preparation only)
  → AnnouncementPolicy + AnnouncementFormatter
  → duplicate / pending / route eligibility checks
  → AnnouncementPlaybackPlanner (Keep / default system duck / owned pause) + TtsEngine
```

The controller is the orchestration boundary. It owns pending announcement
tokens, duplicate history, current-session identity, next-track preparation,
route retries, TTS completion, and any pause/restore obligation. UI code must
not recreate those decisions independently.

## Primary modules

| Area | Source owner | Responsibility |
| --- | --- | --- |
| Process construction | `TrackVoiceApplication` | Creates repository, billing manager, controller. |
| Permission/lifecycle bridge | `service/TrackVoiceNotificationListenerService` | Attaches/detaches session monitoring, exposes listener state, and handles screen events. |
| Status notification | `service/TrackVoiceStatusNotificationManager` | App-scoped observer that renders the optional status shortcut from controller state and applies its explicit enable/disable action. |
| Session observation | `media/MediaSessionMonitor` | Tracks controllers, maps callbacks, selects a current session, issues scoped pause/resume commands. |
| Metadata mapping | `media/TrackMetadataMapper` | Normalizes metadata, queue descriptions, IDs, durations, and reliable track-number provenance. |
| Playback semantics | `media/PlaybackEvent`, `TemporalPlaybackContextResolver`, `NextTrackPrefetch` | Represents a snapshot, conservative context evidence, metadata-only preparation. |
| Announcement decision | `announcement/AnnouncementPolicy`, `AnnouncementFormatter` | Applies settings/eligibility and builds the exact spoken text. |
| Duplicate/restore state | `DuplicateSuppressor`, `PlaybackRestoreLease`, `PlaybackRestoreObligation` | Prevents callback churn from speaking twice. Selected Pause mode permits one exact, memory-only owned-pause restore only after independent TTS-terminal and pause-acknowledgement conditions. Keep and default system duck create no lease. |
| Audio/TTS | `TtsEngine`, `AudioFocusManager`, `TrackTalkAudioAttributes` | Selects Android voices, speaks text, and uses semantic focus/attributes. |
| Route/device model | `AudioOutputDetector`, `AudioDeviceMonitor`, `LogicalAudioDevice` | Separates the active media route from connected-device inventory. |
| Persistence | `data/DataStoreRepository`, `SettingsModels` | Stores settings, safe migrations, app eligibility, and persisted duplicate state. |
| UI/localization | `ui/TrackVoiceApp`, `TrackTalkStrings`, `localization/LocalizedResources` | Compose screens, resource strings, and app-language presentation. |

## State ownership

`UserSettings` is persisted in DataStore and is the only durable source of
normal user configuration. `AppSettings` contains per-app eligibility only;
it is deliberately not a second announcement policy layer. Premium state is
provided by `PlayBillingManager` and clamped through `forPremiumEntitlement`
before runtime policy uses settings.

The controller keeps ephemeral state in memory: the selected controller,
pending token/job, speech generation, prepared next track, duration pre-arm
token/job, audio-route snapshot, and active restore lease. It persists only what must survive process
recreation, such as the last accepted announcement. Recent media metadata and
per-device route identity live in a separate DataStore file excluded from
Android Auto Backup.

## Lifecycle rules

- `MainActivity.onResume` refreshes notification access and billing UI state.
- Listener connection rebuilds the media monitor from persisted settings and
  current active sessions without requiring an Activity; disconnection detaches
  it while preserving duplicate history for a continuing logical playback and
  requests Android's normal listener rebind path.
- Media callbacks are serialized before announcement decisions are made. Each
  controller attachment has an in-memory generation, so a late callback from a
  detached controller cannot mutate the replacement controller's state.
- A destroyed media session invalidates only its matching controller generation
  and triggers one active-session reconciliation; it is not treated as a
  TrackTalk or NotificationListener failure.
- A session refresh is infrastructure churn, not automatically a new playback
  occurrence.
- A selected player that vanishes, is destroyed, or is replaced after TrackTalk
  pauses it invalidates the restore lease. Reconnect recovers observation only;
  it never rediscovers a player for automatic resume.
- Duration pre-arm is one lightweight, memory-only timer tied to monitor,
  controller, session, and track identity generations. It may refresh safe
  metadata/text/voice preparation shortly before an estimated end, but cannot
  call TTS or control playback; a confirmed current-track event still enters
  the normal announcement gate.

## Metadata boundary

TrackTalk formats announcements solely from the observed Android
`MediaSession` snapshot and local settings. v1 does not query an external music
catalog or transmit media title, artist, album, or duration to enrich metadata.
Missing or ambiguous player metadata remains missing rather than being guessed.
See [Playback semantics](playback-semantics.md).

## Build shape

- Android application ID: `com.trackvoice`.
- minSdk 26, compile/target SDK 36.
- Kotlin/JVM and Java source compatibility: 17.
- Compose Material 3, DataStore Preferences, Google Play Billing.
- Debug-only diagnostic/experiment code stays in `app/src/debug` and
  `app/src/testDebug`; it must not become production behavior by proximity.

For validation layers and commands, see [Testing](testing.md).
