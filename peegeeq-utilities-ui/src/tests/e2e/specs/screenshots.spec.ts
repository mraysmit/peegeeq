import { test, expect } from '../page-objects'
import * as fs from 'fs'
import * as path from 'path'

/**
 * Documentation screenshot capture (rewritten 2026-07-19 for the connect-only,
 * fully-built UI: generator Zones A–E, Template Manager, Value List Manager,
 * connect/detach lifecycle — the Create Setup / Create Queue pages are gone).
 *
 * Walks every page and primary action and writes a full-page PNG for each into
 * `docs/screenshots/`. Referenced from "Appendix A — UI Screenshots" in
 * `docs/PEEGEEQ_DEVOPS_UTILITIES_DESIGN.md`.
 *
 * NOT part of the normal e2e suite — runs only under
 * `playwright.screenshots.config.ts`:
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
    // Clean any leftover binding, then provision the demo setup + queue via the
    // admin REST path (the UI is connect-only; provisioning is admin-tool-only).
    await request.delete(`${API_BASE_URL}/api/v1/setups/${DEMO_SETUP_ID}`)
    const create = await request.post(`${API_BASE_URL}/api/v1/database-setup/create`, {
      data: {
        setupId: DEMO_SETUP_ID,
        databaseConfig: {
          host: dbConfig.host,
          port: dbConfig.port,
          databaseName: `peegeeq_screenshot_demo_${Date.now()}`,
          username: dbConfig.username,
          password: dbConfig.password,
          schema: 'public',
          templateDatabase: 'template0',
          encoding: 'UTF8',
        },
        queues: [{ queueName: DEMO_QUEUE_NAME, maxRetries: 3, visibilityTimeout: 30 }],
        eventStores: [],
      },
      timeout: 120000,
    })
    if (!create.ok()) {
      throw new Error(`Provision demo setup failed: ${create.status()} ${await create.text()}`)
    }
  })

  test.afterAll(async ({ request }) => {
    // Non-destructive detach; the throwaway database stays in the throwaway container.
    await request.delete(`${API_BASE_URL}/api/v1/setups/${DEMO_SETUP_ID}`)
  })

  async function shot(page: import('@playwright/test').Page, name: string) {
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.screenshot({ path: path.join(SHOTS_DIR, name), fullPage: true })
  }

  /** Select the demo setup in Zone A (other suites' setups may also be attached). */
  async function selectDemoTarget(page: import('@playwright/test').Page) {
    await expect(page.locator('#target-setup-select')).toBeVisible({ timeout: 15000 })
    await page.locator('.ant-select:has(#target-setup-select)').click()
    await page.locator('.ant-select-dropdown').getByTitle(DEMO_SETUP_ID).click()
    await expect(page.locator('#target-queue-select')).toBeVisible({ timeout: 15000 })
  }

  test('capture all pages and actions', async ({ page }) => {
    test.setTimeout(300000)

    // ── 01 Overview ────────────────────────────────────────────────────────
    await page.goto('/')
    await page.waitForLoadState('load')
    await expect(page.getByTestId('app-layout')).toBeVisible()
    await page.waitForTimeout(800)
    await shot(page, '01-overview.png')

    // ── 02 Tools ───────────────────────────────────────────────────────────
    await page.goto('/tools')
    await page.waitForLoadState('load')
    await page.waitForTimeout(500)
    await shot(page, '02-tools.png')

    // ── 03 Value List Manager — populated, edit panel open ─────────────────
    await page.goto('/generator/value-lists')
    await page.getByRole('button', { name: /New List/i }).click()
    await page.getByLabel(/List name/i).fill('first_names')
    await page.getByLabel(/Values \(one per line\)/i).fill('Mark\nDave\nJanet\nAna')
    await page.getByRole('button', { name: /^Save$/ }).click()
    await expect(page.getByTestId('list-count-first_names')).toContainText('4')
    await page.getByTestId('list-edit-first_names').click()
    await page.waitForTimeout(300)
    await shot(page, '03-value-lists.png')
    await page.getByRole('button', { name: /^Cancel$/ }).click()

    // ── 04 Message Generator — Zones A–E with a target selected ────────────
    await page.goto('/generator')
    await selectDemoTarget(page)
    await page.getByLabel(/^Name$/i).fill('Demo order template')
    await page.getByLabel(/Message type/i).fill('order.created')
    await page
      .getByLabel(/Payload/i)
      .fill('{\n  "orderId": "ORD-{{messageId}}",\n  "customer": "{{list:first_names}}",\n  "amount": {{random:500}},\n  "timestamp": "{{timestamp}}"\n}')
    await page.getByRole('button', { name: /^Save$/ }).click()
    await page.waitForTimeout(500)
    await shot(page, '04-message-generator.png')

    // ── 05 Generator — preview modal ───────────────────────────────────────
    await page.getByRole('button', { name: /^Preview$/ }).click()
    await expect(page.getByTestId('preview-modal')).toBeVisible()
    await page.waitForTimeout(300)
    await shot(page, '05-generator-preview.png')
    await page.locator('.ant-modal-footer').getByRole('button', { name: /^Close$/ }).click()

    // ── 06 Generator — completed run summary ───────────────────────────────
    await page.getByLabel(/Rate \(msg\/s\)/i).fill('5')
    await page.getByLabel(/Duration \(seconds\)/i).fill('2')
    await page.getByRole('button', { name: /^Start$/ }).click()
    await expect(page.getByTestId('summary-card')).toBeVisible({ timeout: 20000 })
    await page.waitForTimeout(300)
    await shot(page, '06-generator-run-summary.png')
    await page.getByRole('button', { name: /New run/ }).click()

    // ── 07 Template Manager — populated ────────────────────────────────────
    await page.goto('/generator/templates')
    await expect(page.getByRole('link', { name: 'Demo order template' })).toBeVisible()
    await page.waitForTimeout(300)
    await shot(page, '07-templates.png')

    // ── 08 Setups list — populated ─────────────────────────────────────────
    await page.goto('/setups')
    await expect(page.locator(`tr:has-text("${DEMO_SETUP_ID}")`)).toBeVisible({ timeout: 15000 })
    await page.waitForTimeout(500)
    await shot(page, '08-setups-list.png')

    // ── 09 Detach setup — confirmation popover (non-destructive copy) ──────
    await page.getByTestId(`detach-setup-${DEMO_SETUP_ID}`).click()
    await expect(page.getByText(`Detach setup "${DEMO_SETUP_ID}"?`)).toBeVisible()
    await page.waitForTimeout(300)
    await shot(page, '09-detach-setup-confirm.png')
    await page.keyboard.press('Escape')

    // ── 10 Connect setup — form with connection details expanded ───────────
    await page.getByTestId('connect-setup-button').click()
    await page.waitForURL('**/setups/connect')
    await expect(page.getByTestId('connect-setup-page')).toBeVisible()
    await page.getByLabel('Setup ID').fill('my-existing-setup')
    await page.getByLabel('Database name').fill('peegeeq_production')
    await page.getByLabel('Database password').fill('example-password')
    await page.getByText('Connection details').click()
    await page.waitForTimeout(300)
    await shot(page, '10-connect-setup.png')
    await page.getByTestId('back-button').click()

    // ── 11 Setup detail — with queue ───────────────────────────────────────
    await page.getByTestId(`view-details-${DEMO_SETUP_ID}`).click()
    await page.waitForURL(`**/setups/${DEMO_SETUP_ID}`)
    await expect(page.getByTestId('setup-detail-queues')).toBeVisible({ timeout: 20000 })
    await expect(page.locator(`text=${DEMO_QUEUE_NAME}`)).toBeVisible({ timeout: 20000 })
    await page.waitForTimeout(500)
    await shot(page, '11-setup-detail.png')

    // ── 12 Delete queue — confirmation popover ─────────────────────────────
    await page.getByTestId(`delete-queue-${DEMO_QUEUE_NAME}`).click()
    await expect(page.getByText(`Delete queue "${DEMO_QUEUE_NAME}"?`)).toBeVisible()
    await page.waitForTimeout(300)
    await shot(page, '12-delete-queue-confirm.png')
    await page.keyboard.press('Escape')

    // ── 13 Schedule-a-run modal (filled) ───────────────────────────────────
    await page.goto('/generator')
    await selectDemoTarget(page)
    // Short run so the later run-now (15) completes quickly — the schedule
    // freezes whatever Zone B holds when the modal captures it.
    await page.getByLabel(/Rate \(msg\/s\)/i).fill('5')
    await page.getByLabel(/Duration \(seconds\)/i).fill('2')
    await page.getByLabel(/Message type/i).fill('order.created')
    await page
      .getByLabel(/Payload/i)
      .fill('{\n  "orderId": "ORD-{{messageId}}",\n  "customer": "{{list:first_names}}"\n}')
    await page.getByRole('button', { name: /Schedule…/ }).click()
    await expect(page.getByTestId('schedule-run-modal')).toBeVisible()
    await page.getByLabel(/Schedule name/i).fill('Nightly soak run')
    const future = new Date(Date.now() + 3600_000)
    const pad = (n: number) => String(n).padStart(2, '0')
    await page
      .locator('#schedule-run-at')
      .fill(
        `${future.getFullYear()}-${pad(future.getMonth() + 1)}-${pad(future.getDate())}T${pad(future.getHours())}:${pad(future.getMinutes())}`
      )
    await page.waitForTimeout(300)
    await shot(page, '13-schedule-run-modal.png')
    await page.getByRole('button', { name: /Create schedule/i }).click()

    // ── 14 Scheduled Runs — Schedules tab with a schedule ──────────────────
    await page.goto('/generator/schedules')
    await expect(page.getByTestId('schedule-row-Nightly soak run')).toBeVisible()
    await page.waitForTimeout(300)
    await shot(page, '14-scheduled-runs.png')

    // ── 15 Scheduled Runs — run history after a real firing (run-now) ──────
    await page.locator('[data-testid^="schedule-run-now-"]').click()
    await page.getByRole('tab', { name: /Run history/ }).click()
    await expect(page.getByTestId('history-row-0')).toContainText('COMPLETED', { timeout: 30000 })
    await page.waitForTimeout(300)
    await shot(page, '15-schedule-run-history.png')

    // ── 16 Scheduled Runs — Templates tab ──────────────────────────────────
    await page.getByTestId('history-row-0').getByRole('button', { name: /Save as template/i }).click()
    await page.getByRole('button', { name: /Save template/i }).click()
    await page.getByRole('tab', { name: /Templates/ }).click()
    await expect(page.locator('[data-testid^="template-row-"]')).toBeVisible()
    await page.waitForTimeout(300)
    await shot(page, '16-schedule-templates.png')

    // Clean up all demo localStorage state so reruns start clean.
    await page.evaluate(() => {
      localStorage.removeItem('peegeeq_msg_templates')
      localStorage.removeItem('peegeeq_value_lists')
      localStorage.removeItem('peegeeq_generator_schedules')
      localStorage.removeItem('peegeeq_schedule_run_history')
      localStorage.removeItem('peegeeq_schedule_templates')
    })
  })
})
