import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  test: {
    globals: false,  // Disable globals to avoid conflicts with Playwright
    environment: 'jsdom',
    setupFiles: ['./src/tests/vitest.setup.ts'],
    include: [
      'src/**/*.{test,spec}.{js,mjs,cjs,ts,mts,cts,jsx,tsx}',
      'src/tests/**/*.{test,spec}.{js,mjs,cjs,ts,mts,cts,jsx,tsx}'
    ],
    exclude: [
      'node_modules',
      'dist',
      '.git',
      '.cache',
      'src/tests/e2e/**' // Exclude Playwright E2E tests - run with npx playwright test
    ],
    testTimeout: 10000,
    hookTimeout: 10000,
    teardownTimeout: 10000,
    // Integration tests may need more time
    slowTestThreshold: 5000,
    // Coverage configuration
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
      exclude: [
        'node_modules/',
        'src/tests/',
        '**/*.d.ts',
        '**/*.config.*',
        'dist/'
      ]
    }
  },
  resolve: {
    alias: {
      '@': '/src'
    }
  }
})
