import { test, expect } from '../page-objects'
import * as fs from 'fs'

/**
 * Ramp (breaking-point) mode E2E — real backend, no mocks (G.1a obligation).
 *
 * Provisions its own setup + queue, then drives Ramp mode end to end: mode
 * switch → planned steps listed before anything runs → a REAL climbing run
 * publishing to the backend → the knee readout. Also pins the two refusals a
 * ramp carries, which the unit tests cannot prove without a target:
 * Schedule is blocked, and a ramp cannot be saved as a scenario.
 *
 * The ramp is deliberately tiny (2 s steps, cap reached in three) so the whole
 * run is a few seconds.
 */

const API_BASE_URL = 'http://127.0.0.1:8088'
const SCHEMA = 'public'
const QUEUE_NAME = 'rampq'

interface DbConnectionInfo {
  host: string
  port: number
  username: string
  password: string
}

function readDbConfig(): DbConnectionInfo {
  const raw = JSON.parse(fs.readFileSync('testcontainers-db.json', 'utf8'))
  return { host: raw.host, port: raw.port, username: raw.username, password: raw.password }
}

test.describe.configure({ mode: 'serial' })

test.describe('Ramp mode', () => {
  const stamp = Date.now()
  const SETUP_ID = `e2e-ramp-${stamp}`
  const DB_NAME = `e2e_ramp_db_${stamp}`

  test.beforeAll(async ({ request }) => {
    const db = readDbConfig()
    const create = await request.post(`${API_BASE_URL}/api/v1/database-setup/create`, {
      data: {
        setupId: SETUP_ID,
        databaseConfig: {
          host: db.host,
          port: db.port,
          databaseName: DB_NAME,
          username: db.username,
          password: db.password,
          schema: SCHEMA,
          templateDatabase: 'template0',
          encoding: 'UTF8',
        },
        queues: [{ queueName: QUEUE_NAME, maxRetries: 3, visibilityTimeout: 30 }],
        eventStores: [],
      },
      timeout: 120000,
    })
    if (!create.ok()) {
      throw new Error(`Provision (create) failed: ${create.status()} ${await create.text()}`)
    }
  })

  test.afterAll(async ({ request }) => {
    await request.delete(`${API_BASE_URL}/api/v1/setups/${SETUP_ID}`)
  })

  async function openRampMode(page: import('@playwright/test').Page): Promise<void> {
    await page.goto('/generator')
    await expect(page.locator('#target-setup-select')).toBeVisible({ timeout: 15000 })
    await page.locator('.ant-select:has(#target-setup-select)').click()
    await page.locator('.ant-select-dropdown').getByTitle(SETUP_ID).click()
    await expect(page.locator('#target-queue-select')).toBeVisible({ timeout: 15000 })
    await page.getByTestId('generator-mode').getByText('Ramp', { exact: true }).click()
    await expect(page.getByTestId('ramp-controls')).toBeVisible()
  }

  /** A short ramp: 5 → 15 msg/s in steps of 5, each held 2 s = three steps. */
  async function setSmallRamp(page: import('@playwright/test').Page): Promise<void> {
    await page.getByLabel(/Start rate/i).fill('5')
    await page.getByLabel(/Step size/i).fill('5')
    await page.getByLabel(/Step every/i).fill('2')
    await page.getByLabel(/Max rate/i).fill('15')
  }

  test('plans the steps before anything runs, and refuses what a ramp cannot do', async ({ page }) => {
    test.setTimeout(120000)
    page.on('pageerror', (error) => console.error('Page error:', error.message))

    await openRampMode(page)
    await setSmallRamp(page)

    // The preview and the step table agree, because both come from the same
    // builder the run uses.
    await expect(page.getByTestId('ramp-plan-preview')).toContainText('3 steps')
    await expect(page.getByTestId('ramp-plan-preview')).toContainText('15')
    await expect(page.getByTestId(/^profile-result-row-/)).toHaveCount(3)

    // Every step is PENDING before the run — not "0 sent".
    await expect(page.getByTestId(/^profile-result-row-/).first()).toContainText('pending')

    // Both refusals, asserted WITH a target selected (the unit tests cannot):
    // a schedule stores one RunConfig, and a Scenario has no ramp kind.
    await expect(page.getByRole('button', { name: /Schedule/ })).toBeDisabled()
    await expect(page.getByTestId('scenario-save-as')).toBeDisabled()
  })

  test('climbs for real and reports the max sustained rate', async ({ page }) => {
    test.setTimeout(120000)
    page.on('pageerror', (error) => console.error('Page error:', error.message))

    await openRampMode(page)
    await setSmallRamp(page)
    await page.getByLabel(/Message type/i).fill('e2e.ramp')
    await page.getByLabel(/Payload/i).fill('{"id":"{{messageId}}"}')

    await page.getByRole('button', { name: /^Start$/ }).click()

    // The ramp runs its steps against the real backend and reports a knee.
    // These rates are trivial for a healthy queue, so it reaches the cap.
    await expect(page.getByTestId('ramp-knee')).toBeVisible({ timeout: 90000 })
    await expect(page.getByTestId('ramp-knee')).toContainText(/sustained/i)
    // 5×2 + 10×2 + 15×2 = 60 requested across the three steps.
    await expect(page.getByTestId('profile-total-requested')).toContainText('60')
    await expect(page.getByTestId('profile-total-sent')).toContainText('60')
  })

  test('an empty ramp (start above the cap) is surfaced and cannot start', async ({ page }) => {
    test.setTimeout(120000)
    page.on('pageerror', (error) => console.error('Page error:', error.message))

    await openRampMode(page)
    await page.getByLabel(/Start rate/i).fill('500')
    await page.getByLabel(/Max rate/i).fill('100')

    await expect(page.getByTestId('ramp-empty-advisory')).toBeVisible()
    await expect(page.getByTestId(/^profile-result-row-/)).toHaveCount(0)
    await expect(page.getByRole('button', { name: /^Start$/ })).toBeDisabled()
  })
})
