/**
 * Tests for templateService.ts (§12 of the feature design).
 *
 * localStorage is provided by jsdom. Browser download is exercised by spying on
 * URL.createObjectURL / anchor click at the boundary — no business logic is mocked.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { loadAll, saveAll, exportTemplate, exportAll, importFromFile } from '../../services/templateService'
import type { MessageTemplate } from '../../types/generator'

const STORAGE_KEY = 'peegeeq_msg_templates'

function makeTemplate(overrides: Partial<MessageTemplate> = {}): MessageTemplate {
  return {
    id: 'tmpl-1',
    name: 'Order Created',
    description: 'An order event',
    messageType: 'order.created',
    payloadSchema: '{"orderId":"ORD-{{messageId}}"}',
    headers: { source: 'generator' },
    priority: 5,
    delaySeconds: 0,
    messageGroup: undefined,
    createdAt: '2026-05-30T00:00:00.000Z',
    updatedAt: '2026-05-30T00:00:00.000Z',
    ...overrides,
  }
}

describe('templateService', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  describe('loadAll / saveAll', () => {
    it('returns an empty array when nothing is stored', () => {
      expect(loadAll()).toEqual([])
    })

    it('round-trips templates through localStorage', () => {
      const templates = [makeTemplate(), makeTemplate({ id: 'tmpl-2', name: 'Payment' })]
      saveAll(templates)
      expect(loadAll()).toEqual(templates)
    })

    it('persists under the peegeeq_msg_templates key', () => {
      saveAll([makeTemplate()])
      expect(localStorage.getItem(STORAGE_KEY)).not.toBeNull()
    })

    it('returns an empty array when stored JSON is corrupt', () => {
      localStorage.setItem(STORAGE_KEY, '{not json')
      expect(loadAll()).toEqual([])
    })
  })

  describe('importFromFile', () => {
    it('imports a JSON array of valid templates', async () => {
      const arr = [makeTemplate(), makeTemplate({ id: 'tmpl-2', name: 'Payment' })]
      const file = new File([JSON.stringify(arr)], 'templates.json', { type: 'application/json' })

      const { templates, errors } = await importFromFile(file)

      expect(errors).toEqual([])
      expect(templates).toHaveLength(2)
      expect(templates[0].id).toBe('tmpl-1')
    })

    it('imports a single template object (not wrapped in an array)', async () => {
      const file = new File([JSON.stringify(makeTemplate())], 'one.json', {
        type: 'application/json',
      })

      const { templates, errors } = await importFromFile(file)

      expect(errors).toEqual([])
      expect(templates).toHaveLength(1)
    })

    it('reports an error and skips an invalid template', async () => {
      const invalid = { id: 'x', name: 'missing fields' }
      const file = new File([JSON.stringify([makeTemplate(), invalid])], 'mixed.json', {
        type: 'application/json',
      })

      const { templates, errors } = await importFromFile(file)

      expect(templates).toHaveLength(1)
      expect(errors.length).toBeGreaterThan(0)
    })

    it('reports an error for a non-JSON file', async () => {
      const file = new File(['not json at all'], 'bad.json', { type: 'application/json' })

      const { templates, errors } = await importFromFile(file)

      expect(templates).toEqual([])
      expect(errors.length).toBeGreaterThan(0)
    })
  })

  describe('exportTemplate / exportAll', () => {
    it('triggers a single-file download for one template', () => {
      const createObjectURL = vi.fn().mockReturnValue('blob:url')
      const revokeObjectURL = vi.fn()
      vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })
      const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

      exportTemplate(makeTemplate())

      expect(createObjectURL).toHaveBeenCalledOnce()
      expect(clickSpy).toHaveBeenCalledOnce()
    })

    it('triggers a download for all templates', () => {
      const createObjectURL = vi.fn().mockReturnValue('blob:url')
      const revokeObjectURL = vi.fn()
      vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })
      const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

      exportAll([makeTemplate(), makeTemplate({ id: 'tmpl-2' })])

      expect(createObjectURL).toHaveBeenCalledOnce()
      expect(clickSpy).toHaveBeenCalledOnce()
    })
  })
})
