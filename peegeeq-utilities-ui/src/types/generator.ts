/**
 * Type definitions for the Queue Message Generator (§10 of the feature design).
 *
 * These are pure type/interface declarations with no runtime behaviour.
 */
import type { ExerciserSettings } from './exerciser'
import type { TraceSettings } from './trace'

/** Lifecycle status of a publication run. */
export type RunStatus = 'idle' | 'running' | 'completed' | 'stopped' | 'error'

/** A named list of string values used by {{list:name}} placeholder tokens. */
export interface ValueList {
  name: string // localStorage key; must match [A-Za-z0-9_-]+
  values: string[] // the actual string values
  createdAt: string // ISO 8601
  updatedAt: string // ISO 8601
}

/** A named message template with placeholder tokens, persisted in localStorage. */
export interface MessageTemplate {
  id: string
  name: string
  description?: string
  messageType: string
  payloadSchema: string
  headers: Record<string, string>
  priority: number
  delaySeconds: number
  messageGroup?: string
  createdAt: string
  updatedAt: string
}

/** Full configuration for a single publication run. */
export interface RunConfig {
  setupId: string
  queueName: string
  rate: number // msg/s, no upper cap
  durationSecs: number
  maxBatchSize: number // 1–100
  warnThreshold: number // 0 = no warning (non-blocking)
  maxConsecErrors: number // 0 = disabled
  template: MessageTemplate
  previewIndex: number // messageId to use for Preview (default 1)
  /**
   * Per-message delay/priority/group strategies (design §19.5 — exerciser
   * mode). When present, the engine derives the three MessageRequest fields
   * per message via exerciserPlan.assignmentFor, overriding the template's
   * scalar priority/delaySeconds/messageGroup. Absent for every other mode.
   */
  ordering?: ExerciserSettings
  /**
   * Correlation-id strategy (design §19.6 — trace-seed mode). When present,
   * the engine derives each message's correlationId via tracePlan.traceFor —
   * both the MessageRequest field and the {{correlationId}} token — instead
   * of the run's single identity id. Absent for every other mode. Never set
   * together with `ordering`; the modes are mutually exclusive.
   */
  trace?: TraceSettings
}

/** The Zone B rate/duration/guard settings — the numeric slice of {@link RunConfig}. */
export type RateSettings = Pick<
  RunConfig,
  'rate' | 'durationSecs' | 'maxBatchSize' | 'warnThreshold' | 'maxConsecErrors'
>

/** A single failed batch publication, recorded for the run summary. */
export interface PublishError {
  messageIndex: number
  httpStatus?: number
  message: string
  timestamp: string
}

/** Live, mutable run state owned by the generator store. */
export interface RunState {
  status: RunStatus
  totalToSend: number
  sent: number
  errors: PublishError[]
  elapsedMs: number
  currentRate: number // rolling 1-second window
  consecErrors: number // current consecutive error streak
  runId: string | null
  startedAt: number | null
  autoStopReason?: string // populated when status === 'error'
}

/** Immutable summary produced when a run reaches a terminal state. */
export interface RunSummary {
  totalSent: number
  /**
   * Messages the engine BUILT and handed to publishBatch, successful or not.
   * Distinct from `totalSent` (server-acknowledged only) and not derivable
   * from it: a failed batch's ids were attempted, and the manifest (§19.5)
   * must cover them. Optional because summaries predating G.5 (stored run
   * history) and the runStarter's synthetic failure summary do not carry it.
   */
  totalAttempted?: number
  targetTotal: number
  avgRate: number
  durationMs: number
  totalErrors: number
  finalStatus: RunStatus
  runId: string
  errors: PublishError[]
}
