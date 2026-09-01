# Changelog

All notable changes to this package will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Companion-watch sessions: `WatchSessionRelay` and `WatchStatus`.** Drives a
  session on a paired Wear OS watch over the Wearable Data Layer — sends
  `start_session` / `stop_session` on `/synheart/session/command` and streams the
  watch's events back from `/synheart/session/event` as typed `SessionEvent`s.

  This logic already existed, but only inside `synheart-session-flutter`'s
  Android plugin, whose module declares no `maven-publish` — so it was compiled
  into a Flutter plugin AAR that nothing could depend on. A native Android host,
  and `synheart-core-kotlin`, had no way to run a watch session at all even
  though the implementation worked and the companion watch app was already
  listening on those paths. It belongs with the rest of the session SDK; the
  Flutter plugin should now consume it rather than carry its own copy, so the
  two cannot drift.

  The Data Layer behaviour is unchanged, including both message paths. The
  callback API became a `Flow<SessionEvent>` that completes on the watch's
  terminal event, and `getStatus`'s callback became a `suspend fun status()`.

  `play-services-wearable` is `compileOnly`: a phone-only host should not be made
  to ship Play Services, and `WatchStatus.supported` already reports false when
  it is absent.

## [0.2.1] - 2026-05-26

### Added
- `SynheartSession` facade + typed event stream (`SessionEvent`,
  `SessionErrorEvent`, `SessionStatus`, `SessionSummary`) — API parity
  with the Flutter SDK.

### CI
- All workflows opt into Node 24 (June 2026 deprecation prep).
- `close-external-prs` org-membership check repaired.

### Distribution
- Maven Central: `ai.synheart:synheart-session:0.2.1`

## [0.2.0] - 2026-05-15

Initial open-source release of the Synheart Session SDK for Android.

The SDK runs a handler-driven session engine that consumes a
`BiosignalProvider` (mock, BLE HRM, Health Connect, or your own
implementation) and an optional `BehaviorProvider`, then emits typed
session events: `session_started`, `biosignal_frame`, `session_frame`,
`session_summary`, `session_error`.

### Public surface
- `SessionEngine` with pluggable `BiosignalProvider` and optional
  `BehaviorProvider`. `MockBiosignalProvider` (1 Hz sinusoidal) and
  `MockBehaviorProvider` ship for local development.
- `WearBiosignalProvider` — bridges synheart-wear `BleHrmProvider` for
  real BLE HR streaming (reflection-based, no hard dependency).
- `HealthConnectBiosignalProvider` — wraps `HealthConnectAdapter`
  from synheart-wear via reflection (handler-based polling, 2 s).
- `BehaviorSdkProvider` — wraps `SynheartBehavior.getCurrentStats()`
  from synheart-behavior-kotlin via reflection.
- `SampleRingBuffer` — thread-safe sliding window buffer with
  configurable window.
- HR/HRV computation: mean HR is computed locally; SDNN/RMSSD/pNN50
  are ingested via `ingestHsiMetrics()` from the upstream runtime.
- Sealed `SessionError` class with 5 error subtypes.

### Distribution
- Maven Central: `ai.synheart:synheart-session:0.2.0`

[Unreleased]: https://github.com/synheart-ai/synheart-session-kotlin/compare/v0.2.1...HEAD
[0.2.1]: https://github.com/synheart-ai/synheart-session-kotlin/releases/tag/v0.2.1
[0.2.0]: https://github.com/synheart-ai/synheart-session-kotlin/releases/tag/v0.2.0
