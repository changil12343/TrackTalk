# UI and UX

TrackTalk uses Compose Material 3 with a compact utility-first hierarchy. The
UI should explain the current effective behavior without exposing unstable
MediaSession inference or making a free user feel that the app is a demo.

## Navigation

The main bottom navigation has four user-facing destinations:

1. Home
2. Guide and Voice
3. Apps
4. Device

Diagnostics live inside Device rather than consuming a permanent bottom-tab
slot. App language is also a compact general preference, not a dominant
announcement-setting control.

## Home

The visual hierarchy is:

```text
TrackTalk state
Voice-announcement status
Temporary permission guidance, if needed
Current playback
Optional compact Free Plus promotion
```

The Current Playback card shows app, title, artist, and album, followed by
compact inline `Announcement` and `Reading` rows. It does not contain a nested
current-announcement card, a last-detected diagnostic row, or a paragraph that
misstates album metadata as playback-source knowledge. Long titles may use up
to two lines and must not collide with controls or break label alignment.

The Home reading summary is derived from the same effective global settings
used at runtime. Use `→` to show spoken order. It must not invent whether the
current song came from an album or playlist.

## Permission presentation

There are two intentionally distinct permission concepts:

- **Music detection — Required.** Notification Listener access is needed to
  observe active media sessions. When absent, Home shows one compact required
  card, hides optional notification prompting and Free Plus promotion, and
  uses an honest playback-empty explanation.
- **Status notification — Optional.** On Android versions with runtime
  notification permission, a compact status item may appear when the status
  notification is enabled. It truthfully surfaces missing music-detection
  access as a setup state, otherwise summarizes TrackTalk's own effective
  state and offers one enable/disable action; it never duplicates player
  metadata or transport controls.

The Home state resolver keeps these cases separate: required missing,
required granted/optional missing, both granted, required later revoked, and
optional later revoked. Returning from Settings refreshes permission state;
the UI does not repeatedly launch system permission flows.

## Announcement settings

There is one global ordered field configuration. The beta-visible chips are
Title, Artist, and Album. Fresh settings enable only Title and preserve the
available canonical order `Title → Artist → Album`. A tap enables/disables a
field; a long press and drag reorders active fields. Turning off a chip closes
the active gap without scrolling the viewport to an inactive chip.

Track Number is deliberately hidden in beta. Legacy content-specific settings
remain only for safe migration and must not reappear as a UI model or a runtime
precedence layer.

Immediate reading means effective delay and minimum-playback duration are zero;
the inapplicable controls are hidden. Detailed timing, speed, pitch, custom
voice volume, music attenuation, repeat behavior, and automation remain Plus
controls where runtime entitlement applies. Announcement gain defaults to
Follow media volume; the percentage slider is shown only after Custom is
selected.

The music-during-announcement control exposes Keep, Lower music (the default),
and Pause then announce under the existing entitlement rules. A saved
`MusicTreatment.PAUSE` remains selected after reload. Platform observability
limits are documented in playback semantics, not added as a separate UI warning.

## Apps and devices

Apps are grouped by category for scanning, but their individual setting is
only TrackTalk eligibility. The screen gives the shared explanation once,
rather than repeating it on every row. Music-streaming apps are enabled by
default; other categories remain off until a user enables them.

Device reflects output/permission/TTS diagnostics and Plus automation. It uses
the logical-device model described in
[Device routing and automation](device-routing-automation.md).

## Voice UI

Automatic language shows an automatic voice choice, not an arbitrary manual
catalog. A fixed language filters manual voices to compatible entries. Voice
rows use deterministic display numbering and engine-provided network,
quality, and latency labels. Unknown gender is omitted rather than guessed.
Each manual voice has a compact exact-voice preview that replaces any prior
preview.

## Localization and copy

App UI language is `SYSTEM`, Korean, or English; a system Korean locale uses
Korean and unsupported system locales use English. Spoken language is an
independent TTS choice. Do not hard-code a visible string when the existing
localization layer owns it.

Keep copy short when the selected control already communicates behavior. Use
feature language rather than Android-internal terminology, especially for
permissions. Production App Info offers developer feedback by email and does
not show a GitHub/source/project-page link.

## Plus presentation

Plus is contextual. A Free user can see a compact Home promotion after core
permission is resolved; an active Plus user does not see a large Home sales
card. Locked automation/details are grouped at their relevant settings rather
than repeated in headers or every viewport. See
[Product decisions](product-decisions.md) for entitlement boundaries.
