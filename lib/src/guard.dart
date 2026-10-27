/// Read-only guard for OBD/UDS commands.
///
/// [HardGuard] is a fixed, library-owned block list: it never grants access
/// to a service a caller's [CommandPolicy] has not already been asked
/// about. A command is only ever written to the adapter once both the
/// policy and this guard have allowed it.
library;

/// Result of running a command through [HardGuard.check].
sealed class GuardResult {
  const GuardResult();
}

/// The command passed the hard guard and was normalized to
/// [normalizedCommand] (whitespace stripped, upper-cased).
class GuardAllowed extends GuardResult {
  final String normalizedCommand;

  const GuardAllowed(this.normalizedCommand);
}

/// The command was rejected by the hard guard for [reason].
class GuardRejected extends GuardResult {
  final String reason;

  const GuardRejected(this.reason);
}

/// Caller-supplied restriction on top of [HardGuard].
///
/// Implementations decide which commands an app is allowed to send at all;
/// [HardGuard] then still rejects anything outside its own read-only
/// allow-list regardless of what the policy permits.
abstract class CommandPolicy {
  /// Whether [normalizedCommand] (already normalized by [HardGuard]) is
  /// allowed by this policy.
  bool allows(String normalizedCommand);
}

/// The fixed, library-owned read-only guard.
///
/// Allows only a fixed allow-list of adapter `AT`/`ST` commands, OBD mode
/// 01 reads (`01` + a 2-hex-digit PID), and UDS ReadDataByIdentifier (`22`
/// + a 4-hex-digit DID) other than the VIN (`F190`, `F802`) and ECU serial
/// number (`F18C`) identifiers. Every other service, and anything that is
/// not well-formed hex, is rejected.
///
/// The adapter allow-list is exact, not a bare `AT`/`ST` prefix check:
/// STN-chip `ST` commands can transmit arbitrary CAN frames (`STPX`) or
/// rewrite persistent adapter settings (`STSBR`), and ELM `ATPP` writes
/// programmable parameters, so a prefix match alone would defeat the
/// read-only guarantee.
class HardGuard {
  HardGuard._();

  static final RegExp _hex = RegExp(r'^[0-9A-F]+$');
  static final RegExp _whitespace = RegExp(r'\s+');

  // Real allowed commands are at most 6 hex characters (service + 2-byte
  // DID); anything longer than this is rejected outright rather than
  // falling through to a per-service length check.
  static const int _maxHexLength = 10;

  static const Set<String> _blockedDids = {'F190', 'F802', 'F18C'};

  // Adapter commands that are allowed verbatim, with no argument, or whose
  // only variation is a fixed 0/1 flag already spelled out here.
  static const Set<String> _exactAdapterCommands = {
    'ATZ',
    'ATD',
    'ATI',
    'AT@1',
    'ATRV',
    'ATDP',
    'ATDPN',
    'ATE0',
    'ATE1',
    'ATL0',
    'ATL1',
    'ATS0',
    'ATS1',
    'ATH0',
    'ATH1',
    'ATCAF0',
    'ATCAF1',
    'ATAR',
    'ATMA',
    'ATCRA',
    'STI',
    'STDI',
  };

  // Adapter commands that take a bounded hex/numeric argument. Each pattern
  // spells out the exact argument shape the adapter accepts; anything
  // outside it (e.g. ATSPD, the auto-search ATSPAn variants, or odd-length
  // ATFCSD payloads) is rejected rather than guessed at.
  static final List<RegExp> _adapterPatterns = [
    RegExp(r'^ATSP[0-9A-C]$'),
    RegExp(r'^ATAT[0-2]$'),
    RegExp(r'^ATST[0-9A-F]{2}$'),
    RegExp(r'^ATSH(?:[0-9A-F]{3}|[0-9A-F]{6}|[0-9A-F]{8})$'),
    RegExp(r'^ATCRA(?:[0-9A-F]{3}|[0-9A-F]{8})$'),
    RegExp(r'^ATCF(?:[0-9A-F]{3}|[0-9A-F]{8})$'),
    RegExp(r'^ATCM(?:[0-9A-F]{3}|[0-9A-F]{8})$'),
    RegExp(r'^ATFCSH(?:[0-9A-F]{3}|[0-9A-F]{8})$'),
    RegExp(r'^ATFCSD(?:[0-9A-F]{2}){1,5}$'),
    RegExp(r'^ATFCSM[0-2]$'),
  ];

  static bool _isAllowedAdapterCommand(String normalized) {
    return _exactAdapterCommands.contains(normalized) ||
        _adapterPatterns.any((pattern) => pattern.hasMatch(normalized));
  }

  /// Normalizes [command] and checks it against the hard, library-owned
  /// read-only allow-list.
  static GuardResult check(String command) {
    final normalized = command.replaceAll(_whitespace, '').toUpperCase();

    if (normalized.isEmpty) {
      return const GuardRejected('empty command');
    }

    if (normalized.startsWith('AT') || normalized.startsWith('ST')) {
      if (_isAllowedAdapterCommand(normalized)) {
        return GuardAllowed(normalized);
      }
      return const GuardRejected('adapter command not in allow-list');
    }

    if (!_hex.hasMatch(normalized)) {
      return const GuardRejected('not a valid hex command');
    }

    if (normalized.length % 2 != 0) {
      return const GuardRejected('odd-length hex command');
    }

    if (normalized.length > _maxHexLength) {
      return const GuardRejected('command too long');
    }

    final service = normalized.substring(0, 2);
    switch (service) {
      case '01':
        if (normalized.length == 4) {
          return GuardAllowed(normalized);
        }
        return const GuardRejected('malformed mode 01 read');
      case '22':
        if (normalized.length != 6) {
          return const GuardRejected('malformed UDS ReadDataByIdentifier');
        }
        final did = normalized.substring(2);
        if (_blockedDids.contains(did)) {
          return GuardRejected('blocked identifier $did');
        }
        return GuardAllowed(normalized);
      default:
        return GuardRejected('service $service not allowed');
    }
  }
}

/// Combines [HardGuard] with a caller-supplied [CommandPolicy].
///
/// A command must pass both: the policy decides what an app is allowed to
/// ask for, [HardGuard] still rejects anything outside its own read-only
/// allow-list regardless of what the policy permits.
class GuardedCommandFilter {
  final CommandPolicy policy;

  const GuardedCommandFilter(this.policy);

  /// Runs [command] through [HardGuard] and, if that allows it, through
  /// [policy].
  GuardResult check(String command) {
    final hardResult = HardGuard.check(command);
    if (hardResult is GuardRejected) {
      return hardResult;
    }
    final allowed = hardResult as GuardAllowed;
    if (!policy.allows(allowed.normalizedCommand)) {
      return const GuardRejected('rejected by caller policy');
    }
    return allowed;
  }
}
