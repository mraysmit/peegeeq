import { test, expect } from '../page-objects'
import * as fs from 'fs'
import * as path from 'path'

/**
 * Documentation screenshot capture.
 *
 * Walks every page and every primary action in the PeeGeeQ Utilities UI and
 * writes a full-page PNG for each into `docs/screenshots/`. The captured files
 * are referenced from the "Appendix A — UI Screenshots" section of
 * `docs/QUEUE_MESSAGE_GENERATOR_DESIGN.md`.
 *
 * This spec is NOT part of the normal e2e suite — it runs only under
 * `playwright.screenshots.config.ts`. It creates a throwaway demo setup and
 * queue so the data-bearing pages render with real content, then cleans them up.
 *
 * Run with:
 *   npx playwright test --config=playwright.screenshots.config.ts
 */

const API_BASE_URL = 'http://127.0.0.1:8088'
const DEMO_SETUP_ID = 'screenshot-demo'
const DEMO_QUEUE_NAME = 'demo_orders'

const SHOTS_DIR = path.join(process.cwd(), 'docs', 'screenshots')

interface DbConnectionInfo {
  host: string
  port: number
  username: string
  password: string
}

function readDbConfig(): DbConnectionInfo {
  const filePath = path.join(process.cwd(), 'testcontainers-db.json')
  const raw = JSON.parse(fs.readFileSync(filePath, 'utf8'))
  return { host: raw.host, port: raw.port, username: raw.username, password: raw.password }
}

test.describe.configure({ mode: 'serial' })

test.describe('UI documentation screenshots', () => {
  let dbConfig: DbConnectionInfo

  test.beforeAll(async ({ request }) => {
    fs.mkdirSync(SHOTS_DIR, { recursive: true })
    dbConfig = readDbConfig()
    // Start from a clean slate for the demo setup.
    await request.delete(`${API_BASE_URL}/api/v1/database-setup/${DEMO_SETUP_ID}`)
  })

  test.afterAll(async ({ request }) => {
    // Remove the throwaway demo setup so we don't pollute shared DB state.
    await request.delete(`${API_BASE_URL}/api/v1/database-setup/${DEMO_SETUP_ID}`)
  })

  /**
   * Capture a full-page screenshot. `page.screenshot({ fullPage: true })`
   * combined with the tall viewport from the config captures all content.
   */
  async function shot(page: import('@playwright/test').Page, name: string) {
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.screenshot({ path: path.join(SHOTS_DIR, name), fullPage: true })
  }

  test('capture all pages and actions', async ({ page, generatorPage, setupsPage }) => {
    // ── 01 Overview ────────────────────────────────────────────────────────
    await page.goto('/')
    await page.waitForLoadState('load')
    await expect(page.getByTestId('app-layout')).toBeVisible()
    await page.waitForTimeout(800) // let charts/stats settle
    await shot(page, '01-overview.png')

    // ── 02 Tools ───────────────────────────────────────────────────────────
    await page.goto('/tools')
    await page.waitForLoadState('load')
    await page.waitForTimeout(500)
    await shot(page, '02-tools.png')

    // ── 03 Message Generator ────────────────────────────────────────────────
    await page.goto('/generator')
    await page.waitForLoadState('load')
    await expect(page.getByTestId('generator-page')).toBeVisible()
    await page.waitForTimeout(800)
    await shot(page, '03-message-generator.png')

    // ── 04 Templates (Phase 3 placeholder) ──────────────────────────────────
    await page.goto('/generator/templates')
    await page.waitForLoadState('load')
    await expect(page.getByText('Template Manager')).toBeVisible()
    await shot(page, '04-templates.png')

    // ── 05 Value Lists (Phase 3 placeholder) ────────────────────────────────
    await page.goto('/generator/value-lists')
    await page.waitForLoadState('load')
    await expect(page.getByText('Value List Manager')).toBeVisible()
    await shot(page, '05-value-lists.png')

    // ── 06 Setups list (before creating the demo setup) ─────────────────────
    await page.goto('/setups')
    await page.waitForLoadState('load')
    await expect(page.getByTestId('setups-page')).toBeVisible()
    await page.waitForTimeout(800)
    await shot(page, '06-setups-list.png')

    // ── 07 Create Setup — empty form ────────────────────────────────────────
    await page.getByTestId('create-setup-button').click()
    await page.waitForURL('**/generator/setup/new')
    await expect(page.getByTestId('create-setup-page')).toBeVisible()
    await shot(page, '07-create-setup-empty.png')

    // ── 08 Create Setup — filled + connection details expanded ──────────────
    await generatorPage.getWizardSetupNameInput().fill(DEMO_SETUP_ID)
    await generatorPage.getWizardDatabaseNameInput().fill('peegeeq_screenshot_demo')
    await generatorPage.getWizardPasswordInput().fill(dbConfig.password)
    await generatorPage.expandConnectionDetails()
    await generatorPage.getWizardHostInput().fill(dbConfig.host)
    await generatorPage.getWizardPortInput().fill(String(dbConfig.port))
    await page.waitForTimeout(300)
    await shot(page, '08-create-setup-filled.png')

    // Submit — creates the demo setup in the backend.
    await generatorPage.getWizardCreateButton().click()
    await page.waitForURL('**/setups', { timeout: 60000 })
    await expect(setupsPage.getHeading()).toBeVisible()
    await expect(page.locator(`text=${DEMO_SETUP_ID}`)).toBeVisible({ timeout: 20000 })

    // ── 09 Setups list — populated ──────────────────────────────────────────
    await page.waitForTimeout(800)
    await shot(page, '09-setups-list-populated.png')

    // ── 10 Setup detail — no queues yet ─────────────────────────────────────
    await page.getByTestId(`view-details-${DEMO_SETUP_ID}`).click()
    await page.waitForURL(`**/setups/${DEMO_SETUP_ID}`)
    await expect(page.getByTestId('setup-detail-page')).toBeVisible()
    await expect(page.getByTestId('setup-detail-descriptions')).toBeVisible({ timeout: 20000 })
    await page.waitForTimeout(500)
    await shot(page, '10-setup-detail-empty.png')

    // ── 11 Create Queue — empty form ────────────────────────────────────────
    await page.getByTestId('create-queue-button').click()
    await page.waitForURL(`**/setups/${DEMO_SETUP_ID}/queues/new`)
    await expect(page.getByTestId('create-queue-page')).toBeVisible()
    await shot(page, '11-create-queue-empty.png')

    // ── 12 Create Queue — filled + advanced settings expanded ───────────────
    await page.getByLabel(/Queue name/i).fill(DEMO_QUEUE_NAME)
    await page.getByText('Advanced settings').click()
    await page.waitForTimeout(300)
    await shot(page, '12-create-queue-advanced.png')

    // Submit — creates the demo queue.
    await page.getByTestId('create-queue-button').click()
    await page.waitForURL(`**/setups/${DEMO_SETUP_ID}`, { timeout: 30000 })
    await expect(page.getByTestId('setup-detail-queues')).toBeVisible({ timeout: 20000 })
    await expect(page.locator(`text=${DEMO_QUEUE_NAME}`)).toBeVisible({ timeout: 20000 })

    // ── 13 Setup detail — with queue ────────────────────────────────────────
    await page.waitForTimeout(500)
    await shot(page, '13-setup-detail-with-queue.png')

    // ── 14 Delete queue — confirmation popover ──────────────────────────────
    await page.getByTestId(`delete-queue-${DEMO_QUEUE_NAME}`).click()
    await expect(page.getByText(`Delete queue "${DEMO_QUEUE_NAME}"?`)).toBeVisible()
    await page.waitForTimeout(300)
    await shot(page, '14-delete-queue-confirm.png')
    // Dismiss the popover without deleting.
    await page.keyboard.press('Escape')

    // ── 15 Delete setup — confirmation popover ──────────────────────────────
    await page.getByTestId('delete-detail-button').click()
    await expect(page.getByText(`Delete setup "${DEMO_SETUP_ID}"?`)).toBeVisible()
    await page.waitForTimeout(300)
    await shot(page, '15-delete-setup-confirm.png')
    await page.keyboard.press('Escape')
  })
})
