# TrackTalk documentation

This directory is the canonical, durable documentation for TrackTalk. It
describes the current product and architecture; it is not a chronological work
log.

## Reading order

1. [Architecture](architecture.md) — component boundaries and data flow.
2. [Product decisions](product-decisions.md) — durable user-facing policy.
3. [Playback semantics](playback-semantics.md) — the most safety-critical
   rules for MediaSession metadata, identity, duplicate suppression, owned
   pause/restore, and prefetch.
4. [Audio and TTS](audio-tts.md) — audio focus, speech, TTS completion, and
   timing boundaries.
5. [Device routing and automation](device-routing-automation.md) — route
   resolution, logical devices, and Plus automation.
6. [UI/UX](ui-ux.md) — navigation, localization, defaults, and permissions.
7. [Testing](testing.md) and [Beta release](beta-release.md) — validation and
   release discipline.

## Ownership map

| Question | Canonical document |
| --- | --- |
| Which component owns a responsibility? | [Architecture](architecture.md) |
| What should the product claim or expose? | [Product decisions](product-decisions.md) |
| When is an event a new song? | [Playback semantics](playback-semantics.md) |
| When may TrackTalk pause or restore music? | [Playback semantics](playback-semantics.md) |
| How does TrackTalk request focus, duck, and speak? | [Audio and TTS](audio-tts.md) |
| How are headphones and Bluetooth devices interpreted? | [Device routing and automation](device-routing-automation.md) |
| What should a new user see? | [UI/UX](ui-ux.md) |
| Which tests prove a change? | [Testing](testing.md) |
| Which approaches are intentionally not production behavior? | [Rejected experiments](experiments/rejected-approaches.md) |

## Evidence hierarchy

When documentation conflicts with implementation, use this order:

1. Current production source.
2. Current deterministic tests.
3. Committed architecture/models.
4. Verified real-device observations, labeled as such.
5. Historical handoffs, issue notes, or old screenshots.

`CODEX_HANDOFF.md` is intentionally outside this hierarchy. It is an
untracked, temporary note for the active working tree and must not become a
second product specification.

## Documentation rules

- Keep stable decisions here and link to the owner rather than duplicating
  them across files.
- Label provider/device observations with their validation scope; do not turn
  a Samsung or one-player observation into an Android guarantee.
- Never paste local paths, device serials, IP addresses, Bluetooth addresses,
  screenshots, raw logs, credentials, or payment secrets into these files.
- Put transient captures under ignored `artifacts/`, review them locally, and
  remove disposable files after validation.
- Read [AGENTS.md](../AGENTS.md) before making code or documentation changes.
