/**
 * Token edge cases for templateResolver, deepening templateResolver.test.ts.
 *
 * Dimensions: repeated occurrences of the same token within one message
 * (which tokens repeat the same value and which regenerate per occurrence),
 * numeric boundaries of the padded counter and the random tokens, malformed
 * token forms (all left as literal text), substitution-order and injection
 * safety for user-supplied list values, JSON-validity consequences, unicode,
 * and volume.
 */
import { describe, it, expect } from 'vitest'
import { resolveTemplate, resolveString, findMissingLists } from '../../engine/templateResolver'
import type { TemplateContext } from '../../engine/templateResolver'

const BASE: TemplateContext = {
  messageId: 1,
  index: 0,
  runId: 'run-123',
  correlationId: 'corr-456',
  now: new Date('2026-05-30T14:23:00.000Z'),
  valueLists: {},
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

describe('repeated occurrences within one message', () => {
  it('{{messageId}} repeats the same value at every occurrence', () => {
    expect(resolveString('{{messageId}}|{{messageId}}|{{messageId}}', BASE)).toBe(
      '00000001|00000001|00000001'
    )
  })

  it('{{uuid}} repeats the SAME uuid at every occurrence within one resolution', () => {
    // The uuid is memoised per CONTEXT (one context = one message), so all
    // occurrences in one message share it — payload and headers alike. §5.3
    // requires per-MESSAGE freshness; per-occurrence freshness is not promised.
    const [a, b] = resolveString('{{uuid}}|{{uuid}}', BASE).split('|')
    expect(a).toMatch(UUID)
    expect(a).toBe(b)
  })

  it('{{randomAlpha:N}} regenerates per occurrence', () => {
    const parts = resolveString('{{randomAlpha:20}}|{{randomAlpha:20}}', BASE).split('|')
    expect(parts[0]).toHaveLength(20)
    expect(parts[1]).toHaveLength(20)
    expect(parts[0]).not.toBe(parts[1]) // 62^20 collision probability is negligible
  })

  it('{{random:N}} evaluates per occurrence within its range', () => {
    const parts = resolveString(
      Array.from({ length: 50 }, () => '{{random:1000}}').join('|'),
      BASE
    ).split('|')
    expect(parts).toHaveLength(50)
    for (const p of parts) {
      const n = Number(p)
      expect(Number.isInteger(n)).toBe(true)
      expect(n).toBeGreaterThanOrEqual(0)
      expect(n).toBeLessThan(1000)
    }
  })

  it('{{list:name}} picks per occurrence', () => {
    const context = { ...BASE, valueLists: { names: ['a', 'b', 'c', 'd', 'e', 'f'] } }
    const picks = new Set(
      resolveString(Array.from({ length: 100 }, () => '{{list:names}}').join(''), context).split('')
    )
    // 100 independent picks from 6 values produce more than one distinct value.
    expect(picks.size).toBeGreaterThan(1)
    for (const p of picks) expect(['a', 'b', 'c', 'd', 'e', 'f']).toContain(p)
  })
})

describe('numeric boundaries', () => {
  it('pads messageId to 8 digits up to 99999999 and does not truncate beyond', () => {
    expect(resolveString('{{messageId}}', { ...BASE, messageId: 99_999_999 })).toBe('99999999')
    expect(resolveString('{{messageId}}', { ...BASE, messageId: 100_000_000 })).toBe('100000000')
  })

  it('{{random:0}} resolves to 0 and {{random:1}} always to 0', () => {
    expect(resolveString('{{random:0}}', BASE)).toBe('0')
    for (let i = 0; i < 20; i++) {
      expect(resolveString('{{random:1}}', BASE)).toBe('0')
    }
  })

  it('{{randomAlpha:0}} resolves to an empty string', () => {
    expect(resolveString('[{{randomAlpha:0}}]', BASE)).toBe('[]')
  })

  it('{{randomAlpha:500}} produces exactly 500 alphanumeric characters', () => {
    const result = resolveString('{{randomAlpha:500}}', BASE)
    expect(result).toHaveLength(500)
    expect(result).toMatch(/^[A-Za-z0-9]{500}$/)
  })

  it('{{index}} is not zero-padded', () => {
    expect(resolveString('{{index}}', { ...BASE, index: 7 })).toBe('7')
  })
})

describe('malformed token forms are left as literal text', () => {
  it.each([
    ['unknown token', '{{unknown}}'],
    ['case-sensitive name', '{{MessageId}}'],
    ['spaces inside braces', '{{ messageId }}'],
    ['unclosed braces', '{{messageId}'],
    ['random without count', '{{random:}}'],
    ['random with non-numeric count', '{{random:abc}}'],
    ['random with negative count', '{{random:-5}}'],
    ['list with empty name', '{{list:}}'],
    ['list with a space in the name', '{{list:bad name}}'],
    ['single braces', '{messageId}'],
  ])('%s stays literal', (_label, token) => {
    expect(resolveString(token, BASE)).toBe(token)
  })

  it('adjacent valid tokens both resolve with nothing lost between them', () => {
    expect(resolveString('{{messageId}}{{index}}', BASE)).toBe('000000010')
  })

  it('a token in a JSON key position resolves', () => {
    expect(resolveTemplate('{"{{messageId}}": true}', BASE)).toEqual({ '00000001': true })
  })
})

describe('substitution safety for user-supplied list values', () => {
  it('a list value containing replacement-pattern characters ($&, $\', $1) is inserted literally', () => {
    const context = { ...BASE, valueLists: { tricky: ['$& $` $\' $1 $$'] } }
    expect(resolveString('{{list:tricky}}', context)).toBe('$& $` $\' $1 $$')
  })

  it('a list value containing a token string is NOT re-resolved (no injection)', () => {
    // The list substitution runs last; token text arriving from list data must
    // stay literal, not become an injection vector.
    const context = { ...BASE, valueLists: { inject: ['{{messageId}}'] } }
    expect(resolveString('{{list:inject}}', context)).toBe('{{messageId}}')
  })

  it('a list value containing backslashes survives unchanged', () => {
    const context = { ...BASE, valueLists: { paths: ['C:\\temp\\file'] } }
    expect(resolveString('{{list:paths}}', context)).toBe('C:\\temp\\file')
  })

  it('unicode list values survive unchanged', () => {
    const context = { ...BASE, valueLists: { names: ['Müller — 佐藤 🚀'] } }
    expect(resolveString('{{list:names}}', context)).toBe('Müller — 佐藤 🚀')
  })
})

describe('JSON-validity consequences (documented limitation: values are not JSON-escaped)', () => {
  it('a list value containing a double quote makes resolveTemplate throw', () => {
    const context = { ...BASE, valueLists: { bad: ['he said "hi"'] } }
    expect(() => resolveTemplate('{"v":"{{list:bad}}"}', context)).toThrow()
  })

  it('the same value is fine through resolveString (header path, no JSON parsing)', () => {
    const context = { ...BASE, valueLists: { bad: ['he said "hi"'] } }
    expect(resolveString('{{list:bad}}', context)).toBe('he said "hi"')
  })

  it('a bare numeric token outside quotes produces a JSON number', () => {
    const result = resolveTemplate('{"n": {{random:5}}}', BASE) as { n: number }
    expect(typeof result.n).toBe('number')
  })
})

describe('volume', () => {
  it('resolves 200 tokens in one template completely', () => {
    const template = Array.from({ length: 200 }, () => '{{messageId}}').join(',')
    const result = resolveString(template, BASE)
    expect(result.split(',')).toHaveLength(200)
    expect(result).not.toContain('{{')
  })
})

describe('findMissingLists — name-pattern edges', () => {
  it('accepts hyphens, underscores, and digits in list names', () => {
    expect(findMissingLists('{{list:a-b}} {{list:c_d}} {{list:x9}}', {})).toEqual([
      'a-b',
      'c_d',
      'x9',
    ])
  })

  it('reports a present-but-empty list as missing', () => {
    expect(findMissingLists('{{list:empty}}', { empty: [] })).toEqual(['empty'])
  })

  it('ignores malformed list tokens entirely', () => {
    expect(findMissingLists('{{list:}} {{list:bad name}} {{lists:x}}', {})).toEqual([])
  })

  it('preserves first-appearance order across duplicates', () => {
    expect(findMissingLists('{{list:b}} {{list:a}} {{list:b}}', {})).toEqual(['b', 'a'])
  })
})
