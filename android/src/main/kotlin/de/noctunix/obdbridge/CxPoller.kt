package de.noctunix.obdbridge

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/** One completed poll attempt, success or failure alike — what [CxPoller.sink] receives, and
 * what [CxRecordingService] turns into a [CxBufferWriter.appendExchange] call. [rawBytes] is
 * [CxCommandResult.rawBytes] verbatim: the complete frame before the prompt on
 * [ObdTransportStatusMessage.OK], or whatever partial bytes had already arrived for a
 * timeout/overflow/disconnect/cancellation, empty for every outcome that never reached the wire
 * at all. [responseText] is the already-decoded companion carried alongside for the live view,
 * which caches text, not bytes — a buffer writer must use [rawBytes], never re-encode this.
 * [oneShot] is `true` for a step that came from [CxPoller.enqueueOneShot] rather than the
 * original plan's recurring steps — carried through to [CxBufferWriter.appendExchange]'s own
 * `oneShot` field and to [ObdEventsApi.onOneShotResult]. */
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
    val oneShot: Boolean = false,
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
 *
 * [enqueueOneShot] additionally lets a caller append plan-shaped, read-once work
 * ([ObdApi.enqueueOneShot] only ever calls this for a recording's poller, never the live view's)
 * that [scheduleNext] interleaves into whatever idle time appears between two due recurring
 * steps, at most one queued step per idle gap so a recurring step is never delayed by more than
 * one step's own duration.
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
    /** What happens once [maxConsecutiveFailures] is reached —
     * [ObdConsecutiveFailuresPolicyMessage.endRecording] (the default) still stops this poller
     * and fires [onConsecutiveFailuresExceeded] as before; [ObdConsecutiveFailuresPolicyMessage.resyncAndContinue]
     * instead fires it without stopping, so polling keeps running through whatever resync
     * [CxCommandSession] already has in progress from the failures that ran the counter up. */
    private val onConsecutiveFailuresPolicy: ObdConsecutiveFailuresPolicyMessage = ObdConsecutiveFailuresPolicyMessage.END_RECORDING,
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
    /** Upper bound on [oneShotQueueDepth] — [enqueueOneShot] rejects a batch outright, queuing
     * none of it, once accepting it would exceed this. */
    private val oneShotQueueCapacity: Int = DEFAULT_ONE_SHOT_QUEUE_CAPACITY,
    /** Once [QUIET_MODULE_NO_DATA_STREAK] recurring reads in a row on one module (or every one of
     * its steps, if it has fewer) answer `NO DATA`, that module's steps are held back for this
     * long, after which a single read probes whether it answers again. An ECU that went to sleep
     * (a parked or charging vehicle) otherwise costs a full adapter timeout per planned step.
     * `0` (the default) disables this. */
    private val quietModuleBackoffMs: Long = 0L,
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
    private val moduleNoDataStreaks = mutableMapOf<String, Int>()
    private val moduleQuietUntilMs = mutableMapOf<String, Long>()
    private val noDataFlags = ConcurrentHashMap<String, Boolean>()
    private var allNoDataSinceMs: Long? = null

    @Volatile
    private var stopped = false
    private var entries: List<Entry> = emptyList()
    private var activeModuleId: String? = null
    private var scheduledTask: CxScheduledTask? = null

    /** Steps queued by [enqueueOneShot], read exactly once each, in FIFO order — never one timer
     * per entry, the same single-scheduling-loop design [scheduleNext] already uses for
     * [entries]. */
    private val oneShotQueue = ArrayDeque<ObdStepMessage>()

    /** Modules [enqueueOneShot] introduced that were not already part of the original plan (or
     * that override one that was) — consulted by [moduleFor] alongside [modules] itself, which
     * stays whatever [start] was constructed with. */
    private val oneShotModuleOverrides = mutableMapOf<String, ObdModuleMessage>()

    /** Number of steps still waiting in [oneShotQueue] — what [CxRecordingService] reports as
     * [ObdRecordingStateMessage.oneShotQueueDepth]. */
    val oneShotQueueDepth: Int get() = oneShotQueue.size

    fun start(steps: List<ObdStepMessage>) {
        val now = clock.now()
        entries = steps.map { Entry(it, now) }
        scheduleNext()
    }

    fun stop() {
        stopped = true
        scheduledTask?.cancel()
        scheduledTask = null
        // A stopped poller never runs anything queued here again — see CxRecordingService's own
        // "queue cleared when the recording ends" requirement. Every step still waiting here was
        // already accepted by a prior enqueueOneShot call, so dropping it silently would break
        // that call's "exactly one result event" contract just as much as never reporting it at
        // all would.
        cancelQueuedOneShots()
    }

    /** Appends [steps] to [oneShotQueue], merging [modules] into [oneShotModuleOverrides] first
     * so a step addressing a module this poller does not already know the setup for (or one
     * whose setup the caller wants to override) still resolves via [moduleFor]. Rejects the
     * whole batch, queuing none of it, once `oneShotQueue.size + steps.size` would exceed
     * [oneShotQueueCapacity] — never a partial enqueue, never a silent drop. A call after [stop]
     * is also rejected this way: nothing is left running here to ever read it back out. */
    fun enqueueOneShot(modules: List<ObdModuleMessage>, steps: List<ObdStepMessage>): ObdOneShotEnqueueResultMessage {
        if (stopped || oneShotQueue.size + steps.size > oneShotQueueCapacity) {
            return ObdOneShotEnqueueResultMessage.QUEUE_FULL
        }
        modules.forEach { oneShotModuleOverrides[it.moduleId] = it }
        oneShotQueue.addAll(steps)
        // Picks the new work up immediately, in whatever idle time is available right now,
        // rather than waiting for a scheduled wake-up this enqueue happened to arrive before.
        scheduledTask?.cancel()
        scheduledTask = null
        scheduleNext()
        return ObdOneShotEnqueueResultMessage.ACCEPTED
    }

    /** Called after a resync attempt (success or failure) — a resync wipes the adapter's module
     * selection (`ATZ` resets the whole adapter), so every poller sharing this connection must
     * forget which module it last addressed, successful resync or not. */
    fun resetActiveModule() {
        activeModuleId = null
    }

    /** The one scheduling decision this whole class makes: whether to run a due recurring entry,
     * spend idle time on a queued one-shot step, or merely arm a wake-up for whichever of those
     * becomes possible first. Always recomputed from scratch rather than trusting whatever an
     * earlier call decided — the only way [enqueueOneShot] can make freshly queued work run in
     * an idle gap that was already being waited out, and the only way a recurring step that
     * becomes due while a one-shot is running gets picked up the instant that one-shot's own
     * callback calls back in here, rather than only once the one-shot's own, unrelated interval
     * would have fired anyway. */
    private fun scheduleNext() {
        if (stopped) return
        val earliestAllowedMs = lastIssuedAtMs?.let { it + minGlobalIntervalMs } ?: 0L
        val now = clock.now()
        fun dueAtMs(entry: Entry) = maxOf(
            entry.nextDueAtMs,
            earliestAllowedMs,
            moduleQuietUntilMs[entry.step.moduleId] ?: 0L,
        )
        val next = entries.minWithOrNull(compareBy({ dueAtMs(it) }, { it.lastRunAtMs }))
        val recurringDueAtMs = next?.let { dueAtMs(it) }
        if (recurringDueAtMs != null && recurringDueAtMs <= now) {
            scheduledTask = null
            runEntry(next!!)
            return
        }
        if (oneShotQueue.isNotEmpty() && now >= earliestAllowedMs) {
            // Idle time before the next due recurring step (or no recurring step at all) —
            // spend exactly one of it on a queued one-shot, then let the next `scheduleNext()`
            // call (from that step's own completion callback) re-check whether a recurring step
            // has since become due, rather than draining the whole queue in one go.
            scheduledTask = null
            runOneShot(oneShotQueue.removeFirst())
            return
        }
        val wakeAtMs = listOfNotNull(
            recurringDueAtMs,
            if (oneShotQueue.isNotEmpty()) maxOf(earliestAllowedMs, now) else null,
        ).minOrNull() ?: return
        scheduledTask = clock.schedule((wakeAtMs - now).coerceAtLeast(0L)) { scheduleNext() }
    }

    private fun runEntry(entry: Entry) {
        if (stopped) return
        val lastQuery = lastQueryAtMsProvider?.invoke()
        if (lastQuery != null && clock.now() - lastQuery > watchdogTimeoutMs) {
            stop()
            onOrphaned?.invoke()
            return
        }
        ensureModuleSelected(entry.step.moduleId) { ok ->
            if (stopped) return@ensureModuleSelected
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
    }

    private fun runOneShot(step: ObdStepMessage) {
        if (stopped) return
        ensureModuleSelected(step.moduleId) { ok ->
            if (stopped) return@ensureModuleSelected
            if (ok) pollOneShot(step) else reportOneShotFailure(step, ObdTransportStatusMessage.WRITE_FAILED)
        }
    }

    /** [moduleFor]'s module (the given or already-planned one — see [enqueueOneShot]'s own doc
     * comment) is re-selected via [runModuleSetup] only if it is not already the adapter's
     * active module; shared by [runEntry] and [runOneShot] since a one-shot step addresses its
     * module exactly like a planned step does and must go through the identical
     * switch-then-run sequence, satisfying "a protocol switch between modules is handled like
     * planned modules" for one-shot steps too. */
    private fun ensureModuleSelected(moduleId: String, onDone: (Boolean) -> Unit) {
        val module = moduleFor(moduleId)
        if (module != null && activeModuleId != module.moduleId) {
            runModuleSetup(module) { ok ->
                if (stopped) return@runModuleSetup
                // On failure, `activeModuleId` must not keep its *previous* value either — the
                // adapter is now left half-switched (some of this module's setup commands may
                // already have gone out), so neither the old nor the new module can be assumed
                // selected; the next poll re-sends setup for whatever module it needs next.
                activeModuleId = if (ok) module.moduleId else null
                onDone(ok)
            }
        } else {
            onDone(true)
        }
    }

    private fun moduleFor(moduleId: String): ObdModuleMessage? = oneShotModuleOverrides[moduleId] ?: modules[moduleId]

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
                updateQuietModuleTracking(entry.step.moduleId, result.responseText == "NO DATA")
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

    /** [runOneShot]'s counterpart to [pollStep]: issues [step]'s command exactly once —
     * [ObdStepMessage.intervalMs] is never read, there is no `nextDueAtMs` to update, the step
     * is simply gone once this attempt resolves. A one-shot read counts towards
     * [maxConsecutiveFailures] on failure like any other vehicle read (it says exactly as much
     * about whether the connection is still trustworthy), but deliberately never towards
     * [noDataFlags]/[updateIdleVehicleTracking] — "every *planned* signal reads NO DATA" is a
     * statement about a parked vehicle's bus, which an ad hoc one-shot query has no bearing on
     * either way. */
    private fun pollOneShot(step: ObdStepMessage) {
        if (stopped) return
        val requestedAtEpochMs = System.currentTimeMillis()
        val startNanos = SystemClock.elapsedRealtimeNanos()
        lastIssuedAtMs = clock.now()
        session.execute(step.command, deadlineMs) { result ->
            if (stopped) return@execute
            val receivedAtEpochMs = System.currentTimeMillis()
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val monotonicElapsedNs = nowNanos - monotonicBaseNs
            val durationNs = nowNanos - startNanos
            val transport = toTransportStatusMessage(result.outcome)
            // Reported to the sink before the bookkeeping below, the same ordering `pollStep`
            // uses and for the same reason: a reaction to `onFailure()` below can itself end the
            // recording, and this exchange must already be in the buffer by then.
            sink?.invoke(
                CxPollExchange(
                    signalId = step.signalId,
                    transport = transport,
                    responseText = result.responseText,
                    rawBytes = result.rawBytes.toByteArray(),
                    requestedAtEpochMs = requestedAtEpochMs,
                    receivedAtEpochMs = receivedAtEpochMs,
                    monotonicElapsedNs = monotonicElapsedNs,
                    durationNs = durationNs,
                    oneShot = true,
                ),
            )
            if (transport == ObdTransportStatusMessage.OK) {
                consecutiveFailures = 0
            } else {
                // Same reasoning as `pollStep`'s own branch: a resync this failure can trigger
                // wipes the adapter's module selection.
                activeModuleId = null
                onFailure()
            }
            if (!stopped) scheduleNext()
        }
    }

    /** [runOneShot]'s counterpart to [recordFailure] — a one-shot step's module-setup failure,
     * reported the same way: no write to the bus, no `nextDueAtMs` bookkeeping (there is none
     * for a one-shot step), the step is simply gone either way. */
    private fun reportOneShotFailure(step: ObdStepMessage, transport: ObdTransportStatusMessage) {
        val nowEpochMs = System.currentTimeMillis()
        val monotonicElapsedNs = SystemClock.elapsedRealtimeNanos() - monotonicBaseNs
        sink?.invoke(
            CxPollExchange(
                signalId = step.signalId,
                transport = transport,
                responseText = null,
                rawBytes = ByteArray(0),
                requestedAtEpochMs = nowEpochMs,
                receivedAtEpochMs = nowEpochMs,
                monotonicElapsedNs = monotonicElapsedNs,
                durationNs = 0L,
                oneShot = true,
            ),
        )
        onFailure()
        if (!stopped) scheduleNext()
    }

    /** Reports every step still waiting in [oneShotQueue] as [ObdTransportStatusMessage.CANCELLED]
     * (no raw data, [CxPollExchange.oneShot] `true`) and empties it — called from [stop], the one
     * place that ever discards queued one-shot work outright, so a step [enqueueOneShot] already
     * accepted never simply vanishes regardless of *why* this poller stopped (a caller-requested
     * stop, the recording ending, an idle-vehicle or consecutive-failure callback that itself
     * calls [stop]). */
    private fun cancelQueuedOneShots() {
        while (oneShotQueue.isNotEmpty()) {
            val step = oneShotQueue.removeFirst()
            val nowEpochMs = System.currentTimeMillis()
            val monotonicElapsedNs = SystemClock.elapsedRealtimeNanos() - monotonicBaseNs
            sink?.invoke(
                CxPollExchange(
                    signalId = step.signalId,
                    transport = ObdTransportStatusMessage.CANCELLED,
                    responseText = null,
                    rawBytes = ByteArray(0),
                    requestedAtEpochMs = nowEpochMs,
                    receivedAtEpochMs = nowEpochMs,
                    monotonicElapsedNs = monotonicElapsedNs,
                    durationNs = 0L,
                    oneShot = true,
                ),
            )
        }
    }

    /** Common bookkeeping for any non-OK poll attempt, module-setup failure included: counts
     * towards [maxConsecutiveFailures] and, once over it, reports out — the connection itself is
     * left for the caller to tear down or retry, this class only notices. Under
     * [ObdConsecutiveFailuresPolicyMessage.endRecording] that also stops this poller, same as
     * before; under [ObdConsecutiveFailuresPolicyMessage.resyncAndContinue] the counter merely
     * resets and polling keeps running — [CxCommandSession] already started its own resync off
     * the first of these failures, long before this threshold was even reached. */
    private fun onFailure() {
        consecutiveFailures++
        allNoDataSinceMs = null
        if (consecutiveFailures >= maxConsecutiveFailures && !stopped) {
            consecutiveFailures = 0
            if (onConsecutiveFailuresPolicy == ObdConsecutiveFailuresPolicyMessage.RESYNC_AND_CONTINUE) {
                onConsecutiveFailuresExceeded?.invoke()
            } else {
                stop()
                onConsecutiveFailuresExceeded?.invoke()
            }
        }
    }

    private fun updateQuietModuleTracking(moduleId: String, noData: Boolean) {
        if (quietModuleBackoffMs <= 0L) return
        if (!noData) {
            moduleNoDataStreaks.remove(moduleId)
            moduleQuietUntilMs.remove(moduleId)
            return
        }
        val streak = (moduleNoDataStreaks[moduleId] ?: 0) + 1
        moduleNoDataStreaks[moduleId] = streak
        val moduleStepCount = entries.count { it.step.moduleId == moduleId }
        if (streak >= minOf(QUIET_MODULE_NO_DATA_STREAK, moduleStepCount)) {
            moduleQuietUntilMs[moduleId] = clock.now() + quietModuleBackoffMs
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

    companion object {
        const val DEFAULT_ONE_SHOT_QUEUE_CAPACITY = 4096
        const val QUIET_MODULE_NO_DATA_STREAK = 3
    }
}
