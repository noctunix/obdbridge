/// Generic, profile-free module setup for an OBD/UDS session over ISO
/// 15765-4 CAN. This library knows nothing about vehicles, signals or
/// DIDs — [buildModuleSetupCommands] only compiles the fixed adapter
/// sequence a vehicle read against [requestHeader]/[responseHeader] needs
/// first: explicit protocol selection (never auto-detect), the request
/// header, a receive filter on the one expected responder, and flow
/// control addressed back at the request header so a multi-frame
/// response's consecutive-frame request reaches the right address instead
/// of a neighboring responder's.
library;

/// Addressing width of an OBD/UDS CAN bus.
enum ObdProtocol {
  elevenBitCan(wireValue: 6, headerLength: 3, protocolSelectCommand: 'ATSP6'),
  twentyNineBitCan(
    wireValue: 7,
    headerLength: 8,
    protocolSelectCommand: 'ATSP7',
  );

  const ObdProtocol({
    required this.wireValue,
    required this.headerLength,
    required this.protocolSelectCommand,
  });

  /// The integer a plan message carries for this protocol: 6 or 7.
  final int wireValue;

  /// Hex-digit width of a responder header under this protocol.
  final int headerLength;

  /// The explicit `ATSP6`/`ATSP7` protocol-select command — never `ATSP0`
  /// auto-detection, which has been observed to pick the wrong protocol in
  /// practice.
  final String protocolSelectCommand;

  static ObdProtocol? fromWireValue(int value) => switch (value) {
    6 => ObdProtocol.elevenBitCan,
    7 => ObdProtocol.twentyNineBitCan,
    _ => null,
  };
}

/// Builds the adapter-only setup commands for one module: explicit
/// [protocol] selection, the request header, a receive filter on
/// [responseHeader], and flow control addressed back at [requestHeader].
///
/// This is the literal adapter command list only — it does not check the
/// result against any command policy. A caller composing a full plan runs
/// every command returned here (and everything else in the plan) through
/// its own `CommandPolicy` before sending it anywhere; native additionally
/// enforces `HardGuard` immediately before writing, regardless of what the
/// caller's policy allowed.
List<String> buildModuleSetupCommands({
  required ObdProtocol protocol,
  required String requestHeader,
  required String responseHeader,
}) => [
  protocol.protocolSelectCommand,
  'ATSH$requestHeader',
  'ATCRA$responseHeader',
  'ATFCSH$requestHeader',
  'ATFCSD300000',
  'ATFCSM1',
];
