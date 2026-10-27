# obdbridge

Shared transport layer for ELM327/STN compatible BLE UART adapters
(service `FFF0`, notify `FFF1`, write `FFF2`), developed and tested against
an OBDLink CX. It connects, runs a serialized command session, and executes
read-only OBD/UDS command plans for apps that each supply their own policy
of what a vehicle is asked.

## Read-only guarantee

Every command passes the caller-supplied policy before it is written, and
the library additionally rejects on its own, regardless of that policy:

- any service other than mode 01 reads, UDS ReadDataByIdentifier (`0x22`),
  and an explicit, fixed allow-list of adapter `AT`/`ST` commands,
- the VIN DIDs `F190` and `F802`,
- the ECU serial number `F18C`.

No write, clear-DTC, routine, security-access, session-control or reset
service can ever be reached through this library. Callers are expected to
supply a stricter allow-list on top of this hard block, not a looser one.

The adapter allow-list is exact, not a bare `AT`/`ST` prefix check: STN-chip
`ST` commands can transmit arbitrary CAN frames (`STPX`) or rewrite
persistent adapter settings (`STSBR`), and ELM `ATPP` writes programmable
parameters, so a prefix match alone would defeat the guarantee above. Only
query/configuration commands with a bounded argument shape are allowed
(`ATZ`, `ATE0`/`1`, `ATSP0`–`ATSPC`, `ATSH`/`ATCRA`/`ATCF`/`ATCM`/`ATFCSH`
with a 3- or 8-hex-digit CAN ID, `STI`, `STDI`, and similar) — see
`lib/src/guard.dart` / `HardGuard.kt` for the full list.

This is implemented as two layers, each available on both Dart (`HardGuard`,
`CommandPolicy`, `GuardedCommandFilter` in `lib/src/guard.dart`) and Kotlin
(the same types in `android/src/main/kotlin/de/noctunix/obdbridge/HardGuard.kt`):
the caller's `CommandPolicy` decides what its app is allowed to ask a vehicle
for, and `HardGuard` then still rejects anything outside its own read-only
allow-list regardless of what the policy permits. A command only reaches the
adapter once both layers have allowed it. Both implementations are checked
against the same fixtures in `test/fixtures/guard_vectors.json`.

## Scope

- BLE connection (Android, via the Nordic Android BLE Library): scan, bond,
  GATT, MTU negotiation, teardown (`CxBleClient`).
- A serialized command session: framing to `>`, overflow handling, resync
  (`CxCommandSession`).
- The two-layer guard above (`CommandPolicy`, `HardGuard`).
- Plan execution at a cadence, live or buffered (`CxPoller`).
- A foreground recording service with reconnect/backoff
  (`CxRecordingService`); a crash-safe JSONL buffer for recorded sessions
  (`CxBufferWriter`), surviving a process kill via a restart marker
  (`CxRecordingRestartMarker`).
- An ELM/ISO-TP response parser (`ElmResponseParser`) and a generic,
  profile-free module-setup builder (`ObdProtocol`,
  `buildModuleSetupCommands`) — this package has no vehicle, profile or DID
  knowledge; a consumer supplies its own command plan and policy.
- The `NativeObdApi`/`ObdEventsApi` Pigeon contract (`pigeons/obdbridge_api.dart`).

Caller-specific values this package cannot hardcode (the activity a
recording notification's tap opens, its small icon, the `SharedPreferences`
file the restart marker uses, the buffer header's `format` identifier) are
supplied once via `NativeObdApi.configure` — see `ObdBridgeConfigMessage`'s
doc comment.

Each `ObdStartRecordingRequest` also carries an `ObdRecordingOptionsMessage`:

- `holdWakeLock` (default `false`): holds a `PARTIAL_WAKE_LOCK` for the
  recording's lifetime, released on every exit path (stop, idle stop, max
  duration, error, service destroy). The foreground service alone keeps the
  process alive but not necessarily the CPU awake under Doze/screen-off;
  this is the caller's opt-in to the stronger guarantee, at the usual
  wake-lock battery cost.
- `resumeAfterSystemKill` (default `false`): reserved for a future actual
  resume-after-process-kill feature, not yet honored —
  `CxRecordingService` never self-restarts today, and a process kill
  always ends an abandoned recording as `interrupted` via the restart
  marker regardless of this flag.

Android only; there is no iOS implementation yet.

## Third-party licences

The Nordic Android BLE Library (`no.nordicsemi.android:ble`) is
BSD-3-Clause; its licence text ships as
`android/src/main/assets/licenses/NORDIC-ANDROID-BLE-LICENSE.txt`.

## Status

Transport layer and recording service implemented and unit-tested
(JVM/Robolectric on the Kotlin side, `flutter test` on the Dart side); not
yet verified against a real adapter from this repo (see
`CxRecordingService`'s own class doc on what JVM tests cannot exercise).

## Depending on this package

Without a submodule, add a `git` dependency pinned to a tag:

```yaml
dependencies:
  obdbridge:
    git:
      url: https://github.com/noctunix/obdbridge.git
      ref: v0.0.1
```

For local development against a checked-out copy, add a gitignored
`pubspec_overrides.yaml` next to `pubspec.yaml`:

```yaml
dependency_overrides:
  obdbridge:
    path: ../obdbridge
```

## License

AGPL-3.0-or-later, see [LICENSE](LICENSE). Copyright Noctunix.
