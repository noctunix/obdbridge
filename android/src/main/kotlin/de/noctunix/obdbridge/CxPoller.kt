package de.noctunix.obdbridge

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/** One completed poll attempt, success or failure alike — what [CxPoller.sink] receives, and
 * what [CxRecordingService] turns into a [CxBufferWriter.appendExchange] call. [rawBytes] is
 * [CxCommandResult.rawBytes] verbatim: the complete frame before the prompt on
 * [ObdTransportStatusMessage.OK], or whatever partial bytes had already arrived for a
 * timeout/overflow/disconnect/cancellation, empty for every outcome that never reached the wire
 * at all. [responseText] is the already-decoded companion carried alongside for the live view,
 * which caches text, not bytes — a buffer writer must use [rawBytes], never re-encode this. */
@Suppress("ArrayInDataClass")
data class CxPollExchange(
    val signalId: String,
    val transport: ObdTransportStatusMessage,
    /** Set only when [transport] is [ObdTransportStatusMessage.OK]. */
    val responseText: String?,
    val rawBytes: ByteArray,
    val requestedAtEpochMs: Long,
    val receivedAtEpochMs: Long,
    val monotonicElapsedNs: Long,
    val durationNs: Long,
)

/**
 * Repeatedly issues every step of a compiled poll plan, respecting each step's own `intervalMs`
 * and the plan's global `maxReadsPerSecond`, and re-running a module's setup via [modules]
 * whenever the step about to run addresses a different module than the last one actually
 * written — an ELM327/STN adapter has exactly one selected module at a time; nothing else here
 * ever switches it back. Exactly one poll is ever in flight at a time (a single scheduling loop,
 * not one timer per step), which is what makes the "which module is active" bookkeeping safe
 * without its own lock. [stop] cancels the next scheduled poll and makes any later in-flight
 * result for this instance a no-op.
 *
 * Shared, unmodified, between the live view ([ObdApi.startLive]) and a recording
 * ([CxRecordingService]) — both need the identical compiled-plan-at-a-cadence behavior; only
 * their lifecycle differs (a live poller ends with the Flutter page that started it, a recording
 * poller outlives the whole Flutter engine), which this class has no opinion on at all. The
 * optional [sink]/[onConsecutiveFailuresExceeded]/[onIdleVehicleDetected] parameters exist for
 * the recording's extra requirements (buffering every exchange, ending on a connection that
 * stopped answering, recognizing a parked vehicle) and are simply left `null` by the live view.
 */
class CxPoller(
    private val session: CxCommandSession,
    private val clock: CxCommandClock,
    private val modules: Map<String, ObdModuleMessage>,
    private val deadlineMs: Long,
    private val setupDeadlineMs: Long,
    maxReadsPerSecond: Int,
    /** Zero point [CxPollExchange.monotonicElapsedNs] values are relative to — the connection's
     * own `connect()` success instant, shared with [ObdLiveStateMessage.timebaseId] the same way
     * the whole product's monotonic timestamps pair with a `timebaseId`. */
    private val monotonicBaseNs: Long,
    /** Consecutive failed vehicle-read attempts before [onConsecutiveFailuresExceeded] fires —
     * the first failure already triggers [CxCommandSession]'s own resync; this is the budget for
     * a second one right after. [Int.MAX_VALUE] (the default) disables the check outright. */
    private val maxConsecutiveFailures: Int = Int.MAX_VALUE,
    /** Stop and call [onIdleVehicleDetected] once every step has answered `NO DATA` for this
     * long — a parked vehicle whose bus went quiet, not a transport failure.
     * [Long.MAX_VALUE] (the default) disables the check. */
    private val idleVehicleStopAfterMs: Long = Long.MAX_VALUE,
    /** `null` disables the orphan watchdog entirely — a recording has no "is anyone still
     * asking" signal to measure itself against, unlike the live view's [ObdApi.liveState]. */
    private val lastQueryAtMsProvider: (() -> Long?)? = null,
    private val watchdogTimeoutMs: Long = 30_000L,
    private val onOrphaned: (() -> Unit)? = null,
    private val onConsecutiveFailuresExceeded: (() -> Unit)? = null,
    private val onIdleVehicleDetected: (() -> Unit)? = null,
    private val sink: ((CxPollExchange) -> Unit)? = null,
) {
    /** [lastRunAtMs] (`-1` until the first run) breaks ties in [scheduleNext] between two entries
     * whose effective due time (after the global rate-limit floor) comes out equal — without it,
     * whichever entry happens first in [entries] would always win every such tie, starving every
     * other entry forever once the global limit makes every due time converge on the same floor. */
    private class Entry(val step: ObdStepMessage, var nextDueAtMs: Long, var lastRunAtMs: Long = -1L)

    /** The latest *successful* ([ObdTransportStatusMessage.OK]) exchange per signal — kept
     * separate from [latestExchanges] so a single transient transport failure never makes an
     * otherwise-fresh value disappear; [ObdApi.liveState] prefers this map and only falls back to
     * [latestExchanges] for a signal that has never once succeeded. */
    val latestSuccessful = ConcurrentHashMap<String, ObdSignalExchangeMessage>()
    val latestExchanges = ConcurrentHashMap<String, ObdSignalExchangeMessage>()

    /** Minimum spacing enforced between any two issued vehicle reads, regardless of which signal
     * — the plan's rate budget is across the whole plan, not per step. */
    private val minGlobalIntervalMs = (1000L / maxReadsPerSecond.coerceAtLeast(1))
    private var lastIssuedAtMs: Long? = null

    private var consecutiveFailures = 0
    private val noDataFlags = ConcurrentHashMap<String, Boolean>()
    private var allNoDataSinceMs: Long? = null

    @Volatile
    private var stopped = false
    private var entries: List<Entry> = emptyList()
    private var activeModuleId: String? = null
    private var scheduledTask: CxScheduledTask? = null

    fun start(steps: List<ObdStepMessage>) {
        val now = clock.now()
        entries = steps.map { Entry(it, now) }
        scheduleNext()
    }

    fun stop() {
        stopped = true
        scheduledTask?.cancel()
        scheduledTask = null
    }

    /** Called after a resync attempt (success or failure) — a resync wipes the adapter's module
     * selection (`ATZ` resets the whole adapter), so every poller sharing this connection must
     * forget which module it last addressed, successful resync or not. */
    fun resetActiveModule() {
        activeModuleId = null
    }

    private fun scheduleNext() {
        if (stopped || entries.isEmpty()) return
        val earliestAllowedMs = lastIssuedAtMs?.let { it + minGlobalIntervalMs } ?: 0L
        val next = entries.minWithOrNull(
            compareBy({ maxOf(it.nextDueAtMs, earliestAllowedMs) }, { it.lastRunAtMs }),
        ) ?: return
        val dueAtMs = maxOf(next.nextDueAtMs, earliestAllowedMs)
        val delay = (dueAtMs - clock.now()).coerceAtLeast(0L)
        if (delay <= 0L) {
            scheduledTask = null
            runEntry(next)
        } else {
            scheduledTask = clock.schedule(delay) { runEntry(next) }
        }
    }

    private fun runEntry(entry: Entry) {
        if (stopped) return
        val lastQuery = lastQueryAtMsProvider?.invoke()
        if (lastQuery != null && clock.now() - lastQuery > watchdogTimeoutMs) {
            stop()
            onOrphaned?.invoke()
            return
        }
        val module = modules[entry.step.moduleId]
        if (module != null && activeModuleId != module.moduleId) {
            runModuleSetup(module) { ok ->
                if (stopped) return@runModuleSetup
                // On failure, `activeModuleId` must not keep its *previous* value either — the
                // adapter is now left half-switched (some of this module's setup commands may
                // already have gone out), so neither the old nor the new module can be assumed
                // selected; the next poll re-sends setup for whatever module it needs next.
                activeModuleId = if (ok) module.moduleId else null
                if (ok) {
                    pollStep(entry)
                } else {
                    // A failed module-setup step typically means the adapter is already mid-resync
                    // (a `TIMEOUT` requires one) — issuing the read anyway would queue it behind
                    // that resync and let it go out *after* `ATZ` resets everything to the default
                    // header, i.e. as a functional broadcast to the whole bus, not the physically
                    // addressed module this plan compiled. Recording a failure and waiting for the
                    // next cycle is the only safe choice here.
                    recordFailure(entry, ObdTransportStatusMessage.WRITE_FAILED)
                }
            }
        } else {
            pollStep(entry)
        }
    }

    private fun runModuleSetup(module: ObdModuleMessage, onDone: (Boolean) -> Unit) {
        fun runNext(index: Int) {
            if (index >= module.setup.size) {
                onDone(true)
                return
            }
            session.execute(module.setup[index], setupDeadlineMs) { result ->
                if (stopped) return@execute
                if (result.outcome == CxCommandOutcome.OK && result.responseText == "OK") {
                    runNext(index + 1)
                } else {
                    onDone(false)
                }
            }
        }
        runNext(0)
    }

    private fun pollStep(entry: Entry) {
        if (stopped) return
        val requestedAtEpochMs = System.currentTimeMillis()
        val startNanos = SystemClock.elapsedRealtimeNanos()
        lastIssuedAtMs = clock.now()
        entry.lastRunAtMs = clock.now()
        session.execute(entry.step.command, deadlineMs) { result ->
            if (stopped) return@execute
            val receivedAtEpochMs = System.currentTimeMillis()
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val monotonicElapsedNs = nowNanos - monotonicBaseNs
            val durationNs = nowNanos - startNanos
            val transport = toTransportStatusMessage(result.outcome)
            val exchange = ObdSignalExchangeMessage(
                signalId = entry.step.signalId,
                transport = transport,
                rawResponseText = result.responseText,
                requestedAtEpochMs = requestedAtEpochMs,
                receivedAtEpochMs = receivedAtEpochMs,
                monotonicElapsedNs = monotonicElapsedNs,
            )
            latestExchanges[entry.step.signalId] = exchange
            // Reported to the sink before any of the bookkeeping below that can itself end the
            // recording (a consecutive-failure or idle-vehicle callback can run synchronously
            // out of `onFailure()`/`updateIdleVehicleTracking()`) — this exact exchange is what
            // caused that outcome, and it must land in the buffer before the writer a reaction to
            // it might close, not after.
            sink?.invoke(
                CxPollExchange(
                    signalId = entry.step.signalId,
                    transport = transport,
                    responseText = result.responseText,
                    rawBytes = result.rawBytes.toByteArray(),
                    requestedAtEpochMs = requestedAtEpochMs,
                    receivedAtEpochMs = receivedAtEpochMs,
                    monotonicElapsedNs = monotonicElapsedNs,
                    durationNs = durationNs,
                ),
            )
            if (transport == ObdTransportStatusMessage.OK) {
                latestSuccessful[entry.step.signalId] = exchange
                consecutiveFailures = 0
                noDataFlags[entry.step.signalId] = (result.responseText == "NO DATA")
                updateIdleVehicleTracking()
            } else {
                // A TIMEOUT/OVERFLOW here starts `CxCommandSession`'s own quiet-wait-then-resync
                // cycle *immediately* (well before this poller's own next scheduled poll), which
                // ends by wiping the adapter's module selection (`ATZ`...). `activeModuleId` must
                // not keep claiming a module is still selected once that is possible — otherwise
                // the next step addressing the *same* module would skip setup entirely and its
                // already-dispatched read would go out as a bare `22xxxx` under whatever the
                // adapter resets to (`ATSP0` auto-detect, a functional broadcast header), not the
                // physically addressed module this plan compiled.
                activeModuleId = null
                onFailure()
            }
            entry.nextDueAtMs = clock.now() + entry.step.intervalMs
            if (!stopped) scheduleNext()
        }
    }

    /** Records [transport] as this [entry]'s latest attempt without ever writing to the bus —
     * used when a module-setup failure already makes the vehicle read itself unsafe to send
     * (see [runEntry]'s own doc comment on that case). */
    private fun recordFailure(entry: Entry, transport: ObdTransportStatusMessage) {
        val nowEpochMs = System.currentTimeMillis()
        val monotonicElapsedNs = SystemClock.elapsedRealtimeNanos() - monotonicBaseNs
        latestExchanges[entry.step.signalId] = ObdSignalExchangeMessage(
            signalId = entry.step.signalId,
            transport = transport,
            receivedAtEpochMs = nowEpochMs,
            monotonicElapsedNs = monotonicElapsedNs,
        )
        // Reported before `onFailure()`, the same order `pollStep` uses — a callback it
        // triggers can end the recording (and close its writer) before this exchange would
        // otherwise ever reach it.
        sink?.invoke(
            CxPollExchange(
                signalId = entry.step.signalId,
                transport = transport,
                responseText = null,
                rawBytes = ByteArray(0),
                requestedAtEpochMs = nowEpochMs,
                receivedAtEpochMs = nowEpochMs,
                monotonicElapsedNs = monotonicElapsedNs,
                durationNs = 0L,
            ),
        )
        onFailure()
        entry.nextDueAtMs = clock.now() + entry.step.intervalMs
        if (!stopped) scheduleNext()
    }

    /** Common bookkeeping for any non-OK poll attempt, module-setup failure included: counts
     * towards [maxConsecutiveFailures] and, once over it, stops this poller and reports out —
     * the connection itself is left for the caller to tear down or retry, this class only
     * notices and stops polling it. */
    private fun onFailure() {
        consecutiveFailures++
        allNoDataSinceMs = null
        if (consecutiveFailures >= maxConsecutiveFailures && !stopped) {
            stop()
            onConsecutiveFailuresExceeded?.invoke()
        }
    }

    private fun updateIdleVehicleTracking() {
        val allNoData = entries.isNotEmpty() && entries.all { noDataFlags[it.step.signalId] == true }
        if (!allNoData) {
            allNoDataSinceMs = null
            return
        }
        val since = allNoDataSinceMs
        if (since == null) {
            allNoDataSinceMs = clock.now()
            return
        }
        if (clock.now() - since >= idleVehicleStopAfterMs && !stopped) {
            stop()
            onIdleVehicleDetected?.invoke()
        }
    }

    private fun toTransportStatusMessage(outcome: CxCommandOutcome): ObdTransportStatusMessage = when (outcome) {
        CxCommandOutcome.OK -> ObdTransportStatusMessage.OK
        CxCommandOutcome.TIMEOUT -> ObdTransportStatusMessage.TIMEOUT
        CxCommandOutcome.OVERFLOW -> ObdTransportStatusMessage.OVERFLOW
        CxCommandOutcome.DISCONNECTED -> ObdTransportStatusMessage.DISCONNECTED
        CxCommandOutcome.REJECTED_BY_GUARD -> ObdTransportStatusMessage.REJECTED_BY_GUARD
        CxCommandOutcome.WRITE_FAILED -> ObdTransportStatusMessage.WRITE_FAILED
        CxCommandOutcome.CANCELLED -> ObdTransportStatusMessage.CANCELLED
        // A resync-specific outcome landing here would be a logic error elsewhere (pollStep only
        // ever executes plain vehicle-read steps, never a resync step) — collapsed to the closest
        // honest transport status rather than crashing on an exhaustive `when`.
        CxCommandOutcome.UNEXPECTED_RESPONSE,
        CxCommandOutcome.QUIET_PERIOD_EXPIRED,
        CxCommandOutcome.RESYNC_NOT_CONFIGURED,
        -> ObdTransportStatusMessage.TIMEOUT
    }
}
