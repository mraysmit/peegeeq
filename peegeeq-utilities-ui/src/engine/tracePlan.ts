/**
 * Correlation / trace seed plan (design §19.6 — Phase G.6).
 *
 * A pure module in the exerciserPlan mould: it owns the per-message DECISION —
 * which correlation id message N carries — and the derived emitted-ids report,
 * nothing else. The publication engine calls {@link traceFor} once per message
 * at build time; the report panel calls {@link buildTraceReport} after the
 * run. Both derive from the same inputs, so the report states exactly which
 * ids the run emitted without storing anything per message.
 *
 * Ids are UUID-shaped but DETERMINISTIC in (runId, group), via FNV-1a — the
 * same reasoning as exerciserPlan: the report must be reconstructable after
 * the fact without recording every assignment.
 *
 * Causation chains are an ID SCHEME, reported not sent: `causationId` is by
 * design a bi-temporal event-store attribute, and queue messages carry
 * `correlationId` only. Chains organize the MINTED IDS (1 root +
 * childrenPerParent children per chain) for downstream use in management-ui's
 * CausationTree / Events; they never alter what a message carries.
 */
import type {
  TraceAssignment,
  TraceReport,
  TraceReportEntry,
  TraceSettings,
} from '../types/trace'

/** FNV-1a 32-bit — stable, well-spread, dependency-free (exerciserPlan's). */
function hash32(input: string): number {
  let hash = 0x811c9dc5
  for (let i = 0; i < input.length; i++) {
    hash ^= input.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return hash >>> 0
}

/** A UUID-shaped id minted deterministically from (runId, group). */
function traceIdFor(runId: string, group: number): string {
  const hex = [0, 1, 2, 3]
    .map((k) => hash32(`${runId}:trace:${group}:${k}`).toString(16).padStart(8, '0'))
    .join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`
}

/**
 * The correlation GROUP message `index` (0-based) belongs to.
 *
 * `per-batch` mirrors the engine's real batching: each tick carries the full
 * per-second quota (max(1, floor(rate)) messages) split into groups of at most
 * maxBatchSize, so the remainder batch ends at the tick edge. A plain
 * floor(index / maxBatchSize) would merge a remainder batch with the next
 * tick's first batch whenever rate is not a multiple of maxBatchSize.
 */
function groupIndexFor(
  settings: TraceSettings,
  index: number,
  rate: number,
  maxBatchSize: number
): number {
  const { correlation } = settings
  switch (correlation.kind) {
    case 'per-run':
      return 0
    case 'every-n':
      return Math.floor(index / correlation.n)
    case 'per-batch': {
      const quota = Math.max(1, Math.floor(rate))
      const batchesPerTick = Math.ceil(quota / maxBatchSize)
      const tick = Math.floor(index / quota)
      const positionInTick = index % quota
      return tick * batchesPerTick + Math.floor(positionInTick / maxBatchSize)
    }
  }
}

/** The correlation identity message `index` (0-based) carries. */
export function traceFor(
  settings: TraceSettings,
  runId: string,
  index: number,
  rate: number,
  maxBatchSize: number
): TraceAssignment {
  const group = groupIndexFor(settings, index, rate, maxBatchSize)
  const correlationId = traceIdFor(runId, group)
  const { causation } = settings
  if (!causation.enabled) return { correlationId }

  const chainSize = causation.childrenPerParent + 1
  const positionInChain = group % chainSize
  if (positionInChain === 0) return { correlationId }
  const rootGroup = group - positionInChain
  return { correlationId, parentCorrelationId: traceIdFor(runId, rootGroup) }
}

/**
 * The emitted-ids report for messages 1..count — every entry IS
 * {@link traceFor} at that group's first index, so it cannot disagree with
 * what the engine sent.
 */
export function buildTraceReport(
  settings: TraceSettings,
  runId: string,
  count: number,
  rate: number,
  maxBatchSize: number
): TraceReport {
  const entries: TraceReportEntry[] = []
  let currentGroup = -1
  for (let index = 0; index < count; index++) {
    const group = groupIndexFor(settings, index, rate, maxBatchSize)
    if (group !== currentGroup) {
      currentGroup = group
      const assignment = traceFor(settings, runId, index, rate, maxBatchSize)
      entries.push({
        correlationId: assignment.correlationId,
        role: assignment.parentCorrelationId === undefined ? 'root' : 'child',
        ...(assignment.parentCorrelationId !== undefined
          ? { parentCorrelationId: assignment.parentCorrelationId }
          : {}),
        firstMessageId: index + 1,
        messageCount: 1,
      })
    } else {
      entries[entries.length - 1].messageCount++
    }
  }
  return {
    runId,
    totalMessages: count,
    entries,
    chainCount: entries.filter((e) => e.role === 'root').length,
  }
}
