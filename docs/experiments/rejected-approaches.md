# Rejected playback-boundary approaches

These experiments are recorded so a future latency fix does not quietly revive
a known-bad trade-off. Some supporting state-machine tests may remain in
debug-only source for diagnosis; that does not make the approach production
behavior.

## Direct `STREAM_MUSIC` ducking or mute masking

- It changes the user's global media environment rather than asking Android
  for semantic ducking.
- It can attenuate the effective listening context for speech and risks failed
  restoration when callbacks/processes change.
- Normal ducking uses audio focus; legacy recovery exists only for old writes.

## Accessibility-stream misuse

- TrackTalk is not an AccessibilityService, so an accessibility usage/stream
  is not the correct semantic route for ordinary announcements.
- A louder result is not justification for misleading Android audio semantics.
- Use assistant/speech attributes and verify loudness on the actual device.

## Audio-focus-assisted Fast Pause fallback

- Adding a separate transient-focus command before/alongside pause was tested
  as a possible boundary shortcut, not adopted as an extra production path.
- Focus success does not prove that a provider becomes inaudible sooner or
  restores more reliably than the scoped transport pause.
- Keep the single reactive Fast Pause flow unless new measured evidence and
  listening acceptance justify a deliberate redesign.

## Fixed pre-boundary guards

- Fixed early guards pause/duck before Track B is confirmed current.
- They can cut the end of Track A or act on an inaccurate player boundary.
- No fixed guard belongs in production latency handling.

## 1.5- or 2-second early pause

- A long early pause hides leakage by making an audible artificial gap.
- It is worse for normal listening and cannot adapt to skips/crossfade.
- The approach is rejected rather than retuned with another magic number.

## Pause plus rewind

- Human listening found the beginning of Track B clearly audible before the
  announcement and the resulting playback behavior worse than Fast Pause.
- Rewinding changes the user's media position and compounds provider timing.
- Do not productionize it; retain only isolated diagnostic evidence if useful.

## Virtual interstitial / pre-B hold

- A synthetic hold treats a predicted next item as current playback.
- It risks pausing the wrong item during queue rebuilds, rapid skips, or stale
  metadata and creates a user-visible artificial interstitial.
- Metadata preparation remains allowed; pre-B audio control does not.

## Pure duration-based pause

- Duration and position estimates do not authorize a transport command.
- Players may seek, crossfade, buffer, change speed, or report stale state.
- Duration is diagnostic/preparation evidence only, never an early pause gate.

## Duration-gated early queue pause

- Adding queue identity to duration prediction narrows but does not remove the
  uncertainty of the actual audio boundary.
- Debug state-machine coverage can preserve the safety analysis without
  promoting the behavior to the controller.
- Do not wire it into production audio intervention.

## Whole-second duration hypothesis

- The Samsung duration audit observed fine-grained, repeatable duration values
  rather than an all-whole-second provider limitation.
- Transition estimates still had material error, so precision alone was not
  the missing signal.
- Do not revive an early pause strategy on a duration-granularity assumption.

## Same-main immediate shortcut

- An immediate same-main-loop shortcut must not bypass normal eligibility,
  duplicate, route, and current-track confirmation checks.
- Cutting coroutine dispatch is useful only after the confirmed event is safe
  to act on; it is not permission to act before confirmation.
- Preserve the normal controller ordering rather than creating a parallel path.

## Queue index as album track number

- Queue order can be a recommendation, shuffle, manually selected subset, or
  provider-generated “up next” list.
- `queueIndex + 1` is not factual album position and is never spoken.
- Omit a number unless player metadata/cache/external match proves it.

## Inferring playback source from song metadata

- Album title, total tracks, and one song's disc/track fields describe the
  song/release, not the UI action that began playback.
- Normal media sessions rarely disclose album-page versus playlist/radio/search
  intent reliably.
- Keep source-specific configuration out of the user-facing/runtime policy.

## Historical-track blacklist

- Suppressing any song that was ever announced makes a later genuine replay
  silently disappear.
- The regression contract is `A → B → A` announces A as a new occurrence.
- Persist only enough current-occurrence history to survive process/session
  recreation, not an indefinite listening-history blacklist.
