# Synheart Session Kotlin (Android)

[![Android API 21+](https://img.shields.io/badge/API-21+-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-purple.svg)](https://kotlinlang.org)
[![CI](https://github.com/synheart-ai/synheart-session-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/synheart-ai/synheart-session-kotlin/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/ai.synheart/synheart-session?label=Maven%20Central)](https://central.sonatype.com/artifact/ai.synheart/synheart-session)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

> **Source-available.** This repository is open for reading, auditing, and
> filing issues. We do **not** accept pull requests — see
> [CONTRIBUTING.md](CONTRIBUTING.md) for the rationale and how to contribute
> via issues. Security reports go through [SECURITY.md](SECURITY.md).

Standalone Kotlin SDK for Synheart Session — real-time session capture with on-device HR metrics and behavioral signal fusion.

## Features

- Pluggable `BiosignalProvider` for HR sources (mock, BLE HRM, Health Connect)
- Optional `BehaviorProvider` for behavioral signal fusion (typing, scrolling, taps, app switches)
- HRV metrics (SDNN, RMSSD, pNN50) from the runtime; mean HR computed locally
- Thread-safe sliding window buffer with configurable window size
- Built-in mock provider for local development and testing (no wearable required)
- Typed session events: `session_started`, `session_frame`, `biosignal_frame`, `session_summary`, `session_error`
- Configurable compute profile (window size, emit interval)
- Sealed error classes with 5 error types
- Raw biosignal streaming via `includeRawSamples` opt-in

## Installation

### Maven Central

```groovy
dependencies {
    implementation 'ai.synheart:synheart-session:0.2.1'
}
```

### Local Module

Clone and add as a local module:

```groovy
// settings.gradle
include ':synheart-session'
project(':synheart-session').projectDir = new File('../synheart-session-kotlin')

// build.gradle
dependencies {
    implementation project(':synheart-session')
}
```

## Quick Start

### Default (mock provider — no wearable needed)

```kotlin
import ai.synheart.session.*

val config = SessionConfig(
    sessionId = UUID.randomUUID().toString(),
    mode = SessionMode.FOCUS,
    durationSec = 300,
    profile = ComputeProfile(windowSec = 60, emitIntervalSec = 5)
)

val engine = SessionEngine()
engine.start(config) { event ->
    when (event["type"] as? String) {
        "session_started" -> Log.d("Session", "Started")
        "session_frame" -> {
            val metrics = event["metrics"] as? Map<*, *>
            Log.d("Session", "Metrics: $metrics")
        }
        "session_summary" -> Log.d("Session", "Complete")
        "session_error" -> Log.e("Session", "Error: ${event["message"]}")
    }
}

// Stop early (optional)
engine.stop(config.sessionId)

// Query status
val status = engine.getStatus()
```

## SDK Usage

### Real BLE heart rate data

```kotlin
import ai.synheart.session.*

// Wrap any BLE HRM client as a BiosignalProvider (reflection-based, no compile dep needed)
val provider = WearBiosignalProvider(bleHrmClient)
val engine = SessionEngine(provider)

engine.start(config) { event ->
    // Same event handling — metrics are now computed from real HR data
}
```

### Health Connect heart rate data

```kotlin
import ai.synheart.session.*

// Wrap any Health Connect adapter as a BiosignalProvider (reflection-based)
val provider = HealthConnectBiosignalProvider(healthConnectAdapter, context)

if (provider.isAvailable) {
    val engine = SessionEngine(provider)

    engine.start(config) { event ->
        // Metrics are computed from Health Connect HR data
        // (Wear OS watches, Samsung Health, Fitbit, etc.)
    }
}
```

### Behavioral signals

```kotlin
import ai.synheart.session.*

// Wrap any behavior SDK object as a BehaviorProvider (reflection-based, no compile dep needed)
val behaviorProvider = BehaviorSdkProvider(behaviorSdkInstance)

val engine = SessionEngine(behaviorProvider = behaviorProvider)

engine.start(config) { event ->
    // session_frame events now include a "behavior" key
    val behavior = event["behavior"] as? Map<*, *>
    Log.d("Session", "Stability: ${behavior?.get("stability_index")}")
}
```

### Custom provider

Any class implementing `BiosignalProvider` can drive the session engine:

```kotlin
class MyProvider : BiosignalProvider {
    override val isAvailable = true
    override val name = "my_source"

    override fun startStreaming(onSample: (BiosignalSample) -> Unit) {
        // Push BiosignalSample values into the callback
    }

    override fun stopStreaming() { }
}

val engine = SessionEngine(MyProvider())
```

## Architecture

```
SessionEngine(provider: BiosignalProvider, behaviorProvider: BehaviorProvider?)
  │
  ├── BiosignalProvider                (interface)
  │     ├── MockBiosignalProvider       (sinusoidal mock, 1 Hz)
  │     ├── WearBiosignalProvider       (wraps BLE HRM provider, reflection-based)
  │     └── HealthConnectBiosignalProvider (wraps Health Connect adapter, reflection-based)
  │
  ├── BehaviorProvider                 (interface, pull-based)
  │     ├── MockBehaviorProvider        (stable mid-range values)
  │     └── BehaviorSdkProvider         (wraps behavior SDK, reflection-based)
  │
  ├── SampleRingBuffer           (thread-safe sliding window)
  │
  ├── computeMetrics()           (mean HR local + HRV from the runtime)         
  ├── Types                      (SessionConfig, SessionMode, ComputeProfile)
  └── SessionError               (sealed error class with 5 subclasses)
```

### Data Flow

```
BiosignalProvider.startStreaming()
  → BiosignalSample (timestampMs, bpm, rrIntervalsMs, deviceId, source)
    → SampleRingBuffer (windowed)
      → emitFrame() reads buffer → computeMetrics() → session_frame event
      → emitBiosignalFrame() reads buffer → biosignal_frame event (raw samples)
```

### BiosignalSample

| Field | Type | Description |
|-------|------|-------------|
| `timestampMs` | `Long` | Sample timestamp (ms since epoch) |
| `bpm` | `Double` | Heart rate in beats per minute |
| `rrIntervalsMs` | `List<Double>?` | RR intervals from BLE HRM or Health Connect |
| `deviceId` | `String?` | Source device identifier |
| `source` | `String` | `"mock"`, `"ble_hrm"`, or `"health_connect"` |

### Session Frame Output

Each `session_frame` event contains a flat `metrics` map with:

| Field | Description |
|-------|-------------|
| `hr_mean_bpm` | Mean heart rate (BPM) |
| `hr_sdnn_ms` | SDNN of RR intervals (ms) |
| `rmssd_ms` | RMSSD approximation (ms) |
| `sample_count` | Number of HR samples in window |
| `start_ms` | Window start timestamp (ms) |
| `end_ms` | Window end timestamp (ms) |
| `motion_rms_g` | RMS acceleration in g-force (optional, when accelerometer available) |
| `motion_sample_count` | Number of accelerometer samples in interval (optional) |

### Biosignal Frame Output

When `includeRawSamples = true` is set in `SessionConfig`, `biosignal_frame` events are emitted containing the raw samples in the current buffer window. Each sample includes `timestamp_ms`, `bpm`, `source`, and optionally `rr_intervals_ms` and `device_id`.

### Error Types

```kotlin
SessionError.PermissionDenied("...")   // HR permission not granted
SessionError.SensorUnavailable("...")  // No HR sensor available
SessionError.InvalidState("...")       // Duplicate session, etc.
SessionError.LowBattery("...")         // Device battery too low
SessionError.OsTerminated("...")       // Session killed by OS
```

If the provider fails to start (e.g., BLE HRM not connected), the engine emits a `session_error` event with `error_code: "sensor_unavailable"` instead of throwing.

## Privacy & Security

- **Session-Based Only**: No passive or background HR tracking
- **On-Device Processing**: All metrics computation happens locally on the device
- **No Raw HR Transmission**: Raw heart rate samples stay on device unless explicitly enabled via `includeRawSamples`
- **No Network Calls**: The SDK makes zero network calls — you control what gets persisted or transmitted
- **No Data Retention**: Raw biometric data is not retained after processing
- **Not a Medical Device**: This library is for wellness and research purposes only

## Standalone vs Core SDK

**With Synheart Core SDK:** HRV metrics (SDNN, RMSSD, pNN50) are automatically piped from the runtime via `ingestHsiMetrics()`. No action needed — the core SDK wires this up during session lifecycle.

**Standalone (without core SDK):** Your app must call `engine.ingestHsiMetrics(metrics)` with pre-computed HRV values. If not called, HRV metrics default to `0.0` — mean HR is still computed locally from the sample buffer.

```kotlin
// Standalone usage: manually provide HRV
engine.ingestHsiMetrics(mapOf(
    "hrv.sdnn_ms" to 42.5,
    "hrv.rmssd_ms" to 38.1,
    "hrv.pnn50" to 21.3
))
```

## Backward Compatibility

`SessionEngine()` with no arguments uses `MockBiosignalProvider` by default. All existing code continues to work without changes.

## Testing

Use **JDK 17** (recommended) or **JDK 21** to run Gradle/AGP. Very new JDKs (e.g. 25+) can break Kotlin/Android tooling.

```bash
# Run tests
./gradlew test

# Build
./gradlew build

# Build release AAR
./gradlew assembleRelease

# Publish to Maven Local
./gradlew publishToMavenLocal
```

## Watch Companion App

The Session SDK is designed to work with a Wear OS companion app that unlocks real-time biometric streaming. Due to Health Services API limitations, real-time HR/HRV data requires an active exercise session on the watch — the Session SDK handles this lifecycle automatically.

- [synheart-edge-watch-android](https://github.com/synheart-ai/synheart-edge-watch-android) — Wear OS companion app (Health Services exercise mode, MessageClient/DataClient relay)

When a session starts on the phone, the companion app starts an exercise session on the watch, enabling continuous HR, HRV, and accelerometer streaming back to the phone SDK.

## Related

| Package | Platform | Description |
|---------|----------|-------------|
| [synheart-session-flutter](https://github.com/synheart-ai/synheart-session-flutter) | Flutter | Flutter plugin (wraps this SDK via platform channels) |
| [synheart-session-swift](https://github.com/synheart-ai/synheart-session-swift) | iOS | Swift SDK |
| [synheart-wear-kotlin](https://github.com/synheart-ai/synheart-wear-kotlin) | Android | Wearable SDK (BLE HRM, Health Connect, WHOOP, Garmin) |

## Documentation

Full reference docs live at **[docs.synheart.ai/synheart-session/kotlin](https://docs.synheart.ai/synheart-session/kotlin)** — provider catalog, lifecycle, watch protocol, error reference, and the cross-platform overview.

## Links

- **Source of Truth**: [synheart-session](https://github.com/synheart-ai/synheart-session) — RFCs, protocol definitions, and cross-platform examples

## License

Apache 2.0 — see [LICENSE](LICENSE) for details.
