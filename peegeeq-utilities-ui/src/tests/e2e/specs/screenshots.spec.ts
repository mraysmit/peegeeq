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

  /**
   * Pre-capture settle, centralised (2026-07-21, replacing 16 scattered magic
   * numbers): antd entrance motions and font/layout settling expose no
   * completion event to await, so capture waits one fixed motion-settle
   * interval AFTER the caller's own visibility assertion. Screenshot capture
   * only — this is not a synchronisation pattern for assertions.
   */
  const CAPTURE_SETTLE_MS = 500

  async function shot(page: import('@playwright/test').Page, name: string) {
    await page.waitForTimeout(CAPTURE_SETTLE_MS)
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
    await shot(page, '01-overview.png')

    // ── 02 Tools ───────────────────────────────────────────────────────────
    await page.goto('/tools')
    await page.waitForLoadState('load')
    await shot(page, '02-tools.png')

    // ── 03 Value List Manager — populated, edit panel open ─────────────────
    await page.goto('/generator/value-lists')
    await page.getByRole('button', { name: /New List/i }).click()
    await page.getByLabel(/List name/i).fill('first_names')
    await page.getByLabel(/Values \(one per line\)/i).fill('Mark\nDave\nJanet\nAna')
    await page.getByRole('button', { name: /^Save$/ }).click()
    await expect(page.getByTestId('list-count-first_names')).toContainText('4')
    await page.getByTestId('list-edit-first_names').click()
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
    // Save now shows a success toast; let its lifecycle finish (appear →
    // auto-dismiss) so the capture shows the settled page, not a transient
    // notice over the heading. Event-driven — no fixed wait.
    await expect(page.locator('.ant-message-notice')).toBeVisible()
    await expect(page.locator('.ant-message-notice')).toHaveCount(0, { timeout: 8000 })
    await shot(page, '04-message-generator.png')

    // ── 05 Generator — preview modal ───────────────────────────────────────
    await page.getByRole('button', { name: /^Preview$/ }).click()
    await expect(page.getByTestId('preview-modal')).toBeVisible()
    await shot(page, '05-generator-preview.png')
    await page.locator('.ant-modal-footer').getByRole('button', { name: /^Close$/ }).click()

    // ── 06 Generator — completed run summary ───────────────────────────────
    await page.getByLabel(/Rate \(msg\/s\)/i).fill('5')
    await page.getByLabel(/Duration \(seconds\)/i).fill('2')
    await page.getByRole('button', { name: /^Start$/ }).click()
    await expect(page.getByTestId('summary-card')).toBeVisible({ timeout: 20000 })
    await shot(page, '06-generator-run-summary.png')
    await page.getByRole('button', { name: /New run/ }).click()

    // ── 17 Generator — run IN PROGRESS (live counters + Stop) ──────────────
    await page.getByLabel(/Duration \(seconds\)/i).fill('60')
    await page.getByRole('button', { name: /^Start$/ }).click()
    await expect(page.getByTestId('run-progress')).toBeVisible({ timeout: 10000 })
    // At least one tick's sends acknowledged, so the counters show real numbers.
    await expect(page.getByTestId('sent-counter')).toContainText(/Sent: [1-9]/, { timeout: 10000 })
    await shot(page, '17-generator-running.png')
    await page.getByRole('button', { name: /^Stop$/ }).click()
    await expect(page.getByTestId('summary-card')).toBeVisible({ timeout: 10000 })
    await page.getByRole('button', { name: /New run/ }).click()

    // ── 20 Generator — duplicate header key warning ────────────────────────
    await page.getByRole('button', { name: /Add header/i }).click()
    await page.getByTestId('header-key-0').fill('trace')
    await page.getByTestId('header-value-0').fill('{{uuid}}')
    await page.getByRole('button', { name: /Add header/i }).click()
    await page.getByTestId('header-key-1').fill('trace')
    await page.getByTestId('header-value-1').fill('{{runId}}')
    await expect(page.getByTestId('duplicate-header-warning')).toBeVisible()
    await shot(page, '20-duplicate-header-warning.png')
    await page.getByTestId('header-remove-1').click()
    await page.getByTestId('header-remove-0').click()

    // ── 23 Generator — placeholder reference panel expanded ────────────────
    await page.getByText(/Placeholder reference/i).click()
    await expect(page.getByText('{{randomAlpha:N}}')).toBeVisible()
    await shot(page, '23-placeholder-reference.png')
    await page.getByText(/Placeholder reference/i).click() // collapse again

    // ── 21 Generator — rate advisory (non-blocking warning above threshold) ─
    await page.getByLabel(/Rate \(msg\/s\)/i).fill('600')
    await expect(page.getByTestId('rate-warning')).toBeVisible()
    await shot(page, '21-rate-advisory.png')
    await page.getByLabel(/Rate \(msg\/s\)/i).fill('5') // back under threshold — advisory clears

    // ── 25 Generator — preview with missing value list warning (§6) ────────
    await page.getByLabel(/Payload/i).fill('{"vip": "{{list:vip_names}}"}')
    await page.getByRole('button', { name: /^Preview$/ }).click()
    await expect(page.getByTestId('missing-lists-warning')).toBeVisible()
    await shot(page, '25-preview-missing-list.png')
    await page.locator('.ant-modal-footer').getByRole('button', { name: /^Close$/ }).click()

    // ── 22 Generator — payload validation error (resolve-then-parse, §8) ───
    await page.getByLabel(/Payload/i).fill('{"broken": }')
    await page.getByLabel(/Payload/i).blur()
    await expect(page.getByTestId('payload-error')).toBeVisible()
    await shot(page, '22-payload-validation-error.png')
    // Left invalid deliberately — the schedule flow (13) refills the payload.

    // ── 07 Template Manager — populated ────────────────────────────────────
    await page.goto('/generator/templates')
    await expect(page.getByRole('link', { name: 'Demo order template' })).toBeVisible()
    await shot(page, '07-templates.png')

    // ── 08 Setups list — populated ─────────────────────────────────────────
    await page.goto('/setups')
    await expect(page.locator(`tr:has-text("${DEMO_SETUP_ID}")`)).toBeVisible({ timeout: 15000 })
    await shot(page, '08-setups-list.png')

    // ── 09 Detach setup — confirmation popover (non-destructive copy) ──────
    await page.getByTestId(`detach-setup-${DEMO_SETUP_ID}`).click()
    await expect(page.getByText(`Detach setup "${DEMO_SETUP_ID}"?`)).toBeVisible()
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
    await shot(page, '10-connect-setup.png')
    await page.getByTestId('back-button').click()

    // ── 11 Setup detail — with queue ───────────────────────────────────────
    await page.getByTestId(`view-details-${DEMO_SETUP_ID}`).click()
    await page.waitForURL(`**/setups/${DEMO_SETUP_ID}`)
    await expect(page.getByTestId('setup-detail-queues')).toBeVisible({ timeout: 20000 })
    await expect(page.locator(`text=${DEMO_QUEUE_NAME}`)).toBeVisible({ timeout: 20000 })
    await shot(page, '11-setup-detail.png')

    // (12-delete-queue-confirm was retired with the queue-delete feature,
    // removed 2026-07-21 — the queues list is read-only in this UI.)

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
    await shot(page, '13-schedule-run-modal.png')

    // ── 24 Schedule modal — INTERVAL mode (repeat every N minutes) ─────────
    await page.getByRole('radio', { name: /Repeat every N minutes/i }).click()
    await page
      .locator('#schedule-first-run-at')
      .fill(
        `${future.getFullYear()}-${pad(future.getMonth() + 1)}-${pad(future.getDate())}T${pad(future.getHours())}:${pad(future.getMinutes())}`
      )
    await page.getByLabel(/Every \(minutes\)/i).fill('15')
    await shot(page, '24-schedule-interval-mode.png')
    // Back to the once schedule that the rest of the flow expects.
    await page.getByRole('radio', { name: /Run once/i }).click()
    await page
      .locator('#schedule-run-at')
      .fill(
        `${future.getFullYear()}-${pad(future.getMonth() + 1)}-${pad(future.getDate())}T${pad(future.getHours())}:${pad(future.getMinutes())}`
      )
    await page.getByRole('button', { name: /Create schedule/i }).click()

    // ── 14 Scheduled Runs — Schedules tab with a schedule ──────────────────
    await page.goto('/generator/schedules')
    await expect(page.getByTestId('schedule-row-Nightly soak run')).toBeVisible()
    await shot(page, '14-scheduled-runs.png')

    // ── 18 Scheduled Runs — edit-timing modal (R4 in-place timing edit) ────
    await page.locator('[data-testid^="schedule-edit-timing-"]').click()
    await expect(page.locator('#edit-timing-run-at')).toBeVisible()
    await shot(page, '18-schedule-edit-timing.png')
    await page.keyboard.press('Escape')

    // ── 15 Scheduled Runs — run history after a real firing (run-now) ──────
    await page.locator('[data-testid^="schedule-run-now-"]').click()
    await page.getByRole('tab', { name: /Run history/ }).click()
    await expect(page.getByTestId('history-row-0')).toContainText('COMPLETED', { timeout: 30000 })
    await shot(page, '15-schedule-run-history.png')

    // ── 26 Run history — result filter applied ─────────────────────────────
    await page.getByTestId('history-result-filter').locator('.ant-select').click()
    await page.locator('.ant-select-dropdown').getByTitle('Completed').click()
    await expect(page.getByTestId('history-row-0')).toContainText('COMPLETED')
    await shot(page, '26-history-filter.png')
    await page.getByTestId('history-result-filter').locator('.ant-select').click()
    await page.locator('.ant-select-dropdown').getByTitle('All results').click()

    // ── 19 Scheduled Runs — save-as-template naming modal ──────────────────
    await page.getByTestId('history-row-0').getByRole('button', { name: /Save as template/i }).click()
    await expect(page.locator('#template-draft-name')).toBeVisible()
    await shot(page, '19-schedule-save-as-template.png')

    // ── 16 Scheduled Runs — Templates tab ──────────────────────────────────
    await page.getByRole('button', { name: /Save template/i }).click()
    await page.getByRole('tab', { name: /Templates/ }).click()
    await expect(page.locator('[data-testid^="template-row-"]')).toBeVisible()
    await shot(page, '16-schedule-templates.png')

    // ── 27 Value list delete — referencing-templates warning ───────────────
    // "Demo order template" (saved at 04) references {{list:first_names}}, so
    // the delete confirm names it. Runs LAST: cancelled, nothing is deleted.
    await page.goto('/generator/value-lists')
    await page.getByTestId('list-delete-first_names').click()
    await expect(page.getByTestId('delete-references-warning')).toBeVisible()
    await shot(page, '27-value-list-delete-references.png')
    await page.locator('.ant-modal-confirm').getByRole('button', { name: /^Cancel$/ }).click()

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
