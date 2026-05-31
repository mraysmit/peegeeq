/**
 * Tests for templateStore.ts (§11 of the feature design).
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { useTemplateStore } from '../../stores/templateStore'
import type { MessageTemplate } from '../../types/generator'

function makeTemplate(overrides: Partial<MessageTemplate> = {}): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: crypto.randomUUID(),
    name: 'Order Created',
    messageType: 'order.created',
    payloadSchema: '{"id":"{{uuid}}"}',
    headers: {},
    priority: 5,
    delaySeconds: 0,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  }
}

function reset() {
  localStorage.clear()
  useTemplateStore.setState({ templates: [], selected: null })
}

describe('templateStore', () => {
  beforeEach(reset)

  it('adds a template and persists it', () => {
    const t = makeTemplate()
    useTemplateStore.getState().add(t)

    expect(useTemplateStore.getState().templates).toHaveLength(1)
    expect(localStorage.getItem('peegeeq_msg_templates')).toContain(t.id)
  })

  it('updates an existing template by id', () => {
    const t = makeTemplate({ name: 'Old' })
    useTemplateStore.getState().add(t)
    useTemplateStore.getState().update({ ...t, name: 'New' })

    expect(useTemplateStore.getState().templates[0].name).toBe('New')
  })

  it('removes a template by id and clears selection if it was selected', () => {
    const t = makeTemplate()
    useTemplateStore.getState().add(t)
    useTemplateStore.getState().select(t.id)
    useTemplateStore.getState().remove(t.id)

    expect(useTemplateStore.getState().templates).toHaveLength(0)
    expect(useTemplateStore.getState().selected).toBeNull()
  })

  it('duplicates a template with a new id and "(copy)" name', () => {
    const t = makeTemplate({ name: 'Base' })
    useTemplateStore.getState().add(t)
    useTemplateStore.getState().duplicate(t.id)

    const { templates } = useTemplateStore.getState()
    expect(templates).toHaveLength(2)
    const copy = templates.find((x) => x.id !== t.id)!
    expect(copy.id).not.toBe(t.id)
    expect(copy.name).toContain('copy')
  })

  it('selects a template by id and deselects with null', () => {
    const t = makeTemplate()
    useTemplateStore.getState().add(t)

    useTemplateStore.getState().select(t.id)
    expect(useTemplateStore.getState().selected?.id).toBe(t.id)

    useTemplateStore.getState().select(null)
    expect(useTemplateStore.getState().selected).toBeNull()
  })

  it('loadFromStorage hydrates templates from localStorage', () => {
    const t = makeTemplate()
    localStorage.setItem('peegeeq_msg_templates', JSON.stringify([t]))

    useTemplateStore.getState().loadFromStorage()

    expect(useTemplateStore.getState().templates).toHaveLength(1)
    expect(useTemplateStore.getState().templates[0].id).toBe(t.id)
  })

  describe('importTemplates', () => {
    it('adds new templates and reports the count', () => {
      const a = makeTemplate()
      const b = makeTemplate()

      const result = useTemplateStore.getState().importTemplates([a, b])

      expect(result.added).toBe(2)
      expect(result.skipped).toEqual([])
      expect(useTemplateStore.getState().templates).toHaveLength(2)
    })

    it('skips templates whose id already exists', () => {
      const a = makeTemplate({ name: 'Existing' })
      useTemplateStore.getState().add(a)

      const result = useTemplateStore.getState().importTemplates([a])

      expect(result.added).toBe(0)
      expect(result.skipped).toContain(a.id)
      expect(useTemplateStore.getState().templates).toHaveLength(1)
    })
  })
})
