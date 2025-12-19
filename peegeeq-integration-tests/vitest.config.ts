import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['src/test/typescript/**/*.smoke.ts'],
    globals: true,
    environment: 'node',
    testTimeout: 60000,  // 60 seconds for smoke tests
    hookTimeout: 120000, // 2 minutes for setup/teardown
    reporters: ['verbose', 'json'],
    outputFile: {
      json: './test-results/smoke-test-results.json'
    },
    coverage: {
      enabled: false  // Smoke tests don't need coverage
    }
  }
});

