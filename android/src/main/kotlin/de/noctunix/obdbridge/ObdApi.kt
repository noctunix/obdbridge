package de.noctunix.obdbridge

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * One connection's owned resources: the native monotonic clock's zero point and identity for
 * [ObdLiveStateMessage.timebaseId], and the poller actually issuing [ObdPlanMessage] steps once
 * [ObdApi.startLive] runs. Recreated on every [ObdApi.connect]; [ObdApi] itself is the one
 * long-lived instance shared across Flutter pages.
 */
private class ObdSession(val timebaseId: String) {
    val monotonicBaseNs: Long = SystemClock.elapsedRealtimeNanos()
    var poller: CxPoller? = null

    /** Set while [ObdApi.startLive]'s init/module-setup chain is running, cleared once it
     * resolves either way — guards against a second [ObdApi.startLive] call starting a second
     * [poller] before the first one has even finished its setup (at which point [poller] itself
     * is still `null`). */
    var startingLive = false

    /** Last time [ObdApi.liveState] was actually asked about this session, including the moment
     * [poller] itself started (a Flutter page that starts polling and then never asks about it
     * again is exactly as orphaned as one that never gets the chance to ask at all). [poller]'s
     * own watchdog uses this to notice nobody is listening any more — see its doc comment. */
    var lastLiveStateQueryAtMs: Long? = null
}

/**
 * Native [NativeObdApi] implementation. Owns one [CxBleClient] for the whole app process —
 * [ObdbridgePlugin] holds a single instance rather than constructing one per Flutter
 * engine/page, so a live view and a later recording never fight over two GATT clients.
 * [HardGuard] still runs inside [CxCommandSession] on every write, unconditionally, as the
 * native structural check beneath the caller's own [CommandPolicy] — nothing here bypasses it.
 *
 * [commandSession] is recreated by every [startLive] call rather than built once, because
 * [CxCommandSession.DEFAULT_MAX_RESPONSE_BYTES] is a constructor-time constant and each plan
 * carries its own [ObdLimitsMessage.maxResponseBytes] — a plan's limits must actually reach the
 * session that enforces response size, not just exist in the message.
 *
 * Native knowledge stays limited to running the already-compiled plan's literal commands at
 * their own cadence and caching the latest raw response per signal. It never decodes a response,
 * never knows a DID's meaning, and never invents its own command.
 *
 * Every host method resolves through its own [Result]-based callback rather than blocking the
 * calling thread, including the init/module setup chain inside [startLive] — a real connection's
 * command responses only arrive asynchronously, from a GATT notification callback that itself
 * needs the same thread this method was invoked on, so blocking here would deadlock against it.
 */
class ObdApi
internal constructor(
    private val bleClient: CxBleClient,
    private val clock: CxCommandClock,
    /** `null` in every unit test construction (`ObdApiTest.kt` never supplies one) — only used
     * to start [CxRecordingService], so [startRecording] simply reports failure without one
     * rather than needing a fake `Context` wired through every other test. */
    private val appContext: Context? = null,
) : NativeObdApi {

    constructor(context: Context) : this(
        bleClient = CxBleClient(AndroidCxBleIo(context)),
        clock = AndroidCxCommandClock(),
        appContext = context.applicationContext,
    )

    private var commandSession = buildCommandSession(CxCommandSession.DEFAULT_MAX_RESPONSE_BYTES)

    private fun buildCommandSession(maxResponseBytes: Int) = CxCommandSession(
        transport = object : CxCommandTransport {
            override fun write(bytes: ByteArray): Boolean = bleClient.write(bytes)
        },
        clock = clock,
        maxResponseBytes = maxResponseBytes,
    )

    /** Replaces [commandSession] with a freshly built instance, then neutralizes the one it
     * replaced. The new instance is assigned *before* the old one is cancelled on purpose: a
     * resync step [cancel] interrupts can synchronously invoke that step's own listener, and that
     * listener (see [onResyncStepResult]) tells a self-inflicted cancellation apart from a real
     * failure by checking whether [commandSession] still points at the session it was configured
     * against — a check this ordering is what makes reliable, since the old session is never
     * current by the time its own `cancel` callback runs. Every [CxCommandSession] instance's own
     * `transport` writes straight to the shared [bleClient], independent of whether
     * [commandSession] still points at that instance — a quiet-period/response-timeout task it
     * already scheduled on [clock] keeps running and keeps the ability to write even after this
     * method returns, unless [CxCommandSession.cancel] cancels those tasks outright, which it
     * does. */
    private fun retireCommandSession(maxResponseBytes: Int) {
        val previous = commandSession
        commandSession = buildCommandSession(maxResponseBytes)
        previous.cancel()
    }

    private var eventsApi: ObdEventsApi? = null
    private var session: ObdSession? = null

    private var pendingConnectResolve: ((CxLinkState) -> Unit)? = null
    private var pendingScanResolve: (() -> Unit)? = null
    private val scanResults = mutableListOf<ObdAdapterMessage>()
    private val scanSeenHandles = mutableSetOf<String>()

    /** Set by [startRecording], consumed exactly once by [CxRecordingService.onStartCommand]:
     * Pigeon delivers the request to this process-wide instance, the service (started right
     * after) picks it back up once it exists to actually call `startForeground` on. */
    private val recordingStartLock = Any()
    private var pendingRecordingRequest: ObdStartRecordingRequest? = null

    /** The currently running [CxRecordingService], if any — attached/detached by the service
     * itself ([attachRecordingOwner]), never constructed here. [recordingState]/[stopRecording]
     * simply forward to it; `null` means no recording is running. */
    @Volatile
    private var recordingOwner: CxRecordingService? = null

    /** Lets [CxRecordingService] learn the shared connection it depends on is gone, the same
     * instant [session]'s own poller does — see [onLinkStateChanged]'s `Disconnected`/`Failed`
     * branch. */
    private var recordingDisconnectListener: (() -> Unit)? = null

    /** The one [CxPoller] a recording currently owns, if any — identifies which of [session]'s
     * [ObdSession.poller] (there is only ever one slot) belongs to the recording rather than to
     * a live view, so [stopLive] knows not to touch it and [startLive] knows to refuse outright.
     * A connection has exactly one poller running at a time; a recording starting takes the slot
     * over from whatever live poller was using it, it never runs a second one alongside it. */
    @Volatile
    private var recordingPoller: CxPoller? = null

    /** The last recording's outcome, kept until the next [startRecording] actually begins a new
     * one — otherwise a recording that ends on its own (buffer limit, write failure, exhausted
     * reconnects, …) between two Dart polls would be reported as plain [CxRecordingPhase.IDLE]
     * the moment [recordingOwner] detaches, silently discarding the reason it actually ended.
     * Also what a process restart that finds an abandoned recording marker (see
     * [CxRecordingRestartMarker]) reports through, since no [CxRecordingService] instance exists
     * yet to ask for a live state at all. */
    @Volatile
    private var lastRecordingState: CxRecordingState = CxRecordingState.idle()

    /** Caller-supplied values, set by [configure] — placeholder defaults until then, since a
     * caller is required to call [configure] before anything that actually needs them
     * ([startRecording]'s notification/activity, the restart marker's own file name). */
    @Volatile
    private var config: ObdBridgeConfigMessage = DEFAULT_CONFIG

    private var restartMarkerResolved = false

    init {
        // Reads `commandSession` fresh on every call (a `var` property access, not a captured
        // local) — this keeps working after startLive() replaces it with a plan-sized instance.
        bleClient.setNotificationListener { bytes -> commandSession.onNotification(bytes) }
        bleClient.setStateListener { state -> onLinkStateChanged(state) }
    }

    /** [configure] runs the abandoned-recording check (see [CxRecordingRestartMarker]) itself,
     * rather than this constructor, because it is the first point [config]'s real
     * `sharedPreferencesName` is known — resolving in the constructor would, for the very first
     * [getInstance] call in a fresh process, look in whatever placeholder name [DEFAULT_CONFIG]
     * carries instead of the one a previous process actually wrote the marker under. */
    override fun configure(config: ObdBridgeConfigMessage) {
        this.config = config
        if (restartMarkerResolved) return
        restartMarkerResolved = true
        appContext?.let { context ->
            CxRecordingRestartMarker.resolveAbandoned(context, config.sharedPreferencesName)?.let { interrupted ->
                lastRecordingState = interrupted
            }
        }
    }

    /** Set once by [ObdbridgePlugin] after construction; `null` (the default) means every event
     * is dropped rather than queued — [ObdEventsApi] is display-only, never the only way to
     * learn the current state. */
    fun attachEventsApi(api: ObdEventsApi?) {
        eventsApi = api
    }

    private fun onLinkStateChanged(state: CxLinkState) {
        when (state) {
            // A fresh session for a fresh connection — never `onConnected()` on whatever
            // `commandSession` currently is. The previous connection's session can still have
            // `resyncRequired` set from a disconnect that happened with a command in flight;
            // calling `onConnected()` on it would start its quiet-wait-then-resync cycle, which
            // writes straight to the shared `bleClient` regardless of whether anything still
            // references that session object — see `retireCommandSession`'s doc comment.
            is CxLinkState.Connected -> {
                retireCommandSession(CxCommandSession.DEFAULT_MAX_RESPONSE_BYTES)
            }
            is CxLinkState.Disconnected, is CxLinkState.Failed -> {
                commandSession.onDisconnected()
                session?.poller?.stop()
                session = null
                recordingPoller = null
                // A recording shares this connection rather than owning a second one (see
                // CxRecordingService's class doc) — it has no other way to learn the connection
                // it depends on is gone.
                recordingDisconnectListener?.invoke()
            }
            else -> {}
        }
        eventsApi?.onLinkState(toLinkStateMessage(state)) { }

        if (state is CxLinkState.Connected || state is CxLinkState.Failed) {
            pendingConnectResolve?.let { resolve ->
                pendingConnectResolve = null
                resolve(state)
            }
        }
        if (state is CxLinkState.Idle || state is CxLinkState.Failed) {
            pendingScanResolve?.let { resolve ->
                pendingScanResolve = null
                resolve()
            }
        }
    }

    override fun isObdSupported(): Boolean = true

    override fun capabilities(callback: (Result<ObdCapabilitiesMessage>) -> Unit) {
        callback(
            Result.success(
                ObdCapabilitiesMessage(
                    liveSession = true,
                    foregroundRecording = true,
                    // Reports what the implementation attempts, not a measured guarantee: no
                    // wake lock is held, and behavior under Doze/screen-off has not been
                    // verified on a real device.
                    backgroundRecording = ObdBackgroundRecordingCapabilityMessage.CONTINUOUS,
                    // No restart-after-kill promise exists — see CxRecordingService's class doc.
                    resumeAfterSystemKill = false,
                    // Still-unbuilt auto-connect-on-presence.
                    autoConnectOnPresence = false,
                ),
            ),
        )
    }

    override fun permissionStatus(callback: (Result<ObdPermissionStatusMessage>) -> Unit) {
        callback(Result.success(currentPermissionStatus()))
    }

    /**
     * No Activity-based runtime-permission request exists yet for the Bluetooth permissions this
     * transport needs — this class has no `Activity` reference to launch a system dialog from.
     * This method only re-checks the current status, the same as [permissionStatus]; it never
     * prompts. Building the actual request flow (an `ActivityResultLauncher` wired from the host
     * app's own activity) is a known gap, left for whoever first needs it to actually request
     * rather than just observe.
     */
    override fun requestPermissions(callback: (Result<ObdPermissionStatusMessage>) -> Unit) {
        callback(Result.success(currentPermissionStatus()))
    }

    private fun currentPermissionStatus(): ObdPermissionStatusMessage {
        if (!bleClient.hasRequiredPermissions()) return ObdPermissionStatusMessage.DENIED
        if (!bleClient.isBluetoothEnabled()) return ObdPermissionStatusMessage.BLUETOOTH_OFF
        return ObdPermissionStatusMessage.GRANTED
    }

    override fun scanAdapters(durationMs: Long, callback: (Result<List<ObdAdapterMessage>>) -> Unit) {
        if (pendingScanResolve != null || pendingConnectResolve != null) {
            callback(Result.success(emptyList()))
            return
        }
        scanResults.clear()
        scanSeenHandles.clear()
        // Set *before* calling into bleClient: a fake (and in principle a real) io can resolve
        // synchronously within startScan/connect itself, which fires the state listener — and
        // therefore this pending resolver — before the call below has even returned.
        pendingScanResolve = { callback(Result.success(scanResults.toList())) }
        val accepted = bleClient.startScan(durationMs) { adapter ->
            if (scanSeenHandles.add(adapter.handle)) {
                scanResults.add(ObdAdapterMessage(adapter.handle, adapter.advertisedName, adapter.rssi?.toLong()))
            }
        }
        if (!accepted) {
            val resolve = pendingScanResolve
            pendingScanResolve = null
            resolve?.invoke()
        }
    }

    override fun connect(handle: String, callback: (Result<ObdLinkStateMessage>) -> Unit) {
        if (pendingConnectResolve != null || pendingScanResolve != null) {
            callback(Result.success(toLinkStateMessage(bleClient.currentState())))
            return
        }
        // Same ordering reasoning as scanAdapters above.
        pendingConnectResolve = { state ->
            // `state.handle == handle`, not just `state is Connected`: a rejected
            // `bleClient.connect` (already connected — to a *different* adapter) resolves with
            // whatever is currently connected, which this call never asked for and must not
            // adopt as its own session — doing so would silently keep polling (or start
            // recording on) the wrong adapter under the caller's own, unrelated handle.
            // `session == null` as well: a rejected `connect` to the *same*, already-connected
            // handle resolves with that same Connected state too, and must not replace an
            // already-running session (its poller, if any, would be orphaned with nothing left
            // to stop it) just because this call happened to ask for the handle already active.
            if (state is CxLinkState.Connected && state.handle == handle && session == null) {
                session = ObdSession(timebaseId = UUID.randomUUID().toString())
            }
            callback(Result.success(toLinkStateMessage(state)))
        }
        val accepted = bleClient.connect(handle)
        if (!accepted) {
            val resolve = pendingConnectResolve
            pendingConnectResolve = null
            resolve?.invoke(bleClient.currentState())
        }
    }

    override fun disconnect(callback: (Result<Unit>) -> Unit) {
        session?.poller?.stop()
        session = null
        recordingPoller = null
        bleClient.disconnect()
        // `bleClient.disconnect()` always moves to `Disconnected`, regardless of what was
        // happening before — which `onLinkStateChanged` does not treat as a terminal state for
        // either pending resolver (only `Connected`/`Failed` for connect, `Idle`/`Failed` for
        // scan), so a disconnect that interrupts an in-flight scan or connect would otherwise
        // leave that resolver set forever, rejecting every later `scanAdapters`/`connect` call.
        pendingScanResolve?.let { resolve ->
            pendingScanResolve = null
            resolve()
        }
        pendingConnectResolve?.let { resolve ->
            pendingConnectResolve = null
            resolve(bleClient.currentState())
        }
        callback(Result.success(Unit))
    }

    override fun linkState(): ObdLinkStateMessage = toLinkStateMessage(bleClient.currentState())

    override fun startLive(plan: ObdPlanMessage, callback: (Result<Boolean>) -> Unit) {
        val current = session
        if (bleClient.currentState() !is CxLinkState.Connected || current == null || current.startingLive) {
            callback(Result.success(false))
            return
        }
        if (recordingPoller != null || recordingOwner != null || pendingRecordingRequest != null) {
            // Exactly one poller per connection (see `recordingPoller`'s own doc) — a recording
            // in progress, or merely starting (the window between `startRecording` accepting and
            // `CxRecordingService` actually installing its poller, during which `recordingPoller`
            // itself is still briefly `null`), keeps the slot; `startLive` does not take it over
            // or run alongside it, it simply refuses. The caller already sees the recording's own
            // exchanges through `liveState`, since that is this connection's only poller right now.
            callback(Result.success(false))
            return
        }
        // A poller already running is taken over (stopped, then replaced) rather than rejected:
        // a Flutter page that starts a live session, is torn down without a clean `stopLive`/
        // `disconnect` (process death, a crashed widget tree, ...) and then reopens must be able
        // to start again, not be told "already running" forever by a poller nothing is driving
        // any more.
        current.poller?.stop()
        current.poller = null
        current.startingLive = true
        retireCommandSession(plan.limits.maxResponseBytes.toInt())
        val liveCommandSession = commandSession

        runSequentially(plan.init, plan.limits.adapterStepDeadlineMs) { initOk ->
            if (!initOk) {
                current.startingLive = false
                callback(Result.success(false))
                return@runSequentially
            }
            liveCommandSession.configureResync(
                plan.resync.map { command ->
                    CxResyncStep(command) { response -> isAcceptableSetupResponse(command, response) }
                },
                plan.limits.adapterStepDeadlineMs,
                onStepResult = { result -> onResyncStepResult(current, liveCommandSession, result) },
            )
            current.lastLiveStateQueryAtMs = clock.now()
            val poller = CxPoller(
                session = liveCommandSession,
                clock = clock,
                modules = plan.modules.associateBy { it.moduleId },
                deadlineMs = plan.limits.vehicleReadDeadlineMs,
                setupDeadlineMs = plan.limits.adapterStepDeadlineMs,
                maxReadsPerSecond = plan.limits.maxReadsPerSecond.toInt(),
                monotonicBaseNs = current.monotonicBaseNs,
                // The live view has no use yet for the recording-only consecutive-failure/
                // idle-vehicle limits — a resync failure already drops the connection via
                // onResyncStepResult below, which is this bridge's one escalation path today.
                lastQueryAtMsProvider = { current.lastLiveStateQueryAtMs },
                onOrphaned = { onPollerOrphaned(current) },
            )
            current.poller = poller
            current.startingLive = false
            poller.start(plan.steps)
            callback(Result.success(true))
        }
    }

    /** A resync attempt wipes whatever module the adapter had selected ([ATZ] resets the whole
     * adapter), so every resync step — success or failure alike — must force the next poll to
     * re-send its module setup rather than assume the previous selection survived. A resync step
     * that did not resolve [CxCommandOutcome.OK] leaves [CxCommandSession] permanently blocked
     * for this connection (it does not retry itself); nothing else observes that today, so this
     * is reported upward the only way available — dropping the connection, which the UI already
     * renders as a distinguishable state and the user can act on by reconnecting.
     *
     * [reportingSession] identifies which [CxCommandSession] this listener was configured
     * against, checked against the current [commandSession] rather than [result]'s outcome: a
     * `cancel()` from [retireCommandSession] synchronously delivers [CxCommandOutcome.CANCELLED]
     * to a resync step actually in flight, but by the time that happens [commandSession] already
     * points at the replacement (see that method's own doc comment on the ordering this relies
     * on) — so the identity check alone tells a self-inflicted cancellation from a real failure,
     * without needing to special-case that outcome. [CxCommandOutcome.DISCONNECTED] still needs
     * its own exclusion: [onLinkStateChanged] calls [CxCommandSession.onDisconnected] on the same,
     * still-current instance, so identity alone cannot tell that case apart — disconnecting again
     * here would be a second, nested teardown of a connection already gone. */
    private fun onResyncStepResult(forSession: ObdSession, reportingSession: CxCommandSession, result: CxCommandResult) {
        if (session !== forSession) return
        if (commandSession !== reportingSession) return
        forSession.poller?.resetActiveModule()
        if (result.outcome != CxCommandOutcome.OK && result.outcome != CxCommandOutcome.DISCONNECTED) {
            bleClient.disconnect()
        }
    }

    override fun stopLive(callback: (Result<Unit>) -> Unit) {
        // Never touches a poller a recording owns — a live page's own `stopLive` (dispose,
        // backgrounding, ...) must not stop a recording that happens to be reusing the same
        // connection's one poller slot. See `recordingPoller`'s own doc.
        if (session?.poller !== recordingPoller) {
            session?.poller?.stop()
            session?.poller = null
        }
        callback(Result.success(Unit))
    }

    /** [CxPoller] noticed nobody has asked [liveState] about this session in too long —
     * the Flutter side that started it is gone without a clean `stopLive`/`disconnect` (the
     * engine or Activity was torn down, the process was backgrounded without the page's own
     * lifecycle handling running, ...). Dropping the connection stops every further vehicle read
     * outright, which a foreground-only live session has no other reason to keep making. */
    private fun onPollerOrphaned(forSession: ObdSession) {
        if (session !== forSession) return
        bleClient.disconnect()
    }

    override fun liveState(signalIds: List<String>, callback: (Result<ObdLiveStateMessage>) -> Unit) {
        val current = session
        current?.lastLiveStateQueryAtMs = clock.now()
        val link = toLinkStateMessage(bleClient.currentState())
        val timebaseId = current?.timebaseId ?: ""
        val poller = current?.poller
        val exchanges = signalIds.map { signalId ->
            // The latest *successful* reading, not merely the latest attempt: a single transient
            // timeout/guard-rejection must not make an otherwise-fresh value look like it was
            // never read at all — see `CxPoller.latestSuccessful`'s own doc comment.
            poller?.latestSuccessful?.get(signalId)
                ?: poller?.latestExchanges?.get(signalId)
                ?: ObdSignalExchangeMessage(signalId = signalId, transport = ObdTransportStatusMessage.NOT_YET_POLLED)
        }
        callback(Result.success(ObdLiveStateMessage(link = link, timebaseId = timebaseId, exchanges = exchanges)))
    }

    /** Runs [commands] one at a time, in declared order, waiting for each to resolve a usable
     * reply before the next is attempted; [onDone] fires once, with `false` the moment one
     * command fails rather than running the remainder. Asynchronous throughout — a real response
     * only arrives later, from a GATT notification callback. */
    private fun runSequentially(commands: List<String>, deadlineMs: Long, onDone: (Boolean) -> Unit) {
        fun runNext(index: Int) {
            if (index >= commands.size) {
                onDone(true)
                return
            }
            val command = commands[index]
            commandSession.execute(command, deadlineMs) { result ->
                val ok = result.outcome == CxCommandOutcome.OK && isAcceptableSetupResponse(command, result.responseText)
                if (!ok) onDone(false) else runNext(index + 1)
            }
        }
        runNext(0)
    }

    private fun toLinkStateMessage(state: CxLinkState): ObdLinkStateMessage = when (state) {
        is CxLinkState.Idle -> ObdLinkStateMessage(state = ObdLinkStateValueMessage.IDLE)
        is CxLinkState.Scanning -> ObdLinkStateMessage(state = ObdLinkStateValueMessage.SCANNING)
        is CxLinkState.Connecting -> ObdLinkStateMessage(
            state = ObdLinkStateValueMessage.CONNECTING,
            adapterHandle = state.handle,
        )
        is CxLinkState.Connected -> ObdLinkStateMessage(
            state = if (session?.poller != null) ObdLinkStateValueMessage.RUNNING else ObdLinkStateValueMessage.CONNECTED,
            adapterHandle = state.handle,
            writeLength = state.writeLength.toLong(),
        )
        is CxLinkState.Disconnected -> ObdLinkStateMessage(
            state = ObdLinkStateValueMessage.DISCONNECTED,
            adapterHandle = state.handle,
        )
        is CxLinkState.Failed -> ObdLinkStateMessage(
            state = ObdLinkStateValueMessage.FAILED,
            adapterHandle = state.handle,
            failure = toLinkFailureMessage(state.failure),
        )
    }

    private fun toLinkFailureMessage(failure: CxLinkFailure): ObdLinkFailureMessage = when (failure) {
        CxLinkFailure.PERMISSION_DENIED -> ObdLinkFailureMessage.PERMISSION_DENIED
        CxLinkFailure.BLUETOOTH_OFF -> ObdLinkFailureMessage.BLUETOOTH_OFF
        CxLinkFailure.NOT_FOUND -> ObdLinkFailureMessage.NOT_FOUND
        CxLinkFailure.PAIRING_WINDOW_CLOSED -> ObdLinkFailureMessage.PAIRING_WINDOW_CLOSED
        CxLinkFailure.SERVICE_MISSING -> ObdLinkFailureMessage.SERVICE_MISSING
        CxLinkFailure.TIMEOUT -> ObdLinkFailureMessage.TIMEOUT
        CxLinkFailure.PLATFORM_ERROR -> ObdLinkFailureMessage.PLATFORM_ERROR
    }

    override fun startRecording(request: ObdStartRecordingRequest, callback: (Result<Boolean>) -> Unit) {
        val context = appContext
        if (context == null || bleClient.currentState() !is CxLinkState.Connected || session == null) {
            callback(Result.success(false))
            return
        }
        synchronized(recordingStartLock) {
            if (recordingOwner != null || pendingRecordingRequest != null) {
                callback(Result.success(false))
                return
            }
            pendingRecordingRequest = request
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, CxRecordingService::class.java))
        } catch (e: Exception) {
            // `ForegroundServiceStartNotAllowedException` (API 31+) and friends — the system
            // refused to even start the service; nothing left running to clean up.
            synchronized(recordingStartLock) { pendingRecordingRequest = null }
            callback(Result.success(false))
            return
        }
        callback(Result.success(true))
    }

    override fun stopRecording(sessionId: String, callback: (Result<Unit>) -> Unit) {
        recordingOwner?.let { owner -> if (owner.sessionId == sessionId) owner.requestStop() }
        callback(Result.success(Unit))
    }

    /** While [recordingOwner] itself is still `null` but [pendingRecordingRequest] is set —
     * the window between [startRecording] accepting and [CxRecordingService] actually starting
     * — reports `recording` with that request's own `sessionId` rather than [lastRecordingState],
     * which is whatever an unrelated, earlier recording last left behind. Without this, a poll
     * landing in that window would read as "that earlier recording's own end state" and could
     * make a caller believe the just-accepted start never happened at all. */
    override fun recordingState(): ObdRecordingStateMessage {
        val pending = pendingRecordingRequest
        if (recordingOwner == null && pending != null) {
            return CxRecordingState(
                sessionId = pending.sessionId,
                phase = CxRecordingPhase.RECORDING,
                lastFlushedSequence = 0L,
                bufferBytes = 0L,
                gapCount = 0,
                writeError = null,
                resumedAfterRestart = false,
            ).toMessage()
        }
        return (recordingOwner?.currentState() ?: lastRecordingState).toMessage()
    }

    /** Drops [lastRecordingState] once Dart confirms [sessionId]'s buffer is durably imported —
     * the buffer file itself is Dart's to delete, never this method's; the two are deliberately
     * sequenced on the caller's side (import commits, *then* this call, *then* the file is
     * removed), so a crash or channel failure here never races a deletion against an import
     * that has not actually committed. A no-op whenever [sessionId] names the currently running
     * recording ([recordingOwner]) rather than a finished one — acknowledging a session that is
     * still being written would otherwise let a later, unrelated poll read this recording's own
     * end state as "nothing recorded". Also a no-op when [sessionId] does not match
     * [lastRecordingState] at all, which [lastSequence] never needs to disambiguate — there is
     * only ever one remembered outcome to forget. */
    override fun acknowledgeImported(sessionId: String, lastSequence: Long, callback: (Result<Unit>) -> Unit) {
        if (lastRecordingState.sessionId == sessionId && recordingOwner?.sessionId != sessionId) {
            lastRecordingState = CxRecordingState.idle()
        }
        callback(Result.success(Unit))
    }

    /** Consumed exactly once, by [CxRecordingService.onStartCommand] right after it exists —
     * `null` for a system-restarted service (there is no such thing here, see that class's own
     * doc) or a duplicate/stale start intent. */
    internal fun consumePendingRecordingRequest(): ObdStartRecordingRequest? = synchronized(recordingStartLock) {
        val pending = pendingRecordingRequest
        pendingRecordingRequest = null
        pending
    }

    internal fun attachRecordingOwner(owner: CxRecordingService?) {
        recordingOwner = owner
    }

    internal fun setRecordingDisconnectListener(listener: (() -> Unit)?) {
        recordingDisconnectListener = listener
    }

    /** The connection [CxRecordingService] must reuse rather than opening a second GATT client —
     * `null` when nothing is connected. Takes the connection's one poller slot over from
     * whatever is currently using it (a live view, or this same recording's own poller before a
     * reconnect — see [recordingPoller]'s doc on why there is only ever one) and resizes
     * [commandSession] for [maxResponseBytes], exactly like [startLive] would for its own
     * takeover — unconditionally, since nothing is left running on the old slot to protect.
     *
     * Known gap, shared with [startLive]'s own takeover: the replaced [CxCommandSession] can
     * still have a vehicle read genuinely in flight on the wire; this does not wait out a quiet
     * period before the caller's own first init command, so that read's late reply can in rare
     * cases be misread as the answer to that command instead (visible result: a reported init
     * failure, never a silently wrong value). Left for whichever package next touches this
     * takeover path to actually close. */
    internal fun prepareConnectionForRecording(maxResponseBytes: Int): CxCommandSession? {
        val current = session ?: return null
        if (bleClient.currentState() !is CxLinkState.Connected) return null
        current.poller?.stop()
        current.poller = null
        recordingPoller = null
        retireCommandSession(maxResponseBytes)
        return commandSession
    }

    /** Occupies the connection's one poller slot with [poller] — called once [CxRecordingService]
     * has actually built it, after [prepareConnectionForRecording] already cleared the slot.
     * `liveState`'s own poller lookup (`session.poller`) is exactly how a live view sees a
     * running recording's data: there is no separate recording-only query path. */
    internal fun installRecordingPoller(poller: CxPoller) {
        val current = session ?: return
        current.poller = poller
        recordingPoller = poller
    }

    /** The reverse of [installRecordingPoller] — a no-op unless [poller] is still the one
     * installed (an already-superseded poller, e.g. from a reconnect race, must not clear a
     * slot it no longer owns). Does not itself call [CxPoller.stop]; the caller already did
     * before deciding the recording is over. */
    internal fun uninstallRecordingPoller(poller: CxPoller) {
        if (recordingPoller !== poller) return
        recordingPoller = null
        if (session?.poller === poller) session?.poller = null
    }

    /** Drops the connection outright so [CxRecordingService] can reconnect cleanly — used where
     * the link is still nominally "Connected" per [bleClient] but no longer trustworthy (the
     * poller's own consecutive-failure budget ran out), unlike a real GATT disconnect
     * notification, which [onLinkStateChanged] already reacts to on its own. */
    internal fun disconnectForRecording() {
        bleClient.disconnect()
    }

    /** The recording's own last-known outcome, reported once it is actually over — see
     * [lastRecordingState]'s doc for why this exists at all. */
    internal fun reportRecordingEnded(state: CxRecordingState) {
        lastRecordingState = state
    }

    internal val recordingClock: CxCommandClock get() = clock

    /** [ObdSession.timebaseId]/[ObdSession.monotonicBaseNs] for whichever connection is
     * currently up, or `null` if none is. */
    internal fun currentTimebase(): Pair<String, Long>? = session?.let { it.timebaseId to it.monotonicBaseNs }

    internal fun currentAdapterHandle(): String? = (bleClient.currentState() as? CxLinkState.Connected)?.handle

    /** Re-runs the exact same escalation [onResyncStepResult] already applies for the live
     * poller's own resync — shared because a resync step started while a recording owns
     * [onCommandSession] must behave identically regardless of which poller happens to be
     * running at the time. [onCommandSession] is the session the caller's own poller actually
     * runs on, passed in rather than read from the current [commandSession] field — the same
     * explicit-session shape [startLive]/[onResyncStepResult] already use for their own
     * `liveCommandSession`, so a resync configured against one session is never checked against
     * whatever happens to be current by the time its result arrives instead of the one it was
     * actually configured on. A recording stopped and restarted while a resync was still
     * configured on the old session can otherwise have that stale listener's callback arrive
     * against an unrelated, already-current session and disconnect it for a failure that was
     * never its own. */
    internal fun configureResyncForRecording(
        onCommandSession: CxCommandSession,
        resyncCommands: List<String>,
        stepDeadlineMs: Long,
        onStepResult: (CxCommandResult) -> Unit,
    ) {
        val forSession = session ?: return
        onCommandSession.configureResync(
            resyncCommands.map { command -> CxResyncStep(command) { response -> isAcceptableSetupResponse(command, response) } },
            stepDeadlineMs,
            onStepResult = { result ->
                if (session !== forSession) return@configureResync
                if (commandSession !== onCommandSession) return@configureResync
                onStepResult(result)
                if (result.outcome != CxCommandOutcome.OK && result.outcome != CxCommandOutcome.DISCONNECTED) {
                    bleClient.disconnect()
                }
            },
        )
    }

    /** Checks [isConnectedTo] after [connect] resolves rather than trusting its own
     * `state == CONNECTED`/`RUNNING` result value — a rejected `connect` call (already
     * connected, to a *different* adapter) still resolves with a `Connected` state, just for
     * the wrong handle; reporting that as a successful reconnect would silently keep recording
     * on an unrelated adapter under this recording's own `vehicleId`. */
    internal fun reconnectForRecording(handle: String, callback: (Boolean) -> Unit) {
        if (isConnectedTo(handle)) {
            callback(true)
            return
        }
        connect(handle) { callback(isConnectedTo(handle)) }
    }

    internal fun isConnectedTo(handle: String): Boolean =
        (bleClient.currentState() as? CxLinkState.Connected)?.handle == handle

    /** [CxRecordingService]'s own view of [config] — whatever [configure] last set, or
     * [DEFAULT_CONFIG] if a caller never called it at all (a caller error; [startRecording]
     * still runs, just with the placeholder notification/activity/preferences name). */
    internal fun currentConfig(): ObdBridgeConfigMessage = config

    companion object {
        @Volatile
        private var instance: ObdApi? = null

        /** Placeholder until a real [configure] call — a caller is required to call it before
         * [startRecording], but [ObdApi] itself must never be in a state where reading [config]
         * crashes for having skipped that. */
        private val DEFAULT_CONFIG = ObdBridgeConfigMessage(
            activityClassName = "",
            smallIconResourceName = "",
            sharedPreferencesName = "obdbridge_recording_restart",
            bufferFormatId = "obdbridge-buffer",
        )

        /** The one shared [ObdApi] for the whole process — surviving the hosting `Activity` being
         * recreated (e.g. on rotation) is the point: a live BLE connection must not be torn down
         * and rebuilt just because it was. Always construct with `context.applicationContext`,
         * never an Activity, for exactly that reason. */
        fun getInstance(context: Context): ObdApi =
            instance ?: synchronized(this) {
                instance ?: ObdApi(context.applicationContext).also { instance = it }
            }

        /** `ATZ` replies with the adapter's own identification banner, not `OK` — every other
         * fixed adapter/module-setup command this product ever sends does reply plain `OK`. A
         * framed-but-unexpected reply (e.g. `?` for a command the adapter did not recognize) is a
         * real setup failure, not merely "no reply at all", so it must not pass just because
         * [CxCommandOutcome.OK] (which only means "a complete frame with a prompt arrived") came
         * back. Shared between the init-sequence check and the resync-step check: both run the
         * identical command set (`plan.init`/`plan.resync`). */
        fun isAcceptableSetupResponse(command: String, responseText: String?): Boolean =
            if (command == "ATZ") {
                // The real banner is the adapter's own ELM327/STN identification line — anything
                // else (`STOPPED`, `OK`, `NO DATA`, a stray vehicle-read answer that raced into
                // this frame) must not pass as "the reset actually happened".
                responseText != null && (responseText.startsWith("ELM327") || responseText.startsWith("STN"))
            } else {
                responseText == "OK"
            }
    }
}
