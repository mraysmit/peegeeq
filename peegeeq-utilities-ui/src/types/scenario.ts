/**
 * Type definitions for saved scenarios (design §19.4 — Phase G.4).
 *
 * Pure type declarations with no runtime behaviour, mirroring
 * src/types/generator.ts and src/types/schedule.ts.
 */
import type { RunConfig } from './generator'

/**
 * A named, replayable run configuration. Persisted under `peegeeq_scenarios`.
 *
 * `config` is the FULL SNAPSHOT at save time — target, rate/duration/guards,
 * the working template, and the preview index — the same working-copy contract
 * a schedule uses. Everything the §19.4 table shows besides the name and the
 * timestamps is DERIVED from `config` at render time (target string, rate ×
 * duration total, template name) and is deliberately not stored.
 *
 * The mode and phase list that §19.3 (Profile mode) needs are not fields here:
 * nothing produces them until G.3 builds that mode, and a stored field with no
 * producer is data that cannot be trusted.
 */
export interface Scenario {
  id: string
  name: string
  config: RunConfig
  createdAt: string // ISO 8601
  updatedAt: string // ISO 8601
}
