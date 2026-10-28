import 'package:flutter_test/flutter_test.dart';
import 'package:obdbridge/obdbridge.dart';

void main() {
  test('obdbridgeVersion matches pubspec version', () {
    expect(obdbridgeVersion, '0.2.1');
  });
}
