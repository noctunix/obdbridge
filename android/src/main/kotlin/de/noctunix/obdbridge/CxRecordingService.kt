package de.noctunix.obdbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service owning one recording's [CxPoller] and [CxBufferWriter], independent of the
 * Flutter engine or any Activity: it keeps running across the app being backgrounded, the
 * hosting Activity being destroyed, even the whole Flutter engine being torn down, because none
 * of those are this service's own lifecycle. It reuses [ObdApi]'s existing [CxBleClient]/
 * [CxCommandSession] rather than opening a second GATT client — connection and buffer-file
 * ownership each have exactly one owner (this service for the file, [ObdApi] for the
 * connection), the same one-owner rule the live view already follows; [ObdApi] additionally
 * guarantees only one [CxPoller] ever runs per connection at all (see its `recordingPoller` doc),
 * so a live view and a recording can never poll the adapter at the same time.
 *
 * Every state transition here runs on the main looper ([handler]), not a dedicated thread —
 * deliberately: [ObdApi]'s own connection/session state (`CxBleClient`, `CxCommandSession`,
 * [ObdApi.connect]'s pending-resolver fields) is already only ever touched from the main thread
 * in production (GATT callbacks, the shared [CxCommandClock]'s own `Handler`), and mutating it
 * from a second thread here would be exactly the kind of cross-thread hazard that connection
 * already has no locking against.
 *
 * **Deliberately no self-restart after a process kill.** This service's `onStartCommand` always
 * returns [Service.START_NOT_STICKY] — unlike, say, a GPS tracking service re-armed via
 * `START_STICKY` and a persisted marker, a restarted process here has no connection to resume
 * onto: `CxBleClient`'s whole state (the GATT connection itself) dies with the process, unlike a
 * `LocationManager` registration, which the platform can re-arm independently of the app's own
 * process. A self-restart here could do nothing but immediately fail to reconnect to an adapter
 * it was never told about, for a plan it no longer has. [CxRecordingRestartMarker] still
 * persists enough (`sessionId`, buffer path) for the *next* process to recognize and close out an
 * abandoned recording as [CxRecordingPhase.INTERRUPTED] instead of leaving it silently
 * unaccounted for — resuming it is a different, larger feature this marker does not attempt.
 *
 * **Not verified on-device** — the foreground notification actually surviving screen-off, a real
 * reconnect after a real adapter dropout, and actual behavior under a real process kill are
 * outside what JVM unit tests can exercise. The deterministic pieces (state transitions, buffer
 * format, limit enforcement) are covered by `CxBufferWriterTest`/`CxPollerTest`/
 * `CxRecordingStateTest`.
 */
class CxRecordingService : Service() {

    private lateinit var handler: Handler

    /** Non-null exactly while a recording owned by this instance is in progress — the identity
     * [ObdApi.stopRecording] checks before forwarding a stop request here. */
    @Volatile
    var sessionId: String? = null
        private set

    private var writer: CxBufferWriter? = null
    private var poller: CxPoller? = null
    private var request: ObdStartRecordingRequest? = null
    private var adapterHandle: String? = null
    private var reconnectAttempts = 0

    /** `true` from the moment a connection loss or a consecutive-failure budget runs out until
     * the reconnect it triggers either succeeds or gives up for good. Guards against the two
     * independent ways a dropout can be noticed (the poller's own counter, and [ObdApi]'s
     * disconnect listener) both scheduling their own backoff chain for the same gap. */
    private var reconnectInFlight = false

    @Volatile private var phase: CxRecordingPhase = CxRecordingPhase.IDLE
    @Volatile private var writeError: String? = null
    private var stopReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        ContextCompat.registerReceiver(this, stopReceiver, IntentFilter(ACTION_STOP), ContextCompat.RECEIVER_NOT_EXPORTED)
        stopReceiverRegistered = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (sessionId != null) {
            // Already recording — a duplicate start intent (e.g. the system redelivering before
            // this service fully reported itself started) must not re-initialize anything.
            return START_NOT_STICKY
        }
        val obd = ObdApi.getInstance(applicationContext)
        val pending = obd.consumePendingRecordingRequest()
        // `startForeground` must be called on every path through this method once the system has
        // already launched this service via `startForegroundService` — calling `stopSelf()`
        // without ever reaching `startForeground` is the documented "did not then call
        // Service.startForeground()" crash, regardless of how early a later check fails.
        if (!startForegroundSafely(pending?.notification)) {
            pending?.let { reportFailedStart(obd, it, "foreground service start refused") }
            stopSelf()
            return START_NOT_STICKY
        }
        if (pending == null || !beginRecording(obd, pending)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Reports a start that never actually became a running recording — without this, Dart's
     * [ObdApi.recordingState] would keep answering with whatever an unrelated, earlier
     * recording last reported (or plain idle), for a start it was already told succeeded. The
     * caller is expected to match this against the `sessionId` it just tried to start, not
     * assume every `interrupted` state belongs to it. */
    private fun reportFailedStart(obd: ObdApi, startRequest: ObdStartRecordingRequest, reason: String) {
        obd.reportRecordingEnded(
            CxRecordingState(
                sessionId = startRequest.sessionId,
                phase = CxRecordingPhase.INTERRUPTED,
                lastFlushedSequence = 0L,
                bufferBytes = 0L,
                gapCount = 0,
                writeError = reason,
                resumedAfterRestart = false,
            ),
        )
    }

    override fun onDestroy() {
        // Unconditional, unlike a plain "mark interrupted and stop": `recordingOwner`/the
        // disconnect listener on `ObdApi` must never keep pointing at a destroyed instance —
        // doing so would hang `recordingState()` on this recording's last phase and reject every
        // later `startRecording` for the rest of the process's life.
        endRecordingInternal(CxRecordingPhase.INTERRUPTED, "service destroyed")
        if (stopReceiverRegistered) {
            try {
                unregisterReceiver(stopReceiver)
            } catch (e: Exception) {
                // already unregistered
            }
        }
        super.onDestroy()
    }

    // ── start ────────────────────────────────────────────────────────────

    private fun startForegroundSafely(text: ObdRecordingNotificationMessage?): Boolean {
        ensureNotificationChannel(text?.channelName ?: "OBD recording")
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, buildNotification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(text))
            }
            true
        } catch (e: Exception) {
            // `ForegroundServiceStartNotAllowedException` and friends — the platform refused.
            false
        }
    }

    private fun beginRecording(obd: ObdApi, startRequest: ObdStartRecordingRequest): Boolean {
        val plan = startRequest.plan
        val commandSession = obd.prepareConnectionForRecording(plan.limits.maxResponseBytes.toInt())
        val timebase = obd.currentTimebase()
        if (commandSession == null || timebase == null) {
            reportFailedStart(obd, startRequest, "no usable connection to record over")
            return false
        }

        val config = obd.currentConfig()
        val bufferWriter = try {
            CxBufferWriter(startRequest.bufferFilePath, plan.limits.maxBufferBytes).also {
                it.writeHeader(
                    sessionId = startRequest.sessionId,
                    bufferFormatId = config.bufferFormatId,
                    profileId = plan.profileId,
                    profileVersion = plan.profileVersion,
                    profileFormatVersion = plan.formatVersion,
                    timebaseId = timebase.first,
                    createdUtcMs = startRequest.startedAtEpochMs,
                )
            }
        } catch (e: Exception) {
            reportFailedStart(obd, startRequest, "could not open the buffer file")
            return false
        }
        if (bufferWriter.failed) {
            bufferWriter.close()
            reportFailedStart(obd, startRequest, "could not write the buffer file header")
            return false
        }

        request = startRequest
        writer = bufferWriter
        sessionId = startRequest.sessionId
        adapterHandle = obd.currentAdapterHandle()
        phase = CxRecordingPhase.RECORDING
        writeError = null
        reconnectAttempts = 0
        reconnectInFlight = false

        obd.attachRecordingOwner(this)
        obd.setRecordingDisconnectListener { onConnectionLost(obd) }
        CxRecordingRestartMarker.save(
            applicationContext,
            sharedPreferencesName = config.sharedPreferencesName,
            sessionId = startRequest.sessionId,
            bufferFilePath = startRequest.bufferFilePath,
            startedAtEpochMs = startRequest.startedAtEpochMs,
        )

        if (plan.limits.maxRecordingMs > 0) {
            handler.postDelayed(
                { endRecordingInternal(CxRecordingPhase.STOPPED, "maximum recording duration reached") },
                plan.limits.maxRecordingMs,
            )
        }

        runAdapterSequence(commandSession, plan.init, plan.limits.adapterStepDeadlineMs) { ok ->
            if (sessionId != startRequest.sessionId) return@runAdapterSequence
            if (!ok) {
                endRecordingInternal(CxRecordingPhase.INTERRUPTED, "adapter initialization failed")
                return@runAdapterSequence
            }
            startPolling(obd, startRequest, commandSession, timebase)
        }
        return true
    }

    private fun startPolling(
        obd: ObdApi,
        startRequest: ObdStartRecordingRequest,
        commandSession: CxCommandSession,
        timebase: Pair<String, Long>,
    ) {
        if (sessionId != startRequest.sessionId) return
        // Only `reconnectInFlight` resets here, once polling has actually (re)started — not
        // merely once a BLE connect succeeds (the init sequence right above this call can still
        // fail and need another real reconnect attempt). `reconnectAttempts` resets later still,
        // on the first genuinely successful vehicle read in `onExchange` — a connection that
        // answers init commands but then times out on every real read must not get its budget
        // replenished just for having started polling at all; see that reset's own doc comment.
        reconnectInFlight = false
        val plan = startRequest.plan
        obd.configureResyncForRecording(commandSession, plan.resync, plan.limits.adapterStepDeadlineMs) { _ -> poller?.resetActiveModule() }
        val newPoller = CxPoller(
            session = commandSession,
            clock = obd.recordingClock,
            modules = plan.modules.associateBy { it.moduleId },
            deadlineMs = plan.limits.vehicleReadDeadlineMs,
            setupDeadlineMs = plan.limits.adapterStepDeadlineMs,
            maxReadsPerSecond = plan.limits.maxReadsPerSecond.toInt(),
            monotonicBaseNs = timebase.second,
            maxConsecutiveFailures = plan.limits.maxConsecutiveTimeouts.toInt(),
            idleVehicleStopAfterMs = plan.limits.idleVehicleStopAfterMs,
            onConsecutiveFailuresExceeded = { onConsecutiveFailuresExceeded(obd) },
            onIdleVehicleDetected = { endRecordingInternal(CxRecordingPhase.STOPPED, "vehicle idle, no data on any signal") },
            sink = { exchange -> onExchange(exchange, timebase.first) },
        )
        poller = newPoller
        obd.installRecordingPoller(newPoller)
        newPoller.start(plan.steps)
    }

    /** Appends one poll attempt to [writer] and ends the recording the moment the writer itself
     * reports it cannot continue — a write failure or the buffer size limit, in that priority
     * order since a write failure is the more specific diagnosis of the two. [CxPollExchange]'s
     * own [CxPollExchange.rawBytes] is used verbatim — never the decoded [CxPollExchange.responseText],
     * which exists for the live view, not for what this buffer persists. */
    private fun onExchange(exchange: CxPollExchange, timebaseId: String) {
        val bufferWriter = writer ?: return
        if (exchange.transport == ObdTransportStatusMessage.OK) {
            // Only a genuinely successful vehicle read proves the connection is actually
            // functional end to end — resetting this budget merely because polling started
            // (the adapter answering init commands) would let a connection that answers init
            // but times out on every real read cycle through disconnect → reconnect → init-ok
            // → reset forever, since `maxReconnectAttemptsPerGap` would never accumulate across
            // cycles that each "succeed" at getting this far.
            reconnectAttempts = 0
        }
        bufferWriter.appendExchange(
            signalId = exchange.signalId,
            requestStartedUtcMs = exchange.requestedAtEpochMs,
            receivedUtcMs = if (exchange.transport == ObdTransportStatusMessage.OK) exchange.receivedAtEpochMs else null,
            monotonicStartNs = exchange.monotonicElapsedNs - exchange.durationNs,
            durationNs = exchange.durationNs,
            timebaseId = timebaseId,
            rawBytes = exchange.rawBytes,
            transport = transportWireName(exchange.transport),
        )
        if (bufferWriter.failed) {
            endRecordingInternal(CxRecordingPhase.INTERRUPTED, "buffer write failed", writeFailed = true)
        } else if (bufferWriter.limitReached) {
            endRecordingInternal(CxRecordingPhase.STOPPED, "buffer size limit reached")
        }
    }

    /** Starts the reconnect sequence for a lost connection, or does nothing if one is already
     * running — the single place a backoff chain actually begins. A disconnect/failure
     * notification arriving while [reconnectInFlight] is already set (a failed reconnect
     * attempt's own `Failed` state transition chief among them — see [attemptReconnect]) is a
     * no-op here; that attempt's own callback is what schedules the next try, not this method a
     * second time. */
    private fun beginOrContinueRecovery(obd: ObdApi, gapReason: String?) {
        poller?.stop()
        poller = null
        if (reconnectInFlight) return
        gapReason?.let { writer?.appendGap(it) }
        reconnectInFlight = true
        scheduleReconnect(obd)
    }

    /** The budget `ObdLimitsMessage.maxConsecutiveTimeouts` governs ran out — the connection is
     * no longer trustworthy even though [CxBleClient] may still consider it nominally connected.
     * Starts recovery first, then drops the connection for real via
     * [ObdApi.disconnectForRecording]: that disconnect also cascades into [onConnectionLost]
     * through the same listener a genuine BLE drop would, which [beginOrContinueRecovery]'s own
     * guard absorbs as a no-op rather than a second backoff chain. */
    private fun onConsecutiveFailuresExceeded(obd: ObdApi) {
        if (sessionId == null) return
        beginOrContinueRecovery(obd, "consecutive vehicle-read failures exceeded the configured limit")
        obd.disconnectForRecording()
    }

    /** [ObdApi] noticed the shared connection itself is gone — the same event the live poller's
     * own orphan path reacts to, and also what [onConsecutiveFailuresExceeded]'s own
     * [ObdApi.disconnectForRecording] call lands back here through. */
    private fun onConnectionLost(obd: ObdApi) {
        if (sessionId == null) return
        beginOrContinueRecovery(obd, "connection lost")
    }

    // ── reconnect ────────────────────────────────────────────────────────

    private fun scheduleReconnect(obd: ObdApi) {
        val plan = request?.plan ?: return
        val handleAddr = adapterHandle
        if (handleAddr == null) {
            endRecordingInternal(CxRecordingPhase.INTERRUPTED, "no adapter handle to reconnect to")
            return
        }
        if (reconnectAttempts >= plan.limits.maxReconnectAttemptsPerGap) {
            endRecordingInternal(CxRecordingPhase.INTERRUPTED, "reconnect attempts exhausted")
            return
        }
        val backoffMs = nextBackoffMs(plan)
        reconnectAttempts++
        handler.postDelayed({ attemptReconnect(obd, handleAddr) }, backoffMs)
    }

    /** Doubles [plan]'s `reconnectBackoffMinMs` per attempt, capped at `reconnectBackoffMaxMs` —
     * a starting exponential curve, not yet measured against a real dropout's actual recovery
     * time. */
    private fun nextBackoffMs(plan: ObdPlanMessage): Long {
        val min = plan.limits.reconnectBackoffMinMs.coerceAtLeast(1L)
        val max = plan.limits.reconnectBackoffMaxMs.coerceAtLeast(min)
        val shift = (reconnectAttempts).coerceIn(0, 30)
        val scaled = min shl shift
        return if (scaled <= 0L) max else scaled.coerceAtMost(max)
    }

    private fun attemptReconnect(obd: ObdApi, handleAddr: String) {
        if (sessionId == null) return
        obd.reconnectForRecording(handleAddr) { connected ->
            handler.post {
                if (sessionId == null) return@post
                if (connected) {
                    resumeAfterReconnect(obd)
                } else {
                    scheduleReconnect(obd)
                }
            }
        }
    }

    /** A reconnect attempt coming back `connected` is not yet a resumed recording — the adapter
     * still needs its init sequence re-run, and that can itself fail (another drop mid-init, or
     * a real protocol rejection). [reconnectAttempts]/[reconnectInFlight] are therefore left
     * alone here; [startPolling] is the only place that resets them, once this attempt has
     * actually produced a running poller again. */
    private fun resumeAfterReconnect(obd: ObdApi) {
        val startRequest = request ?: return
        val plan = startRequest.plan
        val commandSession = obd.prepareConnectionForRecording(plan.limits.maxResponseBytes.toInt())
        val timebase = obd.currentTimebase()
        if (commandSession == null || timebase == null) {
            // Not a reconnect success after all — treat it exactly like a failed attempt rather
            // than giving up outright; `scheduleReconnect` still enforces the attempts budget.
            scheduleReconnect(obd)
            return
        }
        runAdapterSequence(commandSession, plan.init, plan.limits.adapterStepDeadlineMs) { ok ->
            if (sessionId != startRequest.sessionId) return@runAdapterSequence
            if (!ok) {
                // The connection can have dropped again during this very init — `onConnectionLost`
                // (via `ObdApi`'s disconnect listener) may already be mid-cascade for the same
                // event; `scheduleReconnect` only ever runs once per actual gap regardless of
                // which of the two paths reaches it first, since both go through the same
                // `reconnectAttempts` counter and this call does not touch `reconnectInFlight`.
                scheduleReconnect(obd)
                return@runAdapterSequence
            }
            startPolling(obd, startRequest, commandSession, timebase)
        }
    }

    // ── stop ─────────────────────────────────────────────────────────────

    /** The only caller-visible stop entry point — both [ObdApi.stopRecording] and the
     * notification's stop action land here with the same, always-deliberate meaning; there is
     * no path left that stops a recording without the user asking for it (every other ending is
     * this service's own decision and goes straight to [endRecordingInternal] with its own
     * reason). */
    fun requestStop() {
        handler.post { endRecordingInternal(CxRecordingPhase.STOPPED, "stopped by user") }
    }

    /** Ends the recording and, before closing [writer], appends [reason] as the buffer's own
     * closing line — a recording must end visibly, with a reason, in the persisted file itself,
     * not only in whatever Dart can later poll. Forces the write through even if the buffer
     * limit was what just ended the recording (see [CxBufferWriter.appendGap]'s own `force`
     * parameter); never through an actual write failure, which [CxBufferWriter.appendGap]
     * refuses regardless. */
    private fun endRecordingInternal(endPhase: CxRecordingPhase, reason: String, writeFailed: Boolean = false) {
        if (sessionId == null) return
        handler.removeCallbacksAndMessages(null)
        val endedPoller = poller
        poller = null
        endedPoller?.stop()
        // `force = true`: a buffer that already hit its size limit still gets this one closing
        // line — ending with no reason at all because the limit that just ended it also blocks
        // further writes would defeat the entire point of writing one.
        writer?.appendGap(reason, force = true)
        writer?.close()
        phase = endPhase
        writeError = if (writeFailed) reason else null
        sessionId = null
        val obd = ObdApi.getInstance(applicationContext)
        CxRecordingRestartMarker.clear(applicationContext, obd.currentConfig().sharedPreferencesName)
        val finalState = currentState()
        writer = null
        if (endedPoller != null) obd.uninstallRecordingPoller(endedPoller)
        obd.reportRecordingEnded(finalState)
        obd.setRecordingDisconnectListener(null)
        obd.attachRecordingOwner(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun currentState(): CxRecordingState = CxRecordingState(
        sessionId = request?.sessionId,
        phase = phase,
        lastFlushedSequence = writer?.lastSequence ?: 0L,
        bufferBytes = writer?.bufferBytes ?: 0L,
        gapCount = writer?.gaps ?: 0,
        writeError = writeError,
        resumedAfterRestart = false,
    )

    // ── adapter setup ────────────────────────────────────────────────────

    /** Same shape as `ObdApi.runSequentially`, duplicated rather than shared because it runs
     * against whichever [CxCommandSession] the caller passes in (the live view always runs
     * against `ObdApi`'s own, current `commandSession` field, never a caller-supplied one). */
    private fun runAdapterSequence(session: CxCommandSession, commands: List<String>, deadlineMs: Long, onDone: (Boolean) -> Unit) {
        fun runNext(index: Int) {
            if (index >= commands.size) {
                onDone(true)
                return
            }
            val command = commands[index]
            session.execute(command, deadlineMs) { result ->
                val ok = result.outcome == CxCommandOutcome.OK && ObdApi.isAcceptableSetupResponse(command, result.responseText)
                if (!ok) onDone(false) else runNext(index + 1)
            }
        }
        runNext(0)
    }

    // ── notification ─────────────────────────────────────────────────────

    private fun ensureNotificationChannel(channelName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(text: ObdRecordingNotificationMessage?): Notification {
        val config = ObdApi.getInstance(applicationContext).currentConfig()
        val contentIntent = resolveActivityClass(config.activityClassName)?.let { activityClass ->
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, activityClass).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val stopIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_STOP).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(text?.title ?: "OBD recording")
            .setContentText(text?.body ?: "")
            .setSmallIcon(resolveSmallIcon(config.smallIconResourceName))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        if (text != null) builder.addAction(0, text.stopActionLabel, stopIntent)
        return builder.build()
    }

    /** `Class.forName` rather than a type the caller passes directly — [ObdBridgeConfigMessage]
     * crosses the Pigeon channel, where only serializable values (a class name string) survive.
     * `null` if the name is empty (a caller that never called [ObdApi.configure]) or does not
     * resolve to an actual class — the notification is still shown, just without a tap action,
     * rather than crashing the whole recording over a misconfigured activity name. */
    private fun resolveActivityClass(activityClassName: String): Class<*>? {
        if (activityClassName.isEmpty()) return null
        return try {
            Class.forName(activityClassName)
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    /** Resolves [smallIconResourceName] against this app's own `mipmap`/`drawable` resources —
     * a resource id would be meaningless across the plugin/app boundary, so the caller passes a
     * name instead. Falls back to a built-in platform icon rather than passing resource id `0`
     * to [NotificationCompat.Builder.setSmallIcon], which throws. */
    private fun resolveSmallIcon(smallIconResourceName: String): Int {
        if (smallIconResourceName.isNotEmpty()) {
            for (type in arrayOf("mipmap", "drawable")) {
                val id = resources.getIdentifier(smallIconResourceName, type, packageName)
                if (id != 0) return id
            }
        }
        return android.R.drawable.stat_notify_sync
    }

    private val stopReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            requestStop()
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "de.noctunix.obdbridge.recording"
        private const val NOTIFICATION_ID = 4781
        private const val ACTION_STOP = "de.noctunix.obdbridge.action.STOP_OBD_RECORDING"

        /** The literal strings `ObdTransportStatusMessage`'s Dart enum uses, not a Kotlin enum
         * name — see `CxBufferWriter`'s own class doc on why the buffer must match that spelling. */
        internal fun transportWireName(transport: ObdTransportStatusMessage): String = when (transport) {
            ObdTransportStatusMessage.OK -> "ok"
            ObdTransportStatusMessage.TIMEOUT -> "timeout"
            ObdTransportStatusMessage.OVERFLOW -> "overflow"
            ObdTransportStatusMessage.DISCONNECTED -> "disconnected"
            ObdTransportStatusMessage.REJECTED_BY_GUARD -> "rejectedByGuard"
            ObdTransportStatusMessage.WRITE_FAILED -> "writeFailed"
            ObdTransportStatusMessage.CANCELLED -> "cancelled"
            ObdTransportStatusMessage.NOT_YET_POLLED -> "notYetPolled"
        }
    }
}
