import { test, expect } from '../page-objects'
import * as fs from 'fs'

/**
 * Generator publish-FAILURE e2e — real backend, real fault, no HTTP stubbing.
 *
 * Closes the gap found 2026-07-23: every generator e2e exercised the backend
 * on its success path only; the UI's response to a real backend failure
 * (error counter, auto-stop, ERROR state, error history record) was verified
 * exclusively against a mocked publishBatch.
 *
 * Fault production follows the connect-setup.spec.ts precedent: make the REAL
 * backend fail by giving it a genuinely bad situation — here, the target setup
 * is DETACHED via the admin REST path while a run is publishing to it. Every
 * subsequent batch genuinely fails at the server; nothing is intercepted.
 *
 * No assertion on a specific HTTP status: what a real publish-to-detached-
 * setup returns is exactly the reality this spec observes — the UI contract
 * under test is "errors surface, the guard auto-stops, the run settles as
 * ERROR and is recorded", regardless of status code.
 */

const API_BASE_URL = 'http://127.0.0.1:8088'
const SCHEMA = 'public'
const QUEUE_NAME = 'genfailq'

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

test.describe('Generator publish failure', () => {
  const stamp = Date.now()
  const SETUP_ID = `e2e-genfail-${stamp}`
  const DB_NAME = `e2e_genfail_db_${stamp}`

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
    // The test detaches the setup itself; this is cleanup for the failure
    // paths where it did not get that far. A 404 here is fine.
    await request.delete(`${API_BASE_URL}/api/v1/setups/${SETUP_ID}`)
  })

  test.beforeEach(async ({ page }) => {
    page.on('pageerror', error => console.error('Page error:', error.message))
    await page.goto('/generator')
    // Zone A auto-selects the first setup; other specs' setups may be attached
    // concurrently — select OUR setup explicitly.
    await expect(page.locator('#target-setup-select')).toBeVisible({ timeout: 15000 })
    await page.locator('.ant-select:has(#target-setup-select)').click()
    await page.locator('.ant-select-dropdown').getByTitle(SETUP_ID).click()
    await expect(page.locator('#target-queue-select')).toBeVisible({ timeout: 15000 })
  })

  test('detaching the target mid-run: errors surface, the guard auto-stops, the run settles as ERROR', async ({ page, request }) => {
    test.setTimeout(90000)

    // A long run so the fault lands mid-flight, with the auto-stop guard at 5:
    // after the detach, five consecutive failed batches (~5 ticks ≈ 5 s) must
    // terminate the run — wide enough to observe the error counter climbing,
    // short enough to keep the test fast.
    await page.getByLabel(/Rate \(msg\/s\)/i).fill('5')
    await page.getByLabel(/Duration \(seconds\)/i).fill('60')
    await page.getByLabel(/Auto-stop after N consecutive errors/i).fill('5')
    await page.getByLabel(/Message type/i).fill('e2e.genfail')
    await page.getByLabel(/Payload/i).fill('{"id":"{{messageId}}"}')

    // Healthy first: real acknowledged sends before the fault, so the failure
    // observed later is unambiguously caused by the detach.
    await page.getByRole('button', { name: /^Start$/ }).click()
    await expect(page.getByTestId('run-status')).toContainText('RUNNING')
    await expect.poll(async () => {
      const text = await page.getByTestId('sent-counter').textContent()
      const match = text?.match(/Sent: (\d+)/)
      return match ? Number(match[1]) : 0
    }, { timeout: 10000 }).toBeGreaterThan(0)

    // The REAL fault: detach the setup the run is publishing to.
    const detach = await request.delete(`${API_BASE_URL}/api/v1/setups/${SETUP_ID}`)
    if (!detach.ok()) {
      throw new Error(`Mid-run detach failed: ${detach.status()} ${await detach.text()}`)
    }

    // Errors surface in the UI while still running (error counter + list are
    // pre-terminal displays).
    await expect.poll(async () => {
      const text = await page.getByTestId('error-counter').textContent()
      const match = text?.match(/Errors: (\d+)/)
      return match ? Number(match[1]) : 0
    }, { timeout: 20000 }).toBeGreaterThan(0)

    // The consecutive-error guard terminates the run: summary card replaces
    // the progress bar with final status ERROR — never stuck RUNNING.
    const summary = page.getByTestId('summary-card')
    await expect(summary).toBeVisible({ timeout: 20000 })
    await expect(summary).toContainText('ERROR')
    // The auto-stop reason is carried on the status tag (ProgressPanel §7.2).
    await expect(page.getByTestId('run-status')).toContainText('ERROR')

    // Total errors in the summary is a real count from real failed batches.
    const summaryText = await summary.textContent()
    const totalErrors = summaryText?.match(/Total errors\s*(\d+)/)
    expect(totalErrors, 'summary must show a non-zero Total errors').not.toBeNull()

    // The failed manual run joins the run history with an error outcome and
    // the PARTIAL sent count from before the fault.
    const manualRecords = await page.evaluate(() => {
      const raw = localStorage.getItem('peegeeq_schedule_run_history')
      const records = raw
        ? (JSON.parse(raw) as Array<{ scheduleId: string; outcome: { result: string; totalSent: number } }>)
        : []
      return records.filter((r) => r.scheduleId === 'manual')
    })
    expect(manualRecords.length).toBeGreaterThanOrEqual(1)
    expect(manualRecords[0].outcome.result).toBe('error')
    expect(manualRecords[0].outcome.totalSent).toBeGreaterThan(0)

    // Exit the terminal state cleanly.
    await page.getByRole('button', { name: /New run/ }).click()
    await expect(page.getByTestId('run-status')).toContainText('IDLE')
  })
})
