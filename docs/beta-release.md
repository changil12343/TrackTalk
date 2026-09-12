# Beta release

This checklist is for a small closed beta. It is not a claim that all Android
players or device combinations behave identically.

## Release gate

Do not call a build beta-ready while any of these are unresolved:

- frequent duplicate or stale announcements;
- crash, blocked TTS, permanently paused media, or lost restoration;
- incorrect factual metadata such as fabricated track numbers;
- a regression that lets a stale session affect a replacement player;
- a privacy/security issue, a committed secret, or an unintentionally tracked
  local artifact.

Missing metadata on an ambiguous player callback is acceptable. A wrong claim
about playback source or track number is not.

## Required validation

1. Review `git status`, the complete intended diff, and commits ahead of the
   target branch. Preserve unrelated user work.
2. Run debug and release unit tests, `lintDebug`, `check`, debug/release APK
   builds, and debug test APK assembly. See [Testing](testing.md).
3. Run the practical emulator instrumentation subset and report its exact
   scope; an emulator does not replace real media-provider audio testing.
4. Install/update on a user-authorized physical device without clearing data.
   Investigate signing mismatch; never solve it by silently uninstalling the
   user's app.
5. Verify current Home permission states, global reading defaults, app
   enablement, voice selection/preview, output policy, and feedback intent.
6. Run physical media checks for normal transition, direct song, rapid skip,
   background/session churn, owned pause restoration, and route changes.

## Human-only acceptance

Only request human listening answers that logs cannot prove:

- Is the chosen speech volume intelligible over the selected music level?
- Is 100% meaningfully louder than the configured default?
- Is any pre-speech music leakage perceptible and annoying?
- Does Fast Pause interrupt/restore the musical experience acceptably?

Do not turn those subjective questions into a request for the user to manually
repeat checks that a test, log, or UI inspection can establish.

## Packaging and signing

The app currently targets SDK 36, minSdk 26, and Java/Kotlin source target 17.
Build and signing environments are separate from source compatibility. Treat a
debug signing mismatch as an installation-continuity issue, not a reason to
rewrite source or wipe user data.

Before sharing a build, confirm that it contains no keystore, local SDK path,
device identifier, raw validation log, screenshot, or temporary experimental
receiver not intended for the recipient. Production release signing and Play
Console configuration require their own controlled workflow. Follow the
credential-free operator handoff in [Release signing](release-signing.md); an
unsigned release artifact is a validation output, not a publishable build.

## Product readiness statement

The beta promise is the core utility: observe a supported active MediaSession,
speak selected local metadata once, and leave media in the correct state. It
does not promise that every player reveals playback-source intent or canonical
track number. Track Number is retired from the v1 announcement surface.

Ads are deferred. Plus must remain a convenience/personalization/automation
upgrade rather than a repair for core detection, speech, or reliability.

## Source-control handoff

Create focused commits only after the implementation and validation are
coherent. Do not force-push. Do not automatically push local commits or the
temporary `CODEX_HANDOFF.md`; report the final branch relation and let the user
authorize a remote update.
