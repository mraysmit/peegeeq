/**
 * Scheduled-run persistence (Scheduled Runs design §5 — SCH.1).
 *
 * Three localStorage keys, each loaded with PER-ENTRY Zod validation: one
 * corrupt entry is dropped with a named report, never a blanked list (the
 * per-item safeParse rule). The history write path enforces the D8 bounds:
 * 200 entries newest-first (oldest dropped), stored summary errors capped at
 * 20 per record — the totalErrors COUNT stays truthful.
 *
 * Mirrors templateService/valueListService: plain functions over localStorage,
 * Zod for shape, download via Blob + object URL.
 */
import { z } from 'zod'
import { messageTemplateSchema, readFileText } from './templateService'
import type { ScheduledRun, ScheduleRunRecord, ScheduleTemplate } from '../types/schedule'

const SCHEDULES_KEY = 'peegeeq_generator_schedules'
const HISTORY_KEY = 'peegeeq_schedule_run_history'
const TEMPLATES_KEY = 'peegeeq_schedule_templates'

/** D8 bounds. */
export const HISTORY_MAX_ENTRIES = 200
export const HISTORY_MAX_ERRORS_PER_ENTRY = 20

// Ranges match the Zone B input bounds (feature §6.1): stored or imported
// configs must not carry values the UI cannot produce.
const runConfigSchema = z.object({
  setupId: z.string().min(1),
  queueName: z.string().min(1),
  rate: z.number().min(1),
  durationSecs: z.number().min(1).max(3600),
  maxBatchSize: z.number().min(1).max(100),
  warnThreshold: z.number().min(0),
  maxConsecErrors: z.number().min(0),
  template: messageTemplateSchema,
  previewIndex: z.number().min(1),
})

const publishErrorSchema = z.object({
  messageIndex: z.number(),
  httpStatus: z.number().optional(),
  message: z.string(),
  timestamp: z.string(),
})

const runSummarySchema = z.object({
  totalSent: z.number(),
  targetTotal: z.number(),
  avgRate: z.number(),
  durationMs: z.number(),
  totalErrors: z.number(),
  finalStatus: z.enum(['idle', 'running', 'completed', 'stopped', 'error']),
  runId: z.string(),
  errors: z.array(publishErrorSchema),
})

const scheduleSpecSchema = z.discriminatedUnion('kind', [
  z.object({ kind: z.literal('once'), runAt: z.string() }),
  z.object({ kind: z.literal('interval'), firstRunAt: z.string(), everyMinutes: z.number().int().min(1) }),
])

const scheduleOutcomeSchema = z.object({
  at: z.string(),
  result: z.enum(['completed', 'stopped', 'error', 'skipped', 'missed']),
  totalSent: z.number(),
  totalErrors: z.number(),
  detail: z.string().optional(),
})

const scheduledRunSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
  config: runConfigSchema,
  schedule: scheduleSpecSchema,
  enabled: z.boolean(),
  nextRunAt: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
})

const scheduleRunRecordSchema = z.object({
  id: z.string().min(1),
  scheduleId: z.string().min(1),
  scheduleName: z.string(),
  target: z.object({ setupId: z.string(), queueName: z.string() }),
  outcome: scheduleOutcomeSchema,
  summary: runSummarySchema.nullable(),
  config: runConfigSchema,
})

const scheduleTemplateSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
  config: runConfigSchema,
  createdAt: z.string(),
  updatedAt: z.string(),
})

/**
 * Load an array key with per-entry validation. Invalid entries are dropped and
 * reported by name — a visible report, never a silent discard.
 */
function loadValidated<T>(key: string, schema: z.ZodType<T>, describe: (raw: unknown, i: number) => string): T[] {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    const valid: T[] = []
    parsed.forEach((entry, i) => {
      const result = schema.safeParse(entry)
      if (result.success) {
        valid.push(result.data)
      } else {
        console.error(
          `Dropped invalid entry from ${key}: ${describe(entry, i)} — ${result.error.issues
            .map((iss) => iss.message)
            .join(', ')}`
        )
      }
    })
    return valid
  } catch (error) {
    console.error(`Failed to load ${key}:`, error)
    return []
  }
}

function nameOf(raw: unknown, i: number): string {
  const name = (raw as { name?: string; scheduleName?: string; id?: string }) ?? {}
  return name.name ?? name.scheduleName ?? name.id ?? `entry ${i}`
}

// ── Schedules ───────────────────────────────────────────────────────────────

export function loadAllSchedules(): ScheduledRun[] {
  return loadValidated(SCHEDULES_KEY, scheduledRunSchema, nameOf)
}

export function saveAllSchedules(schedules: ScheduledRun[]): void {
  localStorage.setItem(SCHEDULES_KEY, JSON.stringify(schedules))
}

// ── History (D8 bounds enforced on write) ───────────────────────────────────

export function loadHistory(): ScheduleRunRecord[] {
  return loadValidated(HISTORY_KEY, scheduleRunRecordSchema, nameOf)
}

export function saveHistory(records: ScheduleRunRecord[]): void {
  const bounded = records.slice(0, HISTORY_MAX_ENTRIES).map((record) =>
    record.summary && record.summary.errors.length > HISTORY_MAX_ERRORS_PER_ENTRY
      ? {
          ...record,
          summary: {
            ...record.summary,
            errors: record.summary.errors.slice(0, HISTORY_MAX_ERRORS_PER_ENTRY),
          },
        }
      : record
  )
  localStorage.setItem(HISTORY_KEY, JSON.stringify(bounded))
}

// ── Templates ───────────────────────────────────────────────────────────────

export function loadTemplates(): ScheduleTemplate[] {
  return loadValidated(TEMPLATES_KEY, scheduleTemplateSchema, nameOf)
}

export function saveTemplates(templates: ScheduleTemplate[]): void {
  localStorage.setItem(TEMPLATES_KEY, JSON.stringify(templates))
}

// ── Import (the D7 later phase: consumes the R11 export format) ─────────────

/**
 * Parse and validate a `schedules.json` upload. Accepts the exported array or
 * a single schedule object. Returns the structurally valid schedules plus one
 * named error per invalid entry. Duplicate-id handling and nextRunAt
 * recomputation are the store's responsibility (importSchedules).
 */
export async function importSchedulesFromFile(
  file: File
): Promise<{ schedules: ScheduledRun[]; errors: string[] }> {
  let parsed: unknown
  try {
    parsed = JSON.parse(await readFileText(file))
  } catch {
    return { schedules: [], errors: [`${file.name}: not valid JSON`] }
  }

  const candidates = Array.isArray(parsed) ? parsed : [parsed]
  const schedules: ScheduledRun[] = []
  const errors: string[] = []
  candidates.forEach((candidate, i) => {
    const result = scheduledRunSchema.safeParse(candidate)
    if (result.success) {
      schedules.push(result.data as ScheduledRun)
    } else {
      const name = (candidate as { name?: string })?.name ?? `entry ${i}`
      errors.push(`${name}: ${result.error.issues.map((iss) => iss.message).join(', ')}`)
    }
  })
  return { schedules, errors }
}

// ── Export (R11) ────────────────────────────────────────────────────────────

/** Download the complete schedule list as `schedules.json`. */
export function exportAllSchedules(schedules: ScheduledRun[]): void {
  const blob = new Blob([JSON.stringify(schedules, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = 'schedules.json'
  anchor.click()
  URL.revokeObjectURL(url)
}
