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
  and adapter `AT`/`ST` commands,
- the VIN DIDs `F190` and `F802`,
- the ECU serial number `F18C`.

No write, clear-DTC, routine, security-access, session-control or reset
service can ever be reached through this library. Callers are expected to
supply a stricter allow-list on top of this hard block, not a looser one.

This is implemented as two layers, each available on both Dart (`HardGuard`,
`CommandPolicy`, `GuardedCommandFilter` in `lib/src/guard.dart`) and Kotlin
(the same types in `android/src/main/kotlin/de/noctunix/obdbridge/HardGuard.kt`):
the caller's `CommandPolicy` decides what its app is allowed to ask a vehicle
for, and `HardGuard` then still rejects anything outside its own read-only
allow-list regardless of what the policy permits. A command only reaches the
adapter once both layers have allowed it. Both implementations are checked
against the same fixtures in `test/fixtures/guard_vectors.json`.

## Planned scope

- BLE connection: scan, bond, GATT, MTU negotiation, teardown.
- A serialized command session: framing to `>`, overflow handling, resync.
- A policy interface for callers to restrict commands further.
- A foreground recording service with reconnect/backoff and wake lock.
- A crash-safe JSONL buffer for recorded sessions.
- An ELM/ISO-TP response parser.

## Status

Skeleton. No BLE code, no command execution yet.

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
