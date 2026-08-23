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
  → AnnouncementPolicy + AnnouncementFormatter
  → duplicate / pending / route eligibility checks
  → audio preparation + TtsEngine
  → optional owned-pause restore
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
| Duplicate/restore state | `DuplicateSuppressor`, `PlaybackRestoreObligation` | Prevents callback churn from speaking twice and restores only owned pauses. |
| Audio/TTS | `TtsEngine`, `AudioFocusManager`, `TrackTalkAudioAttributes` | Selects Android voices, speaks text, and uses semantic focus/attributes. |
| Route/device model | `AudioOutputDetector`, `AudioDeviceMonitor`, `LogicalAudioDevice` | Separates the active media route from connected-device inventory. |
| Persistence | `data/DataStoreRepository`, `SettingsModels` | Stores settings, safe migrations, app eligibility, cached metadata, and persisted duplicate state. |
| UI/localization | `ui/TrackVoiceApp`, `TrackTalkStrings`, `localization/LocalizedResources` | Compose screens, resource strings, and app-language presentation. |

## State ownership

`UserSettings` is persisted in DataStore and is the only durable source of
normal user configuration. `AppSettings` contains per-app eligibility only;
it is deliberately not a second announcement policy layer. Premium state is
provided by `PlayBillingManager` and clamped through `forPremiumEntitlement`
before runtime policy uses settings.

The controller keeps ephemeral state in memory: the selected controller,
pending token/job, speech generation, prepared next track, audio-route
snapshot, and active restore cycle. It persists only what must survive process
recreation, such as the last accepted announcement and metadata cache entries.

## Lifecycle rules

- `MainActivity.onResume` refreshes notification access and billing UI state.
- Listener connection attaches the media monitor; disconnection detaches it
  while preserving duplicate history for a continuing logical playback.
- Media callbacks are serialized before announcement decisions are made.
- A session refresh is infrastructure churn, not automatically a new playback
  occurrence.
- A selected player can vanish after TrackTalk pauses it; the monitor retains
  the exact accepted controller only for the scoped restore path.

## External metadata boundary

`metadata/ExternalTrackMetadata.kt` defines a provider-independent resolver
contract. The current adapter is `ItunesTrackMetadataResolver`; it receives
only public title, artist, album, and optional duration. It never receives an
account identifier, device identifier, playback history, settings, artwork, or
audio.

The resolver/cache is isolated from normal session mapping. A lookup is
best-effort metadata enrichment, cannot block normal local speech, and must
never cause a second announcement. In the current beta UI, track number is
hidden, so the adapter remains a prepared capability rather than a visible
user promise. See [Playback semantics](playback-semantics.md).

## Build shape

- Android application ID: `com.trackvoice`.
- minSdk 26, compile/target SDK 36.
- Kotlin/JVM and Java source compatibility: 17.
- Compose Material 3, DataStore Preferences, Google Play Billing.
- Debug-only diagnostic/experiment code stays in `app/src/debug` and
  `app/src/testDebug`; it must not become production behavior by proximity.

For validation layers and commands, see [Testing](testing.md).
