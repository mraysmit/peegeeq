/**
 * Systematic import/export boundary tests for templateService (file input is
 * untrusted user data — every rejection path must be exact and named).
 *
 * Dimensions: file-level validity, shape validity, per-entry validation with
 * mixed files, field-level type and RANGE validation (range rules added
 * test-first: the UI bounds priority 1–10 and delay ≥ 0, so the import path
 * must enforce the same), duplicate handling within one file and against
 * storage, unicode and size edges, and export round-trips.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { importFromFile, exportTemplate, exportAll, loadAll } from '../../services/templateService'
import { useTemplateStore } from '../../stores/templateStore'
import type { MessageTemplate } from '../../types/generator'

function makeTemplate(overrides: Partial<MessageTemplate> = {}): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: crypto.randomUUID(),
    name: 'Order created',
    messageType: 'order.created',
    payloadSchema: '{"id":"{{messageId}}"}',
    headers: { source: 'generator' },
    priority: 5,
    delaySeconds: 0,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  }
}

function file(content: string, name = 'templates.json'): File {
  return new File([content], name, { type: 'application/json' })
}

async function importJson(content: unknown, name?: string) {
  return importFromFile(file(JSON.stringify(content), name))
}

async function readBlob(blob: Blob): Promise<string> {
  return new Promise((resolve) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as string)
    reader.readAsText(blob)
  })
}

describe('templateService import — file-level validity', () => {
  it('rejects a file that is not JSON, naming the file', async () => {
    const result = await importFromFile(file('not json at all'))
    expect(result.templates).toEqual([])
    expect(result.errors[0]).toContain('templates.json')
    expect(result.errors[0]).toMatch(/not valid JSON/i)
  })

  it('rejects an empty file', async () => {
    const result = await importFromFile(file(''))
    expect(result.templates).toEqual([])
    expect(result.errors).toHaveLength(1)
  })

  it('rejects a whitespace-only file', async () => {
    const result = await importFromFile(file('   \n\t  '))
    expect(result.templates).toEqual([])
    expect(result.errors).toHaveLength(1)
  })

  it('rejects truncated JSON', async () => {
    const result = await importFromFile(file('[{"id":"a","name":'))
    expect(result.templates).toEqual([])
    expect(result.errors[0]).toMatch(/not valid JSON/i)
  })

  it('accepts a file with a UTF-8 byte-order mark prefix', async () => {
    // Verified behaviour: FileReader.readAsText strips a leading BOM during
    // UTF-8 decoding, so the parser never sees it. Windows editors emit BOMs
    // routinely — accepting them is correct.
    const template = makeTemplate({ name: 'BOM file' })
    const result = await importFromFile(file('﻿' + JSON.stringify([template])))
    expect(result.templates).toEqual([template])
    expect(result.errors).toEqual([])
  })
})

describe('templateService import — top-level shape', () => {
  it.each([
    ['number', 42],
    ['string', 'hello'],
    ['boolean', true],
    ['null', null],
  ])('rejects a top-level %s as a schema-invalid single entry', async (_label, value) => {
    const result = await importJson(value)
    expect(result.templates).toEqual([])
    expect(result.errors).toHaveLength(1)
  })

  it('accepts a single template object (not wrapped in an array)', async () => {
    const template = makeTemplate()
    const result = await importJson(template)
    expect(result.templates).toEqual([template])
    expect(result.errors).toEqual([])
  })

  it('accepts an array of templates', async () => {
    const templates = [makeTemplate(), makeTemplate({ name: 'Second' })]
    const result = await importJson(templates)
    expect(result.templates).toEqual(templates)
  })

  it('accepts an empty array as zero templates with zero errors', async () => {
    const result = await importJson([])
    expect(result.templates).toEqual([])
    expect(result.errors).toEqual([])
  })
})

describe('templateService import — per-entry validation in mixed files', () => {
  it('keeps valid entries and names each invalid one', async () => {
    const good = makeTemplate({ name: 'Good' })
    const result = await importJson([good, { id: 'x', name: 'Broken A' }, { name: 'No id' }])
    expect(result.templates).toEqual([good])
    expect(result.errors).toHaveLength(2)
    expect(result.errors[0]).toContain('Broken A')
  })

  it('names an entry by index when it has no name', async () => {
    const result = await importJson([{ id: 'x' }])
    expect(result.errors[0]).toMatch(/entry 0/)
  })

  it('rejects a nested array entry', async () => {
    const result = await importJson([[makeTemplate()]])
    expect(result.templates).toEqual([])
    expect(result.errors).toHaveLength(1)
  })

  it('rejects a null entry', async () => {
    const good = makeTemplate()
    const result = await importJson([good, null])
    expect(result.templates).toEqual([good])
    expect(result.errors).toHaveLength(1)
  })
})

describe('templateService import — field-level type validation', () => {
  it.each([
    ['id missing', { id: undefined }],
    ['id empty string', { id: '' }],
    ['name missing', { name: undefined }],
    ['name empty string', { name: '' }],
    ['messageType wrong type', { messageType: 7 }],
    ['payloadSchema wrong type', { payloadSchema: { nested: true } }],
    ['headers as array', { headers: ['a'] }],
    ['headers with numeric value', { headers: { k: 1 } }],
    ['priority as string', { priority: '5' }],
    ['delaySeconds as string', { delaySeconds: '0' }],
    ['createdAt missing', { createdAt: undefined }],
    ['updatedAt as number', { updatedAt: 123 }],
  ])('rejects %s', async (_label, override) => {
    const broken = { ...makeTemplate({ name: 'Broken field' }), ...(override as object) }
    const result = await importJson([broken])
    expect(result.templates).toEqual([])
    expect(result.errors).toHaveLength(1)
  })

  it('accepts absent optional fields (description, messageGroup)', async () => {
    const template = makeTemplate()
    delete (template as Partial<MessageTemplate>).description
    delete (template as Partial<MessageTemplate>).messageGroup
    const result = await importJson([template])
    expect(result.templates).toHaveLength(1)
  })

  it('strips unknown extra fields instead of rejecting (forward compatibility)', async () => {
    const result = await importJson([{ ...makeTemplate({ name: 'Extra' }), futureField: 'x' }])
    expect(result.templates).toHaveLength(1)
    expect('futureField' in result.templates[0]).toBe(false)
  })
})

describe('templateService import — RANGE validation (import must enforce the UI bounds)', () => {
  it.each([
    ['priority 0 (below UI minimum 1)', { priority: 0 }],
    ['priority 11 (above UI maximum 10)', { priority: 11 }],
    ['priority negative', { priority: -5 }],
    ['priority fractional', { priority: 2.5 }],
    ['delaySeconds negative', { delaySeconds: -1 }],
  ])('rejects %s', async (_label, override) => {
    const broken = { ...makeTemplate({ name: 'Out of range' }), ...(override as object) }
    const result = await importJson([broken])
    expect(result.templates).toEqual([])
    expect(result.errors).toHaveLength(1)
  })

  it('accepts the boundary values priority 1, priority 10, delaySeconds 0', async () => {
    const result = await importJson([
      makeTemplate({ name: 'P1', priority: 1 }),
      makeTemplate({ name: 'P10', priority: 10 }),
      makeTemplate({ name: 'D0', delaySeconds: 0 }),
    ])
    expect(result.templates).toHaveLength(3)
    expect(result.errors).toEqual([])
  })
})

describe('templateService import — content edges', () => {
  it('accepts unicode names, payloads, and header values', async () => {
    const template = makeTemplate({
      name: 'Auftrag erstellt — 注文 ✓',
      payloadSchema: '{"grüße":"{{messageId}}","emoji":"🚀"}',
      headers: { 'x-großbank': 'überweisung' },
    })
    const result = await importJson([template])
    expect(result.templates).toEqual([template])
  })

  it('accepts a large payload (100 KB)', async () => {
    const template = makeTemplate({ payloadSchema: `{"big":"${'x'.repeat(100_000)}"}` })
    const result = await importJson([template])
    expect(result.templates).toHaveLength(1)
  })

  it('accepts a large batch (500 templates)', async () => {
    const templates = Array.from({ length: 500 }, (_, i) => makeTemplate({ name: `T${i}` }))
    const result = await importJson(templates)
    expect(result.templates).toHaveLength(500)
    expect(result.errors).toEqual([])
  })

  it('is not confused by a header key named __proto__', async () => {
    const result = await importJson([
      { ...makeTemplate({ name: 'Proto' }), headers: { __proto__: 'value' } },
    ])
    // Whatever the outcome, no crash and no pollution of Object.prototype.
    expect(({} as Record<string, unknown>).polluted).toBeUndefined()
    expect(result.templates.length + result.errors.length).toBeGreaterThan(0)
  })
})

describe('templateStore.importTemplates — duplicate handling', () => {
  beforeEach(() => {
    localStorage.clear()
    useTemplateStore.setState({ templates: [], selected: null })
  })

  it('skips an id that already exists in storage, keeping the stored version', () => {
    const stored = makeTemplate({ id: 'fixed', messageType: 'v1' })
    useTemplateStore.getState().add(stored)
    const { added, skipped } = useTemplateStore
      .getState()
      .importTemplates([makeTemplate({ id: 'fixed', messageType: 'v2' })])
    expect(added).toBe(0)
    expect(skipped).toEqual(['fixed'])
    expect(useTemplateStore.getState().templates[0].messageType).toBe('v1')
  })

  it('skips the SECOND occurrence of a duplicate id within one import batch', () => {
    const { added, skipped } = useTemplateStore.getState().importTemplates([
      makeTemplate({ id: 'dup', name: 'First' }),
      makeTemplate({ id: 'dup', name: 'Second' }),
    ])
    expect(added).toBe(1)
    expect(skipped).toEqual(['dup'])
    expect(useTemplateStore.getState().templates[0].name).toBe('First')
  })

  it('persists imported templates to localStorage', () => {
    useTemplateStore.getState().importTemplates([makeTemplate({ id: 'persisted' })])
    expect(loadAll().map((t) => t.id)).toContain('persisted')
  })

  it('an all-duplicates import changes nothing', () => {
    useTemplateStore.getState().add(makeTemplate({ id: 'only' }))
    const before = loadAll()
    const { added } = useTemplateStore.getState().importTemplates([makeTemplate({ id: 'only' })])
    expect(added).toBe(0)
    expect(loadAll()).toEqual(before)
  })
})

describe('templateService export', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('exportTemplate downloads one template whose JSON parses back identically', async () => {
    const createObjectURL = vi.fn(() => 'blob:fake')
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() })
    const template = makeTemplate({ name: 'Round trip' })

    exportTemplate(template)

    const blob = createObjectURL.mock.calls[0][0] as unknown as Blob
    expect(JSON.parse(await readBlob(blob))).toEqual(template)
  })

  it('exportTemplate names the file after the template, falling back to the id', async () => {
    const createObjectURL = vi.fn(() => 'blob:fake')
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() })
    const clicks: string[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicks.push(this.download)
    })

    exportTemplate(makeTemplate({ name: 'Named' }))
    exportTemplate(makeTemplate({ id: 'the-id', name: '' }))
    expect(clicks).toEqual(['Named.json', 'the-id.json'])
  })

  it('exportAll → import round-trips every template', async () => {
    const createObjectURL = vi.fn(() => 'blob:fake')
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() })
    const templates = [makeTemplate({ name: 'A' }), makeTemplate({ name: 'B — ünïcode' })]

    exportAll(templates)

    const blob = createObjectURL.mock.calls[0][0] as unknown as Blob
    const reimported = await importFromFile(file(await readBlob(blob)))
    expect(reimported.templates).toEqual(templates)
    expect(reimported.errors).toEqual([])
  })
})
