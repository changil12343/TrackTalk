# TrackTalk agent guide

TrackTalk is a native Android utility that observes another app's active
`MediaSession` and announces selected track metadata with Android TTS. It is
an external MediaSession observer/controller: it does not stream, own, decode,
or catalogue the user's music.

## Start here

1. Read this file, then [docs/README.md](docs/README.md).
2. Run `git status --short --branch` before editing; preserve valid local work.
3. Treat current source and tests as stronger evidence than old handoffs.
4. Keep `CODEX_HANDOFF.md` untracked and session-specific; it is not product
   documentation.
5. Do not reset, clean, stash away, or replace the working tree with remote
   history unless the user explicitly requests it.

## Repository map

- `app/src/main/java/com/trackvoice/` — production Kotlin code.
- `app/src/test/` — JVM unit coverage for deterministic policy/model logic.
- `app/src/androidTest/` — Android/instrumented behavior and Compose coverage.
- `app/src/debug/` and `app/src/testDebug/` — isolated debug/experiment support.
- `docs/` — canonical detailed project knowledge.
- `artifacts/` — local, ignored validation output only; never commit captures.

## System boundaries

- Input is active Android `MediaSession` metadata/state/queue. Notification Listener access is required for detection; `POST_NOTIFICATIONS` only enables the optional status-bar shortcut.
- TrackTalk reads no general notifications, has no account/server backend, records no playback history for analytics, and sends only title/artist/album/duration to its hidden beta metadata adapter.

## Stable product invariants

- Do not add music streaming or source-player ownership.
- Per-app configuration is enable/disable eligibility only; announcement
  content/timing is global.
- App UI language and spoken-language selection are separate settings.
- Fresh app UI language is `SYSTEM`; unsupported UI locales resolve to English.
- Fresh reading selection enables only `TITLE`; canonical visible order is
  `TITLE → ARTIST → ALBUM`.
- Track number remains hidden from the beta UI. Never derive it from queue
  index or songs heard in the session.
- Fresh announcement volume mode is `FOLLOW_MEDIA`, which passes neutral TTS
  gain (`1.0f`) and leaves normal Android route/stream control intact. Custom
  mode starts its slider at 80%; migration preserves every existing stored
  volume as Custom because its original intent cannot be known, while Free
  runtime entitlement clamps the Plus-only custom control to 80%.
- One logical playback occurrence receives at most one automatic announcement.
  A genuine `A → B → A` sequence can announce A again.
- Only TrackTalk-owned pauses may be restored, and a user/manual pause must
  never be overwritten by stale restoration work.
- Normal ducking uses semantic audio focus/system ducking, not direct
  `STREAM_MUSIC` mutation. Legacy recovery and optional device-volume features
  are separate compatibility paths.

## Playback rules

- Prefer missing metadata to fabricated metadata; album data is not proof of an album-page playback source.
- Internal album/playlist/recommendation evidence never selects a user-facing content-specific reading configuration.
- Metadata may arrive late or enriched; match it to the same logical track rather than speaking twice.
- Next-track prefetch is metadata/TTS preparation only: never early TTS, duck, pause, or pre-boundary hold.
- Audio intervention is reactive to confirmed playback. Do not add predictive pauses, rewinds, fixed guards, polling, or synthetic interstitials without new evidence and explicit approval.

## Core implementation map

- Lifecycle/controller: `TrackVoiceApplication`, `TrackVoiceController`.
- Sessions/mapping: `media/MediaSessionMonitor`, `media/ActiveSessionSelector`, `media/TrackMetadataMapper`.
- Policy/text: `announcement/AnnouncementPolicy`, `AnnouncementFormatter`, `DuplicateSuppressor`.
- Restore: `announcement/PlaybackRestoreObligation` plus `MediaSessionMonitor` pause/resume tokens.
- TTS/audio: `announcement/TtsEngine`, `TrackTalkAudioAttributes`, `AudioFocusManager`, `AnnouncementPlaybackPlan`.
- Persistence: `data/SettingsModels`, `data/DataStoreRepository`.
- UI/localization: `ui/TrackVoiceApp`, `ui/TrackTalkStrings`, `localization/LocalizedResources`.

## Documentation ownership

- [architecture](docs/architecture.md) owns component/data-flow boundaries.
- [product decisions](docs/product-decisions.md) owns durable user-facing
  policy and Free/Plus boundaries.
- [playback semantics](docs/playback-semantics.md) owns MediaSession,
  identity, duplicate, context, metadata, prefetch, and restore-lifecycle
  semantics.
- [audio and TTS](docs/audio-tts.md) owns focus, routing, speech, and TTS completion; it links to playback semantics for restoration.
- [device routing and automation](docs/device-routing-automation.md) owns
  logical devices, route decisions, and automation.
- [UI/UX](docs/ui-ux.md) owns navigation, copy hierarchy, localization, and
  permission presentation.
- [testing](docs/testing.md) and [beta release](docs/beta-release.md) own
  validation/release procedures.
- [rejected approaches](docs/experiments/rejected-approaches.md) records what
  must not be quietly reintroduced.

## Safe workflow

1. Find the owner document and relevant source/test before changing behavior.
2. Keep production code, debug experiments, and temporary captures separated.
3. Add a deterministic regression test for a reproducible policy/state bug.
4. Use instrumentation and real-device logs for Android/provider behavior; unit tests alone do not prove audio timing or player integration.
5. Use ignored `artifacts/screenshots/` and `artifacts/logs/`, then delete disposable captures after review.
6. Do not commit or push generated APKs, keystores, local SDK files, device
   identifiers, logs, screenshots, or `CODEX_HANDOFF.md` by accident.

## Validation baseline

Use [docs/testing.md](docs/testing.md) for the validation matrix and the distinction between emulator evidence and human real-device listening.

## Change discipline

- Do not broaden a focused task into a redesign.
- Keep Java/Kotlin source compatibility at 17 unless a real build requirement
  requires a deliberate migration; the host Gradle JDK is separate.
- Do not claim Spotify/YouTube Music semantics that their MediaSessions do not
  expose.
- Do not mark subjective audio timing/loudness as accepted without a human
  listening result.
