/**
 * Type definitions for the correlation / trace seed generator (design §19.6 —
 * Phase G.6).
 *
 * Pure type declarations with no runtime behaviour, mirroring
 * src/types/exerciser.ts.
 *
 * The SETTINGS are the source of truth. Every message's correlationId and the
 * post-run emitted-ids report are derived by engine/tracePlan.ts —
 * deterministic in (settings, runId, index, rate, maxBatchSize) — so the
 * report can never disagree with what the run sent.
 *
 * Causation chains are an ID SCHEME, reported not sent: `causationId` is by
 * design an attribute of the bi-temporal event store, and queue messages carry
 * `correlationId` only. When chains are seeded, the minted correlation ids are
 * organized parent → child in the report, ready to use downstream
 * (management-ui CausationTree / Events); the messages themselves stay clean.
 */

/** How correlation ids are allotted across the run's messages. */
export type CorrelationStrategy =
  | { kind: 'per-run' }
  /** One id per PUBLISH BATCH — the engine's real batch boundaries. */
  | { kind: 'per-batch' }
  | { kind: 'every-n'; n: number }

/** Whether and how the minted ids are organized into parent → child chains. */
export interface CausationSettings {
  enabled: boolean
  childrenPerParent: number
}

/** The trace-seed controls (Zone B in trace mode). */
export interface TraceSettings {
  correlation: CorrelationStrategy
  causation: CausationSettings
}

/** The correlation identity one message carries. */
export interface TraceAssignment {
  correlationId: string
  /**
   * The chain root's id when causation chains are seeded and this message's
   * id is a child. REPORT-ONLY — never sent on the message.
   */
  parentCorrelationId?: string
}

/** One minted correlation id in the emitted-ids report. */
export interface TraceReportEntry {
  correlationId: string
  role: 'root' | 'child'
  /** Present for children: the chain root's correlation id. */
  parentCorrelationId?: string
  /** First 1-based message id carrying this correlation id. */
  firstMessageId: number
  /** How many of the run's attempted messages carried it. */
  messageCount: number
}

/** The derived emitted-ids report (Zone E in trace mode). */
export interface TraceReport {
  runId: string
  totalMessages: number
  entries: TraceReportEntry[]
  chainCount: number
}
