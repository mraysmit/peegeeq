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

export const scenarioSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
  config: runConfigSchema,
  createdAt: z.string(),
  updatedAt: z.string(),
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
