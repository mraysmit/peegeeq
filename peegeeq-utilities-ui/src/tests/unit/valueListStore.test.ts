/**
 * Tests for valueListStore.ts (§11 of the feature design).
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { useValueListStore } from '../../stores/valueListStore'
import type { ValueList } from '../../types/generator'

function makeList(name: string, values: string[]): ValueList {
  const now = new Date().toISOString()
  return { name, values, createdAt: now, updatedAt: now }
}

function reset() {
  localStorage.clear()
  useValueListStore.setState({ lists: [], selected: null })
}

describe('valueListStore', () => {
  beforeEach(reset)

  it('adds a list and persists it as a name→values map', () => {
    useValueListStore.getState().add(makeList('first_names', ['Mark', 'Dave']))

    expect(useValueListStore.getState().lists).toHaveLength(1)
    const stored = JSON.parse(localStorage.getItem('peegeeq_value_lists')!)
    expect(stored).toEqual({ first_names: ['Mark', 'Dave'] })
  })

  it('updates an existing list by name', () => {
    useValueListStore.getState().add(makeList('countries', ['GB']))
    useValueListStore.getState().update(makeList('countries', ['GB', 'US']))

    expect(useValueListStore.getState().lists[0].values).toEqual(['GB', 'US'])
  })

  it('removes a list by name and clears selection', () => {
    useValueListStore.getState().add(makeList('countries', ['GB']))
    useValueListStore.getState().select('countries')
    useValueListStore.getState().remove('countries')

    expect(useValueListStore.getState().lists).toHaveLength(0)
    expect(useValueListStore.getState().selected).toBeNull()
  })

  it('selects a list by name and deselects with null', () => {
    useValueListStore.getState().add(makeList('countries', ['GB']))

    useValueListStore.getState().select('countries')
    expect(useValueListStore.getState().selected?.name).toBe('countries')

    useValueListStore.getState().select(null)
    expect(useValueListStore.getState().selected).toBeNull()
  })

  it('loadFromStorage hydrates lists from the stored map', () => {
    localStorage.setItem(
      'peegeeq_value_lists',
      JSON.stringify({ a: ['1', '2'], b: ['3'] })
    )

    useValueListStore.getState().loadFromStorage()

    const names = useValueListStore.getState().lists.map((l) => l.name).sort()
    expect(names).toEqual(['a', 'b'])
  })

  it('snapshot returns a plain name→values map', () => {
    useValueListStore.getState().add(makeList('a', ['1']))
    useValueListStore.getState().add(makeList('b', ['2', '3']))

    expect(useValueListStore.getState().snapshot()).toEqual({ a: ['1'], b: ['2', '3'] })
  })

  describe('importList', () => {
    it('creates a new list in overwrite mode', () => {
      const result = useValueListStore.getState().importList('cities', ['London'], 'overwrite')

      expect(result).toEqual({ name: 'cities', added: 1, total: 1 })
      expect(useValueListStore.getState().snapshot().cities).toEqual(['London'])
    })

    it('overwrite replaces all existing values', () => {
      useValueListStore.getState().add(makeList('cities', ['London', 'Paris']))

      const result = useValueListStore.getState().importList('cities', ['Tokyo'], 'overwrite')

      expect(result).toEqual({ name: 'cities', added: 1, total: 1 })
      expect(useValueListStore.getState().snapshot().cities).toEqual(['Tokyo'])
    })

    it('merge appends only new values, de-duplicating', () => {
      useValueListStore.getState().add(makeList('cities', ['London', 'Paris']))

      const result = useValueListStore
        .getState()
        .importList('cities', ['Paris', 'Tokyo'], 'merge')

      expect(result).toEqual({ name: 'cities', added: 1, total: 3 })
      expect(useValueListStore.getState().snapshot().cities).toEqual(['London', 'Paris', 'Tokyo'])
    })
  })
})
