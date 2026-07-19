/**
 * Template resolution (§8 of the feature design).
 *
 * Pure functions: no side effects, no HTTP calls. Placeholder tokens use
 * {{name}} syntax and are resolved against a per-message/per-run context.
 */

function randomAlpha(n: number): string {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
  return Array.from({ length: n }, () => chars[Math.floor(Math.random() * chars.length)]).join('')
}

function pickFromList(name: string, valueLists: Record<string, string[]>): string {
  const list = valueLists[name]
  if (!list || list.length === 0) return '' // missing/empty list → empty string
  return list[Math.floor(Math.random() * list.length)]
}

export interface TemplateContext {
  messageId: number // 1-based
  index: number // 0-based (messageId - 1)
  runId: string
  correlationId: string
  now: Date
  valueLists: Record<string, string[]> // loaded from localStorage at run/preview start
}

/**
 * Substitute all placeholder tokens in a template string. No parsing — the
 * result is a plain string. Used directly for header values (§5.3: placeholders
 * are valid in header values) and by {@link resolveTemplate} for payloads.
 */
export function resolveString(template: string, context: TemplateContext): string {
  return template
    .replace(/\{\{messageId\}\}/g, String(context.messageId).padStart(8, '0'))
    .replace(/\{\{sequenceId\}\}/g, String(context.messageId).padStart(8, '0'))
    .replace(/\{\{uuid\}\}/g, crypto.randomUUID())
    .replace(/\{\{timestamp\}\}/g, context.now.toISOString())
    .replace(/\{\{unixMs\}\}/g, String(context.now.getTime()))
    .replace(/\{\{correlationId\}\}/g, context.correlationId)
    .replace(/\{\{runId\}\}/g, context.runId)
    .replace(/\{\{index\}\}/g, String(context.index))
    .replace(/\{\{random:(\d+)\}\}/g, (_: string, n: string) =>
      String(Math.floor(Math.random() * Number(n)))
    )
    .replace(/\{\{randomAlpha:(\d+)\}\}/g, (_: string, n: string) => randomAlpha(Number(n)))
    .replace(/\{\{list:([A-Za-z0-9_-]+)\}\}/g, (_: string, name: string) =>
      pickFromList(name, context.valueLists)
    )
}

/**
 * Resolve a template payload string into a parsed JSON object.
 *
 * Per-message tokens (including {{list:...}} lookups) and per-run tokens are
 * substituted, then the result is parsed with JSON.parse — surfacing template
 * authoring errors before publish time. The Preview action wraps this in a
 * try/catch and shows parse errors inline.
 */
export function resolveTemplate(templateJson: string, context: TemplateContext): object {
  return JSON.parse(resolveString(templateJson, context))
}

/**
 * Scan a template string and return the names of any {{list:name}} tokens whose
 * list is missing or empty in the provided valueLists map. Used by the
 * pre-flight validation in Preview and Start.
 */
export function findMissingLists(
  templateJson: string,
  valueLists: Record<string, string[]>
): string[] {
  const names = [...templateJson.matchAll(/\{\{list:([A-Za-z0-9_-]+)\}\}/g)].map((m) => m[1])
  return [...new Set(names)].filter((name) => !valueLists[name] || valueLists[name].length === 0)
}
