/**
 * Type definitions for the Queue Message Generator (§10 of the feature design).
 *
 * These are pure type/interface declarations with no runtime behaviour.
 */

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
}

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
  targetTotal: number
  avgRate: number
  durationMs: number
  totalErrors: number
  finalStatus: RunStatus
  runId: string
  errors: PublishError[]
}
