/**
 * Tests for scenarioStore.ts (design §19.4 — Phase G.4).
 *
 * Real store, real localStorage, no mocks — mirrors templateStore.test.ts.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { useScenarioStore } from '../../stores/scenarioStore'
import type { RunConfig, MessageTemplate } from '../../types/generator'
import type { Scenario } from '../../types/scenario'

const STORAGE_KEY = 'peegeeq_scenarios'

function makeConfig(overrides: Partial<RunConfig> = {}): RunConfig {
  const now = new Date().toISOString()
  const template: MessageTemplate = {
    id: 'tpl-1',
    name: 'Order created',
    messageType: 'order.created',
    payloadSchema: '{"id":"{{messageId}}"}',
    headers: {},
    priority: 5,
    delaySeconds: 0,
    createdAt: now,
    updatedAt: now,
  }
  return {
    setupId: 'demo',
    queueName: 'orders',
    rate: 100,
    durationSecs: 60,
    maxBatchSize: 10,
    warnThreshold: 500,
    maxConsecErrors: 10,
    template,
    previewIndex: 1,
    ...overrides,
  }
}

function makeScenario(overrides: Partial<Scenario> = {}): Scenario {
  const now = new Date().toISOString()
  return {
    id: crypto.randomUUID(),
    name: 'nightly-soak',
    config: makeConfig(),
    createdAt: now,
    updatedAt: now,
    ...overrides,
  }
}

function reset() {
  localStorage.clear()
  useScenarioStore.setState({ scenarios: [], selected: null })
}

describe('scenarioStore', () => {
  beforeEach(reset)

  it('adds a scenario and persists it', () => {
    const s = makeScenario()
    useScenarioStore.getState().add(s)

    expect(useScenarioStore.getState().scenarios).toHaveLength(1)
    expect(localStorage.getItem(STORAGE_KEY)).toContain(s.id)
  })

  it('updates an existing scenario by id', () => {
    const s = makeScenario({ name: 'Old' })
    useScenarioStore.getState().add(s)
    useScenarioStore.getState().update({ ...s, name: 'New' })

    expect(useScenarioStore.getState().scenarios[0].name).toBe('New')
  })

  it('removes a scenario by id and clears the selection if it was selected', () => {
    const s = makeScenario()
    useScenarioStore.getState().add(s)
    useScenarioStore.getState().select(s.id)
    useScenarioStore.getState().remove(s.id)

    expect(useScenarioStore.getState().scenarios).toHaveLength(0)
    expect(useScenarioStore.getState().selected).toBeNull()
  })

  it('selects a scenario by id and deselects with null', () => {
    const s = makeScenario()
    useScenarioStore.getState().add(s)

    useScenarioStore.getState().select(s.id)
    expect(useScenarioStore.getState().selected?.id).toBe(s.id)

    useScenarioStore.getState().select(null)
    expect(useScenarioStore.getState().selected).toBeNull()
  })

  it('loadFromStorage hydrates scenarios from localStorage', () => {
    const s = makeScenario()
    localStorage.setItem(STORAGE_KEY, JSON.stringify([s]))

    useScenarioStore.getState().loadFromStorage()

    expect(useScenarioStore.getState().scenarios).toHaveLength(1)
    expect(useScenarioStore.getState().scenarios[0].id).toBe(s.id)
  })

  describe('importScenarios', () => {
    it('adds new scenarios and reports the count', () => {
      const result = useScenarioStore.getState().importScenarios([makeScenario(), makeScenario()])

      expect(result.added).toBe(2)
      expect(result.skipped).toEqual([])
      expect(useScenarioStore.getState().scenarios).toHaveLength(2)
    })

    it('skips a scenario whose id already exists and leaves the stored one untouched', () => {
      const existing = makeScenario({ name: 'Existing' })
      useScenarioStore.getState().add(existing)

      const result = useScenarioStore
        .getState()
        .importScenarios([{ ...existing, name: 'Clobber attempt' }])

      expect(result.added).toBe(0)
      expect(result.skipped).toContain(existing.id)
      expect(useScenarioStore.getState().scenarios).toHaveLength(1)
      expect(useScenarioStore.getState().scenarios[0].name).toBe('Existing')
    })

    it('skips a duplicate id occurring twice WITHIN one import batch', () => {
      const a = makeScenario()

      const result = useScenarioStore.getState().importScenarios([a, { ...a, name: 'Second copy' }])

      expect(result.added).toBe(1)
      expect(result.skipped).toContain(a.id)
      expect(useScenarioStore.getState().scenarios).toHaveLength(1)
    })
  })
})
