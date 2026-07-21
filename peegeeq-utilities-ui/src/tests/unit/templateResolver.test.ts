/**
 * Tests for templateResolver.ts (§8 of the feature design).
 *
 * Pure functions — no mocks required. Randomness is asserted via range/shape
 * checks rather than fixed values.
 */
import { describe, it, expect } from 'vitest'
import { resolveTemplate, resolveString, findMissingLists } from '../../engine/templateResolver'

const BASE_CONTEXT = {
  messageId: 1,
  index: 0,
  runId: 'run-123',
  correlationId: 'corr-456',
  now: new Date('2026-05-30T14:23:00.000Z'),
  valueLists: {} as Record<string, string[]>,
}

describe('resolveTemplate', () => {
  it('resolves {{messageId}} zero-padded to 8 digits', () => {
    const result = resolveTemplate('{"id":"{{messageId}}"}', { ...BASE_CONTEXT, messageId: 1 })
    expect(result).toEqual({ id: '00000001' })
  })

  it('resolves {{sequenceId}} as an alias for {{messageId}}', () => {
    const result = resolveTemplate('{"id":"{{sequenceId}}"}', { ...BASE_CONTEXT, messageId: 42 })
    expect(result).toEqual({ id: '00000042' })
  })

  it('resolves {{index}} as the 0-based position', () => {
    const result = resolveTemplate('{"i":"{{index}}"}', { ...BASE_CONTEXT, index: 7 })
    expect(result).toEqual({ i: '7' })
  })

  it('resolves {{timestamp}} to ISO 8601', () => {
    const result = resolveTemplate('{"t":"{{timestamp}}"}', BASE_CONTEXT) as { t: string }
    expect(result.t).toBe('2026-05-30T14:23:00.000Z')
  })

  it('resolves {{unixMs}} to epoch milliseconds', () => {
    const result = resolveTemplate('{"t":"{{unixMs}}"}', BASE_CONTEXT) as { t: string }
    expect(result.t).toBe(String(BASE_CONTEXT.now.getTime()))
  })

  it('resolves {{runId}} and {{correlationId}} from context (per-run)', () => {
    const result = resolveTemplate(
      '{"r":"{{runId}}","c":"{{correlationId}}"}',
      BASE_CONTEXT
    )
    expect(result).toEqual({ r: 'run-123', c: 'corr-456' })
  })

  it('resolves {{uuid}} to a UUID (one per message, per the design)', () => {
    const result = resolveTemplate(
      '{"a":"{{uuid}}","b":"{{uuid}}"}',
      BASE_CONTEXT
    ) as { a: string; b: string }
    const uuidRe = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
    expect(result.a).toMatch(uuidRe)
    // All {{uuid}} tokens within a single message share the same value (§5.3).
    expect(result.a).toBe(result.b)
  })

  it('generates a fresh {{uuid}} per MESSAGE (per context) — not per call', () => {
    // The engine builds one context object per message; the context IS the
    // message identity for {{uuid}}.
    const a = resolveTemplate('{"u":"{{uuid}}"}', { ...BASE_CONTEXT }) as { u: string }
    const b = resolveTemplate('{"u":"{{uuid}}"}', { ...BASE_CONTEXT }) as { u: string }
    expect(a.u).not.toBe(b.u)
  })

  it('payload and headers of the SAME message share one {{uuid}} — a header can correlate with its payload', () => {
    // Corrected 2026-07-21: the uuid was per resolveString CALL, so a
    // message's headers carried a different uuid than its payload.
    const context = { ...BASE_CONTEXT }
    const payload = resolveTemplate('{"id":"{{uuid}}"}', context) as { id: string }
    const header = resolveString('{{uuid}}', context)
    expect(header).toBe(payload.id)
  })

  it('resolves {{random:N}} to an integer in [0, N)', () => {
    for (let i = 0; i < 50; i++) {
      const result = resolveTemplate('{"v":"{{random:10}}"}', BASE_CONTEXT) as { v: string }
      const n = Number(result.v)
      expect(Number.isInteger(n)).toBe(true)
      expect(n).toBeGreaterThanOrEqual(0)
      expect(n).toBeLessThan(10)
    }
  })

  it('resolves {{randomAlpha:N}} to an alphanumeric string of length N', () => {
    const result = resolveTemplate('{"v":"{{randomAlpha:12}}"}', BASE_CONTEXT) as { v: string }
    expect(result.v).toMatch(/^[A-Za-z0-9]{12}$/)
  })

  it('resolves {{list:name}} to an element of the named list', () => {
    const valueLists = { colours: ['red', 'green', 'blue'] }
    for (let i = 0; i < 30; i++) {
      const result = resolveTemplate('{"c":"{{list:colours}}"}', {
        ...BASE_CONTEXT,
        valueLists,
      }) as { c: string }
      expect(valueLists.colours).toContain(result.c)
    }
  })

  it('resolves {{list:name}} to "" for a missing list', () => {
    const result = resolveTemplate('{"c":"{{list:missing}}"}', BASE_CONTEXT)
    expect(result).toEqual({ c: '' })
  })

  it('resolves {{list:name}} to "" for an empty list', () => {
    const result = resolveTemplate('{"c":"{{list:empty}}"}', {
      ...BASE_CONTEXT,
      valueLists: { empty: [] },
    })
    expect(result).toEqual({ c: '' })
  })

  it('emits non-placeholder text literally', () => {
    const result = resolveTemplate('{"static":"order.created","n":42}', BASE_CONTEXT)
    expect(result).toEqual({ static: 'order.created', n: 42 })
  })

  it('throws when the resolved string is not valid JSON', () => {
    expect(() => resolveTemplate('{ not json', BASE_CONTEXT)).toThrow()
  })
})

describe('findMissingLists', () => {
  it('returns names of lists that are absent', () => {
    const result = findMissingLists('{"a":"{{list:foo}}","b":"{{list:bar}}"}', {
      foo: ['x'],
    })
    expect(result).toEqual(['bar'])
  })

  it('returns names of lists that are empty', () => {
    const result = findMissingLists('{"a":"{{list:foo}}"}', { foo: [] })
    expect(result).toEqual(['foo'])
  })

  it('deduplicates repeated list references', () => {
    const result = findMissingLists('{{list:foo}} {{list:foo}}', {})
    expect(result).toEqual(['foo'])
  })

  it('returns an empty array when all lists are present and non-empty', () => {
    const result = findMissingLists('{{list:foo}}', { foo: ['a'] })
    expect(result).toEqual([])
  })

  it('returns an empty array when there are no list tokens', () => {
    const result = findMissingLists('{"a":"{{messageId}}"}', {})
    expect(result).toEqual([])
  })
})

describe('resolveString', () => {
  it('substitutes tokens without JSON parsing — the result is a plain string', () => {
    const result = resolveString('run={{runId}} msg={{messageId}}', BASE_CONTEXT)
    expect(result).toBe('run=run-123 msg=00000001')
  })

  it('resolves {{uuid}} to a UUID inside a header-style value', () => {
    const result = resolveString('{{uuid}}', BASE_CONTEXT)
    expect(result).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/)
  })

  it('resolves {{list:name}} from the value lists and "" when missing', () => {
    expect(resolveString('{{list:names}}', { ...BASE_CONTEXT, valueLists: { names: ['Mark'] } })).toBe('Mark')
    expect(resolveString('{{list:absent}}', BASE_CONTEXT)).toBe('')
  })

  it('leaves text without tokens untouched', () => {
    expect(resolveString('plain value', BASE_CONTEXT)).toBe('plain value')
  })
})
