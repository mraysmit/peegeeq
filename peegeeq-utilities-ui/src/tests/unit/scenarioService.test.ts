/**
 * Tests for scenarioService.ts (design §19.4 — Phase G.4).
 *
 * Contract under test:
 * - scenarios round-trip through localStorage under `peegeeq_scenarios`
 * - loading validates PER ENTRY: one corrupt entry is dropped, the rest survive
 *   (a whole-list rejection would blank the user's saved scenarios)
 * - import accepts a single object or an array, and names every rejected entry
 * - export downloads at the Blob/anchor boundary
 *
 * No mocks: real localStorage and the real FileReader path. URL.createObjectURL
 * and anchor click are stubbed at the browser boundary only (jsdom implements
 * neither), exactly as templateService.test.ts does.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  loadAll,
  saveAll,
  exportScenario,
  exportAll,
  importFromFile,
} from '../../services/scenarioService'
import type { RunConfig, MessageTemplate } from '../../types/generator'
import type { Scenario } from '../../types/scenario'

const STORAGE_KEY = 'peegeeq_scenarios'

function makeTemplate(): MessageTemplate {
  return {
    id: 'tpl-1',
    name: 'Order created',
    messageType: 'order.created',
    payloadSchema: '{"id":"{{messageId}}"}',
    headers: { source: 'generator' },
    priority: 5,
    delaySeconds: 0,
    createdAt: '2026-07-30T00:00:00.000Z',
    updatedAt: '2026-07-30T00:00:00.000Z',
  }
}

function makeConfig(overrides: Partial<RunConfig> = {}): RunConfig {
  return {
    setupId: 'demo',
    queueName: 'orders',
    rate: 100,
    durationSecs: 60,
    maxBatchSize: 10,
    warnThreshold: 500,
    maxConsecErrors: 10,
    template: makeTemplate(),
    previewIndex: 1,
    ...overrides,
  }
}

function makeScenario(overrides: Partial<Scenario> = {}): Scenario {
  return {
    id: 'scn-1',
    name: 'nightly-soak',
    config: makeConfig(),
    createdAt: '2026-07-30T00:00:00.000Z',
    updatedAt: '2026-07-30T00:00:00.000Z',
    ...overrides,
  }
}

function importFile(content: string, name = 'scenarios.json'): File {
  return new File([content], name, { type: 'application/json' })
}

describe('scenarioService', () => {
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

    it('round-trips scenarios through localStorage', () => {
      const scenarios = [makeScenario(), makeScenario({ id: 'scn-2', name: 'spike-repro' })]
      saveAll(scenarios)
      expect(loadAll()).toEqual(scenarios)
    })

    it('persists under the peegeeq_scenarios key', () => {
      saveAll([makeScenario()])
      expect(localStorage.getItem(STORAGE_KEY)).not.toBeNull()
    })

    it('returns an empty array when stored JSON is corrupt', () => {
      localStorage.setItem(STORAGE_KEY, '{not json')
      expect(loadAll()).toEqual([])
    })

    it('drops ONE invalid entry and keeps the valid ones', () => {
      const valid = makeScenario()
      localStorage.setItem(STORAGE_KEY, JSON.stringify([valid, { id: 'broken' }]))

      const loaded = loadAll()

      expect(loaded).toHaveLength(1)
      expect(loaded[0].id).toBe('scn-1')
    })

    it('rejects a stored scenario whose config is out of the UI input bounds', () => {
      // maxBatchSize 500 is beyond the Zone B cap of 100: storage must not
      // smuggle in a config the generator cannot produce.
      const outOfBounds = makeScenario({ config: makeConfig({ maxBatchSize: 500 }) })
      localStorage.setItem(STORAGE_KEY, JSON.stringify([outOfBounds]))

      expect(loadAll()).toEqual([])
    })
  })

  describe('importFromFile', () => {
    it('imports a JSON array of valid scenarios', async () => {
      const arr = [makeScenario(), makeScenario({ id: 'scn-2', name: 'spike-repro' })]

      const { scenarios, errors } = await importFromFile(importFile(JSON.stringify(arr)))

      expect(errors).toEqual([])
      expect(scenarios).toHaveLength(2)
      expect(scenarios[0].id).toBe('scn-1')
    })

    it('imports a single scenario object (not wrapped in an array)', async () => {
      const { scenarios, errors } = await importFromFile(
        importFile(JSON.stringify(makeScenario()), 'one.json')
      )

      expect(errors).toEqual([])
      expect(scenarios).toHaveLength(1)
    })

    it('reports a NAMED error and skips an invalid scenario', async () => {
      const invalid = { id: 'x', name: 'Broken entry' }

      const { scenarios, errors } = await importFromFile(
        importFile(JSON.stringify([makeScenario(), invalid]), 'mixed.json')
      )

      expect(scenarios).toHaveLength(1)
      expect(errors).toHaveLength(1)
      expect(errors[0]).toContain('Broken entry')
    })

    it('reports an error for a non-JSON file', async () => {
      const { scenarios, errors } = await importFromFile(importFile('not json at all', 'bad.json'))

      expect(scenarios).toEqual([])
      expect(errors[0]).toContain('not valid JSON')
    })
  })

  describe('exportScenario / exportAll', () => {
    it('triggers a single-file download for one scenario', () => {
      const createObjectURL = vi.fn().mockReturnValue('blob:url')
      vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL: vi.fn() })
      const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

      exportScenario(makeScenario())

      expect(createObjectURL).toHaveBeenCalledOnce()
      expect(clickSpy).toHaveBeenCalledOnce()
    })

    it('triggers a download for all scenarios', () => {
      const createObjectURL = vi.fn().mockReturnValue('blob:url')
      vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL: vi.fn() })
      const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

      exportAll([makeScenario(), makeScenario({ id: 'scn-2' })])

      expect(createObjectURL).toHaveBeenCalledOnce()
      expect(clickSpy).toHaveBeenCalledOnce()
    })
  })
})
