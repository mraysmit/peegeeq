import { defineConfig } from '@playwright/test'
import production from './playwright.config'

// Keep the config at the production config's directory so reporter paths resolve identically.
export default defineConfig({
  ...production,
  testDir: './scripts/reporting/fixtures',
  testMatch: 'browser.spec.ts',
  globalSetup: undefined,
  globalTeardown: undefined,
  webServer: undefined,
  retries: 0,
  projects: [{ name: 'report-contract', use: { headless: true } }],
  outputDir: 'target/report-contract-results',
})
