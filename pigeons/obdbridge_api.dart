// Pigeon input schema for the OBD/CX transport channel. This file is NOT
// shipped: it is consumed by the `pigeon` codegen tool (run via
// `dart run pigeon --input pigeons/obdbridge_api.dart`) to produce the
// Dart/Kotlin bindings. Edit this, then regenerate.
//
// Android only for now: the library has no iOS implementation yet, so no
// Swift output is configured here.
import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/src/obdbridge_api.g.dart',
    kotlinOut: 'android/src/main/kotlin/de/noctunix/obdbridge/ObdApi.g.kt',
    kotlinOptions: KotlinOptions(package: 'de.noctunix.obdbridge'),
    dartPackageName: 'obdbridge',
  ),
)
/// Native platform capability report for the OBD transport.
/// Reported, never assumed — the UI and any later recording orchestration
/// branch on this instead of `Platform.isIOS`.
class ObdCapabilitiesMessage {
  ObdCapabilitiesMessage({
    required this.liveSession,
    required this.foregroundRecording,
    required this.backgroundRecording,
    required this.resumeAfterSystemKill,
    required this.autoConnectOnPresence,
  });

  bool liveSession;
  bool foregroundRecording;
  ObdBackgroundRecordingCapabilityMessage backgroundRecording;
  bool resumeAfterSystemKill;
  bool autoConnectOnPresence;
}

enum ObdBackgroundRecordingCapabilityMessage { none, eventDriven, continuous }

/// Abstract permission status — never an Android-specific runtime
/// permission name.
enum ObdPermissionStatusMessage {
  granted,
  denied,
  permanentlyDenied,
  restricted,
  bluetoothOff,
  unsupported,
}

/// One scan hit. [handle] is opaque and platform-assigned — never a BLE MAC
/// address. [advertisedName]/[rssi] are display-only.
class ObdAdapterMessage {
  ObdAdapterMessage({required this.handle, this.advertisedName, this.rssi});

  String handle;
  String? advertisedName;
  int? rssi;
}

enum ObdLinkStateValueMessage {
  idle,
  scanning,
  connecting,
  connected,
  running,
  suspendedByPlatform,
  disconnected,
  failed,
}

enum ObdLinkFailureMessage {
  permissionDenied,
  bluetoothOff,
  notFound,
  pairingWindowClosed,
  serviceMissing,
  timeout,
  platformError,
}

/// Connection state snapshot: a frozen field set of `state`,
/// `adapterHandle`, `failure` and `writeLength`.
class ObdLinkStateMessage {
  ObdLinkStateMessage({
    required this.state,
    this.adapterHandle,
    this.failure,
    this.writeLength,
  });

  ObdLinkStateValueMessage state;
  String? adapterHandle;
  ObdLinkFailureMessage? failure;
  int? writeLength;
}

/// One module's adapter-only setup sequence, already fully compiled and
/// policy-checked by the caller — native runs these literal commands, it
/// does not derive them from [protocol]/[moduleId].
class ObdModuleMessage {
  ObdModuleMessage({
    required this.moduleId,
    required this.protocol,
    required this.setup,
  });

  String moduleId;

  /// 6 (11-bit CAN) or 7 (29-bit CAN) — carried for diagnostics only; native
  /// never branches on it, the already-compiled [setup] commands are what
  /// actually run.
  int protocol;
  List<String> setup;
}

/// One vehicle-read step: the already-policy-checked command text plus the
/// one responder header its answer must come from, and the module it runs
/// under.
class ObdStepMessage {
  ObdStepMessage({
    required this.signalId,
    required this.moduleId,
    required this.command,
    required this.responseHeader,
    required this.intervalMs,
  });

  String signalId;
  String moduleId;
  String command;
  String responseHeader;

  /// Minimum time between two issuances of this step, milliseconds.
  int intervalMs;
}

class ObdLimitsMessage {
  ObdLimitsMessage({
    required this.maxResponseBytes,
    required this.adapterStepDeadlineMs,
    required this.vehicleReadDeadlineMs,
    required this.maxReadsPerSecond,
    required this.maxConsecutiveTimeouts,
    required this.idleVehicleStopAfterMs,
    required this.reconnectBackoffMinMs,
    required this.reconnectBackoffMaxMs,
    required this.maxReconnectAttemptsPerGap,
    required this.maxRecordingMs,
    required this.maxBufferBytes,
  });

  int maxResponseBytes;
  int adapterStepDeadlineMs;
  int vehicleReadDeadlineMs;
  int maxReadsPerSecond;

  /// Consecutive failed vehicle-read attempts (any non-`ok` transport status) before the
  /// connection is dropped and the gap recorded — the first failure already triggers the
  /// command session's own resync, this is the budget for a *second* one right after.
  int maxConsecutiveTimeouts;

  /// Stop polling and report the recording ended once every vehicle-read signal has answered
  /// `NO DATA` for this long — a parked vehicle whose bus has gone to sleep, not a transport
  /// failure.
  int idleVehicleStopAfterMs;
  int reconnectBackoffMinMs;
  int reconnectBackoffMaxMs;
  int maxReconnectAttemptsPerGap;

  /// Recording-only: total runtime after which the recording ends on its own.
  int maxRecordingMs;

  /// Recording-only: buffer file size after which the recording ends visibly rather than
  /// overwriting anything already written.
  int maxBufferBytes;
}

/// A fully compiled poll plan. Every command in [init], [resync], [modules]
/// and [steps] has already passed the caller's own `CommandPolicy` before
/// this message is built; native's `HardGuard` still checks each one again
/// immediately before writing it — two independent gates, not one.
class ObdPlanMessage {
  ObdPlanMessage({
    required this.planId,
    required this.planVersion,
    required this.formatVersion,
    required this.init,
    required this.resync,
    required this.modules,
    required this.steps,
    required this.limits,
  });

  String planId;
  int planVersion;
  int formatVersion;
  List<String> init;
  List<String> resync;
  List<ObdModuleMessage> modules;
  List<ObdStepMessage> steps;
  ObdLimitsMessage limits;
}

/// Outcome of one step's most recently attempted execution.
/// [notYetPolled] is this package's own addition to the transport-status
/// vocabulary a buffered recording's own stored outcome otherwise carries: a
/// live view asking for a signal's state before its first poll has run needs
/// a distinguishable "nothing happened yet", not a fabricated transport
/// failure.
enum ObdTransportStatusMessage {
  ok,
  timeout,
  overflow,
  disconnected,
  rejectedByGuard,
  writeFailed,
  cancelled,
  notYetPolled,
}

/// The most recent attempt at one signal's step. [rawResponseText] is the
/// adapter's literal response text (echo and trailing prompt already
/// stripped) — set only when [transport] is [ObdTransportStatusMessage.ok];
/// decoding it into a typed value is the caller's job, native never
/// interprets vehicle bytes.
class ObdSignalExchangeMessage {
  ObdSignalExchangeMessage({
    required this.signalId,
    required this.transport,
    this.rawResponseText,
    this.requestedAtEpochMs,
    this.receivedAtEpochMs,
    this.monotonicElapsedNs,
  });

  String signalId;
  ObdTransportStatusMessage transport;
  String? rawResponseText;
  int? requestedAtEpochMs;
  int? receivedAtEpochMs;

  /// Nanoseconds elapsed on the connection's own monotonic clock between
  /// `connect()` succeeding and this response's receipt — continuous and
  /// immune to wall-clock jumps, paired with [ObdLiveStateMessage.timebaseId].
  int? monotonicElapsedNs;
}

/// The bundled live-view query result: link status plus the latest attempt
/// for every signal the caller asked about, in one call — so the UI never
/// has to update once per BLE fragment. A signal absent from [exchanges]
/// was not part of the request;
/// one present with [ObdSignalExchangeMessage.transport] ==
/// [ObdTransportStatusMessage.notYetPolled] has never answered since the
/// current [timebaseId] began.
class ObdLiveStateMessage {
  ObdLiveStateMessage({
    required this.link,
    required this.timebaseId,
    required this.exchanges,
  });

  ObdLinkStateMessage link;

  /// Identifies the monotonic clock [ObdSignalExchangeMessage.monotonicElapsedNs]
  /// values are relative to. A fresh connect always gets a fresh id.
  String timebaseId;
  List<ObdSignalExchangeMessage> exchanges;
}

/// Display texts for the recording's persistent notification — Android
/// requires a foreground service to show one, there is no way to opt out.
/// Every word is supplied by the caller; this message only carries it
/// across.
class ObdRecordingNotificationMessage {
  ObdRecordingNotificationMessage({
    required this.channelName,
    required this.title,
    required this.body,
    required this.stopActionLabel,
  });

  String channelName;
  String title;
  String body;
  String stopActionLabel;
}

/// Caller-supplied, platform-specific values this library has no business
/// hardcoding: the activity a tap on the recording notification should
/// open, the small icon resource it should show, the `SharedPreferences`
/// file the restart marker is kept in, and the identifier string the buffer
/// header's `format` field carries. Call [NativeObdApi.configure] once,
/// before any other method — it also resolves any recording abandoned by a
/// previous, killed process, which needs [sharedPreferencesName] to look in
/// the right place.
class ObdBridgeConfigMessage {
  ObdBridgeConfigMessage({
    required this.activityClassName,
    required this.smallIconResourceName,
    required this.sharedPreferencesName,
    required this.bufferFormatId,
  });

  /// Fully qualified class name (`Class.forName`) of the `Activity` the
  /// recording notification's tap action opens.
  String activityClassName;

  /// Name of a `mipmap`/`drawable` resource in the host app, resolved at
  /// runtime (`Resources.getIdentifier`) rather than a resource id, which
  /// would be meaningless across the plugin/app boundary.
  String smallIconResourceName;

  /// `SharedPreferences` file name backing the recording restart marker.
  String sharedPreferencesName;

  /// The buffer header's `format` field. Callers that already shipped a
  /// different value (e.g. an app that moved its own recorder here) keep
  /// using theirs so previously recorded buffers still import correctly.
  String bufferFormatId;
}

/// Caller-chosen behaviour for one recording, independent of [ObdPlanMessage]
/// (which only ever carries vehicle-read content, never operational
/// policy).
class ObdRecordingOptionsMessage {
  ObdRecordingOptionsMessage({
    this.holdWakeLock = false,
    this.resumeAfterSystemKill = false,
  });

  /// Holds a `PARTIAL_WAKE_LOCK` for the recording's lifetime when `true`,
  /// released on every exit path (stop, idle stop, max duration, error,
  /// service destroy). Default `false`: a recording otherwise relies on the
  /// foreground service alone, which keeps the process alive but does not
  /// keep the CPU awake under Doze/screen-off.
  bool holdWakeLock;

  /// Reserved for a future actual resume-after-kill implementation; not yet
  /// honored. `CxRecordingService` has no self-restart today (see its own
  /// class doc) — a process kill always ends an abandoned recording as
  /// `interrupted` via the restart marker, regardless of this flag. Default
  /// `false` matches that existing, unconditional behaviour.
  bool resumeAfterSystemKill;
}

/// Starts a buffered recording. [sessionId] is a UUIDv7 minted by the
/// caller; [bufferFilePath] is likewise chosen by the caller (native only
/// appends to it). [plan] carries the same compiled, policy-checked plan
/// [NativeObdApi.startLive] takes — recording and live view run the
/// identical commands, just with the result written to a file instead of
/// only cached in memory.
class ObdStartRecordingRequest {
  ObdStartRecordingRequest({
    required this.sessionId,
    required this.vehicleId,
    required this.bufferFilePath,
    required this.startedAtEpochMs,
    required this.plan,
    required this.options,
    this.notification,
  });

  String sessionId;
  String vehicleId;
  String bufferFilePath;
  int startedAtEpochMs;
  ObdPlanMessage plan;
  ObdRecordingOptionsMessage options;

  /// `null` only where the platform would never show a notification at all —
  /// Android's foreground-service requirement means this is in practice
  /// always set there.
  ObdRecordingNotificationMessage? notification;
}

enum ObdRecordingStateValueMessage {
  idle,
  recording,
  suspendedByPlatform,
  stopped,
  interrupted,
}

/// Current recording state, polled by the caller rather than pushed —
/// mirrors [NativeObdApi.linkState]/[liveState]'s own "caller asks" shape
/// rather than adding a second, parallel event channel.
class ObdRecordingStateMessage {
  ObdRecordingStateMessage({
    this.sessionId,
    required this.state,
    required this.lastFlushedSequence,
    required this.bufferBytes,
    required this.gapCount,
    this.writeError,
    required this.resumedAfterRestart,
  });

  String? sessionId;
  ObdRecordingStateValueMessage state;
  int lastFlushedSequence;
  int bufferBytes;
  int gapCount;

  /// Set once a buffer write/flush has failed; the recording state is then
  /// [ObdRecordingStateValueMessage.interrupted].
  String? writeError;

  /// `true` only when a recording that was still pending after a process
  /// restart is actually continuing (polling resumed on the same, still
  /// pending session). Always `false` in this package: a restart here only
  /// ever recognizes an abandoned recording as `interrupted`, it never
  /// resumes one.
  bool resumedAfterRestart;
}

/// Host API implemented in Kotlin (Android). Every method is total: it
/// resolves a value, never throws across the channel. Android's
/// implementation is `ObdApi.kt`, backed by one shared instance owning a
/// `CxBleClient` and a `CxCommandSession` — not recreated per Flutter page,
/// so a live view and a later recording page share one connection rather
/// than opening a second GATT client.
@HostApi()
abstract class NativeObdApi {
  /// Whether the OBD transport exists on this platform/build at all.
  bool isObdSupported();

  /// Supplies the caller-specific values [ObdBridgeConfigMessage] carries.
  /// Call this once, before any other method on this API — see that
  /// message's own doc comment.
  void configure(ObdBridgeConfigMessage config);

  @async
  ObdCapabilitiesMessage capabilities();

  @async
  ObdPermissionStatusMessage permissionStatus();

  /// Requests the Bluetooth runtime permissions this platform needs, if not
  /// already granted, so the system dialog actually appears.
  @async
  ObdPermissionStatusMessage requestPermissions();

  /// Scans for up to [durationMs] and resolves every adapter discovered
  /// advertising the CX UART service; adapter selection stays with the
  /// caller — this never auto-connects.
  @async
  List<ObdAdapterMessage> scanAdapters(int durationMs);

  /// Connects to the deliberately chosen [handle]. Does not yet run any
  /// vehicle read — call [startLive] once connected.
  @async
  ObdLinkStateMessage connect(String handle);

  @async
  void disconnect();

  ObdLinkStateMessage linkState();

  /// Runs [plan]'s module setup once, then repeatedly issues every step at
  /// its own `intervalMs`, caching the latest attempt per signal for
  /// [liveState] to read. `false` if no connection is active or a plan is
  /// already running.
  @async
  bool startLive(ObdPlanMessage plan);

  /// Stops polling; the underlying BLE connection stays up — a live view
  /// closing does not disconnect the adapter out from under a later
  /// recording that might reuse the same connection.
  @async
  void stopLive();

  /// The bundled query backing the live view: current [ObdLinkStateMessage],
  /// the running plan's [ObdLiveStateMessage.timebaseId], and the latest
  /// attempt for each of [signalIds] — one call, no per-fragment updates.
  @async
  ObdLiveStateMessage liveState(List<String> signalIds);

  /// Starts a foreground-service-backed recording of [request.plan] into
  /// [request.bufferFilePath], reusing whatever connection is already
  /// active — `false` if none is, or a recording is already running.
  /// Takes over the existing connection without opening a second GATT
  /// client; it also takes over the one poller that connection ever runs,
  /// from a live session that was polling on it — [startLive] refuses for
  /// as long as this recording exists, there is never a second, concurrent
  /// poller on the same connection.
  @async
  bool startRecording(ObdStartRecordingRequest request);

  /// Stops the recording identified by [sessionId]; a mismatched or absent
  /// id is a no-op. The underlying connection stays up, exactly like
  /// [stopLive].
  @async
  void stopRecording(String sessionId);

  /// Current recording state — polled, not pushed, same shape as
  /// [linkState]/[liveState].
  ObdRecordingStateMessage recordingState();

  /// Tells native that [sessionId]'s buffer has been durably imported up to
  /// [lastSequence] and the file may be forgotten. Never deletes the buffer
  /// file itself — the caller owns that, and only after this call returns,
  /// so a channel failure here never races a deletion against an import
  /// that has not actually committed. A no-op for a [sessionId] this side
  /// no longer recognizes (already forgotten, or belongs to the currently
  /// running recording) — safe to call long after the fact, or more than
  /// once for the same session.
  @async
  void acknowledgeImported(String sessionId, int lastSequence);
}

/// Flutter API — live-display only: never authoritative, never the only way
/// a caller learns the current state (every state is also obtainable via
/// [NativeObdApi.linkState]/[NativeObdApi.liveState]).
@FlutterApi()
abstract class ObdEventsApi {
  void onLinkState(ObdLinkStateMessage state);
}
