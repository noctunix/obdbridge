import 'package:flutter_test/flutter_test.dart';
import 'package:obdbridge/obdbridge.dart';
// `NativeObdApi` is deliberately not re-exported from `obdbridge.dart` (see
// that file's own comment) — this test needs it anyway, to build a fake in
// place of the real native channel.
import 'package:obdbridge/src/obdbridge_api.g.dart' show NativeObdApi;

class _AllowAllPolicy implements CommandPolicy {
  @override
  bool allows(String canonicalCommand) => true;
}

class _DenyCommandsPolicy implements CommandPolicy {
  _DenyCommandsPolicy(this.denied);

  final Set<String> denied;

  @override
  bool allows(String canonicalCommand) => !denied.contains(canonicalCommand);
}

/// Records every plan/request handed to it instead of ever reaching a real
/// native channel — [ObdBridge] is given this in place of a real
/// [NativeObdApi] throughout this suite.
class _FakeNativeObdApi extends NativeObdApi {
  ObdPlanMessage? lastStartLivePlan;
  ObdStartRecordingRequest? lastStartRecordingRequest;
  List<ObdModuleMessage>? lastEnqueuedModules;
  List<ObdStepMessage>? lastEnqueuedSteps;
  String? lastAcknowledgedAutoSession;
  int setAutoRecordingCalls = 0;
  ObdStartRecordingRequest? lastAutoRecordingRequest;
  bool? lastPresenceObservation;

  @override
  Future<bool> startLive(ObdPlanMessage plan) async {
    lastStartLivePlan = plan;
    return true;
  }

  @override
  Future<bool> startRecording(ObdStartRecordingRequest request) async {
    lastStartRecordingRequest = request;
    return true;
  }

  @override
  Future<void> setAutoRecording(ObdStartRecordingRequest? request) async {
    setAutoRecordingCalls++;
    lastAutoRecordingRequest = request;
  }

  @override
  Future<ObdCompanionStatusMessage> setPresenceObservation(bool enabled) async {
    lastPresenceObservation = enabled;
    return _status(presenceObserved: enabled);
  }

  @override
  Future<List<ObdAutoSessionMessage>> pendingAutoSessions() async => [
    ObdAutoSessionMessage(
      sessionId: 's1',
      bufferFilePath: '/b/s1.jsonl',
      startedAtEpochMs: 1,
    ),
  ];

  @override
  Future<void> acknowledgeAutoSession(String sessionId) async {
    lastAcknowledgedAutoSession = sessionId;
  }

  @override
  Future<ObdCompanionStatusMessage> companionStatus() async => _status();

  @override
  Future<ObdOneShotEnqueueResultMessage> enqueueOneShot(
    List<ObdModuleMessage> modules,
    List<ObdStepMessage> steps,
  ) async {
    lastEnqueuedModules = modules;
    lastEnqueuedSteps = steps;
    return ObdOneShotEnqueueResultMessage.accepted;
  }
}

ObdCompanionStatusMessage _status({bool presenceObserved = false}) =>
    ObdCompanionStatusMessage(
      supported: true,
      presenceSupported: true,
      paired: true,
      adapterHandle: 'handle',
      presenceObserved: presenceObserved,
      autoRecordingArmed: false,
      connectGranted: true,
    );

ObdLimitsMessage _limits() => ObdLimitsMessage(
  maxResponseBytes: 4096,
  adapterStepDeadlineMs: 2000,
  vehicleReadDeadlineMs: 2000,
  maxReadsPerSecond: 10,
  maxConsecutiveTimeouts: 3,
  idleVehicleStopAfterMs: 60000,
  reconnectBackoffMinMs: 1000,
  reconnectBackoffMaxMs: 30000,
  maxReconnectAttemptsPerGap: 5,
  maxRecordingMs: 0,
  maxBufferBytes: 1000000,
);

ObdStepMessage _step(String command, {String signalId = 'signal'}) =>
    ObdStepMessage(
      signalId: signalId,
      moduleId: 'm1',
      command: command,
      responseHeader: '7E8',
      intervalMs: 1000,
    );

ObdPlanMessage _plan({
  List<String> init = const [],
  List<String> resync = const [],
  List<ObdModuleMessage> modules = const [],
  List<ObdStepMessage> steps = const [],
}) => ObdPlanMessage(
  planId: 'p',
  planVersion: 1,
  formatVersion: 1,
  init: init,
  resync: resync,
  modules: modules,
  steps: steps,
  limits: _limits(),
);

ObdRecordingOptionsMessage _options() => ObdRecordingOptionsMessage();

void main() {
  group('ObdBridge.startLive', () {
    test(
      'a policy-rejected command never reaches the fake native API',
      () async {
        final fake = _FakeNativeObdApi();
        final bridge = ObdBridge(
          _DenyCommandsPolicy({'0105'}),
          nativeApi: fake,
        );
        final plan = _plan(steps: [_step('0105')]);

        expect(
          () => bridge.startLive(plan),
          throwsA(isA<ObdBridgeCommandRejectedException>()),
        );
        expect(fake.lastStartLivePlan, isNull);
      },
    );

    test(
      'a hard-guard-rejected command is rejected even with an allow-all policy',
      () async {
        final fake = _FakeNativeObdApi();
        final bridge = ObdBridge(_AllowAllPolicy(), nativeApi: fake);
        // The VIN DID — HardGuard blocks this regardless of policy.
        final plan = _plan(steps: [_step('22F190')]);

        final exception = _expectRejection(() => bridge.startLive(plan));
        expect(exception.rejectedCommands, contains('22F190'));
        expect(fake.lastStartLivePlan, isNull);
      },
    );

    test('rejects a command hidden inside module setup', () async {
      final fake = _FakeNativeObdApi();
      final bridge = ObdBridge(_AllowAllPolicy(), nativeApi: fake);
      final plan = _plan(
        modules: [
          ObdModuleMessage(
            moduleId: 'm1',
            protocol: 6,
            // ATPP writes a programmable parameter — not in HardGuard's
            // adapter allow-list.
            setup: ['ATSP6', 'ATPP0C'],
          ),
        ],
      );

      final exception = _expectRejection(() => bridge.startLive(plan));
      expect(exception.rejectedCommands, contains('ATPP0C'));
      expect(fake.lastStartLivePlan, isNull);
    });

    test('rejects a command hidden inside the resync sequence', () async {
      final fake = _FakeNativeObdApi();
      final bridge = ObdBridge(_AllowAllPolicy(), nativeApi: fake);
      final plan = _plan(resync: ['ATZ', '2E0105']); // 2E = write data by id

      final exception = _expectRejection(() => bridge.startLive(plan));
      expect(exception.rejectedCommands, contains('2E0105'));
      expect(fake.lastStartLivePlan, isNull);
    });

    test('an allowed plan passes through unchanged', () async {
      final fake = _FakeNativeObdApi();
      final bridge = ObdBridge(_AllowAllPolicy(), nativeApi: fake);
      final plan = _plan(
        init: ['ATZ', 'ATE0'],
        resync: ['ATZ', 'ATE0'],
        modules: [
          ObdModuleMessage(
            moduleId: 'm1',
            protocol: 6,
            setup: ['ATSP6', 'ATSH710'],
          ),
        ],
        steps: [_step('0100')],
      );

      final result = await bridge.startLive(plan);

      expect(result, isTrue);
      expect(fake.lastStartLivePlan, same(plan));
    });
  });

  group('ObdBridge.startRecording', () {
    test(
      'a policy-rejected command never reaches the fake native API',
      () async {
        final fake = _FakeNativeObdApi();
        final bridge = ObdBridge(
          _DenyCommandsPolicy({'0105'}),
          nativeApi: fake,
        );
        final request = ObdStartRecordingRequest(
          sessionId: 's1',
          vehicleId: 'v1',
          bufferFilePath: '/tmp/does-not-matter.jsonl',
          startedAtEpochMs: 0,
          plan: _plan(steps: [_step('0105')]),
          options: _options(),
        );

        expect(
          () => bridge.startRecording(request),
          throwsA(isA<ObdBridgeCommandRejectedException>()),
        );
        expect(fake.lastStartRecordingRequest, isNull);
      },
    );

    test('an allowed request passes through unchanged', () async {
      final fake = _FakeNativeObdApi();
      final bridge = ObdBridge(_AllowAllPolicy(), nativeApi: fake);
      final request = ObdStartRecordingRequest(
        sessionId: 's1',
        vehicleId: 'v1',
        bufferFilePath: '/tmp/does-not-matter.jsonl',
        startedAtEpochMs: 0,
        plan: _plan(steps: [_step('0100')]),
        options: _options(),
      );

      final result = await bridge.startRecording(request);

      expect(result, isTrue);
      expect(fake.lastStartRecordingRequest, same(request));
    });
  });

  group('ObdBridge.setAutoRecording', () {
    ObdStartRecordingRequest request(
      ObdPlanMessage plan, {
      String path = '/tmp/trips/{sessionId}.jsonl',
    }) => ObdStartRecordingRequest(
      sessionId: 'template',
      vehicleId: 'v1',
      bufferFilePath: path,
      startedAtEpochMs: 0,
      plan: plan,
      options: _options(),
    );

    test('a policy-rejected command never reaches the fake native API', () {
      final fake = _FakeNativeObdApi();
      final bridge = ObdBridge(_DenyCommandsPolicy({'0105'}), nativeApi: fake);

      expect(
        () => bridge.setAutoRecording(request(_plan(steps: [_step('0105')]))),
        throwsA(isA<ObdBridgeCommandRejectedException>()),
      );
      expect(fake.setAutoRecordingCalls, 0);
    });

    test('a hard-guard-rejected command is rejected under allow-all', () {
      final fake = _FakeNativeObdApi();
      final bridge = ObdBridge(_AllowAllPolicy(), nativeApi: fake);

      final exception = _expectRejection(
        () => bridge.setAutoRecording(request(_plan(steps: [_step('22F190')]))),
      );
      expect(exception.rejectedCommands, contains('22F190'));
      expect(fake.setAutoRecordingCalls, 0);
    });

    test('a buffer path without the session placeholder is refused', () {
      final fake = _FakeNativeObdApi();
      final bridge = ObdBridge(_AllowAllPolicy(), nativeApi: fake);

      expect(
        () => bridge.setAutoRecording(
          request(_plan(steps: [_step('0100')]), path: '/tmp/fixed.jsonl'),
        ),
        throwsArgumentError,
      );
      expect(fake.setAutoRecordingCalls, 0);
    });

    test('an allowed request passes through unchanged', () async {
      final fake = _FakeNativeObdApi();
      final bridge = ObdBridge(_AllowAllPolicy(), nativeApi: fake);
      final allowed = request(_plan(steps: [_step('0100')]));

      await bridge.setAutoRecording(allowed);

      expect(fake.lastAutoRecordingRequest, same(allowed));
    });

    test('null clears without any policy check', () async {
      final fake = _FakeNativeObdApi();
      final bridge = ObdBridge(_DenyCommandsPolicy({'0100'}), nativeApi: fake);

      await bridge.setAutoRecording(null);

      expect(fake.setAutoRecordingCalls, 1);
      expect(fake.lastAutoRecordingRequest, isNull);
    });
  });

  group('ObdBridge companion calls', () {
    test('presence observation and status are forwarded', () async {
      final fake = _FakeNativeObdApi();
      final bridge = ObdBridge(_AllowAllPolicy(), nativeApi: fake);

      final status = await bridge.setPresenceObservation(true);

      expect(fake.lastPresenceObservation, isTrue);
      expect(status.presenceObserved, isTrue);
      expect((await bridge.companionStatus()).adapterHandle, 'handle');
    });

    test('auto sessions are listed and acknowledged through', () async {
      final fake = _FakeNativeObdApi();
      final bridge = ObdBridge(_AllowAllPolicy(), nativeApi: fake);

      expect((await bridge.pendingAutoSessions()).single.sessionId, 's1');
      await bridge.acknowledgeAutoSession('s1');

      expect(fake.lastAcknowledgedAutoSession, 's1');
    });
  });

  group('ObdBridge.enqueueOneShot', () {
    test(
      'a policy-rejected command in steps never reaches the fake native API',
      () async {
        final fake = _FakeNativeObdApi();
        final bridge = ObdBridge(
          _DenyCommandsPolicy({'0105'}),
          nativeApi: fake,
        );

        expect(
          () => bridge.enqueueOneShot(const [], [_step('0105')]),
          throwsA(isA<ObdBridgeCommandRejectedException>()),
        );
        expect(fake.lastEnqueuedSteps, isNull);
      },
    );

    test(
      'a hard-guard-rejected command is rejected even with an allow-all policy',
      () async {
        final fake = _FakeNativeObdApi();
        final bridge = ObdBridge(_AllowAllPolicy(), nativeApi: fake);

        final exception = _expectRejection(
          () => bridge.enqueueOneShot(const [], [_step('22F190')]),
        );
        expect(exception.rejectedCommands, contains('22F190'));
        expect(fake.lastEnqueuedSteps, isNull);
      },
    );

    test('rejects a command hidden inside a one-shot module setup', () async {
      final fake = _FakeNativeObdApi();
      final bridge = ObdBridge(_AllowAllPolicy(), nativeApi: fake);
      final modules = [
        ObdModuleMessage(moduleId: 'm1', protocol: 6, setup: ['ATPP0C']),
      ];

      final exception = _expectRejection(
        () => bridge.enqueueOneShot(modules, const []),
      );
      expect(exception.rejectedCommands, contains('ATPP0C'));
      expect(fake.lastEnqueuedModules, isNull);
    });

    test('an allowed batch passes through unchanged', () async {
      final fake = _FakeNativeObdApi();
      final bridge = ObdBridge(_AllowAllPolicy(), nativeApi: fake);
      final modules = [
        ObdModuleMessage(moduleId: 'm1', protocol: 6, setup: ['ATSP6']),
      ];
      final steps = [_step('0100')];

      final result = await bridge.enqueueOneShot(modules, steps);

      expect(result, ObdOneShotEnqueueResultMessage.accepted);
      expect(fake.lastEnqueuedModules, same(modules));
      expect(fake.lastEnqueuedSteps, same(steps));
    });
  });
}

ObdBridgeCommandRejectedException _expectRejection(
  dynamic Function() startCall,
) {
  try {
    startCall();
  } on ObdBridgeCommandRejectedException catch (e) {
    return e;
  }
  fail('expected an ObdBridgeCommandRejectedException');
}
