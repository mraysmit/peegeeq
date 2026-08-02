/**
 * Type definitions for saved scenarios (design §19.4 — Phase G.4).
 *
 * Pure type declarations with no runtime behaviour, mirroring
 * src/types/generator.ts and src/types/schedule.ts.
 */
import type { RunConfig } from './generator'
import type { ProfilePhase } from './profile'

/**
 * A named, replayable run configuration. Persisted under `peegeeq_scenarios`.
 *
 * `config` is the FULL SNAPSHOT at save time — target, rate/duration/guards,
 * the working template, and the preview index — the same working-copy contract
 * a schedule uses. Everything the §19.4 table shows besides the name and the
 * timestamps is DERIVED from `config` at render time (target string, rate ×
 * duration total, template name) and is deliberately not stored.
 *
 * `mode` and `phases` arrived with G.3d, once Profile mode existed to produce
 * them. `mode` is OPTIONAL-with-default at the storage boundary: scenarios
 * saved by G.4 carry no mode, and rejecting them would destroy saved data for a
 * field the user never had. They load as flat.
 */
export interface Scenario {
  id: string
  name: string
  config: RunConfig
  /** Which generator mode replays this scenario. Absent in G.4-era data. */
  mode: 'flat' | 'profile'
  /** The traffic shape — present only for `mode: 'profile'`, never empty. */
  phases?: ProfilePhase[]
  createdAt: string // ISO 8601
  updatedAt: string // ISO 8601
}
