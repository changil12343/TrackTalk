# Product decisions

This file records durable product policy. It is intentionally narrower than a
feature inventory: if a behavior is not reliable enough to promise, it is not
a user-facing mode.

## What TrackTalk is

TrackTalk's core value is simple: while a normal media app is playing, detect
the current track through Android's exposed media session and announce the
information the user selected. The user should be able to configure it once,
put the phone away, and trust one correct announcement more than a richer but
wrong one.

TrackTalk is not a music player, streaming service, recommendation engine,
audio recognizer, or accessibility service.

## Truth before apparent feature count

- A song can have album metadata even when it was selected from search, a
  playlist, liked songs, a radio queue, or a single-item tap.
- Ordinary Android MediaSession data usually does not reveal which player UI
  action started playback.
- Therefore Album / Playlist / Recommendation are not user-facing reading
  modes and never choose separate runtime field settings.
- Conservative internal context evidence may remain useful for diagnostics or
  future high-confidence sequence work, but `UNKNOWN` is preferred over a
  false claim.

## Global reading configuration

There is one global ordered selection of fields. The visible active chips are
also the actual spoken order. For a fresh configuration:

```text
active:   Title
available order: Title → Artist → Album
```

Artist and Album append after currently active fields when enabled. Active
chips can be long-press dragged to reorder. The UI must not auto-scroll to an
inactive chip when a selected chip is turned off. Track Number is retired from
the v1 surface; a legacy stored selection is normalized to supported local
fields during migration.

## Metadata policy

- Use player-provided title, artist, and album when available for v1
  announcements.
- Accept `DISPLAY_TITLE`/`DISPLAY_SUBTITLE` and compatible queue descriptions
  when canonical MediaMetadata keys are absent.
- Never treat queue position or songs heard in a session as canonical track
  number. Local track numbers remain diagnostic metadata only; they are not a
  v1 reading field.
- An absent/ambiguous value is omitted; TrackTalk never queries an external
  catalog to fill it.

## Free and Plus

Free is a complete core experience, not a degraded demo. It includes detection,
global title/artist/album field selection and ordering, standard Android voice
selection/preview, per-app enablement, and reliability behavior.

Plus is a lifetime quality-of-life upgrade for deeper control: speech rate,
pitch, custom voice volume, music attenuation, timing/minimum-playback controls,
same-song repeat control, device/state automation, and future advanced
convenience features. Runtime entitlement must remain synchronized with what
the UI permits.

Automatic announcements use Android semantic system duck by default. Keep
leaves music unchanged; user-selected Pause-and-restore uses the existing
owned-pause lifecycle. All three options remain available under the existing
entitlement rules, and saved Pause settings are preserved on reload.

Ads are intentionally deferred from the beta. If added later, they must never
interrupt TTS, a track transition, the global toggle, background service, or
notification/Quick Settings interaction.

## App and device policy

- App categories improve discovery; only music-streaming apps are enabled by
  default. Other categories require explicit enablement.
- An app's setting decides whether TrackTalk is eligible for that app; it does
  not override global reading content/timing.
- Default output policy is external audio only. Built-in speaker and earpiece
  are not external routes.
- Device-specific automatic activation and screen-off automation are Plus
  controls; route/device detection itself is available for core behavior.

## Privacy and feedback

No user account, analytics SDK, general-notification parsing, playback-history
upload, music streaming, or TrackTalk-owned metadata backend belongs in the
product boundary. Current media information is used locally for TrackTalk
features and announcement text; selected Android TTS engines receive the text
needed to synthesize speech, some selected voices may use network processing,
and provider policy governs that processing. Google Play handles purchases. The
production feedback action opens an `ACTION_SENDTO` `mailto:` intent with only
app version/build, Android version, and device model prefilled. It never
attaches logs, tracks, settings, or history.

The App info card exposes the privacy policy when the release operator supplies
the public HTTPS URL through `tracktalk.privacyPolicyUrl`. An empty value is a
visible release-configuration state, never a fabricated policy link.

## UI language versus speech language

App interface language and TTS language are independent. New UI settings use
system language; Korean systems display Korean, while unsupported UI locales
fall back to English. Speech language can be automatic, system, Korean, or
English and follows voice/segment resolution rather than the UI choice.
