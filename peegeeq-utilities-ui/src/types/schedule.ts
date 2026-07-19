/**
 * Type definitions for scheduled generator runs
 * (PEEGEEQ_GENERATOR_SCHEDULED_RUNS_DESIGN.md §5).
 *
 * Pure type/interface declarations with no runtime behaviour, mirroring
 * src/types/generator.ts.
 */
import type { RunConfig, RunSummary } from './generator'

/** When a schedule fires: once at a datetime, or repeating at a fixed interval. */
export type ScheduleSpec =
  | { kind: 'once'; runAt: string } // ISO 8601
  | { kind: 'interval'; firstRunAt: string; everyMinutes: number } // everyMinutes ≥ 1

/** The result of one firing (or non-firing) of a schedule. */
export interface ScheduleOutcome {
  at: string // firing (or skip/miss) time, ISO 8601
  result: 'completed' | 'stopped' | 'error' | 'skipped' | 'missed'
  totalSent: number // 0 for skipped/missed
  totalErrors: number
  detail?: string // autoStopReason / skip reason / miss reason
}

/**
 * One scheduled generator run. Persisted under `peegeeq_generator_schedules`.
 *
 * Carries SCHEDULING state only (spec, enabled, nextRunAt). Run outcomes live
 * exclusively in the run history ({@link ScheduleRunRecord}) — the schedules
 * table derives its "last outcome" column from the newest history record.
 */
export interface ScheduledRun {
  id: string
  name: string
  /** FULL SNAPSHOT at scheduling time — the working-copy contract (§6). */
  config: RunConfig
  schedule: ScheduleSpec
  enabled: boolean
  nextRunAt: string | null // null once a one-shot is consumed
  createdAt: string
  updatedAt: string
}

/**
 * One row of the run history (R12). Written for EVERY outcome, including
 * skipped and missed. Deliberately denormalised: scheduleName and target are
 * copied in so the entry stays meaningful after its schedule is deleted.
 * Persisted under `peegeeq_schedule_run_history`, newest first, capped (D8).
 */
export interface ScheduleRunRecord {
  id: string
  scheduleId: string
  scheduleName: string
  target: { setupId: string; queueName: string }
  outcome: ScheduleOutcome
  /** Present for fired runs; stored errors capped at 20 (D8). */
  summary: RunSummary | null
  /** Frozen config of the firing — what "Save as template" captures (R13). */
  config: RunConfig
}

/**
 * A reusable run configuration (R13). No timing — that is chosen per schedule.
 * Persisted under `peegeeq_schedule_templates`.
 */
export interface ScheduleTemplate {
  id: string
  name: string
  config: RunConfig
  createdAt: string
  updatedAt: string
}
