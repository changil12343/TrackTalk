# Audio and TTS

TrackTalk's audio goal is one intelligible announcement at a sensible time
without corrupting the user's media state. Android/provider latency and human
perception remain real-device concerns, so deterministic code behavior and
listening acceptance are documented separately.

## Semantic audio route

TTS uses `AudioAttributes.USAGE_ASSISTANT` with `CONTENT_TYPE_SPEECH` through
`TrackTalkAudioAttributes`. TrackTalk is not an AccessibilityService and must
not use accessibility semantics, phone-call routing, Bluetooth SCO forcing, or
global-volume tricks merely to sound louder.

`AudioFocusManager` requests:

- `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` when the configured behavior is duck;
- `AUDIOFOCUS_GAIN_TRANSIENT` when TrackTalk owns an announce-then-play pause;
- no focus for Keep music.

Focus is abandoned when the active announcement cycle completes, fails, or is
cancelled. Focus request success is checked; it is not assumed. Playback
restore ownership is defined in [Playback semantics](playback-semantics.md).

## Music treatment planner

`AnnouncementPlaybackPlanner` is the only normal mapping from settings to
music behavior:

| User intent | Planner result |
| --- | --- |
| Play immediately + Keep | No focus, no attenuation. |
| Play immediately + Lower music | System duck/focus; no direct media-volume write. |
| Announce then play | Scoped MediaSession pause plus transient focus; restore lifecycle is owned by [Playback semantics](playback-semantics.md). |

Legacy saved `PAUSE` treatment is normalized away from immediate play. The
normal duck path never mutates `STREAM_MUSIC`; `LegacyMusicVolumeRecovery`
exists solely to recover a stale direct mutation written by old builds.
`DeviceVolumeManager` is a separate explicit device-volume feature and must not
be repurposed as ordinary ducking.

Fresh defaults are TTS volume 80% and music attenuation 50%. A saved user TTS
volume, including an older one, is never overwritten by migration because no
safe code can distinguish an old default from an intentional preference.

## TTS completion boundary

`TtsEngine` normalizes Android utterance outcomes into success (`onDone`),
error (`onError`), or interruption/cancellation when a request is replaced.
It reports that result to the controller and never decides independently to
send `PLAY`. The controller uses the outcome only for an already-owned restore
cycle; the authoritative token, stale-cycle, user-pause, track-change, retry,
and watchdog rules are in [Playback semantics](playback-semantics.md).

The production baseline is reactive **Fast Pause** after a genuinely confirmed
current track. It is intentionally not an early predicted pause, rewind, or
synthetic gap. See [Rejected experiments](experiments/rejected-approaches.md).

## TTS execution

`TtsEngine` is initialized on the main looper and owns Android
`TextToSpeech`. It applies semantic audio attributes, registers an utterance
listener, and uses replacement semantics so a new preview or announcement does
not stack on old speech.

The engine:

- segments mixed-language text before speech;
- resolves a compatible voice by language, explicit voice, and verified gender
  metadata only;
- caches configured voice/language/rate/pitch to avoid redundant calls;
- refreshes the catalog only after a failed selected voice;
- maps UI volume directly to TTS gain without multiplying it by duck level;
- finishes the controller cycle on success, error, or interruption.

Android `Voice` has no standardized gender property. The UI only shows gender
when engine-provided metadata or a verified mapping supports it; unknown is not
invented. Voice quality/latency/network labels describe engine metadata, not a
measured guarantee of timbre or latency.

## Warming without changing playback

`prepareVoicePlan` can resolve language/voice decisions while the current track
is playing. It reads cache/catalog state only; it must not call `setVoice`,
`setLanguage`, `speak`, media controls, focus, ducking, or pause. A prepared
text/voice plan is reusable only after a real next-track match and after normal
eligibility/policy checks still pass.

## Timing rules

Metadata settlement sometimes waits for title/artist enrichment. Audio
protection must not wait behind that when an immediate decision has already
been made: `AnnouncementAudioTiming` calculates a zero preparation delay for
zero decision delay. Delayed decisions retain a bounded preparation lead.

Do not solve occasional audible media leakage by adding arbitrary delay,
globally muting media, clipping the previous song, pausing based only on
duration, or performing a rewind. A few milliseconds that occur before
MediaSession confirmation can be provider/platform limited; only a real-device
human listening result can judge perceptual improvement.

## Required acceptance checks

Automated checks must cover focus request/abandon, audio attributes, exact TTS
gain mapping, and success/error/cancellation outcome propagation. Restore
ownership, pause-token matching, and stale-event rejection are covered by
[Playback semantics](playback-semantics.md). Real-device listening still must
compare no music, Keep, duck, and announce-then-play cases at the selected
voice/music levels.

For route decisions see [Device routing and automation](device-routing-automation.md);
for full commands and evidence classification see [Testing](testing.md).
