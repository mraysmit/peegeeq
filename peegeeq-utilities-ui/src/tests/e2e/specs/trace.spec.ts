import { test, expect } from '../page-objects'
import * as fs from 'fs'

/**
 * Correlation / trace seed mode E2E — real backend, no mocks (G.6).
 *
 * Provisions its own setup + queue, then drives trace-seed mode end to end:
 * mode switch → correlation controls + rate controls in Zone B → a REAL run
 * whose messages carry minted per-message correlation ids → the derived
 * emitted-ids report with a real download. Also pins the refusals only a
 * selected target can prove (the unit tests cannot): Schedule is blocked and
 * a trace-seed run cannot be saved as a scenario.
 *
 * The run is deliberately tiny (5 msg/s × 2 s = 10 messages) so the whole
 * flow is a few seconds.
 */

const API_BASE_URL = 'http://127.0.0.1:8088'
const SCHEMA = 'public'
const QUEUE_NAME = 'traceq'

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

test.describe('Trace-seed mode', () => {
  const stamp = Date.now()
  const SETUP_ID = `e2e-trace-${stamp}`
  const DB_NAME = `e2e_trace_db_${stamp}`

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

  async function openTraceMode(page: import('@playwright/test').Page): Promise<void> {
    await page.goto('/generator')
    await expect(page.locator('#target-setup-select')).toBeVisible({ timeout: 15000 })
    await page.locator('.ant-select:has(#target-setup-select)').click()
    await page.locator('.ant-select-dropdown').getByTitle(SETUP_ID).click()
    await expect(page.locator('#target-queue-select')).toBeVisible({ timeout: 15000 })
    await page.getByTestId('generator-mode').getByText('Trace seed', { exact: true }).click()
    await expect(page.getByTestId('trace-controls')).toBeVisible()
  }

  test('shows the correlation controls with the scheme summary, and refuses what a trace run cannot do', async ({
    page,
  }) => {
    test.setTimeout(120000)
    page.on('pageerror', (error) => console.error('Page error:', error.message))

    await openTraceMode(page)

    // Zone B carries the correlation strategy AND the rate controls (§19.6).
    await expect(page.getByTestId('rate-controls')).toBeVisible()
    // The scheme summary derives from the defaults: every 100 messages,
    // chains of 1 + 3.
    await expect(page.getByTestId('trace-scheme-summary')).toContainText('100')
    await expect(page.getByTestId('trace-empty')).toBeVisible()

    // Both refusals, asserted WITH a target selected (the unit tests cannot):
    // schedules and scenarios have no trace kind.
    await expect(page.getByRole('button', { name: /Schedule/ })).toBeDisabled()
    await expect(page.getByTestId('scenario-save-as')).toBeDisabled()
  })

  test('runs for real and derives the emitted-ids report', async ({ page }) => {
    test.setTimeout(120000)
    page.on('pageerror', (error) => console.error('Page error:', error.message))

    await openTraceMode(page)

    // A new id every 5 messages with 1 child per parent: 10 messages →
    // 2 ids → 1 chain of root + child.
    await page.getByLabel(/New id every \(messages\)/i).fill('5')
    await page.getByLabel(/Children per parent/i).fill('1')

    await page.getByLabel(/Rate \(msg\/s\)/i).fill('5')
    await page.getByLabel(/Duration \(seconds\)/i).fill('2')
    await page.getByLabel(/Message type/i).fill('e2e.trace')
    await page.getByLabel(/Payload/i).fill('{"id":"{{messageId}}","corr":"{{correlationId}}"}')

    await page.getByRole('button', { name: /^Start$/ }).click()

    // The emitted-ids report replaces the empty state once the run settles.
    await expect(page.getByTestId('trace-panel')).toBeVisible({ timeout: 60000 })
    await expect(page.getByTestId('trace-totals')).toContainText('10 messages')
    await expect(page.getByTestId('trace-totals')).toContainText('2 correlation ids')
    await expect(page.getByTestId('trace-totals')).toContainText('1 causation chain')

    // Two entries: a root (no chain root) and a child naming the root's id.
    await expect(page.getByTestId(/^trace-row-/)).toHaveCount(2)
    await expect(page.getByTestId('trace-row-1')).toContainText('root')
    await expect(page.getByTestId('trace-row-2')).toContainText('child')

    // A clean run carries no delivery caveat; the downstream pointer is there.
    await expect(page.getByTestId('trace-errors-caveat')).toHaveCount(0)
    await expect(page.getByTestId('trace-verify-note')).toContainText(/CausationTree/i)

    // The download is a REAL browser download carrying the report name.
    const downloadPromise = page.waitForEvent('download')
    await page.getByTestId('trace-download').click()
    const download = await downloadPromise
    expect(download.suggestedFilename()).toMatch(/^trace-ids-.+\.json$/)
  })
})
