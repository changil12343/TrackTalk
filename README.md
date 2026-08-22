# TrackTalk

TrackTalk is a native Android utility that observes active Android media
sessions and reads selected track information aloud. It works alongside media
apps; it does not stream or own music playback.

## Documentation

The maintained project documentation lives in [docs/README.md](docs/README.md).
It separates stable product decisions from active implementation and temporary
validation work:

- [Architecture](docs/architecture.md)
- [Product decisions](docs/product-decisions.md)
- [Playback semantics](docs/playback-semantics.md)
- [Audio and TTS](docs/audio-tts.md)
- [Device routing and automation](docs/device-routing-automation.md)
- [UI/UX](docs/ui-ux.md)
- [Testing](docs/testing.md)
- [Beta release](docs/beta-release.md)

For contributor/agent guardrails, start with [AGENTS.md](AGENTS.md).

## Local build

TrackTalk uses Android SDK Platform 36, minSdk 26, Java/Kotlin source target
17, Kotlin, Jetpack Compose Material 3, and DataStore. With a configured local
Android SDK, build a debug APK with:

```text
gradlew.bat :app:assembleDebug --no-configuration-cache
```

Run the normal JVM baseline with:

```text
gradlew.bat :app:testDebugUnitTest --no-configuration-cache
```

See [docs/testing.md](docs/testing.md) for release, lint, instrumentation, and
real-device validation procedures.

## Privacy boundary

TrackTalk relies on Notification Listener access to observe active
`MediaSession` data. Notification access is required for music detection;
notification permission for the optional status-bar shortcut is separate.
TrackTalk does not parse general notifications or upload playback history.

Temporary local validation outputs belong in ignored `artifacts/` directories;
they are not project documentation or release assets.
