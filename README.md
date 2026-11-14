# obdbridge

Shared transport layer for ELM327/STN compatible BLE UART adapters
(service `FFF0`, notify `FFF1`, write `FFF2`), developed and tested against
an OBDLink CX. It connects, runs a serialized command session, and executes
read-only OBD/UDS command plans for apps that each supply their own policy
of what a vehicle is asked.

## Read-only guarantee

`HardGuard` performs no normalization: a command is only ever accepted if
it is already canonical — non-empty, and composed only of `0`-`9`, `A`-`Z`
and `@`. Whitespace, CR/LF, a BOM, lowercase letters, or any other
character reject the command outright, before the allow-list below is
even consulted. This library's own allow-list is intentionally broader
than any specific app's policy; the caller-supplied `CommandPolicy`
remains the authoritative, narrower layer on top of it.

Every command passes the caller-supplied policy before it is written, and
the library additionally rejects on its own, regardless of that policy:

- any service other than mode 01 reads, UDS ReadDataByIdentifier (`0x22`),
  and an explicit, fixed allow-list of adapter `AT`/`ST` commands,
- the VIN DIDs `F190` and `F802`,
- the ECU serial number `F18C`,

where a ReadDataByIdentifier request may carry up to three distinct DIDs
(one CAN single frame) and is rejected if any of them is blocked.

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

On the Dart side this is enforced, not just documented: `ObdBridge` (in
`lib/src/obdbridge.dart`) is the only supported entry point into this
package. `NativeObdApi` itself is not exported from `package:obdbridge/obdbridge.dart`,
so a consumer cannot reach it, and therefore cannot bypass the policy it was
constructed with. `ObdBridge` runs every command a plan carries — `init`,
`resync`, each module's `setup` and every step's `command` — through
`GuardedCommandFilter` before `startLive`/`startRecording`; a plan with any
rejected command throws `ObdBridgeCommandRejectedException`, listing every
rejected command, without calling native at all. Native's own `HardGuard`
check immediately before writing stays in place regardless — this is an
earlier, additional gate, not a replacement for it.

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

## Pairing once, connecting without a scan, starting on presence

Android only. `ObdBridge.pairAdapter()` pairs the CX once through the
system's `CompanionDeviceManager` dialog (filtered on the `FFF0` service,
single device, no device profile; API 26 and up) and remembers it across
process restarts in the plugin's own private preferences. The address never
reaches Dart: `ObdCompanionStatusMessage.adapterHandle` is the same opaque
handle `connect` already takes, and it stays valid after a restart, so
`connect(handle)` reaches the adapter without a scan. The host's `Activity`
must be attached for the dialog; the plugin is `ActivityAware`.

Pairing again replaces the earlier pairing: its platform association and
observation are removed and the handle changes, but the stored recording
request stays. A first pairing changes nothing but the pairing itself.
`forgetAdapter()` removes the pairing, stops observing, aborts a presence
start that is still connecting and erases the request and the suppression
state; it keeps unacknowledged auto sessions (below), whose buffers are
still on disk. `companionStatus()` also drops a pairing the platform no
longer lists, for instance after preferences were restored from a backup
onto another phone.

`companionStatus()` also reports `connectGranted`: whether connecting to the
paired adapter is allowed (`BLUETOOTH_CONNECT` from API 31, nothing below).
`permissionStatus()` keeps describing scanning, so a host that only connects
to a paired adapter should look at `connectGranted` instead of its `denied`.

`companionStatus()` reports `supported` (pairing, API 26+) and
`presenceSupported` (API 31+) separately. Below API 31 pairing and
scan-free connecting still work; presence does not.

`setPresenceObservation(true)` asks the system to watch for the paired
adapter (API 36: by association id; API 31-35: by address). The plugin
declares an exported `CompanionDeviceService` that the system binds when the
adapter appears and reduces its callbacks to one event. All callback
generations are accepted; an event repeating the last processed state within
three seconds is dropped, so nothing is delivered twice. A pairing made below
API 33 learns its association id from the first event that carries the
address; the API 36 event carries only the id, so there the id is looked up
in the platform's association list by the stored address:

- `ObdBridgePresence.listener` (a Kotlin hook, set from
  `Application.onCreate`) is called with `true`/`false`, with or without a
  Flutter engine;
- `ObdPresenceEventsApi.onAdapterPresence` is sent if an engine is attached.
  It is a separate Flutter API so existing implementers of `ObdEventsApi`
  are unaffected; call `ObdPresenceEventsApi.setUp` to listen;
- if `setAutoRecording(request)` stored a request and nothing is recording
  or starting, `CxRecordingService` starts immediately and connects to the
  paired adapter itself. The system allows that foreground-service start
  from this callback only. The connect is retried inside the running
  service with the plan's `reconnectBackoffMinMs`/`reconnectBackoffMaxMs`
  and at most `maxReconnectAttemptsPerGap` attempts, never longer than
  three minutes in total, and not at all when Bluetooth is off or
  `BLUETOOTH_CONNECT` is missing (connecting to a known address needs
  nothing else; scanning keeps its own checks). The notification's stop
  action, `stopRecording` and `forgetAdapter` abort it, including in the
  short window before the service has started. Only an attempt this service
  started is cancelled: a connection the host opened meanwhile stays.
  `ObdRecordingNotificationMessage.connectingBody`, if set, replaces the
  notification text until the link is up.

The stored request is a template. At every start native replaces
`sessionId` with a fresh UUIDv7 and `startedAtEpochMs` with the current time,
and every `{sessionId}` in `bufferFilePath` with that id; the facade rejects
a path without the placeholder. The request passes the caller's
`CommandPolicy` when stored, exactly like `startRecording`, and is stored
together with the host app's version code: after an app update native
discards it, so a policy tightened in the update never runs an old plan
before the host stored the request again (call `setAutoRecording` at every
app start). The host's `ObdBridgeConfigMessage` is persisted on `configure`
so a start before any Dart code ran has its notification and buffer-format
values.

**The link a presence start opened is released when that recording ends**
(unless a live view asked for state in the last ten seconds), because a
connected CX does not advertise and the next trip would go unnoticed.
Releasing makes the adapter advertise again, which would start an empty
recording next to a parked car, so the end is remembered. An appearance is
then ignored while no disappearance was seen since that end and less than
`ObdBridgeConfigMessage.autoStartSuppressionMinutes` (default 30, clamped to
0..1440, `0` disables it) passed. A disappearance after the end lifts the
pause at once, provided an appearance was seen after the end first (the
platform may still report the trip's own disappearance after a short
recording ended, and that must not count): the car left or went to sleep, so
the next appearance is a new trip. Only an idle stop or a stop by the user (notification, Dart, or
during the connect phase) pauses, and only when the link was released and a
pairing exists; transport errors, exhausted reconnects, the duration and
buffer limits, a start that failed to connect and `forgetAdapter` do not.
Pairing again clears the pause.

**Draining auto sessions.** No Dart code runs when native starts a
recording, so the host finds them later: `pendingAutoSessions()` lists every
automatically started recording not yet acknowledged (session id, buffer
path, start, and end time and reason once ended; a start that failed shows
up ended with its reason and nothing on disk). Import the buffers, then call
`acknowledgeAutoSession(sessionId)` for each. The list is kept across
restarts and bounded to the newest 100.

"Disappeared" never stops anything. A connected CX stops advertising, so the
system reports it gone in the middle of every trip and unbinds the service a
few seconds later; the recording ends through its own idle and limit logic.

**Manifest.** The merged manifest of every host now contains the optional
`companion_device_setup` feature, the
`REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`,
`REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` and
`REQUEST_COMPANION_RUN_IN_BACKGROUND` permissions and the exported
`ObdCompanionService`. A host that does not use pairing can drop them (the
permissions need Play Console declarations):

```xml
<uses-feature android:name="android.software.companion_device_setup" tools:node="remove" />
<uses-permission android:name="android.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE" tools:node="remove" />
<uses-permission android:name="android.permission.REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND" tools:node="remove" />
<uses-permission android:name="android.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND" tools:node="remove" />
<service android:name="de.noctunix.obdbridge.ObdCompanionService" tools:node="remove" />
```

**Exclude the plugin's preferences from backup.** `obdbridge_companion.xml`
holds the adapter address, the vehicle id and the plan. The host must
exclude it (this plugin cannot, it does not own the app's backup rules):

```xml
<!-- res/xml/backup_rules.xml, android:fullBackupContent -->
<full-backup-content>
    <exclude domain="sharedpref" path="obdbridge_companion.xml" />
</full-backup-content>

<!-- res/xml/data_extraction_rules.xml, android:dataExtractionRules (API 31+) -->
<data-extraction-rules>
    <cloud-backup><exclude domain="sharedpref" path="obdbridge_companion.xml" /></cloud-backup>
    <device-transfer><exclude domain="sharedpref" path="obdbridge_companion.xml" /></device-transfer>
</data-extraction-rules>
```

Caveats. The consent dialog uses the fixed activity-result request code
`0x0B1D`; a host with another plugin using that code must change one of
them. The plugin keeps one `Activity` reference for the dialog in a
process-wide object: when the plugin instance of any Flutter engine detaches
from its activity the reference is cleared, even if another engine is still
attached, and stays cleared until some engine's activity attaches again (the
dialog then cannot start, and `pairAdapter` reports unpaired).
A recording that also records location from the background needs the host's
own background location permission; the exemption covers only the
connected-device service. Nothing here has been verified on a device.

## One-shot queries during a recording

`ObdBridge.enqueueOneShot(modules, steps)` appends plan-shaped, read-once
work to the recording that is currently running. Each step in `steps` is
read exactly once — its `intervalMs` is ignored — using the given or
already-planned module's setup from `modules`. A queued step runs in any
idle time and, while recurring steps keep the bus busy, after every
recurring read; queued steps on the module that read left active follow
it in FIFO order, so a recurring step is delayed by at most four one-shot
reads. Every command `modules`' setup and `steps` carry is checked against the caller's `CommandPolicy` and `HardGuard` first,
exactly like a plan's own commands; a rejected command throws
`ObdBridgeCommandRejectedException` without calling native at all.

Native keeps these steps in a bounded queue (4096 by default); enqueueing
beyond that rejects the whole batch with
`ObdOneShotEnqueueResultMessage.queueFull` — queuing none of it, never a
silent drop. The queue is cleared when the recording ends, and its current
depth is reported as `ObdRecordingStateMessage.oneShotQueueDepth` so a
caller can pace further enqueueing. Every accepted step gets exactly one
result either way: an executed result written to the recording's buffer
like any other exchange (with an added `oneShot: true` field) and also
delivered through `ObdEventsApi.onOneShotResult`, or, if it is still
queued when the recording stops for any reason, transport status
`cancelled` with no raw data — never silence.
`ObdEventsApi.onOneShotResult` is display-only, like every other event
this package pushes; a result is never only available that way.

```dart
final result = await bridge.enqueueOneShot(
  [], // the module this step addresses is already set up by the running plan
  [
    ObdStepMessage(
      signalId: 'oneShotBatteryTemp',
      moduleId: 'ecu1',
      command: '221105',
      responseHeader: '7E8',
      intervalMs: 0, // ignored for a one-shot step
    ),
  ],
);
```

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
      ref: v0.2.4
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
