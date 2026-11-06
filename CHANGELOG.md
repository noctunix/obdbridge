## 0.2.5

- Fix: a module-setup failure (and a failed vehicle read it already made
  unsafe to send) is now paced like a real read, not fired at CPU speed. A
  connection that genuinely cannot write for a while — a BLE link stall, a
  resync it cannot complete — previously had `CxPoller` drain its *entire*
  one-shot queue as instant `writeFailed` results in one synchronous burst,
  observed in the field as thousands of reads failing within a fraction of
  a second and, in one reproduction, a `StackOverflowError`. Each such
  failure now updates the same global rate-limit floor a dispatched read
  does, so the poller waits out the plan's own read spacing before trying
  the next queued step instead of recursing through the whole backlog —
  the connection gets a real chance to recover before its backlog is
  spent. `ObdLimitsMessage.maxReadsPerSecond` set to `unlimitedMaxReadsPerSecond`
  (1000) or above still disables this pacing, same as it always disabled
  read spacing.
- `ObdModuleMessage.readDeadlineMs`: overrides `ObdLimitsMessage.vehicleReadDeadlineMs`
  for every step addressing that module, for an ECU that legitimately
  needs longer than the rest of the plan budgets for (one that keeps
  extending ISO 14229-2 P2* with NRC 0x78/responsePending past the plan's
  default). `null` (the default) keeps using the plan's own deadline.

## 0.2.4

- Fix: a short-interval recurring step could go unread for minutes. The
  poller ran whichever due step had become due first, so whenever many
  long-interval steps were due at once (every step at the start of a
  recording, and again every round once their total read time exceeded
  their interval) all of them ran before a short step that became due
  later. Among the due steps it now runs the one with the shortest
  interval, then the one whose deadline (due time plus interval) comes
  first: short-interval steps keep their cadence from the start, and
  longer-interval steps get the read budget left over. Longer-interval
  steps are therefore delayed indefinitely if shorter-interval steps alone
  saturate the budget (`maxReadsPerSecond` and the time each read takes);
  keep a plan's short-interval load well below it.

## 0.2.3

- Fix: a recording's poller could run several polls at once.
  `enqueueOneShot` arriving while a read was in flight started the
  still-due recurring step a second time, and every such enqueue added
  another parallel poll chain: recurring steps were read up to six times in
  a row, and one chain could switch the adapter's module between another
  chain's module setup and its read, so a reply could come from a different
  module than the step addresses. Exactly one poll is in flight again;
  queued one-shot work is picked up when it completes.

## 0.2.2

- `ObdLimitsMessage.quietModuleBackoffMs`: once three recurring reads in a
  row on one module (or all of its steps, if it has fewer) answer `NO DATA`,
  the module's steps are held back for this long, then a single read probes
  it again; any other answer resumes its normal schedule. Saves a full
  adapter timeout per planned step while an ECU sleeps (a parked or charging
  vehicle) and leaves that time to queued one-shot reads. Default `0` keeps
  the previous behavior.

## 0.2.1

- `ObdLimitsMessage.onConsecutiveFailures`: set to `resyncAndContinue` to let
  a recording keep polling once `maxConsecutiveTimeouts` is reached instead
  of always ending it. The command session's own resync (already triggered
  by the underlying failures) keeps running, the gap is still logged with a
  reason, and a genuine link drop during it still goes through the existing
  reconnect path. Default `endRecording` keeps the previous behavior.
- A one-shot step accepted by `enqueueOneShot` can no longer vanish without a
  result: a step still queued when the poller stops (user stop, recording
  end, or any other reason) is now reported as transport status `cancelled`
  with no raw data, instead of being dropped silently.

## 0.2.0

- `ObdBridge.enqueueOneShot(modules, steps)`: appends plan-shaped, read-once
  work to the recording currently running. Each step is read exactly once,
  using the given or already-planned module's setup, interleaved into the
  poller's idle time between due recurring steps so the recording's own
  steps keep their timing. Checked against the caller's policy and
  `HardGuard` first, exactly like a plan's own commands. Native keeps a
  bounded queue (4096 by default); a batch that would not fit is rejected
  whole, never partially queued or silently dropped. The queue is cleared
  when the recording ends; its depth is reported as
  `ObdRecordingStateMessage.oneShotQueueDepth`. Each result is written to
  the recording's buffer with an added `oneShot: true` field and also
  delivered through `ObdEventsApi.onOneShotResult`.

## 0.1.1

- Security fix: `HardGuard` no longer normalizes a command before checking it
  (whitespace stripping, upper-casing). A command is only accepted if it is
  already canonical (non-empty, only `0`-`9`, `A`-`Z` and `@`); anything else,
  including an embedded CR/LF that could smuggle a second command onto the
  wire past the single terminator a command session appends, is rejected
  outright. `CxCommandSession` also gained a defensive check rejecting any
  command that still contains `\r`/`\n` right before it would otherwise be
  written.

## 0.1.0

- BLE connection to ELM327/STN UART adapters (scan, bond, GATT, MTU, teardown).
- Serialized command session with framing, overflow handling, resync and a quiet
  period before the first command of a replacing session.
- Two-layer guard: library hard guard (read-only services, adapter allow-list,
  VIN and ECU serial always rejected) plus a caller-supplied `CommandPolicy`,
  enforced by the `ObdBridge` facade.
- Foreground recording service with reconnect/backoff, optional wake lock and a
  crash-safe JSONL buffer with configurable format identifier.
- ELM/ISO-TP response parser and a module-setup builder.

## 0.0.1

Initial skeleton.
