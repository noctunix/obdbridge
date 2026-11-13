package de.noctunix.obdbridge

/**
 * Result of running a command through [HardGuard.check].
 */
sealed class GuardResult {
    /**
     * The command passed the hard guard exactly as given, in
     * [canonicalCommand] — [HardGuard] performs no normalization, so this
     * is the same string that was passed to [HardGuard.check].
     */
    data class Allowed(val canonicalCommand: String) : GuardResult()

    /**
     * The command was rejected by the hard guard for [reason].
     */
    data class Rejected(val reason: String) : GuardResult()
}

/**
 * Caller-supplied restriction on top of [HardGuard].
 *
 * Implementations decide which commands an app is allowed to send at all;
 * [HardGuard] then still rejects anything outside its own read-only
 * allow-list regardless of what the policy permits.
 */
interface CommandPolicy {
    /**
     * Whether [canonicalCommand] (already accepted as canonical by
     * [HardGuard]) is allowed by this policy.
     */
    fun allows(canonicalCommand: String): Boolean
}

/**
 * The fixed, library-owned read-only guard.
 *
 * Performs no normalization: a command is only ever acceptable if it is
 * already canonical — non-empty, and composed only of `0`-`9`, `A`-`Z` and
 * `@` — before the allow-list below even applies. Whitespace, CR/LF, a
 * BOM, any other control or Unicode character, and lowercase letters are
 * all rejected outright rather than stripped or case-folded: a command
 * session writes exactly the string this guard allowed plus a single
 * terminating `\r`, so silently accepting e.g. `"2201\r04"` here would let
 * a second, unreviewed command (`04`, clear diagnostic trouble codes)
 * reach the wire, and would hide the non-canonical input from the
 * caller's own [CommandPolicy], which only ever sees what this guard
 * allowed.
 *
 * Allows only a fixed allow-list of adapter `AT`/`ST` commands, OBD mode
 * 01 reads (`01` + a 2-hex-digit PID), and UDS ReadDataByIdentifier (`22`
 * + one to [MAX_DIDS_PER_READ] distinct 4-hex-digit DIDs, ISO 14229-1
 * §10.2) none of which is the VIN (`F190`, `F802`) or ECU serial number
 * (`F18C`) identifier. Every other service, and anything that is not
 * well-formed hex, is rejected.
 *
 * The adapter allow-list is exact, not a bare `AT`/`ST` prefix check:
 * STN-chip `ST` commands can transmit arbitrary CAN frames (`STPX`) or
 * rewrite persistent adapter settings (`STSBR`), and ELM `ATPP` writes
 * programmable parameters, so a prefix match alone would defeat the
 * read-only guarantee.
 */
object HardGuard {
    private val CANONICAL = Regex("^[0-9A-Z@]+$")
    private val HEX = Regex("^[0-9A-F]+$")

    // A request must fit one CAN single frame (7 data bytes): the ELM327
    // command set cannot send a segmented request, so service + 3 DIDs is
    // the most a read can carry.
    private const val MAX_DIDS_PER_READ = 3

    // Anything longer than the longest allowed read is rejected outright
    // rather than falling through to a per-service length check.
    private const val MAX_HEX_LENGTH = 2 + 4 * MAX_DIDS_PER_READ

    private val BLOCKED_DIDS = setOf("F190", "F802", "F18C")

    // Adapter commands that are allowed verbatim, with no argument, or
    // whose only variation is a fixed 0/1 flag already spelled out here.
    private val EXACT_ADAPTER_COMMANDS = setOf(
        "ATZ", "ATD", "ATI", "AT@1", "ATRV", "ATDP", "ATDPN",
        "ATE0", "ATE1", "ATL0", "ATL1", "ATS0", "ATS1", "ATH0", "ATH1",
        "ATCAF0", "ATCAF1", "ATAR", "ATMA", "ATCRA",
        "STI", "STDI",
    )

    // Adapter commands that take a bounded hex/numeric argument. Each
    // pattern spells out the exact argument shape the adapter accepts;
    // anything outside it (e.g. ATSPD, the auto-search ATSPAn variants, or
    // odd-length ATFCSD payloads) is rejected rather than guessed at.
    private val ADAPTER_PATTERNS = listOf(
        Regex("^ATSP[0-9A-C]$"),
        Regex("^ATAT[0-2]$"),
        Regex("^ATST[0-9A-F]{2}$"),
        Regex("^ATSH(?:[0-9A-F]{3}|[0-9A-F]{6}|[0-9A-F]{8})$"),
        Regex("^ATCRA(?:[0-9A-F]{3}|[0-9A-F]{8})$"),
        Regex("^ATCF(?:[0-9A-F]{3}|[0-9A-F]{8})$"),
        Regex("^ATCM(?:[0-9A-F]{3}|[0-9A-F]{8})$"),
        Regex("^ATFCSH(?:[0-9A-F]{3}|[0-9A-F]{8})$"),
        Regex("^ATFCSD(?:[0-9A-F]{2}){1,5}$"),
        Regex("^ATFCSM[0-2]$"),
    )

    private fun isAllowedAdapterCommand(command: String): Boolean {
        return command in EXACT_ADAPTER_COMMANDS ||
            ADAPTER_PATTERNS.any { it.matches(command) }
    }

    /**
     * Checks [command] against the hard, library-owned read-only allow-list.
     *
     * [command] must already be canonical; see the class doc comment. Any
     * character outside `0`-`9`, `A`-`Z` and `@` — including whitespace,
     * CR/LF, a BOM, lowercase letters, or any other control or Unicode
     * character — rejects the command outright, before the allow-list
     * below is even consulted.
     */
    fun check(command: String): GuardResult {
        if (command.isEmpty()) {
            return GuardResult.Rejected("empty command")
        }

        if (!CANONICAL.matches(command)) {
            return GuardResult.Rejected("not a canonical command")
        }

        if (command.startsWith("AT") || command.startsWith("ST")) {
            return if (isAllowedAdapterCommand(command)) {
                GuardResult.Allowed(command)
            } else {
                GuardResult.Rejected("adapter command not in allow-list")
            }
        }

        if (!HEX.matches(command)) {
            return GuardResult.Rejected("not a valid hex command")
        }

        if (command.length % 2 != 0) {
            return GuardResult.Rejected("odd-length hex command")
        }

        if (command.length > MAX_HEX_LENGTH) {
            return GuardResult.Rejected("command too long")
        }

        val service = command.substring(0, 2)
        return when (service) {
            "01" ->
                if (command.length == 4) {
                    GuardResult.Allowed(command)
                } else {
                    GuardResult.Rejected("malformed mode 01 read")
                }
            "22" -> {
                if (command.length < 6 || (command.length - 2) % 4 != 0) {
                    GuardResult.Rejected("malformed UDS ReadDataByIdentifier")
                } else {
                    val dids = command.substring(2).chunked(4)
                    val blocked = dids.firstOrNull { it in BLOCKED_DIDS }
                    when {
                        blocked != null -> GuardResult.Rejected("blocked identifier $blocked")
                        dids.toSet().size != dids.size -> GuardResult.Rejected("repeated identifier")
                        else -> GuardResult.Allowed(command)
                    }
                }
            }
            else -> GuardResult.Rejected("service $service not allowed")
        }
    }
}

/**
 * Combines [HardGuard] with a caller-supplied [CommandPolicy].
 *
 * A command must pass both: the policy decides what an app is allowed to
 * ask for, [HardGuard] still rejects anything outside its own read-only
 * allow-list regardless of what the policy permits.
 */
class GuardedCommandFilter(private val policy: CommandPolicy) {
    /**
     * Runs [command] through [HardGuard] and, if that allows it, through
     * the policy — both receive [command] exactly as given, with no
     * transformation in between.
     */
    fun check(command: String): GuardResult {
        val hardResult = HardGuard.check(command)
        if (hardResult is GuardResult.Rejected) {
            return hardResult
        }
        val allowed = hardResult as GuardResult.Allowed
        return if (!policy.allows(allowed.canonicalCommand)) {
            GuardResult.Rejected("rejected by caller policy")
        } else {
            allowed
        }
    }
}
