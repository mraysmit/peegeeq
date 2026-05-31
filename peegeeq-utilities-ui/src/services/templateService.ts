/**
 * Template persistence and file import/export (§12 of the feature design).
 *
 * Templates live in localStorage under `peegeeq_msg_templates`. There is no
 * backend persistence in v1.
 */
import { z } from 'zod'
import type { MessageTemplate } from '../types/generator'

const STORAGE_KEY = 'peegeeq_msg_templates'

const messageTemplateSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
  description: z.string().optional(),
  messageType: z.string(),
  payloadSchema: z.string(),
  headers: z.record(z.string(), z.string()),
  priority: z.number(),
  delaySeconds: z.number(),
  messageGroup: z.string().optional(),
  createdAt: z.string(),
  updatedAt: z.string(),
})

/** Read all templates from localStorage. Returns [] when absent or corrupt. */
export function loadAll(): MessageTemplate[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? (parsed as MessageTemplate[]) : []
  } catch (error) {
    console.error('Failed to load templates:', error)
    return []
  }
}

/** Overwrite all templates in localStorage. */
export function saveAll(templates: MessageTemplate[]): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(templates))
}

/** Trigger a browser download of a Blob built from `content`. */
function triggerDownload(content: string, filename: string): void {
  const blob = new Blob([content], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}

/** Read a File's text content via FileReader (works across browser and jsdom). */
export function readFileText(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result ?? ''))
    reader.onerror = () => reject(reader.error ?? new Error('Failed to read file'))
    reader.readAsText(file)
  })
}

/** Download a single template as a `.json` file. */
export function exportTemplate(template: MessageTemplate): void {
  triggerDownload(JSON.stringify(template, null, 2), `${template.name || template.id}.json`)
}

/** Download all templates as a single `.json` file. */
export function exportAll(templates: MessageTemplate[]): void {
  triggerDownload(JSON.stringify(templates, null, 2), 'templates.json')
}

/**
 * Parse and validate a `.json` file upload.
 *
 * Accepts either a single template object or a JSON array of templates.
 * Returns the structurally valid templates plus any per-entry error messages.
 * Duplicate-ID handling against existing storage is the store's responsibility.
 */
export async function importFromFile(
  file: File
): Promise<{ templates: MessageTemplate[]; errors: string[] }> {
  const errors: string[] = []
  let parsed: unknown
  try {
    parsed = JSON.parse(await readFileText(file))
  } catch {
    return { templates: [], errors: [`${file.name}: not valid JSON`] }
  }

  const candidates = Array.isArray(parsed) ? parsed : [parsed]
  const templates: MessageTemplate[] = []
  candidates.forEach((candidate, i) => {
    const result = messageTemplateSchema.safeParse(candidate)
    if (result.success) {
      templates.push(result.data as MessageTemplate)
    } else {
      const name = (candidate as { name?: string })?.name ?? `entry ${i}`
      errors.push(`${name}: ${result.error.issues.map((iss) => iss.message).join(', ')}`)
    }
  })

  return { templates, errors }
}
