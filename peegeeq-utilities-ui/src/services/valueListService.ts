/**
 * Value list persistence and file import/export (§12 of the feature design).
 *
 * Value lists live in localStorage under `peegeeq_value_lists` as a
 * Record<string, string[]>. They back the {{list:name}} placeholder tokens.
 */
import { readFileText } from './templateService'
import { persistJson } from './storagePersist'

const STORAGE_KEY = 'peegeeq_value_lists'

/** Read all value lists from localStorage. Returns {} when absent or corrupt. */
export function loadAll(): Record<string, string[]> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, string[]>)
      : {}
  } catch (error) {
    console.error('Failed to load value lists:', error)
    return {}
  }
}

/** Overwrite all value lists in localStorage. */
export function saveAll(lists: Record<string, string[]>): void {
  persistJson(STORAGE_KEY, lists, 'value lists')
}

/** Download a single list as `{name}.json` containing the value array. */
export function exportList(name: string, values: string[]): void {
  const blob = new Blob([JSON.stringify(values, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${name}.json`
  anchor.click()
  URL.revokeObjectURL(url)
}

/** Strip the directory and extension from a filename, e.g. `first_names.json` → `first_names`. */
function baseName(filename: string): string {
  const last = filename.split(/[\\/]/).pop() ?? filename
  return last.replace(/\.[^.]+$/, '')
}

/**
 * Parse and validate a `.json` file upload into a string array.
 *
 * Accepts a non-empty JSON array of string or number primitives; numbers are
 * coerced to strings (a non-fatal warning is reported). Rejects objects,
 * nested arrays, null values, non-arrays, and empty arrays.
 */
export async function importFromFile(
  file: File
): Promise<{ values: string[]; defaultName: string; errors: string[] }> {
  const defaultName = baseName(file.name)
  let parsed: unknown
  try {
    parsed = JSON.parse(await readFileText(file))
  } catch {
    return { values: [], defaultName, errors: [`${file.name}: not valid JSON`] }
  }

  if (!Array.isArray(parsed)) {
    return { values: [], defaultName, errors: [`${file.name}: expected a JSON array`] }
  }
  if (parsed.length === 0) {
    return { values: [], defaultName, errors: [`${file.name}: array is empty`] }
  }

  const errors: string[] = []
  const values: string[] = []
  let coerced = 0
  for (const item of parsed) {
    if (typeof item === 'string') {
      values.push(item)
    } else if (typeof item === 'number') {
      values.push(String(item))
      coerced++
    } else {
      return {
        values: [],
        defaultName,
        errors: [`${file.name}: array must contain only strings or numbers`],
      }
    }
  }

  if (coerced > 0) {
    errors.push(`${file.name}: coerced ${coerced} numeric value(s) to strings`)
  }

  return { values, defaultName, errors }
}

/**
 * Scan a template payload string for {{list:name}} tokens and return the
 * distinct list names referenced.
 */
export function extractListNames(payloadSchema: string): string[] {
  const names = [...payloadSchema.matchAll(/\{\{list:([A-Za-z0-9_-]+)\}\}/g)].map((m) => m[1])
  return [...new Set(names)]
}
