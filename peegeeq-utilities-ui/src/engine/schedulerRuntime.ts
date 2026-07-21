/**
 * Client-side scheduler (Scheduled Runs design §7 — SCH.3).
 *
 * A 15-second check drives due schedules through the shared runStarter. Rules:
 * - App start NEVER auto-fires (D3): every overdue enabled schedule records a
 *   `missed` outcome with the overdue duration, then advances (a one-shot is
 *   consumed and disabled).
 * - A due schedule fires only when no run is active; otherwise it records
 *   `skipped` and advances (D4). At most one schedule fires per check — after
 *   the first firing the run is active, so later due schedules skip.
 * - Fire-time missing value lists are recorded in the outcome detail (§6).
 * - The Web Locks API elects one firing tab (§7.5, corrected 2026-07-21 from a
 *   localStorage lease, whose acquire was not atomic — two tabs could both
 *   win and double-fire): the first tab to acquire the exclusive lock is the
 *   executor for as long as it lives; the browser releases the lock when the
 *   tab dies, so a waiting tab takes over immediately — no TTL, no heartbeat,
 *   no pagehide handling.
 * - No failure is silent (§10): a schedule that cannot be processed records an
 *   `error` outcome and is disabled; a failed check is reported and the next
 *   check still runs.
 */
import { message } from 'antd'
import { startGeneratorRun } from './runStarter'
import { findMissingLists } from './templateResolver'
import { computeNextRunAt, useScheduleStore } from '../stores/scheduleStore'
import { useGeneratorStore } from '../stores/generatorStore'
import { useValueListStore } from '../stores/valueListStore'
import type { RunConfig } from '../types/generator'
import type { ScheduledRun, ScheduleOutcome } from '../types/schedule'

// Defined in a UI-free module so the Playwright specs can import them;
// re-exported here for all existing importers.
export { CHECK_INTERVAL_MS, SCHEDULER_LOCK_NAME } from './schedulerConstants'
import { CHECK_INTERVAL_MS, SCHEDULER_LOCK_NAME } from './schedulerConstants'

export interface SchedulerRuntime {
  start(): void
  stop(): void
}

/**
 * Fire-time missing-value-lists note (§6): shared by the scheduler and the
 * Scheduled Runs screen's run-now actions. Null when nothing is missing.
 */
export function fireTimeMissingListsNote(config: RunConfig): string | null {
  const surface = [config.template.payloadSchema, ...Object.values(config.template.headers)].join('\n')
  const missing = findMissingLists(surface, useValueListStore.getState().snapshot())
  return missing.length > 0 ? `Missing value lists (resolved to ""): ${missing.join(', ')}` : null
}

/** Build a ScheduleOutcome from a finished run. */
export function outcomeFromRun(
  status: 'completed' | 'stopped' | 'error',
  summary: { totalSent: number; totalErrors: number },
  detail?: string
): ScheduleOutcome {
  return {
    at: new Date().toISOString(),
    result: status,
    totalSent: summary.totalSent,
    totalErrors: summary.totalErrors,
    detail,
  }
}

export function createSchedulerRuntime(): SchedulerRuntime {
  let timer: ReturnType<typeof setInterval> | null = null
  // D3 under lock contention: the missed sweep runs at the FIRST successful
  // lock acquisition, with app start as the cutoff. Running it only at start
  // was a defect — after a reload the previous page can still hold the lock,
  // the sweep would be skipped, and the overdue schedule would fire on the
  // first post-takeover check (an auto-start, which D3 forbids).
  let startedAtMs = 0
  let startupSweepDone = false
  let isLeader = false
  let abortAcquire: AbortController | null = null
  let releaseLock: (() => void) | null = null

  /**
   * Request the executor lock. The request resolves when this tab is granted
   * the exclusive lock — possibly much later, when the holding tab dies or
   * stops. While granted, every check runs; before that, checks return early.
   */
  function acquireLeadership(): void {
    if (!('locks' in navigator)) {
      // Without mutual exclusion this tab must not fire — another tab might
      // fire the same schedule. Said once, loudly; checks stay inert.
      console.error('Web Locks API unavailable; scheduled runs will not fire from this tab')
      message.error('Scheduled runs cannot fire: this browser does not support the Web Locks API.')
      return
    }
    abortAcquire = new AbortController()
    navigator.locks
      .request(SCHEDULER_LOCK_NAME, { mode: 'exclusive', signal: abortAcquire.signal }, () => {
        // Granted: this tab is the executor until stop() or tab death. The
        // browser releases the lock automatically when the page goes away, so
        // a waiting tab takes over immediately — no TTL, no heartbeat.
        isLeader = true
        check() // first-acquisition duties run now: missed sweep, then due schedules
        return new Promise<void>((resolve) => {
          releaseLock = resolve
        })
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return // stop() before grant — the normal cancel path
        console.error('Scheduler lock request failed:', error)
        message.error(
          `Scheduler lock request failed: ${error instanceof Error ? error.message : String(error)}`
        )
      })
  }

  function outcome(
    result: ScheduleOutcome['result'],
    totalSent: number,
    totalErrors: number,
    detail?: string
  ): ScheduleOutcome {
    return { at: new Date().toISOString(), result, totalSent, totalErrors, detail }
  }

  /**
   * D3: schedules already overdue when the app STARTED are recorded as missed —
   * never fired. `cutoffMs` is the app start time; schedules becoming due after
   * it (while waiting for the lock) fire normally.
   */
  function markOverdueAsMissed(cutoffMs: number): void {
    const now = new Date()
    for (const schedule of useScheduleStore.getState().schedules) {
      if (!schedule.enabled || schedule.nextRunAt === null) continue
      const due = new Date(schedule.nextRunAt).getTime()
      if (due >= cutoffMs) continue
      const overdueSecs = Math.round((cutoffMs - due) / 1000)
      useScheduleStore
        .getState()
        .recordOutcome(
          schedule.id,
          outcome('missed', 0, 0, `Missed by ${overdueSecs}s — the app was not open at the scheduled time`),
          null,
          computeNextRunAt(schedule.schedule, now)
        )
    }
  }

  function processDue(schedule: ScheduledRun, now: Date): void {
    const advanceTo = computeNextRunAt(schedule.schedule, now)

    if (useGeneratorStore.getState().runState.status === 'running') {
      useScheduleStore
        .getState()
        .recordOutcome(schedule.id, outcome('skipped', 0, 0, 'Skipped — another run was active'), null, advanceTo)
      return
    }

    const note = fireTimeMissingListsNote(schedule.config)
    const handle = startGeneratorRun(schedule.config, {
      onTerminal: (summary, status, reason) => {
        // History only — the schedule advanced at fire time. Advancing here
        // again would silently discard any slot that came due during the run
        // (which must record a skip instead, per R7).
        const detail = [note, reason].filter(Boolean).join('; ') || undefined
        useScheduleStore
          .getState()
          .recordOutcome(
            schedule.id,
            outcome(status as 'completed' | 'stopped' | 'error', summary.totalSent, summary.totalErrors, detail),
            summary,
            undefined,
            // Fire-time snapshot: keeps the record valid if the schedule is
            // deleted while the run executes.
            { scheduleName: schedule.name, config: schedule.config }
          )
      },
    })
    if (handle === null) {
      // A run started between the status check and here — same as rule 1.
      useScheduleStore
        .getState()
        .recordOutcome(schedule.id, outcome('skipped', 0, 0, 'Skipped — another run was active'), null, advanceTo)
      return
    }
    // Advance AT FIRE TIME so the in-flight run's schedule is no longer due.
    // Without this, any run outlasting one 15-second check is found due again,
    // sees its own run as "another run", records a false skipped row, and a
    // one-shot is consumed prematurely by that skip.
    useScheduleStore.getState().advanceSchedule(schedule.id, advanceTo)
  }

  function check(): void {
    try {
      if (!isLeader) return
      if (!startupSweepDone) {
        markOverdueAsMissed(startedAtMs)
        startupSweepDone = true
      }
      const now = new Date()
      const due = useScheduleStore
        .getState()
        .schedules.filter(
          (s) => s.enabled && s.nextRunAt !== null && new Date(s.nextRunAt).getTime() <= now.getTime()
        )
        .sort((a, b) => a.nextRunAt!.localeCompare(b.nextRunAt!))

      for (const schedule of due) {
        try {
          processDue(schedule, now)
        } catch (error) {
          // One broken schedule must not break the check for the rest. The
          // failure is recorded on the schedule (disabled via null advance)
          // and reported.
          console.error(`Scheduler failed to process "${schedule.name}":`, error)
          message.error(`Scheduled run "${schedule.name}" failed to start: ${error instanceof Error ? error.message : String(error)}`)
          useScheduleStore
            .getState()
            .recordOutcome(
              schedule.id,
              outcome('error', 0, 0, `Scheduler failure: ${error instanceof Error ? error.message : String(error)}`),
              null,
              null
            )
        }
      }
    } catch (error) {
      console.error('Scheduler check failed:', error)
      message.error(`Scheduler check failed: ${error instanceof Error ? error.message : String(error)}`)
    }
  }

  return {
    start() {
      if (timer !== null) return
      startedAtMs = Date.now()
      startupSweepDone = false
      isLeader = false
      useScheduleStore.getState().loadFromStorage()
      // The first check runs inside the lock grant (acquireLeadership), not
      // here — before the grant this tab must not fire anything.
      acquireLeadership()
      timer = setInterval(check, CHECK_INTERVAL_MS)
    },

    stop() {
      if (timer !== null) {
        clearInterval(timer)
        timer = null
      }
      isLeader = false
      releaseLock?.() // releases a HELD lock — a waiting tab takes over immediately
      releaseLock = null
      abortAcquire?.abort() // cancels a still-PENDING request
      abortAcquire = null
    },
  }
}

/** The application-wide instance, started once from App mount (SCH.6). */
export const schedulerRuntime = createSchedulerRuntime()
