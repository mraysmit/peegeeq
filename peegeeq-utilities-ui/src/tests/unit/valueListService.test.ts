/**
 * Tests for valueListService.ts (§12 of the feature design).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  loadAll,
  saveAll,
  importFromFile,
  exportList,
  extractListNames,
} from '../../services/valueListService'

const STORAGE_KEY = 'peegeeq_value_lists'

describe('valueListService', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  describe('loadAll / saveAll', () => {
    it('returns {} when nothing is stored', () => {
      expect(loadAll()).toEqual({})
    })

    it('round-trips lists through localStorage', () => {
      const lists = { first_names: ['Mark', 'Dave'], countries: ['GB', 'US'] }
      saveAll(lists)
      expect(loadAll()).toEqual(lists)
    })

    it('persists under the peegeeq_value_lists key', () => {
      saveAll({ a: ['1'] })
      expect(localStorage.getItem(STORAGE_KEY)).not.toBeNull()
    })

    it('returns {} when stored JSON is corrupt', () => {
      localStorage.setItem(STORAGE_KEY, '{not json')
      expect(loadAll()).toEqual({})
    })
  })

  describe('importFromFile', () => {
    it('imports a JSON array of strings', async () => {
      const file = new File([JSON.stringify(['Mark', 'Dave'])], 'first_names.json', {
        type: 'application/json',
      })

      const { values, defaultName, errors } = await importFromFile(file)

      expect(errors).toEqual([])
      expect(values).toEqual(['Mark', 'Dave'])
      expect(defaultName).toBe('first_names')
    })

    it('coerces numbers to strings and warns', async () => {
      const file = new File([JSON.stringify([1, 'two', 3])], 'mixed.json', {
        type: 'application/json',
      })

      const { values, errors } = await importFromFile(file)

      expect(values).toEqual(['1', 'two', '3'])
      expect(errors.length).toBeGreaterThan(0)
    })

    it('rejects an empty array', async () => {
      const file = new File([JSON.stringify([])], 'empty.json', { type: 'application/json' })

      const { values, errors } = await importFromFile(file)

      expect(values).toEqual([])
      expect(errors.length).toBeGreaterThan(0)
    })

    it('rejects a non-array JSON document', async () => {
      const file = new File([JSON.stringify({ a: 1 })], 'obj.json', { type: 'application/json' })

      const { values, errors } = await importFromFile(file)

      expect(values).toEqual([])
      expect(errors.length).toBeGreaterThan(0)
    })

    it('rejects an array containing objects or null', async () => {
      const file = new File([JSON.stringify(['ok', { x: 1 }, null])], 'bad.json', {
        type: 'application/json',
      })

      const { values, errors } = await importFromFile(file)

      expect(values).toEqual([])
      expect(errors.length).toBeGreaterThan(0)
    })

    it('reports an error for a non-JSON file', async () => {
      const file = new File(['not json'], 'bad.json', { type: 'application/json' })

      const { values, errors } = await importFromFile(file)

      expect(values).toEqual([])
      expect(errors.length).toBeGreaterThan(0)
    })
  })

  describe('exportList', () => {
    it('triggers a download named {name}.json', () => {
      const createObjectURL = vi.fn().mockReturnValue('blob:url')
      const revokeObjectURL = vi.fn()
      vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })
      const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

      exportList('countries', ['GB', 'US'])

      expect(createObjectURL).toHaveBeenCalledOnce()
      expect(clickSpy).toHaveBeenCalledOnce()
    })
  })

  describe('extractListNames', () => {
    it('returns the distinct list names referenced in a payload', () => {
      const schema = '{"a":"{{list:first_names}}","b":"{{list:countries}}","c":"{{list:first_names}}"}'
      expect(extractListNames(schema).sort()).toEqual(['countries', 'first_names'])
    })

    it('returns an empty array when no list tokens are present', () => {
      expect(extractListNames('{"a":"{{messageId}}"}')).toEqual([])
    })
  })
})
