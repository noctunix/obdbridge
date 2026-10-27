// SPDX-License-Identifier: AGPL-3.0-or-later
/// Outcome of reassembling one responder's ISO-TP frames out of a raw ELM
/// text block. Never thrown — a malformed or negative answer is as valid an
/// outcome as a successful one and must be told apart, not caught.
sealed class ParsedResponse {
  const ParsedResponse();
}

/// A fully reassembled positive UDS message: SID byte, the two DID bytes,
/// and the data payload, exactly as transmitted (no truncation, no header).
final class IsoTpMessage extends ParsedResponse {
  const IsoTpMessage(this.bytes);
  final List<int> bytes;

  @override
  bool operator ==(Object other) {
    if (other is! IsoTpMessage || other.bytes.length != bytes.length) {
      return false;
    }
    for (var i = 0; i < bytes.length; i++) {
      if (other.bytes[i] != bytes[i]) return false;
    }
    return true;
  }

  @override
  int get hashCode => Object.hashAll(bytes);
}

/// A fully reassembled negative UDS response (`7F <service> <nrc>`). NRC
/// `0x78` (`requestCorrectlyReceivedResponsePending`) is never final on its
/// own — [ElmResponseParser] keeps listening for the same responder's real
/// answer and only falls back to this if none ever arrives.
final class NegativeUdsResponse extends ParsedResponse {
  const NegativeUdsResponse({required this.service, required this.nrc});
  final int service;
  final int nrc;

  @override
  bool operator ==(Object other) =>
      other is NegativeUdsResponse &&
      other.service == service &&
      other.nrc == nrc;

  @override
  int get hashCode => Object.hash(service, nrc);
}

/// A responder's frames could not be reassembled into a usable message:
/// invalid hex, a first frame without enough length, a missing consecutive
/// frame, or a consecutive frame with the wrong sequence number. [reason]
/// describes the protocol-level defect, never the payload bytes themselves.
final class MalformedResponse extends ParsedResponse {
  const MalformedResponse(this.reason);
  final String reason;

  @override
  bool operator ==(Object other) =>
      other is MalformedResponse && other.reason == reason;

  @override
  int get hashCode => reason.hashCode;
}

/// Adapter-level fault reported instead of any vehicle data, in the
/// adapter's own literal text (ELM327/STN datasheet wording).
enum ElmAdapterErrorKind {
  /// `CAN ERROR` — the adapter could not talk to the bus at all.
  canError,

  /// `BUFFER FULL` — the adapter's receive buffer overflowed.
  bufferFull,

  /// `STOPPED` — a running operation was interrupted (e.g. by a new write).
  stopped,

  /// `?` — the adapter did not recognize the command it was sent.
  unsupportedCommand,

  /// `DATA ERROR` — a checksum or framing fault on the bus.
  dataError,

  /// `<RX ERROR` — a receive-side framing fault, reported with ELM's
  /// continuation-line prefix.
  rxError,
}

/// Outcome of parsing one raw ELM text block: either per-responder messages,
/// an adapter-level `NO DATA` (no responder answered at all, so there was
/// never a header to key a result by), or an adapter-level fault that is
/// not vehicle data at all. Modeled as three distinct variants rather than
/// a nullable/sentinel entry in a map, so a caller cannot read "no result
/// for this header" where the block as a whole never reached the vehicle.
sealed class ElmBlockResult {
  const ElmBlockResult();
}

final class ElmResponses extends ElmBlockResult {
  const ElmResponses(this.byHeader);
  final Map<String, ParsedResponse> byHeader;
}

final class ElmNoData extends ElmBlockResult {
  const ElmNoData();

  @override
  bool operator ==(Object other) => other is ElmNoData;

  @override
  int get hashCode => runtimeType.hashCode;
}

final class ElmAdapterError extends ElmBlockResult {
  const ElmAdapterError(this.kind);
  final ElmAdapterErrorKind kind;

  @override
  bool operator ==(Object other) =>
      other is ElmAdapterError && other.kind == kind;

  @override
  int get hashCode => kind.hashCode;
}

/// Reassembles ELM327 ISO-TP responses, one [ParsedResponse] per responder
/// header, out of the literal adapter text: `\r`-separated frames, no
/// trailing prompt, exactly as the ELM reports them.
class ElmResponseParser {
  const ElmResponseParser();

  static const Map<String, ElmAdapterErrorKind> _adapterErrorTexts = {
    'CAN ERROR': ElmAdapterErrorKind.canError,
    'BUFFER FULL': ElmAdapterErrorKind.bufferFull,
    'STOPPED': ElmAdapterErrorKind.stopped,
    '?': ElmAdapterErrorKind.unsupportedCommand,
    'DATA ERROR': ElmAdapterErrorKind.dataError,
    '<RX ERROR': ElmAdapterErrorKind.rxError,
  };

  static const _noDataText = 'NO DATA';

  static final RegExp _strictHex = RegExp(r'^(?:[0-9A-Fa-f]{2})+$');

  /// [headerLength] is the hex-digit width of the responder header for the
  /// active protocol: 3 for 11-bit CAN addressing, 8 for 29-bit. Only frames
  /// whose header is in [validResponseHeaders] are reassembled; anything
  /// else is bus traffic outside this request's addressing and is ignored,
  /// which is also how several responders answering the same functional
  /// request inside one block are told apart.
  ElmBlockResult parse(
    String rawText, {
    required int headerLength,
    required Set<String> validResponseHeaders,
  }) {
    final trimmed = rawText.trim();
    final upper = trimmed.toUpperCase();
    if (upper == _noDataText) return const ElmNoData();
    final errorKind = _adapterErrorTexts[upper];
    if (errorKind != null) return ElmAdapterError(errorKind);

    final frames = rawText
        .split('\r')
        .map((frame) => frame.trim())
        .where((frame) => frame.isNotEmpty);

    final accumulators = <String, _FrameAccumulator>{};
    final order = <String>[];

    for (final frame in frames) {
      if (frame.length <= headerLength) continue;
      final header = frame.substring(0, headerLength);
      if (!validResponseHeaders.contains(header)) continue;

      var existing = accumulators[header];
      if (existing != null && existing.isResolved) {
        if (existing.failureReason == null &&
            _isResponsePending(existing.data!)) {
          // An interim "please wait" ack, not this responder's real answer —
          // treat this frame as if the header had not replied yet, so a
          // genuine answer in a later frame overwrites it below. If no
          // later frame ever arrives, this ack is still sitting in
          // accumulators[header] and _resolve() reports it as-is.
          existing = null;
        } else {
          continue;
        }
      }

      final bytes = _tryHexToBytes(frame.substring(headerLength));
      if (bytes == null || bytes.isEmpty) {
        accumulators[header] = _FrameAccumulator.failed(
          'frame for responder $header has invalid or empty hex data',
        );
        if (!order.contains(header)) order.add(header);
        continue;
      }

      if (existing == null) {
        accumulators[header] = _startMessage(header, bytes);
        if (!order.contains(header)) order.add(header);
      } else {
        accumulators[header] = _continueMessage(header, existing, bytes);
      }
    }

    return ElmResponses({
      for (final header in order) header: _resolve(accumulators[header]!),
    });
  }

  static bool _isResponsePending(List<int> data) =>
      data.length >= 3 && data[0] == 0x7F && data[2] == 0x78;

  _FrameAccumulator _startMessage(String header, List<int> bytes) {
    final pciType = bytes[0] >> 4;
    if (pciType == 0) {
      final length = bytes[0] & 0x0F;
      if (bytes.length - 1 < length) {
        return _FrameAccumulator.failed(
          'single frame for responder $header is shorter than its declared length',
        );
      }
      return _FrameAccumulator.done(bytes.sublist(1, 1 + length));
    }
    if (pciType == 1) {
      if (bytes.length < 2) {
        return _FrameAccumulator.failed(
          'first frame for responder $header is missing its length byte',
        );
      }
      final totalLength = ((bytes[0] & 0x0F) << 8) | bytes[1];
      // ISO 15765-2: a single frame already carries up to 7 payload bytes,
      // so a first frame is only valid once the full message needs 8+.
      if (totalLength < 8) {
        return _FrameAccumulator.failed(
          'first frame for responder $header declares an implausibly short multi-frame length',
        );
      }
      return _FrameAccumulator.inProgress(
        totalLength: totalLength,
        data: bytes.sublist(2),
        nextSequenceNibble: 1,
      );
    }
    return _FrameAccumulator.failed(
      'frame for responder $header opens with unexpected ISO-TP PCI type 0x${pciType.toRadixString(16)}',
    );
  }

  _FrameAccumulator _continueMessage(
    String header,
    _FrameAccumulator existing,
    List<int> bytes,
  ) {
    final pciType = bytes[0] >> 4;
    if (pciType != 2) {
      return _FrameAccumulator.failed(
        'frame for responder $header was expected to be a consecutive frame',
      );
    }
    final sequenceNibble = bytes[0] & 0x0F;
    if (sequenceNibble != existing.nextSequenceNibble! & 0x0F) {
      return _FrameAccumulator.failed(
        'consecutive frame for responder $header has the wrong sequence number',
      );
    }
    final data = [...existing.data!, ...bytes.sublist(1)];
    if (data.length >= existing.totalLength!) {
      return _FrameAccumulator.done(data.sublist(0, existing.totalLength));
    }
    return _FrameAccumulator.inProgress(
      totalLength: existing.totalLength,
      data: data,
      nextSequenceNibble: existing.nextSequenceNibble! + 1,
    );
  }

  ParsedResponse _resolve(_FrameAccumulator accumulator) {
    if (accumulator.failureReason != null) {
      return MalformedResponse(accumulator.failureReason!);
    }
    if (!accumulator.isResolved) {
      return const MalformedResponse(
        'multi-frame message is missing its final consecutive frame',
      );
    }
    final bytes = accumulator.data!;
    if (bytes.isEmpty) {
      return const MalformedResponse('reassembled message is empty');
    }
    if (bytes[0] == 0x7F) {
      if (bytes.length < 3) {
        return const MalformedResponse(
          'negative response is shorter than SID+service+NRC',
        );
      }
      return NegativeUdsResponse(service: bytes[1], nrc: bytes[2]);
    }
    return IsoTpMessage(bytes);
  }

  List<int>? _tryHexToBytes(String hex) {
    if (hex.isEmpty || hex.length % 2 != 0) return null;
    if (!_strictHex.hasMatch(hex)) return null;
    return [
      for (var i = 0; i < hex.length; i += 2)
        int.parse(hex.substring(i, i + 2), radix: 16),
    ];
  }
}

/// Per-responder reassembly state while frames are still coming in.
class _FrameAccumulator {
  _FrameAccumulator.done(this.data)
    : totalLength = data!.length,
      nextSequenceNibble = null,
      failureReason = null,
      isResolved = true;

  _FrameAccumulator.inProgress({
    required this.totalLength,
    required this.data,
    required this.nextSequenceNibble,
  }) : failureReason = null,
       isResolved = false;

  _FrameAccumulator.failed(this.failureReason)
    : totalLength = null,
      data = null,
      nextSequenceNibble = null,
      isResolved = true;

  final int? totalLength;
  final List<int>? data;
  final int? nextSequenceNibble;
  final String? failureReason;

  /// True once no further frame can change the outcome: either a complete
  /// message was reassembled, or reassembly already failed.
  final bool isResolved;
}
