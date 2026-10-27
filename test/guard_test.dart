import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:obdbridge/obdbridge.dart';

class _AllowAllPolicy implements CommandPolicy {
  @override
  bool allows(String canonicalCommand) => true;

  const _AllowAllPolicy();
}

class _DenyAllPolicy implements CommandPolicy {
  @override
  bool allows(String canonicalCommand) => false;

  const _DenyAllPolicy();
}

void main() {
  final vectorsFile = File('test/fixtures/guard_vectors.json');
  final vectors = jsonDecode(vectorsFile.readAsStringSync()) as List<dynamic>;

  group('HardGuard', () {
    for (final vector in vectors) {
      final map = vector as Map<String, dynamic>;
      final command = map['command'] as String;
      final allowed = map['allowed'] as bool;
      final reason = map['reason'] as String;

      test('${allowed ? 'allows' : 'rejects'} "$command" ($reason)', () {
        final result = HardGuard.check(command);
        expect(result, allowed ? isA<GuardAllowed>() : isA<GuardRejected>());
      });
    }
  });

  group('GuardedCommandFilter', () {
    test('rejects a hard-guard-allowed command when the policy denies it', () {
      final filter = const GuardedCommandFilter(_DenyAllPolicy());
      final result = filter.check('0100');
      expect(result, isA<GuardRejected>());
    });

    test('allows a command the hard guard and policy both allow', () {
      final filter = const GuardedCommandFilter(_AllowAllPolicy());
      final result = filter.check('0100');
      expect(result, isA<GuardAllowed>());
    });

    test(
      'never allows a command the hard guard rejects, regardless of policy',
      () {
        final filter = const GuardedCommandFilter(_AllowAllPolicy());
        final result = filter.check('22F190');
        expect(result, isA<GuardRejected>());
      },
    );
  });
}
