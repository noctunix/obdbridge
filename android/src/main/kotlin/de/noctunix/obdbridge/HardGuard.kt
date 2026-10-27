package de.noctunix.obdbridge

/**
 * Result of running a command through [HardGuard.check].
 */
sealed class GuardResult {
    /**
     * The command passed the hard guard and was normalized to
     * [normalizedCommand] (whitespace stripped, upper-cased).
     */
    data class Allowed(val normalizedCommand: String) : GuardResult()

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
     * Whether [normalizedCommand] (already normalized by [HardGuard]) is
     * allowed by this policy.
     */
    fun allows(normalizedCommand: String): Boolean
}

/**
 * The fixed, library-owned read-only guard.
 *
 * Allows only adapter `AT`/`ST` commands, OBD mode 01 reads (`01` + a
 * 2-hex-digit PID), and UDS ReadDataByIdentifier (`22` + a 4-hex-digit
 * DID) other than the VIN (`F190`, `F802`) and ECU serial number (`F18C`)
 * identifiers. Every other service, and anything that is not well-formed
 * hex, is rejected.
 */
object HardGuard {
    private val HEX = Regex("^[0-9A-F]+$")
    private val WHITESPACE = Regex("\\s+")

    // Real allowed commands are at most 6 hex characters (service + 2-byte
    // DID); anything longer than this is rejected outright rather than
    // falling through to a per-service length check.
    private const val MAX_HEX_LENGTH = 10

    private val BLOCKED_DIDS = setOf("F190", "F802", "F18C")

    /**
     * Normalizes [command] and checks it against the hard, library-owned
     * read-only allow-list.
     */
    fun check(command: String): GuardResult {
        val normalized = command.replace(WHITESPACE, "").uppercase()

        if (normalized.isEmpty()) {
            return GuardResult.Rejected("empty command")
        }

        if (normalized.startsWith("AT") || normalized.startsWith("ST")) {
            return GuardResult.Allowed(normalized)
        }

        if (!HEX.matches(normalized)) {
            return GuardResult.Rejected("not a valid hex command")
        }

        if (normalized.length % 2 != 0) {
            return GuardResult.Rejected("odd-length hex command")
        }

        if (normalized.length > MAX_HEX_LENGTH) {
            return GuardResult.Rejected("command too long")
        }

        val service = normalized.substring(0, 2)
        return when (service) {
            "01" ->
                if (normalized.length == 4) {
                    GuardResult.Allowed(normalized)
                } else {
                    GuardResult.Rejected("malformed mode 01 read")
                }
            "22" -> {
                if (normalized.length != 6) {
                    GuardResult.Rejected("malformed UDS ReadDataByIdentifier")
                } else {
                    val did = normalized.substring(2)
                    if (did in BLOCKED_DIDS) {
                        GuardResult.Rejected("blocked identifier $did")
                    } else {
                        GuardResult.Allowed(normalized)
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
     * the policy.
     */
    fun check(command: String): GuardResult {
        val hardResult = HardGuard.check(command)
        if (hardResult is GuardResult.Rejected) {
            return hardResult
        }
        val allowed = hardResult as GuardResult.Allowed
        return if (!policy.allows(allowed.normalizedCommand)) {
            GuardResult.Rejected("rejected by caller policy")
        } else {
            allowed
        }
    }
}
