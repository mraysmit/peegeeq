import { test, expect } from '../page-objects'
import * as fs from 'fs'

/**
 * Zone A (TargetSelector) failure paths e2e — real faults, no HTTP stubbing.
 *
 * Closes the last two GET-side gaps found 2026-07-23 (the publish-failure gap
 * is closed by generator-failure.spec.ts): the setups-load failure and the
 * queue-load failure for a listed setup were verified only against unit mocks.
 *
 * Both faults are REAL here:
 * - Setups-load failure: the app's own backend-config mechanism
 *   (localStorage `peegeeq_utilities_backend_config`) is pointed at a dead
 *   port — axios makes a genuine connection attempt and gets a genuine
 *   network refusal. Recovery is the real Retry button after restoring the
 *   config.
 * - Queue-load failure: a second setup is DETACHED via the admin REST path
 *   after the page loaded its setup list; switching the dropdown to the now
 *   stale entry issues a real GET /setups/{id}/queues against a setup the
 *   backend no longer has.
 *
 * What the backend actually returns for the stale setup's queue lookup is
 * discovered by this spec, not assumed — the UI contract under test is:
 * failure surfaces as the queue-load error alert (never a fabricated healthy
 * empty state), the armed target is CLEARED (Start disabled), and switching
 * back to a live setup recovers.
 */

const API_BASE_URL = 'http://127.0.0.1:8088'
const SCHEMA = 'public'
const CONFIG_KEY = 'peegeeq_utilities_backend_config'
/** Nothing listens here — connecting fails immediately with a real refusal. */
const DEAD_BACKEND = 'http://127.0.0.1:9'

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

test.describe('Target selection failure paths', () => {
  const stamp = Date.now()
  const SETUP_A = `e2e-tgtfail-a-${stamp}`
  const SETUP_B = `e2e-tgtfail-b-${stamp}`

  async function provision(request: Parameters<Parameters<typeof test.beforeAll>[0]>[0]['request'], setupId: string, dbName: string, queueName: string) {
    const db = readDbConfig()
    const create = await request.post(`${API_BASE_URL}/api/v1/database-setup/create`, {
      data: {
        setupId,
        databaseConfig: {
          host: db.host,
          port: db.port,
          databaseName: dbName,
          username: db.username,
          password: db.password,
          schema: SCHEMA,
          templateDatabase: 'template0',
          encoding: 'UTF8',
        },
        queues: [{ queueName, maxRetries: 3, visibilityTimeout: 30 }],
        eventStores: [],
      },
      timeout: 120000,
    })
    if (!create.ok()) {
      throw new Error(`Provision ${setupId} failed: ${create.status()} ${await create.text()}`)
    }
  }

  test.beforeAll(async ({ request }) => {
    await provision(request, SETUP_A, `e2e_tgtfail_a_${stamp}`, 'tgtfaila')
    await provision(request, SETUP_B, `e2e_tgtfail_b_${stamp}`, 'tgtfailb')
  })

  test.afterAll(async ({ request }) => {
    // B is detached by the test itself; 404s here are fine.
    await request.delete(`${API_BASE_URL}/api/v1/setups/${SETUP_A}`)
    await request.delete(`${API_BASE_URL}/api/v1/setups/${SETUP_B}`)
  })

  test.beforeEach(async ({ page }) => {
    page.on('pageerror', error => console.error('Page error:', error.message))
    // A previous failed run must not leave a dead backend config behind.
    await page.goto('/generator')
    await page.evaluate((key) => localStorage.removeItem(key), CONFIG_KEY)
  })

  test('unreachable backend: setups load shows the error with its cause, and Retry recovers', async ({ page }) => {
    // Point the app at the dead port and reload — a REAL network refusal.
    await page.evaluate(
      ([key, url]) => localStorage.setItem(key, JSON.stringify({ apiUrl: url })),
      [CONFIG_KEY, DEAD_BACKEND]
    )
    await page.reload()

    // The load error surfaces with a cause — never a spinner forever, never a
    // fabricated empty state ("No PeeGeeQ setup connected" would be a lie).
    const alert = page.locator('.ant-alert-error', { hasText: /Failed to load setups/ })
    await expect(alert).toBeVisible({ timeout: 15000 })
    await expect(page.getByText(/No PeeGeeQ setup connected/)).not.toBeVisible()

    // Restore the real backend, then recover through the UI's own Retry.
    await page.evaluate((key) => localStorage.removeItem(key), CONFIG_KEY)
    await alert.getByRole('button', { name: /Retry/i }).click()
    await expect(page.locator('#target-setup-select')).toBeVisible({ timeout: 15000 })
  })

  test('switching to a detached setup: queue-load error surfaces, Start disarms, switching back recovers', async ({ page, request }) => {
    // Healthy first: select A, queue loads, Start is armed.
    await expect(page.locator('#target-setup-select')).toBeVisible({ timeout: 15000 })
    await page.locator('.ant-select:has(#target-setup-select)').click()
    await page.locator('.ant-select-dropdown').getByTitle(SETUP_A).click()
    await expect(page.locator('#target-queue-select')).toBeVisible({ timeout: 15000 })
    await expect(page.getByRole('button', { name: /^Start$/ })).toBeEnabled()

    // The REAL fault: detach B while it is still listed in the page's dropdown.
    const detach = await request.delete(`${API_BASE_URL}/api/v1/setups/${SETUP_B}`)
    if (!detach.ok()) {
      throw new Error(`Detach ${SETUP_B} failed: ${detach.status()} ${await detach.text()}`)
    }

    // Switching to the stale entry issues a real queue lookup against a setup
    // the backend no longer has.
    await page.locator('.ant-select:has(#target-setup-select)').click()
    await page.locator('.ant-select-dropdown').getByTitle(SETUP_B).click()

    // Failure surfaces as the queue-load error (with its cause), NOT as a
    // healthy-looking "no queues yet" empty state — and the previously armed
    // target is cleared: Start must not publish to setup A's queue while the
    // UI shows setup B.
    await expect(page.getByTestId('queue-load-error')).toBeVisible({ timeout: 15000 })
    await expect(page.getByText(/No queues found for this setup/)).not.toBeVisible()
    await expect(page.getByRole('button', { name: /^Start$/ })).toBeDisabled()

    // Recovery: switch back to the live setup — queues load, Start re-arms.
    await page.locator('.ant-select:has(#target-setup-select)').click()
    await page.locator('.ant-select-dropdown').getByTitle(SETUP_A).click()
    await expect(page.locator('#target-queue-select')).toBeVisible({ timeout: 15000 })
    await expect(page.getByRole('button', { name: /^Start$/ })).toBeEnabled()
  })
})
