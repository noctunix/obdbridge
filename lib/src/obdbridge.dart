/// The policy-enforced facade this package expects every consumer to use
/// instead of [NativeObdApi] directly.
library;

import 'guard.dart';
import 'obdbridge_api.g.dart';

/// Thrown by [ObdBridge] when a plan carries one or more commands
/// [GuardedCommandFilter] rejects — native is never called in that case.
class ObdBridgeCommandRejectedException implements Exception {
  ObdBridgeCommandRejectedException(this.rejectedCommands, this.reasons);

  /// The normalized text of every command in the plan that was rejected, in
  /// the order they were found. May contain duplicates if the same rejected
  /// command appears more than once in the plan (e.g. in both [modules]'
  /// setup and a resync sequence).
  final List<String> rejectedCommands;

  /// [HardGuard]/the caller's own [CommandPolicy] rejection reason for each
  /// entry in [rejectedCommands], same order, same length.
  final List<String> reasons;

  @override
  String toString() =>
      'ObdBridgeCommandRejectedException: rejected commands '
      '${rejectedCommands.join(', ')}';
}

/// Wraps [NativeObdApi] with [policy]'s [CommandPolicy], via
/// [GuardedCommandFilter], so that every command a plan carries — [
/// ObdPlanMessage.init], [ObdPlanMessage.resync], each
/// [ObdModuleMessage.setup] and every [ObdStepMessage.command] — is checked
/// before [startLive]/[startRecording] ever reach native. A plan with any
/// rejected command never calls native at all; [ObdBridgeCommandRejectedException]
/// lists every one that was rejected, both by [policy] and by [HardGuard]
/// (which native would reject again regardless, as its own, independent
/// gate — this facade's check is an additional, earlier one, not a
/// replacement for it).
///
/// This is the only supported entry point for a consumer of this package —
/// [NativeObdApi] itself is not exported from `obdbridge.dart` so that
/// calling it directly, and bypassing [policy], is not even possible without
/// reaching into this package's own `src/` directory.
class ObdBridge {
  ObdBridge(this.policy, {NativeObdApi? nativeApi})
    : _native = nativeApi ?? NativeObdApi(),
      _filter = GuardedCommandFilter(policy);

  final CommandPolicy policy;
  final NativeObdApi _native;
  final GuardedCommandFilter _filter;

  Future<bool> isObdSupported() => _native.isObdSupported();

  Future<void> configure(ObdBridgeConfigMessage config) =>
      _native.configure(config);

  Future<ObdCapabilitiesMessage> capabilities() => _native.capabilities();

  Future<ObdPermissionStatusMessage> permissionStatus() =>
      _native.permissionStatus();

  Future<ObdPermissionStatusMessage> requestPermissions() =>
      _native.requestPermissions();

  Future<List<ObdAdapterMessage>> scanAdapters(int durationMs) =>
      _native.scanAdapters(durationMs);

  Future<ObdLinkStateMessage> connect(String handle) => _native.connect(handle);

  Future<void> disconnect() => _native.disconnect();

  Future<ObdLinkStateMessage> linkState() => _native.linkState();

  /// Checks every command [plan] carries against [policy] (via
  /// [GuardedCommandFilter]) before calling [NativeObdApi.startLive]; throws
  /// [ObdBridgeCommandRejectedException] without calling native at all if
  /// any is rejected.
  Future<bool> startLive(ObdPlanMessage plan) {
    _checkPlan(plan);
    return _native.startLive(plan);
  }

  Future<void> stopLive() => _native.stopLive();

  Future<ObdLiveStateMessage> liveState(List<String> signalIds) =>
      _native.liveState(signalIds);

  /// Checks every command [request.plan] carries against [policy], exactly
  /// like [startLive]; throws [ObdBridgeCommandRejectedException] without
  /// calling native at all if any is rejected.
  Future<bool> startRecording(ObdStartRecordingRequest request) {
    _checkPlan(request.plan);
    return _native.startRecording(request);
  }

  Future<void> stopRecording(String sessionId) =>
      _native.stopRecording(sessionId);

  Future<ObdRecordingStateMessage> recordingState() => _native.recordingState();

  Future<void> acknowledgeImported(String sessionId, int lastSequence) =>
      _native.acknowledgeImported(sessionId, lastSequence);

  void _checkPlan(ObdPlanMessage plan) {
    final rejectedCommands = <String>[];
    final reasons = <String>[];

    void checkAll(Iterable<String> commands) {
      for (final command in commands) {
        final result = _filter.check(command);
        if (result is GuardRejected) {
          rejectedCommands.add(command);
          reasons.add(result.reason);
        }
      }
    }

    checkAll(plan.init);
    checkAll(plan.resync);
    for (final module in plan.modules) {
      checkAll(module.setup);
    }
    checkAll(plan.steps.map((step) => step.command));

    if (rejectedCommands.isNotEmpty) {
      throw ObdBridgeCommandRejectedException(rejectedCommands, reasons);
    }
  }
}
