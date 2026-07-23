import { test, expect } from '../page-objects'
import * as fs from 'fs'
import { CHECK_INTERVAL_MS } from '../../../engine/schedulerConstants'

/**
 * Negative assertions ("nothing fires") have no event to poll for — they must
 * let real check cycles elapse. The wait is derived from the REAL cycle
 * constant plus a settle margin, never a hard-coded number that silently
 * breaks when the cycle length changes.
 */
const TWO_CHECK_CYCLES_MS = 2 * CHECK_INTERVAL_MS + 5_000

/**
 * Scheduled generator runs E2E — real backend, real publishing (SCH.7).
 *
 * Covers the full user journey: schedule a run a few seconds ahead from the
 * Message Generator screen, watch it fire while on the Scheduled Runs screen,
 * verify the history row carries real server-acknowledged counts, exercise the
 * result filter, save the entry as a template, re-schedule from the template,
 * run-now from the template, and delete with the history surviving.
 *
 * A second test proves the no-auto-start rule (design D3): a schedule that
 * comes due while the app is closed is recorded as MISSED on the next open,
 * and nothing fires.
 */

const API_BASE_URL = 'http://127.0.0.1:8088'
const SCHEMA = 'public'
const QUEUE_NAME = 'schedq'

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

/** datetime-local string (browser local time) `offsetMs` from now. */
function localDatetime(offsetMs: number): string {
  const d = new Date(Date.now() + offsetMs)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

test.describe.configure({ mode: 'serial' })

test.describe('Scheduled generator runs', () => {
  const stamp = Date.now()
  const SETUP_ID = `e2e-sched-${stamp}`
  const DB_NAME = `e2e_sched_db_${stamp}`

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

  test.beforeEach(async ({ page }) => {
    page.on('pageerror', error => console.error('Page error:', error.message))
    // Schedules persist in localStorage; each test starts clean.
    await page.goto('/generator')
    await page.evaluate(() => {
      localStorage.removeItem('peegeeq_generator_schedules')
      localStorage.removeItem('peegeeq_schedule_run_history')
      localStorage.removeItem('peegeeq_schedule_templates')
    })
    await page.reload()
    await expect(page.locator('#target-setup-select')).toBeVisible({ timeout: 15000 })
    await page.locator('.ant-select:has(#target-setup-select)').click()
    await page.locator('.ant-select-dropdown').getByTitle(SETUP_ID).click()
    await expect(page.locator('#target-queue-select')).toBeVisible({ timeout: 15000 })
  })

  test('full journey: schedule → fires with real counts → filter → template → re-schedule → delete', async ({ page }) => {
    test.setTimeout(180000)

    // Configure a short run and open the schedule modal.
    await page.getByLabel(/Rate \(msg\/s\)/i).fill('5')
    await page.getByLabel(/Duration \(seconds\)/i).fill('2')
    await page.getByLabel(/Message type/i).fill('e2e.sched')
    await page.getByLabel(/Payload/i).fill('{"id":"{{messageId}}"}')
    await page.getByRole('button', { name: /Schedule…/ }).click()
    await expect(page.getByTestId('schedule-run-modal')).toBeVisible()
    await expect(page.getByTestId('schedule-execution-note')).toContainText(/only while this app is open/i)

    await page.getByLabel(/Schedule name/i).fill('E2E scheduled run')
    // Due shortly: the 15 s scheduler check fires it within ~30 s.
    await page.locator('#schedule-run-at').fill(localDatetime(10_000))
    await page.getByRole('button', { name: /Create schedule/i }).click()

    // The schedule appears on the Scheduled Runs screen.
    await page.goto('/generator/schedules')
    await expect(page.getByTestId('schedule-row-E2E scheduled run')).toBeVisible()
    await expect(page.getByTestId('schedules-page-banner')).toContainText(/only while this app is open/i)

    // It fires while we watch: the history row appears with REAL acknowledged
    // counts (5 msg/s × 2 s = 10, all confirmed by the backend).
    await page.getByRole('tab', { name: /Run history/ }).click()
    const firstRow = page.getByTestId('history-row-0')
    await expect(firstRow).toContainText('COMPLETED', { timeout: 60000 })
    await expect(firstRow).toContainText('E2E scheduled run')
    await expect(firstRow).toContainText('10 / 0')

    // The one-shot is consumed.
    await page.getByRole('tab', { name: /^Schedules$/ }).click()
    await expect(page.getByTestId('schedule-row-E2E scheduled run')).toContainText('—')

    // The result filter narrows.
    await page.getByRole('tab', { name: /Run history/ }).click()
    await page.getByTestId('history-result-filter').locator('.ant-select-selector').click()
    await page.locator('.ant-select-dropdown').getByTitle('Missed').click()
    await expect(page.getByTestId('history-row-0')).not.toBeVisible()
    await page.getByTestId('history-result-filter').locator('.ant-select-selector').click()
    await page.locator('.ant-select-dropdown').getByTitle('Completed').click()
    await expect(page.getByTestId('history-row-0')).toBeVisible()

    // Save the fired entry as a template.
    await page.getByTestId('history-row-0').getByRole('button', { name: /Save as template/i }).click()
    await page.getByRole('button', { name: /Save template/i }).click()
    await page.getByRole('tab', { name: /Templates/ }).click()
    const templateRow = page.locator('[data-testid^="template-row-"]')
    await expect(templateRow).toContainText('E2E scheduled run template')

    // Re-schedule from the template: the modal opens prefilled with the frozen config.
    await templateRow.getByRole('button', { name: /Schedule…/ }).click()
    await expect(page.getByTestId('schedule-capture-summary')).toContainText(`${SETUP_ID} / ${QUEUE_NAME}`)
    await page.getByRole('button', { name: /^Cancel$/ }).click()

    // Run-now from the template publishes again, recorded under the template name.
    await templateRow.getByRole('button', { name: /Run now/i }).click()
    await page.getByRole('tab', { name: /Run history/ }).click()
    // Reset the filter to see the new entry.
    await page.getByTestId('history-result-filter').locator('.ant-select-selector').click()
    await page.locator('.ant-select-dropdown').getByTitle('All results').click()
    await expect(page.getByTestId('history-row-0')).toContainText('E2E scheduled run template', { timeout: 30000 })
    await expect(page.getByTestId('history-row-0')).toContainText('COMPLETED', { timeout: 30000 })

    // Delete the schedule and the template; the history rows survive.
    await page.getByRole('tab', { name: /^Schedules$/ }).click()
    await page.locator('[data-testid^="schedule-delete-"]').click()
    await page.locator('.ant-modal-confirm').getByRole('button', { name: /^Delete$/ }).click()
    await expect(page.getByTestId('schedule-row-E2E scheduled run')).not.toBeVisible()

    await page.getByRole('tab', { name: /Templates/ }).click()
    await page.locator('[data-testid^="template-delete-"]').click()
    await page.locator('.ant-modal-confirm').getByRole('button', { name: /^Delete$/ }).click()

    await page.getByRole('tab', { name: /Run history/ }).click()
    await expect(page.getByTestId('history-row-0')).toBeVisible()
    await expect(page.getByTestId('history-row-1')).toBeVisible()
  })

  test('two open tabs fire a due schedule exactly ONCE (the Web Locks executor, §7.5)', async ({ page, context }) => {
    test.setTimeout(180000)

    // Tab A (the fixture page) creates a schedule due shortly.
    await page.getByLabel(/Rate \(msg\/s\)/i).fill('5')
    await page.getByLabel(/Duration \(seconds\)/i).fill('2')
    await page.getByLabel(/Message type/i).fill('e2e.twotab')
    await page.getByLabel(/Payload/i).fill('{"id":"{{messageId}}"}')
    await page.getByRole('button', { name: /Schedule…/ }).click()
    await page.getByLabel(/Schedule name/i).fill('Two tab race')
    await page.locator('#schedule-run-at').fill(localDatetime(10_000))
    await page.getByRole('button', { name: /Create schedule/i }).click()

    // Tab B: a second real tab in the same browser context — same
    // localStorage, its own scheduler competing for the Web Locks lock.
    const pageB = await context.newPage()
    await pageB.goto('/generator/schedules')
    await expect(pageB.getByTestId('scheduled-runs-page')).toBeVisible()

    // Wait until the firing is recorded, then verify EXACTLY one record.
    await expect
      .poll(
        async () =>
          page.evaluate(() => {
            const raw = localStorage.getItem('peegeeq_schedule_run_history')
            const records = raw ? (JSON.parse(raw) as Array<{ scheduleName: string; outcome: { result: string } }>) : []
            return records.filter((r) => r.scheduleName === 'Two tab race' && r.outcome.result === 'completed').length
          }),
        { timeout: 60000 }
      )
      .toBe(1)

    // Two more check cycles pass in both tabs: the count must stay 1 — no
    // double-firing, no replay of the consumed slot.
    await page.waitForTimeout(TWO_CHECK_CYCLES_MS)
    const finalCount = await page.evaluate(() => {
      const raw = localStorage.getItem('peegeeq_schedule_run_history')
      const records = raw ? (JSON.parse(raw) as Array<{ scheduleName: string }>) : []
      return records.filter((r) => r.scheduleName === 'Two tab race').length
    })
    expect(finalCount).toBe(1)
    await pageB.close()
  })

  test('an interval schedule fires repeatedly and stays enabled', async ({ page }) => {
    test.setTimeout(240000)

    await page.getByLabel(/Rate \(msg\/s\)/i).fill('5')
    await page.getByLabel(/Duration \(seconds\)/i).fill('2')
    await page.getByLabel(/Message type/i).fill('e2e.interval')
    await page.getByLabel(/Payload/i).fill('{"id":"{{messageId}}"}')
    await page.getByRole('button', { name: /Schedule…/ }).click()
    await page.getByLabel(/Schedule name/i).fill('Every minute')
    await page.getByLabel(/Repeat every/i).click()
    await page.locator('#schedule-first-run-at').fill(localDatetime(10_000))
    // everyMinutes stays at the input's minimum-adjacent default; set it to 1.
    await page.getByLabel(/Every \(minutes\)/i).fill('1')
    await page.getByRole('button', { name: /Create schedule/i }).click()

    await page.goto('/generator/schedules')
    await page.getByRole('tab', { name: /Run history/ }).click()

    const completedCount = () =>
      page.evaluate(() => {
        const raw = localStorage.getItem('peegeeq_schedule_run_history')
        const records = raw ? (JSON.parse(raw) as Array<{ scheduleName: string; outcome: { result: string } }>) : []
        return records.filter((r) => r.scheduleName === 'Every minute' && r.outcome.result === 'completed').length
      })

    // First firing.
    await expect.poll(completedCount, { timeout: 60000 }).toBe(1)
    // The schedule survives its firing: still enabled, next slot in the future.
    await page.getByRole('tab', { name: /^Schedules$/ }).click()
    const row = page.getByTestId('schedule-row-Every minute')
    await expect(row).not.toContainText('—')
    await expect(row.locator('.ant-switch')).toHaveClass(/ant-switch-checked/)

    // Second firing at the next one-minute slot.
    await expect.poll(completedCount, { timeout: 100000 }).toBe(2)

    // Clean up: delete the interval schedule so it stops firing.
    await page.locator('[data-testid^="schedule-delete-"]').click()
    await page.locator('.ant-modal-confirm').getByRole('button', { name: /^Delete$/ }).click()
    await expect(row).not.toBeVisible()
  })

  test('Import consumes the export format: a future schedule arrives live, a past one-shot arrives consumed', async ({ page }) => {
    test.setTimeout(120000)

    // Build a schedules.json in the export format: one future, one past.
    const template = {
      id: 'imp-tpl', name: 'Imported template', messageType: 'e2e.import',
      payloadSchema: '{"id":"{{messageId}}"}', headers: {}, priority: 5, delaySeconds: 0,
      createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
    }
    const config = {
      setupId: SETUP_ID, queueName: QUEUE_NAME, rate: 5, durationSecs: 2,
      maxBatchSize: 10, warnThreshold: 0, maxConsecErrors: 0, template, previewIndex: 1,
    }
    const future = new Date(Date.now() + 3600_000).toISOString()
    const past = new Date(Date.now() - 3600_000).toISOString()
    const now = new Date().toISOString()
    const schedules = [
      {
        id: 'imp-future', name: 'Imported future', config,
        schedule: { kind: 'once', runAt: future }, enabled: true, nextRunAt: future,
        createdAt: now, updatedAt: now,
      },
      {
        id: 'imp-past', name: 'Imported past', config,
        schedule: { kind: 'once', runAt: past }, enabled: true, nextRunAt: past,
        createdAt: now, updatedAt: now,
      },
    ]

    await page.goto('/generator/schedules')
    // The import input lives inside the in-app file dialog (2026-07-23).
    await page.getByRole('button', { name: /^Import$/ }).click()
    await page.getByTestId('schedule-import-input').setInputFiles({
      name: 'schedules.json',
      mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify(schedules)),
    })

    // The future schedule arrives live with its slot (the next-run column shows
    // a relative time); the past one-shot arrives consumed — no next run — and
    // NEVER fires a backlog. (Both rows show a dash in the OUTCOME column,
    // since neither has fired.)
    await expect(page.getByTestId('schedule-row-Imported future')).toBeVisible()
    await expect(page.getByTestId('schedule-row-Imported future')).toContainText('(in ')
    await expect(page.getByTestId('schedule-row-Imported past')).toBeVisible()
    await expect(page.getByTestId('schedule-row-Imported past')).not.toContainText('(in ')

    // Two check cycles pass: no firing of the imported backlog, no history rows.
    await page.waitForTimeout(TWO_CHECK_CYCLES_MS)
    const historyCount = await page.evaluate(() => {
      const raw = localStorage.getItem('peegeeq_schedule_run_history')
      return raw ? (JSON.parse(raw) as unknown[]).length : 0
    })
    expect(historyCount).toBe(0)
  })

  test('a schedule due while the app is closed is recorded as MISSED and never auto-fires (D3)', async ({ page }) => {
    test.setTimeout(120000)

    await page.getByLabel(/Message type/i).fill('e2e.missed')
    await page.getByLabel(/Payload/i).fill('{"id":"{{messageId}}"}')
    await page.getByRole('button', { name: /Schedule…/ }).click()
    await page.getByLabel(/Schedule name/i).fill('Missed while closed')
    const dueAtMs = Date.now() + 4_000
    await page.locator('#schedule-run-at').fill(localDatetime(4_000))
    await page.getByRole('button', { name: /Create schedule/i }).click()

    // Close the app (navigate away from the origin) across the due time. The
    // wait is on the actual condition — the schedule being overdue — not a
    // guessed fixed delay. One extra second clears the datetime-local's
    // whole-second granularity.
    await page.goto('about:blank')
    await expect.poll(() => Date.now(), { timeout: 15_000 }).toBeGreaterThan(dueAtMs + 1_000)

    // Reopen: the schedule is overdue. It must be marked MISSED — never fired.
    await page.goto('/generator/schedules')
    await page.getByRole('tab', { name: /Run history/ }).click()
    const row = page.getByTestId('history-row-0')
    await expect(row).toContainText('MISSED', { timeout: 15000 })
    await expect(row).toContainText('Missed while closed')
    await expect(row).toContainText('0 / 0') // nothing was published
    await expect(row).toContainText(/app was not open/i)

    // The consumed one-shot is disabled.
    await page.getByRole('tab', { name: /^Schedules$/ }).click()
    await expect(page.getByTestId('schedule-row-Missed while closed')).toContainText('—')
  })
})
