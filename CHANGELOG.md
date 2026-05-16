# Changelog

All notable changes to this package will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/synheart-ai/synheart-session-kotlin/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/synheart-ai/synheart-session-kotlin/releases/tag/v0.2.0
