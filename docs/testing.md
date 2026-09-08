# Testing and validation

TrackTalk is an Android/media integration utility. Passing JVM tests proves
deterministic policy and state logic; it does not prove a third-party media
app's callback timing, audio route, or perceived loudness.

## Standard local commands

Run from the repository root. Use the narrowest relevant target first, then
the wider suite before a release-quality change:

```text
gradlew.bat :app:testDebugUnitTest --no-configuration-cache
gradlew.bat :app:testReleaseUnitTest --no-configuration-cache
gradlew.bat :app:lintDebug --no-configuration-cache
gradlew.bat :app:check --no-configuration-cache
gradlew.bat :app:assembleDebug --no-configuration-cache
gradlew.bat :app:assembleRelease --no-configuration-cache
gradlew.bat :app:assembleDebugAndroidTest --no-configuration-cache
gradlew.bat :app:connectedDebugAndroidTest --no-configuration-cache
```

Do not change Java/Kotlin source compatibility merely because the Gradle host
JDK differs; source target 17 is intentional.

## Test layers

| Layer | What it proves | Representative coverage |
| --- | --- | --- |
| JVM unit | Deterministic rules | field defaults/migrations, announcement policy, duplicate semantics, TTS/voice policy, route evidence, logical device normalization, metadata matching, prefetch. |
| Instrumented | Android framework/Compose behavior | MediaSession mapping/monitoring, DataStore persistence, notification permission matrix, localization, voice warm path, feedback intent, restore behavior. |
| Debug experiment | Isolated hypothesis behavior | duration/prefetch/interstitial state machines; not release acceptance. |
| Emulator | install/launch and supported instrumented tests | framework integration, not real music-provider audio. |
| Physical device | provider/route/audio acceptance | real MediaSession data, Samsung route behavior, player pause/restore, perceived speech and leakage. |

## Required regression coverage

When changing playback behavior, retain or add coverage for:

- late title/artist enrichment without a second announcement;
- duplicate callback churn and logical `A → B → A` replay;
- unrelated notification posted/removed followed by active-session refresh,
  listener reconnect, or controller replacement while the same core track is
  playing, all with zero new TTS and zero transport commands;
- a supported media-notification hint whose fresh MediaSession snapshot finds
  a callback-missed replacement track, same-track hint snapshots, unrelated
  notifications, burst coalescing, and a normal metadata-callback/hint race;
  only the authoritative changed snapshot may produce one announcement;
- process/session recreation with the same currently playing track;
- controller replacement with a late stale callback, session destruction or
  removal, listener reconnect, and monitor restart without any inherited
  `PLAY` authority or UI launch;
- album-only mixed-frame correction as enrichment without lease, duplicate,
  or duration-prediction identity churn; transient previous queue-item IDs with
  the same core track likewise preserve lease/duplicate/pre-arm state, while a
  coherent queue plus provider/core replacement invalidates old work;
- duration pre-arm extrapolation, duration-key absence/invalid fallback,
  seek/speed/pause invalidation, late stale timer rejection, controller/
  listener generation replacement, and duplicate metadata without a second
  preparation or announcement;
- stale pending/prepared work after quick next/previous or session replacement;
- direct player track number versus queue index rejection;
- route transition/retry and external-only policy;
- all three automatic-announcement modes: Keep and system duck produce one
  TTS, zero `PAUSE`, zero restore leases, zero automatic `PLAY`, and final
  authoritative `PLAYING`; selected Pause produces one owned `PAUSE`, a valid
  acknowledged lease, TTS, and exactly one valid restore. Saved
  `MusicTreatment.PAUSE` survives reload; a fresh default remains Duck;
- owned pause restore in both event orders (`PAUSED` before TTS and TTS before
  a delayed `PAUSED`), a provider callback that reuses the pre-command PLAYING
  timestamp, a new track with no same-track PLAYING callback baseline whose
  post-command PAUSED source timestamp predates the local pause command,
  metadata/queue PAUSED remaps that must not acknowledge early,
  missing/stale pause acknowledgement expiring with zero `PLAY`, user
  observable newer pause/stop, stale TTS completion, process recovery without a lease, exactly
  one legitimate `PLAY`, and zero retry/resurrection after controller/session/
  listener invalidation. Device validation waits for the bounded final
  authoritative state: a stale post-`PLAY` PAUSED callback followed by PLAYING
  is diagnostic-only, while a genuine final PAUSED state fails restore;
- observable newer playback intent cancels restoration with zero `PLAY`.
  A redundant external pause while the source is already paused may publish
  no new state/callback; do not require TrackTalk to identify an unexposed
  command. Classify that case separately under the
  [platform observability limitation](playback-semantics.md#owned-pause-restore-lifecycle),
  retaining the existing valid owned-restore semantics;
- immediate policy forcing delay/minimum playback to zero;
- Follow-media TTS gain (`1.0f`), saved custom-volume persistence, and legacy
  volume migration to Custom without changing its value;
- global beta-visible field ordering/toggle/drag behavior.

## Metadata tests

External metadata tests are deterministic fakes, never live-network JVM tests.
Cover exact matches, duration-assisted variants, edition normalization, artist
or album mismatch, equally strong ambiguity, multi-disc fields, rate limit,
malformed/empty responses, cache freshness, negative cache, and timeout.
The expected safe failure is a normal local announcement without a number.

## Real-device procedure

Use ADB only with a device the user connected for this task. Do not uninstall
the production app or clear data simply to overcome a signing/test-package
issue. Confirm notification access, selected media session, TTS state, and
output route first.

For a media test, record only minimal validation facts: player, local
title/artist/album availability, current track-number provenance, decision,
duplicate count, focus/pause/restore events, and outcome. Do not retain raw
track logs or device identifiers in committed documentation.

Human listening is required for voice loudness, whether music leaks perceptibly
before speech, and whether a pause interrupts a musical transition. The agent
can automate setup/log correlation but must not claim subjective success from
Logcat alone.

## Temporary artifacts

Put temporary screenshots in `artifacts/screenshots/` and raw diagnostics in
`artifacts/logs/`. Both paths are ignored. Inspect locally, retain only a
short non-sensitive conclusion in documentation when useful, and delete
disposable captures after the validation pass.

## Failure reporting

Report exact command, target, pass/fail/skip scope, device/emulator scope, and
whether a failure is test code, infrastructure, signing, or application
behavior. Do not say “instrumentation passed” when only a filtered test ran or
the runner timed out.
