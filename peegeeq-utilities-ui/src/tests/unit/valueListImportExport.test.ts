/**
 * Systematic import/export boundary tests for valueListService and the
 * valueListStore import semantics (file input is untrusted user data).
 *
 * Dimensions: file-level validity, array-shape rules, element typing and
 * coercion, filename-derived list names, unicode and size edges, and the
 * overwrite/merge semantics against existing lists.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { importFromFile, exportList, extractListNames, loadAll } from '../../services/valueListService'
import { useValueListStore } from '../../stores/valueListStore'

function file(content: string, name = 'names.json'): File {
  return new File([content], name, { type: 'application/json' })
}

async function readBlob(blob: Blob): Promise<string> {
  return new Promise((resolve) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as string)
    reader.readAsText(blob)
  })
}

describe('valueListService import — file-level validity', () => {
  it('rejects non-JSON content, naming the file', async () => {
    const result = await importFromFile(file('definitely not json'))
    expect(result.values).toEqual([])
    expect(result.errors[0]).toContain('names.json')
    expect(result.errors[0]).toMatch(/not valid JSON/i)
  })

  it('rejects an empty file', async () => {
    const result = await importFromFile(file(''))
    expect(result.values).toEqual([])
    expect(result.errors).toHaveLength(1)
  })

  it('rejects truncated JSON', async () => {
    const result = await importFromFile(file('["Mark","Da'))
    expect(result.errors[0]).toMatch(/not valid JSON/i)
  })
})

describe('valueListService import — shape rules', () => {
  it.each([
    ['an object', '{"a":1}'],
    ['a bare string', '"hello"'],
    ['a bare number', '7'],
    ['null', 'null'],
    ['a boolean', 'true'],
  ])('rejects %s with "expected a JSON array"', async (_label, content) => {
    const result = await importFromFile(file(content))
    expect(result.values).toEqual([])
    expect(result.errors[0]).toMatch(/expected a JSON array/i)
  })

  it('rejects an empty array explicitly', async () => {
    const result = await importFromFile(file('[]'))
    expect(result.errors[0]).toMatch(/array is empty/i)
  })

  it.each([
    ['a null element', '["a", null]'],
    ['a boolean element', '["a", true]'],
    ['an object element', '["a", {"x":1}]'],
    ['a nested array element', '["a", ["b"]]'],
  ])('rejects the whole file when it contains %s', async (_label, content) => {
    const result = await importFromFile(file(content))
    expect(result.values).toEqual([])
    expect(result.errors[0]).toMatch(/only strings or numbers/i)
  })
})

describe('valueListService import — element typing and coercion', () => {
  it('accepts an all-strings array unchanged', async () => {
    const result = await importFromFile(file('["Mark","Dave","Janet"]'))
    expect(result.values).toEqual(['Mark', 'Dave', 'Janet'])
    expect(result.errors).toEqual([])
  })

  it('coerces numbers to strings and reports the exact count as a warning', async () => {
    const result = await importFromFile(file('[1, 2.5, "three", -4]'))
    expect(result.values).toEqual(['1', '2.5', 'three', '-4'])
    expect(result.errors).toHaveLength(1)
    expect(result.errors[0]).toMatch(/coerced 3 numeric/i)
  })

  it('keeps empty strings and duplicates as supplied (the store handles merging)', async () => {
    const result = await importFromFile(file('["", "a", "a"]'))
    expect(result.values).toEqual(['', 'a', 'a'])
  })

  it('accepts unicode values', async () => {
    const result = await importFromFile(file('["Müller","佐藤","O\'Brien — Sr."]'))
    expect(result.values).toHaveLength(3)
  })

  it('accepts a large array (5000 values)', async () => {
    const values = Array.from({ length: 5000 }, (_, i) => `v${i}`)
    const result = await importFromFile(file(JSON.stringify(values)))
    expect(result.values).toHaveLength(5000)
    expect(result.errors).toEqual([])
  })
})

describe('valueListService import — filename-derived list name', () => {
  it.each([
    ['first_names.json', 'first_names'],
    ['no_extension', 'no_extension'],
    ['multi.part.name.json', 'multi.part.name'],
    ['C:\\lists\\windows_path.json', 'windows_path'],
    ['/tmp/unix_path.json', 'unix_path'],
  ])('derives %s → %s', async (filename, expected) => {
    const result = await importFromFile(file('["a"]', filename))
    expect(result.defaultName).toBe(expected)
  })
})

describe('valueListStore.importList — overwrite/merge semantics', () => {
  const now = new Date().toISOString()

  beforeEach(() => {
    localStorage.clear()
    useValueListStore.setState({ lists: [], selected: null })
  })

  it('a new name imports as a fresh list regardless of mode', () => {
    const result = useValueListStore.getState().importList('fresh', ['a', 'b'], 'merge')
    expect(result).toEqual({ name: 'fresh', added: 2, total: 2 })
    expect(loadAll()['fresh']).toEqual(['a', 'b'])
  })

  it('overwrite replaces the entire existing list', () => {
    useValueListStore.getState().add({ name: 'l', values: ['old1', 'old2'], createdAt: now, updatedAt: now })
    const result = useValueListStore.getState().importList('l', ['new'], 'overwrite')
    expect(result).toEqual({ name: 'l', added: 1, total: 1 })
    expect(loadAll()['l']).toEqual(['new'])
  })

  it('merge appends only values not already present, preserving existing order', () => {
    useValueListStore.getState().add({ name: 'l', values: ['a', 'b'], createdAt: now, updatedAt: now })
    const result = useValueListStore.getState().importList('l', ['b', 'c', 'a', 'd'], 'merge')
    expect(result).toEqual({ name: 'l', added: 2, total: 4 })
    expect(loadAll()['l']).toEqual(['a', 'b', 'c', 'd'])
  })

  it('an all-duplicates merge adds zero and leaves the list unchanged', () => {
    useValueListStore.getState().add({ name: 'l', values: ['a', 'b'], createdAt: now, updatedAt: now })
    const result = useValueListStore.getState().importList('l', ['a', 'b'], 'merge')
    expect(result).toEqual({ name: 'l', added: 0, total: 2 })
    expect(loadAll()['l']).toEqual(['a', 'b'])
  })

  it('merge does not de-duplicate values already duplicated within the incoming array beyond presence', () => {
    useValueListStore.getState().add({ name: 'l', values: ['a'], createdAt: now, updatedAt: now })
    const result = useValueListStore.getState().importList('l', ['b', 'b'], 'merge')
    // Documented behaviour: presence is checked against the EXISTING list only,
    // so an incoming internal duplicate is appended twice.
    expect(result.total).toBe(3)
    expect(loadAll()['l']).toEqual(['a', 'b', 'b'])
  })
})

describe('valueListService export', () => {
  it('exportList downloads the value array whose JSON parses back identically', async () => {
    const createObjectURL = vi.fn(() => 'blob:fake')
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() })

    exportList('countries', ['GB', 'US', 'DE'])

    const blob = createObjectURL.mock.calls[0][0] as unknown as Blob
    expect(JSON.parse(await readBlob(blob))).toEqual(['GB', 'US', 'DE'])
  })

  it('export → import round-trips, including unicode', async () => {
    const createObjectURL = vi.fn(() => 'blob:fake')
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() })
    const values = ['Müller', '佐藤', 'plain']

    exportList('people', values)

    const blob = createObjectURL.mock.calls[0][0] as unknown as Blob
    const reimported = await importFromFile(file(await readBlob(blob), 'people.json'))
    expect(reimported.values).toEqual(values)
    expect(reimported.defaultName).toBe('people')
  })
})

describe('extractListNames (the import/export-adjacent scanner)', () => {
  it('returns distinct names in first-appearance order', () => {
    expect(extractListNames('{{list:a}} {{list:b}} {{list:a}}')).toEqual(['a', 'b'])
  })

  it('returns [] when no tokens exist', () => {
    expect(extractListNames('{"plain": true}')).toEqual([])
  })

  it('ignores malformed tokens', () => {
    expect(extractListNames('{{list:}} {{list:bad name}} {{lists:x}}')).toEqual([])
  })
})
