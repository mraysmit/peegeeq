import { defineConfig } from 'vitest/config'
import production from '../../vitest.config'

// Exercise the production reporters with a tiny real suite, not mocked reporter calls.
export default defineConfig({
  ...production,
  test: {
    ...production.test,
    include: ['scripts/reporting/fixtures/unit.test.ts'],
    exclude: [],
    setupFiles: [],
    environment: 'node',
  },
})
