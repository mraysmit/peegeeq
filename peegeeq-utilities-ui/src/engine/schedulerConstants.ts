/**
 * Scheduler timing constants, in a UI-free module.
 *
 * The Playwright e2e specs import these to derive their negative-assertion
 * waits ("nothing fires across two check cycles") from the REAL cycle length —
 * a hard-coded 35 s in a spec silently breaks when the cycle changes. This
 * module must stay free of antd/React/store imports so it loads in the
 * Playwright node runtime.
 */

/** The scheduler's check cycle (§7.1). */
export const CHECK_INTERVAL_MS = 15_000

/** Web Locks name electing the single firing tab across this origin's tabs (§7.5). */
export const SCHEDULER_LOCK_NAME = 'peegeeq_scheduler'
