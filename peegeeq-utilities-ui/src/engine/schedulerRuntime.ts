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
 * - A localStorage lease elects one firing tab (§7.5): a tab fires only while
 *   it holds the lease; an expired lease (no heartbeat within the TTL) is
 *   taken over. Heartbeats renew on every check.
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

export const CHECK_INTERVAL_MS = 15_000
export const LEASE_TTL_MS = 30_000
const LEASE_KEY = 'peegeeq_scheduler_lease'

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

export function createSchedulerRuntime(tabId: string = crypto.randomUUID()): SchedulerRuntime {
  let timer: ReturnType<typeof setInterval> | null = null
  // D3 under lease contention: the missed sweep runs at the FIRST successful
  // lease acquisition, with app start as the cutoff. Running it only at start
  // was a defect — after a reload the previous page's lease can still be
  // alive, the sweep would be skipped, and the overdue schedule would fire on
  // the first post-takeover check (an auto-start, which D3 forbids).
  let startedAtMs = 0
  let startupSweepDone = false

  /**
   * Acquire or renew the firing lease. Returns false when another live tab
   * holds it. localStorage has no compare-and-set; the write-then-read-back
   * check resolves near-simultaneous writes to a single winner.
   */
  function holdsLease(): boolean {
    try {
      const now = Date.now()
      const raw = localStorage.getItem(LEASE_KEY)
      if (raw) {
        const lease = JSON.parse(raw) as { tabId?: string; heartbeat?: number }
        const alive = typeof lease.heartbeat === 'number' && now - lease.heartbeat <= LEASE_TTL_MS
        if (alive && lease.tabId !== tabId) return false
      }
      localStorage.setItem(LEASE_KEY, JSON.stringify({ tabId, heartbeat: now }))
      const readBack = JSON.parse(localStorage.getItem(LEASE_KEY) ?? '{}') as { tabId?: string }
      return readBack.tabId === tabId
    } catch (error) {
      // Without a working lease this tab must not fire — another tab might.
      console.error('Scheduler lease unavailable; this tab will not fire schedules:', error)
      return false
    }
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
   * it (while waiting for the lease) fire normally.
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

  function releaseLease(): void {
    try {
      const raw = localStorage.getItem(LEASE_KEY)
      if (raw && (JSON.parse(raw) as { tabId?: string }).tabId === tabId) {
        localStorage.removeItem(LEASE_KEY)
      }
    } catch (error) {
      console.error('Scheduler lease release failed (another tab will take over on expiry):', error)
    }
  }

  // On navigation/reload the browser does not run React unmount cleanup, so the
  // lease would stay alive for its full TTL and stall the next page's scheduler.
  const onPageHide = () => releaseLease()

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
        const detail = [note, reason].filter(Boolean).join('; ') || undefined
        useScheduleStore
          .getState()
          .recordOutcome(
            schedule.id,
            outcome(status as 'completed' | 'stopped' | 'error', summary.totalSent, summary.totalErrors, detail),
            summary,
            computeNextRunAt(schedule.schedule, new Date())
          )
      },
    })
    if (handle === null) {
      // A run started between the status check and here — same as rule 1.
      useScheduleStore
        .getState()
        .recordOutcome(schedule.id, outcome('skipped', 0, 0, 'Skipped — another run was active'), null, advanceTo)
    }
  }

  function check(): void {
    try {
      if (!holdsLease()) return
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
      useScheduleStore.getState().loadFromStorage()
      window.addEventListener('pagehide', onPageHide)
      check()
      timer = setInterval(check, CHECK_INTERVAL_MS)
    },

    stop() {
      if (timer !== null) {
        clearInterval(timer)
        timer = null
      }
      window.removeEventListener('pagehide', onPageHide)
      releaseLease()
    },
  }
}

/** The application-wide instance, started once from App mount (SCH.6). */
export const schedulerRuntime = createSchedulerRuntime()
