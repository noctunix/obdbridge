import 'package:flutter_test/flutter_test.dart';
import 'package:obdbridge/obdbridge.dart';

void main() {
  group('ObdProtocol', () {
    test('elevenBitCan carries the documented wire shape', () {
      expect(ObdProtocol.elevenBitCan.wireValue, 6);
      expect(ObdProtocol.elevenBitCan.headerLength, 3);
      expect(ObdProtocol.elevenBitCan.protocolSelectCommand, 'ATSP6');
    });

    test('twentyNineBitCan carries the documented wire shape', () {
      expect(ObdProtocol.twentyNineBitCan.wireValue, 7);
      expect(ObdProtocol.twentyNineBitCan.headerLength, 8);
      expect(ObdProtocol.twentyNineBitCan.protocolSelectCommand, 'ATSP7');
    });

    test('fromWireValue resolves 6 and 7, and rejects anything else', () {
      expect(ObdProtocol.fromWireValue(6), ObdProtocol.elevenBitCan);
      expect(ObdProtocol.fromWireValue(7), ObdProtocol.twentyNineBitCan);
      expect(ObdProtocol.fromWireValue(0), isNull);
      expect(ObdProtocol.fromWireValue(8), isNull);
    });
  });

  group('buildModuleSetupCommands', () {
    test(
      '11-bit CAN module compiles the documented adapter sequence in order',
      () {
        final commands = buildModuleSetupCommands(
          protocol: ObdProtocol.elevenBitCan,
          requestHeader: '710',
          responseHeader: '77A',
        );

        expect(commands, [
          'ATSP6',
          'ATSH710',
          'ATCRA77A',
          'ATFCSH710',
          'ATFCSD300000',
          'ATFCSM1',
        ]);
      },
    );

    test(
      '29-bit CAN module selects protocol 7 and uses the wider headers verbatim',
      () {
        final commands = buildModuleSetupCommands(
          protocol: ObdProtocol.twentyNineBitCan,
          requestHeader: '18DA06F1',
          responseHeader: '18DAF106',
        );

        expect(commands, [
          'ATSP7',
          'ATSH18DA06F1',
          'ATCRA18DAF106',
          'ATFCSH18DA06F1',
          'ATFCSD300000',
          'ATFCSM1',
        ]);
      },
    );
  });
}
