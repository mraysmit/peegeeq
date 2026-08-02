import { test, expect } from '../page-objects'
import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'

/**
 * Saved-scenario round trip E2E — real backend, no mocks (G.4 obligation).
 *
 * Provisions its own setup + queue via the admin REST path, then drives the
 * complete journey the unit tests can only cover in pieces:
 *
 *   generator → Save as… → /tools row (DERIVED target + run columns) →
 *   Export (real browser download) → Delete → Import (the exported file) →
 *   Load → generator repopulated INCLUDING the target
 *
 * The target restore is the load-bearing assertion: Zone A auto-selects the
 * first setup and first queue, so a scenario that cannot re-select its own
 * setup+queue would silently retarget the run at another queue.
 *
 * Each Playwright test gets a fresh browser context, so localStorage does not
 * survive between tests — every journey here is self-contained by design.
 */

const API_BASE_URL = 'http://127.0.0.1:8088'
const SCHEMA = 'public'
const QUEUE_NAME = 'scenq'

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

test.describe('Saved scenarios', () => {
  const stamp = Date.now()
  const SETUP_ID = `e2e-scenario-${stamp}`
  const DB_NAME = `e2e_scenario_db_${stamp}`
  const SCENARIO_NAME = `nightly-soak-${stamp}`

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

  /** Select OUR setup + queue — other specs' setups may sort first in Zone A. */
  async function selectOurTarget(page: import('@playwright/test').Page): Promise<void> {
    await expect(page.locator('#target-setup-select')).toBeVisible({ timeout: 15000 })
    await page.locator('.ant-select:has(#target-setup-select)').click()
    await page.locator('.ant-select-dropdown').getByTitle(SETUP_ID).click()
    await expect(page.locator('#target-queue-select')).toBeVisible({ timeout: 15000 })
  }

  test('round trip: save → tools row → export → delete → import → load restores the target', async ({ page }) => {
    test.setTimeout(90000)
    page.on('pageerror', (error) => console.error('Page error:', error.message))

    // ── Configure a run worth saving ────────────────────────────────────────
    await page.goto('/generator')
    await selectOurTarget(page)
    await page.getByLabel(/Rate \(msg\/s\)/i).fill('42')
    await page.getByLabel(/Duration \(seconds\)/i).fill('17')
    await page.getByLabel(/Message type/i).fill('e2e.scenario')
    await page.getByLabel(/Payload/i).fill('{"id":"{{messageId}}"}')
    await expect(page.getByTestId('total-messages')).toContainText('714')

    // ── Save as… ────────────────────────────────────────────────────────────
    await page.getByTestId('scenario-save-as').click()
    await page.getByTestId('scenario-name-input').fill(SCENARIO_NAME)
    await page.getByTestId('scenario-save-confirm').click()
    await expect(page.getByTestId('scenario-name-input')).toHaveCount(0)

    // ── The Tools row, with both DERIVED columns ────────────────────────────
    await page.goto('/tools')
    await expect(page.getByTestId('tools-page')).toBeVisible()
    const row = page.getByRole('row').filter({ hasText: SCENARIO_NAME })
    await expect(row).toHaveCount(1)
    // Target and the rate × duration total are computed from the stored config.
    await expect(row).toContainText(`${SETUP_ID} / ${QUEUE_NAME}`)
    await expect(row).toContainText('714')

    // ── Export: a real browser download ─────────────────────────────────────
    const downloadPromise = page.waitForEvent('download')
    await row.locator('[data-testid^="scenario-export-"]').click()
    const download = await downloadPromise
    const exportPath = path.join(os.tmpdir(), `pgq-scenario-${stamp}.json`)
    await download.saveAs(exportPath)
    const exported = JSON.parse(fs.readFileSync(exportPath, 'utf8'))
    expect(exported.name).toBe(SCENARIO_NAME)
    expect(exported.config.setupId).toBe(SETUP_ID)
    expect(exported.config.queueName).toBe(QUEUE_NAME)
    expect(exported.config.rate).toBe(42)

    // ── Delete: back to the empty state ─────────────────────────────────────
    await row.locator('[data-testid^="scenario-delete-"]').click()
    await page.locator('.ant-popconfirm').getByRole('button', { name: /^Delete$/ }).click()
    await expect(page.getByTestId('scenarios-empty')).toBeVisible()

    // ── Import the file we exported: the row returns ────────────────────────
    await page.getByRole('button', { name: /Import/ }).click()
    await page.getByTestId('scenario-import-input').setInputFiles(exportPath)
    const restored = page.getByRole('row').filter({ hasText: SCENARIO_NAME })
    await expect(restored).toHaveCount(1)
    await expect(restored).toContainText(`${SETUP_ID} / ${QUEUE_NAME}`)

    // ── Load: the generator is repopulated, TARGET INCLUDED ─────────────────
    await restored.getByRole('button', { name: /^Load$/ }).click()
    await expect(page).toHaveURL(/\/generator$/)
    await expect(page.getByLabel(/Rate \(msg\/s\)/i)).toHaveValue('42')
    await expect(page.getByLabel(/Duration \(seconds\)/i)).toHaveValue('17')
    await expect(page.getByLabel(/Message type/i)).toHaveValue('e2e.scenario')
    // The saved target is re-selected rather than Zone A's first-setup default.
    await expect(
      page.locator('.ant-select:has(#target-setup-select) .ant-select-selection-item')
    ).toHaveText(SETUP_ID, { timeout: 15000 })
    await expect(
      page.locator('.ant-select:has(#target-queue-select) .ant-select-selection-item')
    ).toContainText(QUEUE_NAME)
    // Restored fully enough to publish: Start is armed.
    await expect(page.getByRole('button', { name: /^Start$/ })).toBeEnabled()

    fs.unlinkSync(exportPath)
  })

  test('the generator scenario bar loads a saved scenario without leaving the page', async ({ page }) => {
    test.setTimeout(90000)
    page.on('pageerror', (error) => console.error('Page error:', error.message))

    await page.goto('/generator')
    await selectOurTarget(page)
    await page.getByLabel(/Rate \(msg\/s\)/i).fill('33')
    await page.getByLabel(/Duration \(seconds\)/i).fill('11')

    await page.getByTestId('scenario-save-as').click()
    await page.getByTestId('scenario-name-input').fill(SCENARIO_NAME)
    await page.getByTestId('scenario-save-confirm').click()
    await expect(page.getByTestId('scenario-name-input')).toHaveCount(0)

    // Drift away from the saved values, then Load them back in place.
    await page.getByLabel(/Rate \(msg\/s\)/i).fill('7')
    await page.getByLabel(/Duration \(seconds\)/i).fill('5')
    await expect(page.getByTestId('total-messages')).toContainText('35')

    await page.locator('.ant-select:has(#scenario-select)').click()
    await page.locator('.ant-select-dropdown').getByTitle(SCENARIO_NAME).click()
    await page.getByTestId('scenario-load').click()

    await expect(page.getByLabel(/Rate \(msg\/s\)/i)).toHaveValue('33')
    await expect(page.getByLabel(/Duration \(seconds\)/i)).toHaveValue('11')
    await expect(page.getByTestId('total-messages')).toContainText('363')
    // Still on the generator — the bar never navigates.
    await expect(page).toHaveURL(/\/generator$/)
  })

  test('a scenario whose queue no longer exists warns instead of silently retargeting', async ({ page, request }) => {
    test.setTimeout(120000)
    page.on('pageerror', (error) => console.error('Page error:', error.message))

    // Save a scenario against a SECOND throwaway setup, then destroy it. The
    // scenario now points at a target the backend no longer serves — the case
    // that would otherwise publish load at whatever Zone A auto-selects.
    const doomedStamp = Date.now()
    const DOOMED_SETUP = `e2e-scenario-doomed-${doomedStamp}`
    const db = readDbConfig()
    const create = await request.post(`${API_BASE_URL}/api/v1/database-setup/create`, {
      data: {
        setupId: DOOMED_SETUP,
        databaseConfig: {
          host: db.host,
          port: db.port,
          databaseName: `e2e_scenario_doomed_db_${doomedStamp}`,
          username: db.username,
          password: db.password,
          schema: SCHEMA,
          templateDatabase: 'template0',
          encoding: 'UTF8',
        },
        queues: [{ queueName: 'doomedq', maxRetries: 3, visibilityTimeout: 30 }],
        eventStores: [],
      },
      timeout: 120000,
    })
    if (!create.ok()) {
      throw new Error(`Provision (doomed) failed: ${create.status()} ${await create.text()}`)
    }

    await page.goto('/generator')
    await expect(page.locator('#target-setup-select')).toBeVisible({ timeout: 15000 })
    await page.locator('.ant-select:has(#target-setup-select)').click()
    await page.locator('.ant-select-dropdown').getByTitle(DOOMED_SETUP).click()
    await expect(page.locator('#target-queue-select')).toBeVisible({ timeout: 15000 })

    const doomedScenario = `doomed-${doomedStamp}`
    await page.getByTestId('scenario-save-as').click()
    await page.getByTestId('scenario-name-input').fill(doomedScenario)
    await page.getByTestId('scenario-save-confirm').click()
    await expect(page.getByTestId('scenario-name-input')).toHaveCount(0)

    // Destroy the target the scenario points at.
    const detach = await request.delete(`${API_BASE_URL}/api/v1/setups/${DOOMED_SETUP}`)
    expect(detach.ok()).toBeTruthy()

    await page.goto('/tools')
    const doomedRow = page.getByRole('row').filter({ hasText: doomedScenario })
    await doomedRow.getByRole('button', { name: /^Load$/ }).click()

    // The substitution is NAMED, not silent.
    await expect(page.getByTestId('target-unavailable')).toBeVisible({ timeout: 20000 })
    await expect(page.getByTestId('target-unavailable')).toContainText(DOOMED_SETUP)
  })
})
