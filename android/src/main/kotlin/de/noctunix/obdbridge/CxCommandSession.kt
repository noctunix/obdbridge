package de.noctunix.obdbridge

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/** How a finished command exchange ended up. Mirrors the transport states a caller eventually
 * reports upstream; this type carries no vehicle-level interpretation (`NO DATA`, negative
 * response codes) — that is read out of [CxCommandResult.responseText] by a later layer.
 * [UNEXPECTED_RESPONSE], [QUIET_PERIOD_EXPIRED] and [RESYNC_NOT_CONFIGURED] are specific to a
 * resync attempt rather than to a dispatched command — see [CxResyncStep] and [configureResync]. */
enum class CxCommandOutcome {
    OK,
    TIMEOUT,
    OVERFLOW,
    DISCONNECTED,
    REJECTED_BY_GUARD,
    WRITE_FAILED,
    CANCELLED,

    /** A resync step got back a well-formed frame before its deadline, but the content failed
     * [CxResyncStep.isAcceptableResponse] — e.g. an ELM327 answering a command it was not
     * actually idle for with `STOPPED` instead of running it, which still parses as a complete
     * frame with a prompt. */
    UNEXPECTED_RESPONSE,

    /** The adapter never went quiet for the full [CxCommandSession.maxQuietPeriodMs] budget of a
     * resync attempt; no step was ever written for that attempt. */
    QUIET_PERIOD_EXPIRED,

    /** The quiet wait elapsed but [configureResync] had been given no steps to run. */
    RESYNC_NOT_CONFIGURED,
}

/** [responseText] (echo and trailing prompt already removed, multiple lines joined with `\n`)
 * is set only when [outcome] is [CxCommandOutcome.OK]. [rawBytes] carries whatever was actually
 * received before the prompt for [CxCommandOutcome.OK], or whatever partial bytes had already
 * arrived — capped at the response-size limit — for [CxCommandOutcome.TIMEOUT]/
 * [CxCommandOutcome.OVERFLOW]/[CxCommandOutcome.CANCELLED]/[CxCommandOutcome.DISCONNECTED]; empty
 * for a command that was never written at all ([CxCommandOutcome.REJECTED_BY_GUARD],
 * [CxCommandOutcome.WRITE_FAILED]) or that never existed as a dispatched command in the first
 * place ([CxCommandOutcome.QUIET_PERIOD_EXPIRED], [CxCommandOutcome.RESYNC_NOT_CONFIGURED]). */
data class CxCommandResult(
    val outcome: CxCommandOutcome,
    val responseText: String?,
    val rawBytes: List<Byte> = emptyList(),
)

/** Schedules one delayed callback and allows cancelling it before it fires, and reports the
 * current time on the same basis [schedule]'s delay is measured against. [CxCommandSession]
 * depends on this rather than [Handler]/[SystemClock] directly so a test can control elapsed
 * time instead of sleeping through real timeouts. */
interface CxCommandClock {
    fun now(): Long
    fun schedule(delayMs: Long, action: () -> Unit): CxScheduledTask
}

/** A still-pending [CxCommandClock.schedule] call. [cancel] is a no-op once the callback has
 * already fired or was already cancelled. */
interface CxScheduledTask {
    fun cancel()
}

/** Production [CxCommandClock] backed by the main-thread [Handler] and [SystemClock.uptimeMillis],
 * the same time basis [Handler.postDelayed] itself schedules against. */
class AndroidCxCommandClock(private val handler: Handler = Handler(Looper.getMainLooper())) : CxCommandClock {
    override fun now(): Long = SystemClock.uptimeMillis()

    override fun schedule(delayMs: Long, action: () -> Unit): CxScheduledTask {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMs)
        return object : CxScheduledTask {
            override fun cancel() = handler.removeCallbacks(runnable)
        }
    }
}

/** Writes one already-framed command to the adapter. Implemented by whatever owns the GATT
 * write characteristic; splitting into the negotiated write length, if the command line exceeds
 * it, is that implementation's job, not this session's. */
interface CxCommandTransport {
    /** Writes [bytes]. Returns `false` if the write could not even be attempted — e.g. no
     * active GATT connection — which [CxCommandSession] reports as [CxCommandOutcome.WRITE_FAILED]
     * without ever starting a timeout for it. */
    fun write(bytes: ByteArray): Boolean
}

/** One resync step: the command to send, and the check its response content must pass to count
 * as success rather than [CxCommandOutcome.UNEXPECTED_RESPONSE] — a well-formed frame with a
 * prompt is not proof the adapter actually ran *this* command, since e.g. an ELM327 answers one
 * received while still busy with `STOPPED` instead of queuing it. */
class CxResyncStep(val command: String, val isAcceptableResponse: (String) -> Boolean) {
    companion object {
        /** Most adapter commands (`ATE0`, `ATL0`, `ATS0`, `ATH1`, ...) answer exactly `OK`. */
        fun expectOk(command: String) = CxResyncStep(command) { it == "OK" }
    }
}

private const val PROMPT_BYTE: Int = '>'.code

/**
 * Serializes ELM327/AT command exchanges over one adapter connection: exactly one command is ever
 * outstanding, its response is framed on the `>` prompt across however many BLE notifications it
 * takes to arrive, and a second [execute] call made before the first has finished is queued
 * rather than interleaved. [onNotification] (bytes received), [onConnected] and [onDisconnected]
 * are all driven by whoever owns the actual GATT callbacks; this class has no Bluetooth API
 * dependency of its own, and holds no connection-specific state itself beyond what the calls
 * below update — nothing here *requires* a new instance per connection. [ObdApi] nonetheless
 * builds a fresh one on every `Connected` state and on every `startLive`, rather than calling
 * [onConnected] on whichever instance already exists, to avoid resuming a stale quiet-wait cycle
 * left over from a now-irrelevant previous connection.
 *
 * A failure on an otherwise live connection — [CxCommandOutcome.TIMEOUT] or [CxCommandOutcome.OVERFLOW]
 * on a written command — starts the quiet-wait-then-resync cycle itself, immediately; [onConnected]
 * is not needed for that, since the connection was never lost. [cancel] and [onDisconnected] never
 * start that cycle themselves, even when what they catch is a written command or an
 * already-running quiet wait: there may be no live transport left to resync over, so a resync
 * stays required for a later [onConnected] call to actually resume, once a connection exists
 * again to run it on — a [cancel] that catches only queued, never-written work leaves no resync
 * required at all. Running this connection's own ordinary per-connect initialization remains the
 * caller's job via plain [execute] calls regardless of any of this — [onConnected] only re-arms a
 * previously *failed* resync, it is not itself an init trigger. [configureResync] only sets what
 * the resync sequence is; it never starts one on its own, and a second [onConnected] (or an
 * automatic start racing one already triggered by a failure) while one is already in progress
 * changes nothing.
 *
 * The quiet wait exists because a response to the very command that just failed can still be in
 * flight: writing the first resync step before it has arrived would let it get mistaken for that
 * step's own response. Any bytes arriving during the quiet wait restart it rather than being
 * attributed to anything; if the adapter never goes quiet within [maxQuietPeriodMs], the attempt
 * counts as failed without ever writing a step ([CxCommandOutcome.QUIET_PERIOD_EXPIRED]) and the
 * session stays blocked for the next [onConnected] to try again — the same happens, immediately,
 * if no sequence was ever given to [configureResync] ([CxCommandOutcome.RESYNC_NOT_CONFIGURED]).
 * Every command actually written goes through [HardGuard] immediately beforehand; a
 * rejected command never reaches [transport].
 */
class CxCommandSession(
    private val transport: CxCommandTransport,
    private val clock: CxCommandClock,
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    private val guard: (String) -> Boolean = { HardGuard.check(it) is GuardResult.Allowed },
    private val quietPeriodMs: Long = DEFAULT_QUIET_PERIOD_MS,
    private val maxQuietPeriodMs: Long = DEFAULT_MAX_QUIET_PERIOD_MS,
) {
    init {
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive" }
        require(quietPeriodMs > 0) { "quietPeriodMs must be positive" }
        require(maxQuietPeriodMs > quietPeriodMs) {
            "maxQuietPeriodMs ($maxQuietPeriodMs) must exceed quietPeriodMs ($quietPeriodMs), " +
                "or every resync attempt fails before the quiet period could ever elapse"
        }
    }

    private class PendingCommand(
        val command: String,
        val deadlineMs: Long,
        val enqueuedAtMs: Long,
        val isResync: Boolean,
        val callback: (CxCommandResult) -> Unit,
    ) {
        val buffer = ByteArrayOutputStream()
        var responseTimeoutTask: CxScheduledTask? = null
        var queueDeadlineTask: CxScheduledTask? = null
        var finished = false
    }

    /** One configured resync attempt, snapshotted when it actually starts so a concurrent
     * [configureResync] call cannot mix an old and a new sequence mid-chain. */
    private class ResyncRun(
        val epoch: Long,
        val steps: List<CxResyncStep>,
        val stepDeadlineMs: Long,
        val listener: ((CxCommandResult) -> Unit)?,
    )

    private val queue = ArrayDeque<PendingCommand>()
    private var active: PendingCommand? = null
    private var resyncRequired = false

    /** `true` while waiting out the quiet period before the first resync step; every dispatch
     * is blocked during this, including resync steps themselves. */
    private var quiescing = false
    private var quietTask: CxScheduledTask? = null
    private var quietOverallDeadlineTask: CxScheduledTask? = null

    /** `true` from the moment a resync attempt starts (entering the quiet wait) until it
     * concludes one way or another — success, a failing step, the overall deadline, or a
     * teardown. Makes [onConnected] (and the automatic start after a live-connection failure)
     * idempotent: a second trigger while an attempt is already running changes nothing, rather
     * than restarting the quiet wait's own deadline or orphaning the attempt already in flight. */
    private var resyncAttemptInProgress = false

    private var resyncSteps: List<CxResyncStep> = emptyList()
    private var resyncStepDeadlineMs: Long = 0L
    private var resyncStepListener: ((CxCommandResult) -> Unit)? = null

    /** Bumped by [onDisconnected] and [cancel]. A resync step's own continuation captures the
     * epoch current when it was started and checks it again before dispatching the next step —
     * so a teardown that happens to land between two steps stops the chain instead of writing a
     * step meant for a connection that no longer exists. Also what [failWrittenCommand] checks
     * before starting the quiet wait, in case the very callback it just invoked reacted to the
     * failure by tearing down the connection itself. */
    private var epoch = 0L

    /** Set for the duration of [onDisconnected]/[cancel]'s own callback invocations so that a
     * caller which reacts to one of those callbacks by calling [execute] again immediately gets
     * the same terminal outcome back instead of being queued into a session mid-teardown. */
    private var tearingDownAs: CxCommandOutcome? = null

    /** Sets the sequence run whenever a resync is needed. Replaces whatever was configured
     * before; a resync already in progress keeps running the sequence it started with. Each
     * step's response is checked against [CxResyncStep.isAcceptableResponse], not just for
     * having arrived with a prompt at all. [onStepResult], if given, is invoked once per step as
     * it completes — including a failing one, which stops the sequence and leaves regular
     * commands blocked — and once, with [CxCommandOutcome.QUIET_PERIOD_EXPIRED] or
     * [CxCommandOutcome.RESYNC_NOT_CONFIGURED] and no step ever attempted, if the quiet wait
     * elapsed without one. */
    @Synchronized
    fun configureResync(steps: List<CxResyncStep>, stepDeadlineMs: Long, onStepResult: ((CxCommandResult) -> Unit)? = null) {
        resyncSteps = steps
        resyncStepDeadlineMs = stepDeadlineMs
        resyncStepListener = onStepResult
    }

    /** Queues [command] for execution; runs immediately if nothing else is outstanding and no
     * resync is required, otherwise waits for every command ahead of it (including a required
     * resync) to finish. [callback] fires exactly once. It fires synchronously, within this very
     * call, only when the session is mid-teardown, or when nothing is queued ahead so [command]
     * is dispatched immediately and is either rejected by [HardGuard] or fails to write;
     * every other case fires later, from [onNotification], a timeout, or the queue deadline
     * below. If it is still queued by the time [deadlineMs] has elapsed without ever being
     * written, it ends as [CxCommandOutcome.TIMEOUT] rather than waiting indefinitely for a
     * resync that may never complete; once actually written, the same [deadlineMs] bounds the
     * response — queued and response wait together never exceed [deadlineMs] from this call. */
    @Synchronized
    fun execute(command: String, deadlineMs: Long, callback: (CxCommandResult) -> Unit) {
        tearingDownAs?.let {
            callback(CxCommandResult(it, null))
            return
        }
        enqueue(PendingCommand(command, deadlineMs, clock.now(), isResync = false, callback = callback), atFront = false)
    }

    /** Feeds [bytes] from one BLE notification into the response of whatever command is
     * currently outstanding. Bytes arriving while quiescing restart the quiet wait rather than
     * being accepted as anyone's response. Bytes arriving while nothing is outstanding and the
     * session is not quiescing — a stray notification behind an already-completed frame — are
     * simply discarded. */
    @Synchronized
    fun onNotification(bytes: ByteArray) {
        if (quiescing) {
            restartQuietTimer()
            return
        }
        val current = active ?: return
        current.buffer.write(bytes)
        val raw = current.buffer.toByteArray()
        val promptIndex = raw.indexOfFirst { it.toInt() and 0xFF == PROMPT_BYTE }
        if (promptIndex >= 0) {
            if (promptIndex > maxResponseBytes) {
                failWrittenCommand(current, CxCommandOutcome.OVERFLOW)
                return
            }
            val frame = raw.copyOfRange(0, promptIndex)
            finish(current, CxCommandResult(CxCommandOutcome.OK, decodeFrame(frame, current.command), frame.toList()))
            return
        }
        if (raw.size > maxResponseBytes) {
            failWrittenCommand(current, CxCommandOutcome.OVERFLOW)
        }
    }

    /** Required once a connection exists: the active command, if any, and every command still
     * queued end as [CxCommandOutcome.DISCONNECTED]. Deliberately starts no timer of any kind —
     * there is nothing to write to any more. If a command was actually outstanding on the wire,
     * or a resync was already waiting out its quiet period, a resync is required again before
     * any further regular command; [onConnected] is what actually resumes it, once a connection
     * exists again to resume it on. Disconnecting while genuinely idle leaves no resync required
     * — this tracks only recovery from a command that was actually in flight, never a connection's
     * own per-connect initialization, which is the caller's job via plain [execute] calls on
     * every [onConnected] regardless of whether one happens to be required here as well. */
    @Synchronized
    fun onDisconnected() = teardown(CxCommandOutcome.DISCONNECTED)

    /** Cancels the active command, if any, and everything still queued, as
     * [CxCommandOutcome.CANCELLED]. If a command was actually outstanding on the wire, or a
     * resync was already waiting out its quiet period, the same resync requirement as a timeout
     * applies — cancelling the bookkeeping does not stop whatever the adapter is already in the
     * middle of sending — but, like [onDisconnected], starts no timer of its own; call
     * [onConnected] (the connection is presumably still up after a mere [cancel]) to resume. */
    @Synchronized
    fun cancel() = teardown(CxCommandOutcome.CANCELLED)

    /** Call once a connection exists, including right after the very first connect. A no-op
     * unless a resync is required and none is already running, in which case it starts the
     * quiet-wait-then-resync cycle against the connection that now exists. Does not itself run
     * any per-connect initialization — see the class documentation. */
    @Synchronized
    fun onConnected() {
        if (resyncRequired && !resyncAttemptInProgress) enterQuietPeriod()
    }

    private fun teardown(outcome: CxCommandOutcome) {
        epoch++
        val wasQuiescing = quiescing
        quietTask?.cancel()
        quietTask = null
        quietOverallDeadlineTask?.cancel()
        quietOverallDeadlineTask = null
        quiescing = false
        resyncAttemptInProgress = false
        tearingDownAs = outcome

        val wasActive = active
        active = null
        wasActive?.responseTimeoutTask?.cancel()
        wasActive?.finished = true

        val queued = mutableListOf<PendingCommand>()
        while (true) queued.add(queue.poll() ?: break)
        for (command in queued) {
            command.queueDeadlineTask?.cancel()
            command.finished = true
        }

        // Set before the callbacks below, not after: one of them may itself call [onConnected]
        // (the class documentation suggests exactly that) and must see the requirement already
        // in place, under the epoch this teardown just bumped to, rather than finding a no-op.
        if (wasActive != null || wasQuiescing) {
            resyncRequired = true
        }

        wasActive?.let {
            it.callback(CxCommandResult(outcome, null, cappedPartial(it)))
        }
        for (command in queued) command.callback(CxCommandResult(outcome, null))

        tearingDownAs = null
    }

    private fun enqueue(pending: PendingCommand, atFront: Boolean) {
        if (atFront) {
            queue.addFirst(pending)
        } else {
            queue.addLast(pending)
            pending.queueDeadlineTask = clock.schedule(pending.deadlineMs) { onQueueDeadline(pending) }
        }
        dispatchNext()
    }

    private fun onQueueDeadline(pending: PendingCommand) {
        synchronized(this) {
            if (pending.finished) return
            if (!queue.remove(pending)) return
            pending.finished = true
            pending.callback(CxCommandResult(CxCommandOutcome.TIMEOUT, null))
        }
    }

    private fun dispatchNext() {
        if (active != null || quiescing) return
        val next = queue.peekFirst() ?: return
        if (resyncRequired && !next.isResync) return
        queue.pollFirst()
        next.queueDeadlineTask?.cancel()
        active = next
        if (!guard(next.command)) {
            finish(next, CxCommandResult(CxCommandOutcome.REJECTED_BY_GUARD, null))
            return
        }
        val wrote = transport.write("${next.command}\r".toByteArray(Charsets.US_ASCII))
        if (!wrote) {
            finish(next, CxCommandResult(CxCommandOutcome.WRITE_FAILED, null))
            return
        }
        val remaining = (next.deadlineMs - (clock.now() - next.enqueuedAtMs)).coerceAtLeast(0L)
        next.responseTimeoutTask = clock.schedule(remaining) { onTimeout(next) }
    }

    private fun onTimeout(command: PendingCommand) {
        synchronized(this) {
            if (active !== command || command.finished) return
            failWrittenCommand(command, CxCommandOutcome.TIMEOUT)
        }
    }

    /** Common path for every outcome of a command that was actually written to the adapter and
     * therefore might still get a response in flight: finishes it and requires a resync before
     * any further regular command. A failing *regular* command starts the quiet-wait-then-resync
     * cycle (the connection is still up — this is not a disconnect); a failing *resync step* does
     * not restart it — a resync step that itself fails leaves the session blocked for whoever
     * owns the connection to decide (typically a disconnect), rather than retrying the same
     * resync forever on its own. The epoch is re-checked after [finish] returns: a callback
     * invoked from it may itself have called [cancel] or [onDisconnected] (typically to escalate
     * right after this very failure), and that teardown already requires its own resync through
     * [onConnected] — starting the quiet wait here too would race whatever it just tore down. */
    private fun failWrittenCommand(command: PendingCommand, outcome: CxCommandOutcome) {
        resyncRequired = true
        val epochBeforeCallback = epoch
        finish(command, CxCommandResult(outcome, null, cappedPartial(command)), dispatch = false)
        // The callback just invoked by `finish` may itself have torn the connection down (epoch
        // changed) or already started its own resync attempt (e.g. by calling `onConnected`
        // directly) — either way, starting one here too would stomp on it instead of leaving it
        // idempotently alone.
        if (!command.isResync && epoch == epochBeforeCallback && !resyncAttemptInProgress) enterQuietPeriod()
    }

    private fun cappedPartial(command: PendingCommand): List<Byte> {
        val raw = command.buffer.toByteArray()
        return raw.copyOf(minOf(raw.size, maxResponseBytes)).toList()
    }

    private fun finish(command: PendingCommand, result: CxCommandResult, dispatch: Boolean = true) {
        if (command.finished) return
        command.finished = true
        command.responseTimeoutTask?.cancel()
        if (active === command) active = null
        command.callback(result)
        if (dispatch) dispatchNext()
    }

    private fun enterQuietPeriod() {
        quiescing = true
        resyncAttemptInProgress = true
        val myEpoch = epoch
        quietOverallDeadlineTask?.cancel()
        quietOverallDeadlineTask = clock.schedule(maxQuietPeriodMs) { onQuietPeriodOverallDeadline(myEpoch) }
        restartQuietTimer()
    }

    private fun restartQuietTimer() {
        quietTask?.cancel()
        val myEpoch = epoch
        quietTask = clock.schedule(quietPeriodMs) { onQuietPeriodElapsed(myEpoch) }
    }

    private fun onQuietPeriodElapsed(myEpoch: Long) {
        synchronized(this) {
            if (myEpoch != epoch || !quiescing) return
            quiescing = false
            quietTask = null
            quietOverallDeadlineTask?.cancel()
            quietOverallDeadlineTask = null
            beginResyncIfConfigured(myEpoch)
        }
    }

    /** The adapter never went quiet for the full resync attempt; no step is ever written for
     * this attempt, and [resyncRequired] stays set for the next [onConnected] to try again. */
    private fun onQuietPeriodOverallDeadline(myEpoch: Long) {
        synchronized(this) {
            if (myEpoch != epoch || !quiescing) return
            quiescing = false
            resyncAttemptInProgress = false
            quietTask?.cancel()
            quietTask = null
            quietOverallDeadlineTask = null
            resyncStepListener?.invoke(CxCommandResult(CxCommandOutcome.QUIET_PERIOD_EXPIRED, null))
        }
    }

    private fun beginResyncIfConfigured(myEpoch: Long) {
        if (resyncSteps.isEmpty()) {
            resyncAttemptInProgress = false
            resyncStepListener?.invoke(CxCommandResult(CxCommandOutcome.RESYNC_NOT_CONFIGURED, null))
            return
        }
        runResyncStep(ResyncRun(myEpoch, resyncSteps, resyncStepDeadlineMs, resyncStepListener), 0)
    }

    private fun runResyncStep(run: ResyncRun, index: Int) {
        if (index >= run.steps.size) {
            resyncAttemptInProgress = false
            resyncRequired = false
            dispatchNext()
            return
        }
        val step = run.steps[index]
        val pending = PendingCommand(step.command, run.stepDeadlineMs, clock.now(), isResync = true) { result ->
            onResyncStepResult(run, step, index, result)
        }
        enqueue(pending, atFront = true)
    }

    private fun onResyncStepResult(run: ResyncRun, step: CxResyncStep, index: Int, result: CxCommandResult) {
        val verified = if (result.outcome == CxCommandOutcome.OK && !step.isAcceptableResponse(result.responseText ?: "")) {
            result.copy(outcome = CxCommandOutcome.UNEXPECTED_RESPONSE)
        } else {
            result
        }
        // Reset before invoking the listener, not after: a listener that reacts to a failed step
        // by calling [onConnected] right away must not find this still `true` and be silently
        // ignored as "an attempt is already running". A teardown in between already reset it.
        if (verified.outcome != CxCommandOutcome.OK && run.epoch == epoch) {
            resyncAttemptInProgress = false
        }
        run.listener?.invoke(verified)
        if (run.epoch != epoch || verified.outcome != CxCommandOutcome.OK) return
        runResyncStep(run, index + 1)
    }

    companion object {
        /** A starting value for the response-size limit; the actual limit always comes from the
         * caller's plan. */
        const val DEFAULT_MAX_RESPONSE_BYTES = 4096

        /** A starting value for how long the adapter must stay silent before a resync step is
         * actually written, not yet measured against the real adapter. */
        const val DEFAULT_QUIET_PERIOD_MS = 300L

        /** A starting value for the longest a resync attempt waits for that silence at all
         * before giving up on this attempt rather than waiting forever on an adapter that never
         * stops talking. */
        const val DEFAULT_MAX_QUIET_PERIOD_MS = 5_000L
    }
}

/** Splits [rawBeforePrompt] into trimmed, non-empty lines on `\r`, drops the first line if it is
 * exactly [sentCommand] (the adapter's echo of what was sent, present unless `ATE0` already
 * disabled it), and joins what remains with `\n`. */
private fun decodeFrame(rawBeforePrompt: ByteArray, sentCommand: String): String {
    val lines = String(rawBeforePrompt, Charsets.US_ASCII)
        .split("\r")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val withoutEcho = if (lines.isNotEmpty() && lines.first().equals(sentCommand, ignoreCase = true)) {
        lines.drop(1)
    } else {
        lines
    }
    return withoutEcho.joinToString("\n")
}
