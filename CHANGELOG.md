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
