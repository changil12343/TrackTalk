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
The monitor assigns a fresh callback generation whenever it attaches a
`MediaController`; the framework session key alone is not enough because a
late callback from a detached controller can share that key with its
replacement. Metadata, playback, queue, and destruction callbacks must match
the current generation before they can affect session state, TTS, or restore.

For a supported player, an eligible media transport notification with
MediaSession evidence may queue one coalesced **MediaSession reconciliation
hint** for the currently selected package. The notification is never track
metadata, identity, a playback occurrence, or speech authority: the monitor
reads a fresh controller snapshot and feeds the ordinary normalized update
path. A same-track snapshot is side-effect free; it creates no candidate,
focus cycle, transport command, or announcement. Unrelated/system
notifications and package-mismatched notifications do not reconcile.

`onSessionDestroyed` invalidates only the matching controller generation and
then performs one active-session reconciliation to select and synchronize a
replacement. A disappearing provider session is infrastructure churn, not a
reason to stop the NotificationListener or erase duplicate history. Failed
cross-process MediaSession reads, callback registration, and transport commands
are isolated at that boundary, logged, and reconciled in the same way.

## Track identity and occurrence

TrackTalk needs two related notions:

- **Logical track identity** decides whether partial metadata, a queue
  description, and canonical metadata are the same current item.
- **Playback occurrence** decides whether a logical track should be announced
  again.

A stable non-empty media ID is strongest. For short-lived ownership and
duration-preparation state, core identity is separated from display metadata:
it uses source plus media ID when available, otherwise normalized title and
artist. Reliable track/disc metadata and a coherent active queue item can
reject a real replacement. Album, artwork, and other display fields remain
factual announcement metadata, but an album-only correction while the core
identity is unchanged is enrichment, not a track change. This narrow rule does
not make album differences globally interchangeable when core identity is
missing or conflicting.

The completed announcement guard is deliberately narrower than the pending
enrichment matcher. It must avoid treating two different tracks with similar
metadata as the same completed announcement.

## Duplicate-suppression contract

One accepted playback occurrence gets at most one automatic announcement.
This includes repeated metadata callbacks, pause/resume churn, queue refreshes,
and late optional metadata.

An unrelated notification being posted or removed is not a playback
occurrence. Notification-listener reconnect, active-session reconciliation,
controller replacement, and controller-generation changes likewise preserve
the current logical-track baseline. Re-observing the same core track through
any of those infrastructure paths creates no announcement candidate, pause,
or TTS request. Controller generations remain callback-validity evidence only;
they are never track identity.

The optional media-notification reconciliation hint follows the same rule. A
normal metadata callback and a hint snapshot may race for the same new track;
the existing occurrence and duplicate gates remain the sole exactly-once
authority.

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

Same-track restart eligibility exists only after an explicit `STOPPED`
snapshot, a fresh `PLAYING` position near the start of the track, and is scoped
to that exact framework session and logical/core track. A later-position
same-track snapshot after `STOPPED` can be stale provider state while a direct
selection settles, so it is baseline-only rather than a new occurrence. An
empty active-session refresh, listener teardown, session replacement, or a
`STOPPED` snapshot from another media session cannot lend that eligibility to
the current player. Losing that exact session discards the boundary; it does
not turn a later same-track baseline into a new occurrence.

## Owned-pause restore lifecycle

The automatic-announcement planner supports Keep, semantic system duck (the
default), and user-selected Pause-and-restore. Keep and system duck issue no
transport commands and create no restore lease. A saved `MusicTreatment.PAUSE`
remains Pause after reload; only that selected mode enters the lifecycle below.

Playback restore is an ephemeral ownership lease, not a general attempt to make
the selected player play again. A lease exists only after
`MediaSessionMonitor.pauseSelectedIfPlaying` successfully issues a TrackTalk
pause for the expected playing session and logical track. A user-created
pause, an already paused/stopped player, a provider pause, or focus-only ducking
creates no lease.

`PlaybackRestoreLease` is memory-only and records the announcement, monitor,
logical-session, controller-callback, framework-session, and logical-track
identities that existed when TrackTalk issued `PAUSE`. It also records that the
pause command was actually issued by TrackTalk. The lease is never persisted;
process recreation begins with no playback authority even when duplicate
history and settings are restored.

The lease independently records `ttsCompleted` and `pauseAcknowledged`.
A matching current TTS completion/error/interruption (or an immediate
audio-focus failure in that same transaction) marks only the first condition;
an attributable post-command `PLAYBACK_STATE` callback carrying `PAUSED` marks
only the second. Metadata/queue callbacks that merely remap a PAUSED controller
snapshot cannot acknowledge the command. Their arrival order is irrelevant.
The lease can grant one restore only after both are true and the speech
generation, selected session, active source, controller generation, and core
track identity still match. A stale TTS callback, cancelled delayed
announcement, watchdog, controller/listener teardown, or lifecycle cleanup
invalidates the lease without sending `PLAY`.

Replayed states with the same framework update timestamp remain harmless
controller churn. Once TrackTalk's own pause is acknowledged, an observable
effective transition to `PLAYING`, or `STOPPED`/`NONE`, disqualifies the pending
automatic restore. A repeated `PAUSED` state cannot establish another pause
command: `MediaSession` exposes state, not pause-command provenance.

A post-acknowledgement metadata, queue, or reconciliation snapshot — and even
a repeated `PAUSED` playback-state callback — can expose a refreshed provider
timestamp without proving a new pause command. It does not cancel an owned
lease. If a source resumes during TTS, the observable `PLAYING` transition
cancels automatic restore before any later user pause can be overwritten. This
prevents provider callback churn from stranding a source that TrackTalk paused.

TrackTalk cancels automatic restoration when a newer playback intent can be
observed. Redundant pause commands issued while the source is already paused
may not generate an observable MediaSession state change. This is an
**unobservable idempotent command limitation**, not new playback authority:
without a newer observable event, the existing valid owned-pause lease may
still restore exactly once.

YouTube Music may deliver a real ordered `PLAYING → PAUSED` callback while
reusing an older `lastPositionUpdateTime`, and a newly selected track may have
no separate same-track `PLAYING_STATE` callback before TrackTalk pauses it.
Attribution therefore uses the exact monitor/controller/session/core-track
identities plus TrackTalk's monotonic callback sequence as command provenance.
The first exact `PAUSED` playback callback received locally after the pause
command watermark can acknowledge ownership while the lease remains valid.
Metadata/queue snapshots and callbacks at or before the command watermark
cannot. A framework state timestamp after the command or at least a known
same-track PLAYING callback baseline remains useful evidence; an explicit
timestamp regression behind that known baseline is rejected. A missing
baseline, missing source timestamp, or source timestamp merely older than the
local pause-command time does not by itself veto an otherwise authoritative
post-command callback.

Metadata and queue callbacks may be mixed or incomplete while a provider
refreshes its controller. `PlaybackRestoreTrackMatcher` classifies an
album-only correction on an unchanged core track as metadata enrichment and
keeps the exact lease. Raw queue item IDs are auxiliary occurrence evidence:
an isolated or metadata-incoherent queue-ID mismatch cannot outvote an exact
core-track match, while a coherent queue change combined with changed provider
identity can reject a replacement occurrence. A real title/artist, reliable
identity, coherent occurrence, or source mismatch cancels the lease.

`MediaSessionManager` may return a new `MediaController` wrapper for the exact
same framework token during active-session reconciliation. The monitor keeps
the already registered wrapper and callback generation in that case; no lease
handoff is needed because playback ownership never changed. A different token,
session destruction/removal, selected-session change, listener reconnect, or
monitor generation change still invalidates the lease even if a later
controller reports the same visible metadata. The monitor never retains or
rediscovers an invalidated old controller as playback authority.

When TTS finishes before the attributable `PAUSED` update, the lease remains
pending; a still-`PLAYING` snapshot is neither success nor failure and consumes
no authority. When `PAUSED` arrives first, the lease waits for TTS. Once both
conditions hold, the same active controller receives exactly one `PLAY`; there
is no automatic retry. A matching `PLAYING` callback/confirmation completes
restoration. If the provider has already returned to `PLAYING` after an owned
pause, TrackTalk sends no redundant command. A missing session, ambiguous
state, command failure, later pause, or identity mismatch cancels/fails the
lease rather than overriding user intent.

The playback quick-settings/UI toggle is a separate explicit user command. Its
user-requested `PLAY` path first cancels any automatic lease and is never used
by listener reconnect, process recovery, TTS cleanup, or announcement timers.

The controller also arms a bounded speech-completion watchdog. If Android TTS
never returns a completion callback, it releases focus and invalidates the
lease without sending `PLAY`. A late timer is cleanup, never playback authority.
If TTS is complete but an attributable pause acknowledgement never arrives, a
separate safety expiry discards the pending lease and likewise sends no
`PLAY`; it is not the normal synchronization mechanism.
See [Audio and TTS](audio-tts.md) for the TTS outcomes that feed this lifecycle.

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
When `MediaMetadata.METADATA_KEY_DURATION` is explicitly present and usable,
TrackTalk may estimate the current position from `PlaybackState.position`,
`lastPositionUpdateTime`, and playback speed, then enter a short pre-arm window
near the estimated end. The pre-arm is memory-only and may refresh existing
next-track metadata/text/voice preparation; it may not speak, request focus,
duck, pause, resume, or treat duration expiry as a transition.

Each duration timer is tied to monitor generation, controller callback
generation, selected session, and core current-track identity, so an
album-only display correction does not create another prediction. Track changes,
seek/position or speed changes, pause/stop, duration changes, replacement,
reconnect, and stale callbacks cancel it. Missing, zero/invalid, unusually
short, or live/indefinite durations stay entirely on the normal reactive
metadata path. A Samsung duration-precision audit found fine-grained
repeatable values, yet transition prediction still had material error; duration
precision is therefore not a trustworthy authorization to pause or hold media
early. No high-frequency playback-position polling is permitted.

See [Audio and TTS](audio-tts.md) for focus and TTS completion, and
[Rejected experiments](experiments/rejected-approaches.md) for boundary
techniques that must not be reintroduced casually.
