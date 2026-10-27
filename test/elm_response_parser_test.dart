// SPDX-License-Identifier: AGPL-3.0-or-later
import 'package:flutter_test/flutter_test.dart';
import 'package:obdbridge/obdbridge.dart';

void main() {
  const parser = ElmResponseParser();

  Map<String, ParsedResponse> responsesOf(ElmBlockResult result) =>
      (result as ElmResponses).byHeader;

  group('single ISO-TP frame', () {
    test('protocol-7 single frame reassembles to the documented payload', () {
      // Real capture for odometerKm: header 18DAF106, PCI 0x07 (single frame,
      // length 7), SID 62, DID F4A6, data 00 02 9A 90.
      final result = parser.parse(
        '18DAF1060762F4A600029A90',
        headerLength: 8,
        validResponseHeaders: {'18DAF106'},
      );
      expect(result, isA<ElmResponses>());
      final byHeader = responsesOf(result);
      expect(byHeader.keys, ['18DAF106']);
      expect(
        byHeader['18DAF106'],
        const IsoTpMessage([0x62, 0xF4, 0xA6, 0x00, 0x02, 0x9A, 0x90]),
      );
    });
  });

  group('first frame plus consecutive frame', () {
    test('protocol-6 multi-frame reassembles to the documented payload', () {
      // Real capture for hvEnergyContent: first frame PCI 0x10 0x0B (total
      // length 11), then one consecutive frame PCI 0x21.
      final result = parser.parse(
        '77A100B622AB8000005\r77A21E300000000AAAA',
        headerLength: 3,
        validResponseHeaders: {'77A'},
      );
      expect(
        responsesOf(result)['77A'],
        const IsoTpMessage([
          0x62,
          0x2A,
          0xB8,
          0x00,
          0x00,
          0x05,
          0xE3,
          0x00,
          0x00,
          0x00,
          0x00,
        ]),
      );
    });

    test('a missing consecutive frame leaves the message malformed', () {
      final result = parser.parse(
        '77A100B622AB8000005',
        headerLength: 3,
        validResponseHeaders: {'77A'},
      );
      final response = responsesOf(result)['77A'];
      expect(response, isA<MalformedResponse>());
      expect(
        (response as MalformedResponse).reason,
        contains('final consecutive frame'),
      );
    });

    test('a consecutive frame with the wrong sequence number is malformed', () {
      // The real capture's second frame starts with 0x21 (sequence 1); this
      // corrupts it to 0x23 (sequence 3), which must not reassemble.
      final result = parser.parse(
        '77A100B622AB8000005\r77A23E300000000AAAA',
        headerLength: 3,
        validResponseHeaders: {'77A'},
      );
      final response = responsesOf(result)['77A'];
      expect(response, isA<MalformedResponse>());
      expect(
        (response as MalformedResponse).reason,
        contains('sequence number'),
      );
    });

    test('a first frame declaring fewer than 8 total bytes is malformed', () {
      // ISO 15765-2: a single frame already carries up to 7 payload bytes,
      // so FF.DL below 8 can never be a legitimate first frame.
      final result = parser.parse(
        '77A1004622AB8',
        headerLength: 3,
        validResponseHeaders: {'77A'},
      );
      final response = responsesOf(result)['77A'];
      expect(response, isA<MalformedResponse>());
      expect(
        (response as MalformedResponse).reason,
        contains('implausibly short'),
      );
    });
  });

  group('status responses', () {
    test('a literal NO DATA block resolves to ElmNoData, not a map entry', () {
      final result = parser.parse(
        'NO DATA',
        headerLength: 3,
        validResponseHeaders: {'77A'},
      );
      expect(result, const ElmNoData());
    });

    test('a known adapter error block resolves to ElmAdapterError', () {
      final result = parser.parse(
        'CAN ERROR',
        headerLength: 3,
        validResponseHeaders: {'77A'},
      );
      expect(result, const ElmAdapterError(ElmAdapterErrorKind.canError));
    });

    test('a negative UDS response decodes service and NRC', () {
      // SID 0x7F, service 0x22 (ReadDataByIdentifier), NRC 0x31
      // (requestOutOfRange), carried as a single frame of length 3.
      final result = parser.parse(
        '77A037F2231',
        headerLength: 3,
        validResponseHeaders: {'77A'},
      );
      expect(
        responsesOf(result)['77A'],
        const NegativeUdsResponse(service: 0x22, nrc: 0x31),
      );
    });

    test('NRC 0x78 (response pending) is superseded by the real answer', () {
      // The ECU first says "still working" (7F 22 78), then actually
      // answers with the real single-frame payload on the same header.
      final result = parser.parse(
        '77A037F2278\r77A0462F40D2A',
        headerLength: 3,
        validResponseHeaders: {'77A'},
      );
      expect(
        responsesOf(result)['77A'],
        const IsoTpMessage([0x62, 0xF4, 0x0D, 0x2A]),
      );
    });

    test('NRC 0x78 with no later answer is reported as-is, not lost', () {
      final result = parser.parse(
        '77A037F2278',
        headerLength: 3,
        validResponseHeaders: {'77A'},
      );
      expect(
        responsesOf(result)['77A'],
        const NegativeUdsResponse(service: 0x22, nrc: 0x78),
      );
    });
  });

  group('malformed frames', () {
    test('invalid hex in the data part is malformed, not a crash', () {
      final result = parser.parse(
        '77A10GARBAGE',
        headerLength: 3,
        validResponseHeaders: {'77A'},
      );
      expect(responsesOf(result)['77A'], isA<MalformedResponse>());
    });

    test('a signed-looking hex string is rejected, not parsed as negative', () {
      // int.parse(radix: 16) alone would accept a leading '-'; the strict
      // hex check must reject it before that ever runs.
      final result = parser.parse(
        '77A10-F2231',
        headerLength: 3,
        validResponseHeaders: {'77A'},
      );
      expect(responsesOf(result)['77A'], isA<MalformedResponse>());
    });

    test('a frame no longer than the header alone is silently ignored', () {
      final result = parser.parse(
        '77A',
        headerLength: 3,
        validResponseHeaders: {'77A'},
      );
      expect(responsesOf(result), isEmpty);
    });

    test('frames outside the valid header set are bus noise, not an error', () {
      final result = parser.parse(
        '7E8037F2231',
        headerLength: 3,
        validResponseHeaders: {'77A'},
      );
      expect(responsesOf(result), isEmpty);
    });
  });

  group('multiple responders in one block', () {
    test('each responder header gets its own reassembled message', () {
      final result = parser.parse(
        '18DAF1040462F40D00\r18DAF1060462F40D05',
        headerLength: 8,
        validResponseHeaders: {'18DAF104', '18DAF106'},
      );
      final byHeader = responsesOf(result);
      expect(byHeader.keys, ['18DAF104', '18DAF106']);
      expect(
        byHeader['18DAF104'],
        const IsoTpMessage([0x62, 0xF4, 0x0D, 0x00]),
      );
      expect(
        byHeader['18DAF106'],
        const IsoTpMessage([0x62, 0xF4, 0x0D, 0x05]),
      );
    });
  });
}
