/**
 * Initial values for the generator's Zone B/C controls.
 *
 * Split out of the component files (2026-08-04 debt tidy-up): a component
 * file that also exports constants breaks Vite's fast-refresh contract
 * (react-refresh/only-export-components), so every "defaults" export lives
 * here and the component files export components only. Each constant keeps
 * its owning component's doc reference.
 */
import type { MessageTemplate, RateSettings } from '../../types/generator'
import type { ProfilePhase } from '../../types/profile'
import type { RampSettings } from '../../types/ramp'
import type { ExerciserSettings } from '../../types/exerciser'
import type { TraceSettings } from '../../types/trace'

/** §6.1 Zone B defaults (RateControls). */
export const RATE_DEFAULTS: RateSettings = {
  rate: 10,
  durationSecs: 60,
  maxBatchSize: 10,
  warnThreshold: 500,
  maxConsecErrors: 10,
}

/** Blank working copy for the New action (TemplateEditor). */
export function blankTemplate(): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: crypto.randomUUID(),
    name: 'Untitled',
    messageType: '',
    payloadSchema: '{}',
    headers: {},
    priority: 5,
    delaySeconds: 0,
    createdAt: now,
    updatedAt: now,
  }
}

/** A new phase: modest, immediately runnable defaults with a fresh id (ProfilePhasesEditor). */
export function makeDefaultPhase(): ProfilePhase {
  return { id: crypto.randomUUID(), label: 'phase', rate: 100, durationSecs: 30 }
}

/** §19.1 defaults: a gentle climb that reaches a useful rate quickly (RampControls). */
export const RAMP_DEFAULTS: RampSettings = {
  startRate: 10,
  stepRate: 50,
  stepSecs: 10,
  maxRate: 500,
  stopOn: 'error-rate',
  errorRatePercent: 5,
}

/** §19.5 defaults: the mock's manifest shape — grouped round-robin, fixed 5s/p5 (ExerciserControls). */
export const EXERCISER_DEFAULTS: ExerciserSettings = {
  delay: { kind: 'fixed', seconds: 5 },
  priority: { kind: 'fixed', priority: 5 },
  group: { kind: 'round-robin', groups: 4 },
}

/**
 * §19.6 defaults: the mock's output arithmetic — a new id every 100 messages,
 * chains of 1 root + 3 children (1,200 messages → 12 ids / 3 chains)
 * (TraceControls).
 */
export const TRACE_DEFAULTS: TraceSettings = {
  correlation: { kind: 'every-n', n: 100 },
  causation: { enabled: true, childrenPerParent: 3 },
}
