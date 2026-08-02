/**
 * Saved-scenario persistence and file import/export (design §19.4 — Phase G.4).
 *
 * Scenarios live in localStorage under `peegeeq_scenarios`. There is no backend
 * persistence: a scenario is a client-side replay of a run configuration.
 *
 * Mirrors templateService/scheduleService — plain functions over localStorage,
 * Zod for shape, download via Blob + object URL — and loads with PER-ENTRY
 * validation, so one corrupt entry is dropped with a named report instead of
 * blanking the whole list.
 */
import { z } from 'zod'
import { readFileText, triggerDownload } from './templateService'
import { loadValidated, runConfigSchema } from './scheduleService'
import { persistJson } from './storagePersist'
import type { Scenario } from '../types/scenario'

const STORAGE_KEY = 'peegeeq_scenarios'

/** Bounds match the phases editor's inputs: rate ≥ 0 (0 = idle), duration 1–3600. */
const profilePhaseSchema = z.object({
  id: z.string().min(1),
  label: z.string(),
  rate: z.number().min(0),
  durationSecs: z.number().min(1).max(3600),
})

export const scenarioSchema = z
  .object({
    id: z.string().min(1),
    name: z.string().min(1),
    config: runConfigSchema,
    // Optional-with-default, NOT a discriminated union: scenarios saved before
    // G.3d carry no mode, and a strict union would drop the user's saved data
    // for a field that did not exist when they saved it.
    mode: z.enum(['flat', 'profile']).default('flat'),
    phases: z.array(profilePhaseSchema).optional(),
    createdAt: z.string(),
    updatedAt: z.string(),
  })
  .superRefine((scenario, ctx) => {
    // A profile scenario with no phases can never run — the runner refuses an
    // empty profile. Storing one would be a scenario that silently does nothing.
    if (scenario.mode === 'profile' && (scenario.phases ?? []).length === 0) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['phases'],
        message: 'a profile scenario needs at least one phase',
      })
    }
  })

function nameOf(raw: unknown, i: number): string {
  const entry = (raw as { name?: string; id?: string }) ?? {}
  return entry.name ?? entry.id ?? `entry ${i}`
}

/** Read all scenarios from localStorage, dropping and reporting invalid entries. */
export function loadAll(): Scenario[] {
  return loadValidated(STORAGE_KEY, scenarioSchema, nameOf)
}

/** Overwrite all scenarios in localStorage. */
export function saveAll(scenarios: Scenario[]): void {
  persistJson(STORAGE_KEY, scenarios, 'scenarios')
}

/** Download a single scenario as a `.json` file. */
export function exportScenario(scenario: Scenario): void {
  triggerDownload(JSON.stringify(scenario, null, 2), `${scenario.name || scenario.id}.json`)
}

/** Download all scenarios as a single `.json` file. */
export function exportAll(scenarios: Scenario[]): void {
  triggerDownload(JSON.stringify(scenarios, null, 2), 'scenarios.json')
}

/**
 * Parse and validate a `.json` file upload.
 *
 * Accepts either a single scenario object or an array of scenarios. Returns the
 * structurally valid scenarios plus one named error per invalid entry.
 * Duplicate-id handling against existing storage is the store's responsibility.
 */
export async function importFromFile(
  file: File
): Promise<{ scenarios: Scenario[]; errors: string[] }> {
  let parsed: unknown
  try {
    parsed = JSON.parse(await readFileText(file))
  } catch {
    return { scenarios: [], errors: [`${file.name}: not valid JSON`] }
  }

  const candidates = Array.isArray(parsed) ? parsed : [parsed]
  const scenarios: Scenario[] = []
  const errors: string[] = []
  candidates.forEach((candidate, i) => {
    const result = scenarioSchema.safeParse(candidate)
    if (result.success) {
      scenarios.push(result.data as Scenario)
    } else {
      errors.push(`${nameOf(candidate, i)}: ${result.error.issues.map((iss) => iss.message).join(', ')}`)
    }
  })

  return { scenarios, errors }
}
