import { test, expect, type Page } from '@playwright/test'
import * as fs from 'fs'

const API_BASE_URL = 'http://127.0.0.1:8088'
const stamp = Date.now()
const SETUP_ID = `e2e-compare-contracts-${stamp}`

function readDbConfig() {
  const raw = JSON.parse(fs.readFileSync('testcontainers-db.json', 'utf8'))
  return { host: raw.host, port: raw.port, username: raw.username, password: raw.password }
}

async function enterCompareMode(page: Page) {
  const radio = page.getByRole('radio', { name: 'Compare' })
  await radio.evaluate((element: HTMLInputElement) => element.click())
  await expect(radio).toBeChecked()
}

const checks: Array<{ name: string; verify: (page: Page) => Promise<void> }> = [
  { name: 'Compare remains selected', verify: async (page) => expect(page.getByRole('radio', { name: 'Compare' })).toBeChecked() },
  { name: 'two-target container', verify: async (page) => expect(page.getByTestId('compare-targets')).toBeVisible() },
  { name: 'native target row', verify: async (page) => expect(page.getByTestId('compare-row-native')).toBeVisible() },
  { name: 'outbox target row', verify: async (page) => expect(page.getByTestId('compare-row-outbox')).toBeVisible() },
  { name: 'native setup selector', verify: async (page) => expect(page.getByRole('combobox', { name: 'Native queue setup' })).toBeAttached() },
  { name: 'outbox setup selector', verify: async (page) => expect(page.getByRole('combobox', { name: 'Outbox queue setup' })).toBeAttached() },
  { name: 'shared rate controls', verify: async (page) => expect(page.getByTestId('rate-controls')).toBeVisible() },
  { name: 'empty comparison result', verify: async (page) => expect(page.getByTestId('compare-results-empty')).toBeVisible() },
]

test.describe('Compare and cross-cutting responsive contracts', () => {
  test.describe.configure({ mode: 'serial' })

  test.beforeAll(async ({ request }) => {
    const db = readDbConfig()
    const response = await request.post(`${API_BASE_URL}/api/v1/database-setup/create`, {
      data: {
        setupId: SETUP_ID,
        databaseConfig: {
          host: db.host,
          port: db.port,
          databaseName: `e2e_compare_contracts_${stamp}`,
          username: db.username,
          password: db.password,
          schema: 'public',
          templateDatabase: 'template0',
          encoding: 'UTF8',
        },
        queues: [
          { queueName: 'contracts_native', implementationType: 'native', maxRetries: 3, visibilityTimeout: 30 },
          { queueName: 'contracts_outbox', implementationType: 'outbox', maxRetries: 3, visibilityTimeout: 30 },
        ],
        eventStores: [],
      },
      timeout: 120000,
    })
    if (!response.ok()) {
      throw new Error(`Compare contract setup failed: ${response.status()} ${await response.text()}`)
    }
  })

  test.afterAll(async ({ request }) => {
    const response = await request.delete(`${API_BASE_URL}/api/v1/setups/${SETUP_ID}`)
    if (!response.ok() && response.status() !== 404) {
      throw new Error(`Compare contract cleanup failed: ${response.status()} ${await response.text()}`)
    }
  })

  for (const check of checks) {
    test(check.name, async ({ page }) => {
      await page.goto('/generator')
      await enterCompareMode(page)
      await check.verify(page)
    })
  }
})
