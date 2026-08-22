# Device routing and automation

TrackTalk distinguishes **connected devices** from the **route currently used
by media**. A Bluetooth headset can be connected while media is still playing
through the phone speaker; device inventory alone is not permission to speak
on an external route.

## Output policy

The durable output policy is one of:

- `ALL_OUTPUTS` — announcements may use any selected route.
- `EXTERNAL_ONLY` — announcements require an external media route.

New settings default to `EXTERNAL_ONLY`. Built-in speaker and earpiece are
never external. The policy is evaluated by announcement eligibility; it does
not alter media detection, TrackTalk's global enabled state, or the user's
media volume.

## Route resolution

`AudioOutputDetector` builds media `AudioAttributes` and asks Android for the
devices used for those attributes. On supported Android versions, that result
is authoritative. When unavailable, legacy Bluetooth/wired state and output
types are a fallback.

The resolver returns `EXTERNAL`, `SPEAKER`, `TRANSITIONING`, or `UNKNOWN`:

- **EXTERNAL** only when an external route is actually supported by evidence.
- **SPEAKER** for a built-in speaker/earpiece route without corroborated
  external activity.
- **TRANSITIONING** for Samsung's short-lived stale built-in-route report that
  conflicts with active Bluetooth evidence; the controller performs one
  bounded recheck rather than speaking on a speculative route.
- **UNKNOWN** when Android does not provide enough trustworthy evidence.

Do not treat merely seeing a Bluetooth device in `getDevices()` as proof that
media is routed there. Do not turn one vendor workaround into an Android-wide
claim without device evidence.

## Logical device model

`AudioDeviceMonitor` observes supported output types and maps them into
logical device categories: wired, USB, Bluetooth, Bluetooth LE, hearing aid,
HDMI, line audio, or other. `LogicalAudioDeviceNormalizer` combines different
Bluetooth profiles for the same physical device when Android exposes a stable
identity.

A Bluetooth identity is hashed locally before it becomes a persistence key.
When Android withholds a safe identity, TrackTalk uses a connection-session
fallback and deliberately does not merge same-name devices. Documentation and
logs must never expose raw Bluetooth addresses.

## Automation

Device-specific auto-enable and screen-off activation are Plus automation
controls. The pure `DeviceAutomationPolicy` keeps repeated route callbacks
idempotent: it activates only when Plus is active and at least one persisted
logical device setting is both enabled and auto-enabled.

Automation does not bypass:

- global TrackTalk enablement;
- Notification Listener access;
- output-policy eligibility;
- safe teardown on screen-on/device disconnect;
- the user's direct changes to state.

## Status-bar shortcut

The persistent shortcut notification is created by
`TrackVoiceNotificationListenerService` when the listener is connected and
`showStatusNotification` is enabled. It uses a low-importance notification
channel and opens TrackTalk; it is not a substitute for media detection.

On Android versions requiring `POST_NOTIFICATIONS`, permission is optional.
Core MediaSession detection works without it. Home progressively reveals the
optional shortcut prompt only after required Notification Listener access is
granted; see [UI/UX](ui-ux.md).

## Validation focus

Test route logic with deterministic evidence matrices, and test real devices
with speaker, Bluetooth, and wired/USB routes where available. Verify both
that external-only suppresses a genuine built-in route and that it does not
silence a corroborated Bluetooth media route during a transient platform
report. See [Testing](testing.md).
