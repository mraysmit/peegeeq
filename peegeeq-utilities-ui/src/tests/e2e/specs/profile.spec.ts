import { test, expect } from '../page-objects'
import * as fs from 'fs'

/**
 * Traffic-profile mode E2E — real backend, no mocks (G.3d obligation).
 *
 * Provisions its own setup + queue, then drives Profile mode end to end:
 * mode switch → phases editor → a REAL multi-phase run publishing to the
 * backend → per-phase achieved-vs-requested results → save as a profile
 * scenario → reload it and confirm the shape came back.
 *
 * This is the only automated coverage of the page's profile Start/Stop wiring:
 * the sequencer's semantics are unit-tested with fakes, but nothing else
 * exercises the page actually driving it against a live backend.
 *
 * Phases are deliberately tiny (2 s + 2 s) so the whole run is a few seconds.
 */

const API_BASE_URL = 'http://127.0.0.1:8088'
const SCHEMA = 'public'
const QUEUE_NAME = 'profileq'

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

test.describe('Traffic profile mode', () => {
  const stamp = Date.now()
  const SETUP_ID = `e2e-profile-${stamp}`
  const DB_NAME = `e2e_profile_db_${stamp}`

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

  async function selectOurTarget(page: import('@playwright/test').Page): Promise<void> {
    await expect(page.locator('#target-setup-select')).toBeVisible({ timeout: 15000 })
    await page.locator('.ant-select:has(#target-setup-select)').click()
    await page.locator('.ant-select-dropdown').getByTitle(SETUP_ID).click()
    await expect(page.locator('#target-queue-select')).toBeVisible({ timeout: 15000 })
  }

  /** Set the Nth phase row's rate and duration (rows are ordered as displayed). */
  async function setPhase(
    page: import('@playwright/test').Page,
    index: number,
    label: string,
    rate: number,
    durationSecs: number
  ): Promise<void> {
    const row = page.getByTestId(/^phase-row-/).nth(index)
    await row.getByLabel('Label').fill(label)
    await row.getByLabel('Rate (msg/s)').fill(String(rate))
    await row.getByLabel('Duration (seconds)').fill(String(durationSecs))
  }

  test('runs a two-phase profile for real and reports achieved vs requested per phase', async ({ page }) => {
    test.setTimeout(120000)
    page.on('pageerror', (error) => console.error('Page error:', error.message))

    await page.goto('/generator')
    await selectOurTarget(page)

    // Switch to Profile mode: Zone B becomes the phases editor.
    await page.getByTestId('generator-mode').getByText('Profile', { exact: true }).click()
    await expect(page.getByTestId('profile-phases-editor')).toBeVisible()
    await expect(page.getByTestId('profile-results-panel')).toBeVisible()

    // Scheduling cannot carry a profile — the button must be blocked here even
    // though a target IS selected (the unit tests cannot prove this: without a
    // backend they have no target and everything is disabled anyway).
    await expect(page.getByRole('button', { name: /Schedule/ })).toBeDisabled()

    // Two short phases: 5/s for 2 s, then 10/s for 2 s = 30 requested.
    await setPhase(page, 0, 'warm', 5, 2)
    await page.getByRole('button', { name: /Add phase/i }).click()
    await setPhase(page, 1, 'spike', 10, 2)
    await expect(page.getByTestId('profile-total-messages')).toContainText('30')
    await expect(page.getByTestId('profile-total-duration')).toContainText('4')

    await page.getByLabel(/Message type/i).fill('e2e.profile')
    await page.getByLabel(/Payload/i).fill('{"id":"{{messageId}}"}')

    await page.getByRole('button', { name: /^Start$/ }).click()

    // Both phases settle. The per-phase rows report the acknowledged sends the
    // BACKEND confirmed, so this asserts real publishing, not local counters.
    const rows = page.getByTestId(/^profile-result-row-/)
    await expect(rows).toHaveCount(2)
    await expect(page.getByTestId('profile-total-sent')).toContainText('30', { timeout: 60000 })
    await expect(page.getByTestId('profile-total-requested')).toContainText('30')

    // Neither phase under-delivered on a healthy backend.
    await expect(page.getByTestId(/^profile-shortfall-/)).toHaveCount(0)
  })

  test('a profile is saved and reloaded as a scenario, restoring its phases', async ({ page }) => {
    test.setTimeout(120000)
    page.on('pageerror', (error) => console.error('Page error:', error.message))

    const scenarioName = `profile-scenario-${stamp}`

    await page.goto('/generator')
    await selectOurTarget(page)
    await page.getByTestId('generator-mode').getByText('Profile', { exact: true }).click()
    await expect(page.getByTestId('profile-phases-editor')).toBeVisible()

    await setPhase(page, 0, 'burst', 40, 3)
    await page.getByRole('button', { name: /Add phase/i }).click()
    await setPhase(page, 1, 'idle', 0, 5)
    // The idle row is named as such, not left looking like an unset rate.
    await expect(page.getByTestId('phase-idle-badge-1')).toBeVisible()
    await expect(page.getByTestId('profile-total-messages')).toContainText('120')

    await page.getByTestId('scenario-save-as').click()
    await page.getByTestId('scenario-name-input').fill(scenarioName)
    await page.getByTestId('scenario-save-confirm').click()
    await expect(page.getByTestId('scenario-name-input')).toHaveCount(0)

    // The Tools row describes it by its PHASES, not by a flat rate it never uses.
    await page.goto('/tools')
    const row = page.getByRole('row').filter({ hasText: scenarioName })
    await expect(row).toHaveCount(1)
    await expect(row).toContainText('Profile')
    await expect(row).toContainText('2 phases')

    // Load restores mode AND the shape.
    await row.getByRole('button', { name: /^Load$/ }).click()
    await expect(page).toHaveURL(/\/generator$/)
    await expect(page.getByTestId('profile-phases-editor')).toBeVisible({ timeout: 15000 })
    await expect(page.getByTestId(/^phase-row-/)).toHaveCount(2)
    await expect(page.getByTestId('profile-total-messages')).toContainText('120')
    await expect(page.getByTestId('phase-idle-badge-1')).toBeVisible()
  })

  test('Stop during a profile halts the sequence and does not start the next phase', async ({ page }) => {
    test.setTimeout(120000)
    page.on('pageerror', (error) => console.error('Page error:', error.message))

    await page.goto('/generator')
    await selectOurTarget(page)
    await page.getByTestId('generator-mode').getByText('Profile', { exact: true }).click()
    await expect(page.getByTestId('profile-phases-editor')).toBeVisible()

    // A long first phase so there is time to stop inside it, and a second phase
    // that must never run.
    await setPhase(page, 0, 'long', 5, 60)
    await page.getByRole('button', { name: /Add phase/i }).click()
    await setPhase(page, 1, 'never', 5, 5)

    await page.getByLabel(/Message type/i).fill('e2e.profile.stop')
    await page.getByLabel(/Payload/i).fill('{"id":"{{messageId}}"}')

    await page.getByRole('button', { name: /^Start$/ }).click()
    await expect(page.getByTestId('run-status')).toContainText('RUNNING', { timeout: 20000 })

    await page.getByRole('button', { name: /^Stop$/ }).click()

    // Exactly one phase result: the stopped phase. The second never starts.
    await expect(page.getByTestId(/^profile-result-row-/).filter({ hasText: 'stopped' })).toHaveCount(1, {
      timeout: 30000,
    })
    // "never" stays PENDING — it did not run and did not report zero sent.
    const neverRow = page.getByTestId(/^profile-result-row-/).nth(1)
    await expect(neverRow).toContainText('pending')
  })
})
