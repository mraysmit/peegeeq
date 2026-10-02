import { expect, test } from 'vitest'

test('unit report preserves the real outcome', () => {
  expect(process.env.PEEGEEQ_REPORT_CONTRACT_FAIL).not.toBe('1')
})
