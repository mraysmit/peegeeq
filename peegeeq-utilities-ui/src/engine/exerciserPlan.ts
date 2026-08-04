/**
 * Delay / Priority / FIFO assignment plan (design §19.5 — Phase G.5-send).
 *
 * A pure module in the rampPlan mould: it owns the per-message DECISIONS —
 * which delay, priority and group message N carries — and nothing else. The
 * publication engine calls {@link assignmentFor} once per message at build
 * time; the manifest panel calls {@link buildManifest} after the run. Both
 * derive from the same (settings, runId, index) inputs, so the manifest states
 * exactly what the run sent without storing anything per message.
 *
 * "Random" values are deterministic in (runId, index) via a 32-bit FNV-1a
 * hash. That is deliberate, not a shortcut: an exerciser needs spread, and the
 * manifest needs to be reconstructable after the fact. True randomness would
 * force the engine to record every assignment — stored derived data, the exact
 * drift this codebase bans.
 */
import type {
  ExerciserSettings,
  ManifestEntry,
  MessageAssignment,
} from '../types/exerciser'

/** Round-robin priority cycles 1..PRIORITY_LEVELS by index (§19.5 mock). */
const PRIORITY_LEVELS = 10

/** FNV-1a 32-bit over `runId:index` — stable, well-spread, dependency-free. */
function hash32(runId: string, index: number): number {
  const input = `${runId}:${index}`
  let hash = 0x811c9dc5
  for (let i = 0; i < input.length; i++) {
    hash ^= input.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return hash >>> 0
}

/**
 * The delay, priority and group message `index` (0-based) carries.
 *
 * Throws when the per-key group strategy names a missing or empty value list:
 * fabricating a group would report FIFO ordering the run never exercised. The
 * page blocks Start in that state; the engine's build-error path is the
 * defence in depth.
 */
export function assignmentFor(
  settings: ExerciserSettings,
  runId: string,
  index: number,
  valueLists: Record<string, string[]>
): MessageAssignment {
  const { delay, priority, group } = settings

  let delaySeconds: number
  switch (delay.kind) {
    case 'fixed':
      delaySeconds = delay.seconds
      break
    case 'random':
      delaySeconds = hash32(runId, index) % (delay.maxSeconds + 1)
      break
    case 'per-index-ramp':
      delaySeconds = Math.min(index * delay.stepSeconds, delay.maxSeconds)
      break
  }

  const priorityValue =
    priority.kind === 'fixed' ? priority.priority : (index % PRIORITY_LEVELS) + 1

  let messageGroup: string
  switch (group.kind) {
    case 'single':
      messageGroup = group.group
      break
    case 'round-robin':
      messageGroup = `grp-${index % group.groups}`
      break
    case 'per-key': {
      const list = valueLists[group.listName]
      if (!list || list.length === 0) {
        throw new Error(
          `Value list "${group.listName}" is missing or empty — the per-key group strategy cannot assign groups`
        )
      }
      messageGroup = list[hash32(runId, index) % list.length]
      break
    }
  }

  return { delaySeconds, priority: priorityValue, messageGroup }
}

/**
 * The manifest for messages 1..count — each row IS {@link assignmentFor} at
 * that index, so it cannot disagree with what the engine built.
 */
export function buildManifest(
  settings: ExerciserSettings,
  runId: string,
  count: number,
  valueLists: Record<string, string[]>
): ManifestEntry[] {
  return Array.from({ length: count }, (_, index) => ({
    messageId: index + 1,
    ...assignmentFor(settings, runId, index, valueLists),
  }))
}
