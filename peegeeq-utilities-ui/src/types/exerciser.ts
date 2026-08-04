/**
 * Type definitions for the Delay / Priority / FIFO exerciser (design §19.5 —
 * Phase G.5-send).
 *
 * Pure type declarations with no runtime behaviour, mirroring
 * src/types/profile.ts and src/types/ramp.ts.
 *
 * The SETTINGS are the source of truth. Every per-message assignment (delay,
 * priority, group) and the post-run manifest are derived from them by
 * `assignmentFor` / `buildManifest` in engine/exerciserPlan.ts — deterministic
 * in (settings, runId, index) — so the manifest can never disagree with what
 * the run sent. Nothing derived is stored.
 */

/** How each message's delaySeconds is assigned. */
export type DelayStrategy =
  | { kind: 'fixed'; seconds: number }
  /** Uniform in [0, maxSeconds], deterministic per (runId, index). */
  | { kind: 'random'; maxSeconds: number }
  /** Grows with the message index: min(index × stepSeconds, maxSeconds). */
  | { kind: 'per-index-ramp'; stepSeconds: number; maxSeconds: number }

/** How each message's priority is assigned. */
export type PriorityStrategy =
  | { kind: 'fixed'; priority: number }
  /** Cycles 1..10 by message index. */
  | { kind: 'round-robin' }

/**
 * How each message's messageGroup is assigned — the FIFO ordering surface.
 *
 * `per-key` deviates from the §19.5 mock's `{{customerId}}` notation: the only
 * per-message key source this app has is a value list, so the strategy names
 * the list directly instead of pretending to accept arbitrary tokens. The pick
 * is deterministic per (runId, index), unlike the {{list:...}} template token,
 * so the manifest can state which group each message carried.
 */
export type GroupStrategy =
  | { kind: 'single'; group: string }
  /** Groups grp-0 .. grp-(groups-1), assigned round-robin by index. */
  | { kind: 'round-robin'; groups: number }
  | { kind: 'per-key'; listName: string }

/** The exerciser's controls (Zone B in exerciser mode). */
export interface ExerciserSettings {
  delay: DelayStrategy
  priority: PriorityStrategy
  group: GroupStrategy
}

/** The three MessageRequest fields the exerciser drives, for one message. */
export interface MessageAssignment {
  delaySeconds: number
  priority: number
  messageGroup: string
}

/** One manifest row: which assignment a message id carried. */
export interface ManifestEntry extends MessageAssignment {
  messageId: number // 1-based, the same id {{messageId}} resolves to
}
