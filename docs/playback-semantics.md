# Playback semantics

This document defines what TrackTalk may infer from Android media sessions and
what it must refuse to invent. It is the canonical reference for new-track
identity, duplicate behavior, factual metadata, and preparation.

## Input model

`MediaSessionMonitor` receives active-session, metadata, playback-state, and
queue callbacks. `TrackMetadataMapper` turns each callback into a
`PlaybackEvent` with source package, media ID, title, artist, album, playback
state/position, queue data, and track-number provenance.

Players may publish a partial event first and enrich it afterward. In
particular, a usable title may be in `DISPLAY_TITLE` rather than canonical
`TITLE`, and artist may arrive in `DISPLAY_SUBTITLE` or a queue description.
The same mapper feeds Home and the speech pipeline so UI-visible metadata and
spoken metadata do not intentionally diverge.

## Active-session selection

The monitor tracks all active controllers and selects an appropriate playable
session rather than blindly trusting a single callback. Session callback
objects are infrastructure; a controller replacement or refresh does not by
itself mean a new song.

The selected session has a generation/key. Work scheduled from an old session
must be rejected once a newer session/package/current track supersedes it.

## Track identity and occurrence

TrackTalk needs two related notions:

- **Logical track identity** decides whether partial metadata, a queue
  description, and canonical metadata are the same current item.
- **Playback occurrence** decides whether a logical track should be announced
  again.

A stable non-empty media ID is strongest. When it is absent or temporarily
replaced, source package plus compatible title/artist/album, reliable
track/disc metadata, and the active queue item can bridge only short metadata
enrichment. Neither artwork nor a transient queue ID is a reason to speak
again.

The completed announcement guard is deliberately narrower than the pending
enrichment matcher. It must avoid treating two different tracks with similar
metadata as the same completed announcement.

## Duplicate-suppression contract

One accepted playback occurrence gets at most one automatic announcement.
This includes repeated metadata callbacks, pause/resume churn, queue refreshes,
and late optional metadata.

The suppression history is not a historical blacklist. A real sequence
`A → B → A` is a new occurrence and can announce A again. A repeat-one cycle
follows the repeat setting; it is not inferred from an ordinary seek. The last
accepted logical track is persisted before session reconnect so Android process
recreation does not make a continuously playing song look new.

Pending announcement text/event data is replaceable only when the incoming
event matches the same logical current track. A newer confirmed track cancels
old pending work; no late speech for an obsolete prediction is allowed.

At a hard playback boundary, a position reset paired with the last accepted
track's stale metadata is not immediately a new occurrence. The boundary
identity guard defers that ambiguous frame for one bounded metadata
confirmation; a confirmed replacement track proceeds normally, while a stable
same-track restart remains eligible. This guard validates identity only: it
does not pause, duck, seek, or otherwise control playback.

## Owned-pause restore lifecycle

Playback restore is an ownership state machine, not a general attempt to make
the selected player play again. An obligation exists only after
`MediaSessionMonitor.pauseSelectedIfPlaying` successfully issues a TrackTalk
pause for the expected playing session and logical track. A user-created
pause, a provider pause, or focus-only ducking creates no obligation.

The pause token carries the accepted session/track identity. The controller
then reserves one restore-cycle ID and binds it to the session generation and
the speech generation. Only one cycle can be active. Every completion,
callback, retry, or watchdog action must match that cycle; stale cycles and
callbacks are ignored rather than applied to whichever player is now selected.

Replayed states with the same framework update timestamp remain harmless
controller churn. Once TrackTalk's own pause is acknowledged, an observable
newer `PLAYING`, `PAUSED`, or `STOPPED` state disqualifies the pending automatic
restore rather than being overridden by a stale `PLAY` request.

Metadata and queue callbacks may be mixed or incomplete while a provider
refreshes its controller. `PlaybackRestoreTrackMatcher` accepts only a
compatible logical track; a real track/source mismatch cancels the cycle.
Controller recreation is infrastructure churn, not permission to resume a new
player: the monitor may retain the exact controller that accepted TrackTalk's
pause, or wait a bounded time for the same logical session/track to become
identifiable again.

When a matching cycle reaches its TTS outcome, restoration asks the monitor to
issue the first `PLAY` synchronously. The monitor allows at most one bounded
retry, only after the same logical track is recovered or a missing callback is
handled. It does not poll position or play an identity-incomplete session.
A matching `PLAYING` callback/confirmation completes restoration. A real track
change, a newer user pause after playback resumes, an identity mismatch, or an
expired recovery window cancels/fails the cycle rather than overriding user
intent.

The controller also arms a bounded speech-completion watchdog. If Android TTS
never returns a completion callback, it releases focus and routes the same
cycle through the guarded restore path; the watchdog is not a second playback
policy. See [Audio and TTS](audio-tts.md) for the TTS outcomes that feed this
lifecycle.

## Playback context is evidence, not source intent

`PlaybackCollectionResolver` and `TemporalPlaybackContextResolver` retain
conservative internal evidence such as explicit queue titles, complete
canonical album queue metadata, multi-album queues, and natural transitions.
They are useful for diagnostics and may label internal `ALBUM`, `PLAYLIST`,
`ALGORITHMIC`, or `UNKNOWN` states.

They do **not** prove that the user pressed an album, playlist, recommendation,
or direct-song button. Runtime reading configuration is global regardless of
that evidence. Album title, total track count, queue length, and queue position
alone must never be promoted to source intent.

## Factual track numbers

`AlbumTrackNumberResolver` accepts a number only when its provenance is
reliable:

1. direct player MediaMetadata;
2. queue-item metadata or a stable cached queue-item value; or
3. a high-confidence external catalog match.

Queue index and “the Nth song heard” are expressly forbidden. A shuffled or
reordered queue does not invalidate a genuine explicit player number, but it
also never creates a number from its position.

### External metadata capability

`ExternalTrackMetadataResolver` is provider independent. The current iTunes
Search adapter sends title, artist, album, and optional duration to find a
canonical track number. It uses normalized exact/edition-neutral comparisons,
minimum confidence `0.86`, and a minimum winner margin `0.08`. Artist/album
evidence and duration proximity improve matching; ambiguous, missing,
rate-limited, malformed, or timed-out results produce no number.

Cache identity uses normalized title/artist/album. Successful matches are kept
for 30 days; ambiguous/not-found results are negatively cached for 6 hours;
transient failures for 5 minutes. A current-utterance lookup is bounded and
never causes a second announcement after it returns.

This is intentionally a hidden beta capability: Track Number is filtered from
the current beta UI and runtime field selection. Do not represent external
lookup as a shipped user-visible guarantee until end-to-end provider validation
and product approval explicitly change that boundary.

## Next-track preparation

When the queue exposes an unambiguous next item, `NextTrackPrefetch` may retain
its identity and available metadata. The controller can pre-format candidate
text, resolve a voice plan, and start a metadata-only external lookup while the
current track plays.

Preparation is never playback authority:

- It is not a current `PlaybackEvent`.
- It may not speak, pause, duck, request focus, or change output.
- It is invalidated by session/package/queue/next-item changes or a mismatched
  actual transition.
- Actual current media metadata remains authoritative and may correct the
  prepared data.

This removes safe TrackTalk-side work after confirmation without pretending to
know an exact media boundary.

## Timing boundary

The app uses a confirmed new current event before speech or music intervention.
`AnnouncementAudioTiming` may start audio preparation before a deliberate
metadata-settlement wait, but immediate reading protects audio immediately.

Duration/position data may support diagnostics and one-shot preparation only.
A Samsung duration-precision audit found fine-grained repeatable duration
values, yet transition prediction still had material error; duration precision
is therefore not a trustworthy authorization to pause or hold media early.
No high-frequency playback-position polling is permitted.

See [Audio and TTS](audio-tts.md) for focus and TTS completion, and
[Rejected experiments](experiments/rejected-approaches.md) for boundary
techniques that must not be reintroduced casually.
