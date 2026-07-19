import { test, expect } from '../page-objects'
import * as fs from 'fs'

/**
 * Generator run E2E — real backend, no mocks (B.5/B.6 obligation).
 *
 * Provisions its own setup + queue via the admin REST path, then drives the
 * assembled generator page end-to-end: Zone B (rate/duration/total + threshold
 * advisory), Zone C (template editing), Zone D (Preview, Start, Stop), Zone E
 * (RUNNING counters, terminal summary card, New run). This is the first place
 * the publication engine publishes real messages — the summary's sent count is
 * the server-acknowledged total.
 */

const API_BASE_URL = 'http://127.0.0.1:8088'
const SCHEMA = 'public'
const QUEUE_NAME = 'genrunq'

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

test.describe('Generator run', () => {
  const stamp = Date.now()
  const SETUP_ID = `e2e-genrun-${stamp}`
  const DB_NAME = `e2e_genrun_db_${stamp}`

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
    await page.goto('/generator')
    // Other specs may have their own setups attached concurrently and Zone A
    // auto-selects the first one — select OUR setup explicitly, then the queue
    // select appearing means the target is resolved and Start can enable.
    await expect(page.locator('#target-setup-select')).toBeVisible({ timeout: 15000 })
    // antd Select: the inner input is covered by the selection span — click the wrapper.
    await page.locator('.ant-select:has(#target-setup-select)').click()
    await page.locator('.ant-select-dropdown').getByTitle(SETUP_ID).click()
    await expect(page.locator('#target-queue-select')).toBeVisible({ timeout: 15000 })
  })

  test('Zone B: total updates live and the threshold advisory appears and dismisses', async ({ page }) => {
    const rate = page.getByLabel(/Rate \(msg\/s\)/i)
    const duration = page.getByLabel(/Duration \(seconds\)/i)

    await rate.fill('20')
    await duration.fill('30')
    await expect(page.getByTestId('total-messages')).toContainText('600')

    // Cross the default threshold (500) → advisory appears; dismiss it; drop back under → gone.
    await rate.fill('600')
    await expect(page.getByTestId('rate-warning')).toBeVisible()
    await page.getByTestId('rate-warning').getByRole('button', { name: /close/i }).click()
    await expect(page.getByTestId('rate-warning')).not.toBeVisible()
    await rate.fill('20')
  })

  test('full run: template → preview → start → counters → summary → new run', async ({ page }) => {
    test.setTimeout(60000)

    // Zone B: a short, deterministic run — 5 msg/s for 3 s = 15 messages.
    await page.getByLabel(/Rate \(msg\/s\)/i).fill('5')
    await page.getByLabel(/Duration \(seconds\)/i).fill('3')
    await expect(page.getByTestId('total-messages')).toContainText('15')

    // Zone C: real template content.
    await page.getByLabel(/Message type/i).fill('e2e.genrun')
    await page.getByLabel(/Payload/i).fill('{"id":"{{messageId}}","run":"{{runId}}"}')

    // Zone D: preview resolves message #1 locally — no publish yet.
    await page.getByRole('button', { name: /^Preview$/ }).click()
    await expect(page.getByTestId('preview-modal')).toBeVisible()
    await expect(page.getByTestId('preview-modal')).toContainText('00000001')
    await expect(page.getByTestId('preview-modal')).toContainText('e2e.genrun')
    // Two close controls exist (modal X + footer button) — use the footer one.
    await page.locator('.ant-modal-footer').getByRole('button', { name: /^Close$/ }).click()

    // Start → RUNNING, counters climb with real acknowledged sends.
    await page.getByRole('button', { name: /^Start$/ }).click()
    await expect(page.getByTestId('run-status')).toContainText('RUNNING')
    await expect.poll(async () => {
      const text = await page.getByTestId('sent-counter').textContent()
      const match = text?.match(/Sent: (\d+)/)
      return match ? Number(match[1]) : 0
    }, { timeout: 10000 }).toBeGreaterThan(0)

    // Completion at duration → summary card replaces the bar.
    const summary = page.getByTestId('summary-card')
    await expect(summary).toBeVisible({ timeout: 15000 })
    await expect(summary).toContainText('COMPLETED')
    await expect(summary).toContainText('15') // target total; sent equals it on a healthy run

    // New run → back to IDLE, card gone.
    await page.getByRole('button', { name: /New run/ }).click()
    await expect(page.getByTestId('summary-card')).not.toBeVisible()
    await expect(page.getByTestId('run-status')).toContainText('IDLE')

    // Manual runs join the run history (the second later phase): the completed
    // run above must have a "Manual run" record with its acknowledged counts.
    const manualRecords = await page.evaluate(() => {
      const raw = localStorage.getItem('peegeeq_schedule_run_history')
      const records = raw
        ? (JSON.parse(raw) as Array<{ scheduleId: string; scheduleName: string; outcome: { result: string; totalSent: number } }>)
        : []
      return records.filter((r) => r.scheduleId === 'manual')
    })
    expect(manualRecords.length).toBeGreaterThanOrEqual(1)
    expect(manualRecords[0].outcome.result).toBe('completed')
    expect(manualRecords[0].outcome.totalSent).toBe(15)
  })

  test('stop: a long run stops on demand and reports STOPPED', async ({ page }) => {
    test.setTimeout(60000)

    await page.getByLabel(/Rate \(msg\/s\)/i).fill('5')
    await page.getByLabel(/Duration \(seconds\)/i).fill('60')
    await page.getByLabel(/Message type/i).fill('e2e.genrun')
    await page.getByLabel(/Payload/i).fill('{"id":"{{messageId}}"}')

    await page.getByRole('button', { name: /^Start$/ }).click()
    await expect(page.getByTestId('run-status')).toContainText('RUNNING')
    await expect.poll(async () => {
      const text = await page.getByTestId('sent-counter').textContent()
      const match = text?.match(/Sent: (\d+)/)
      return match ? Number(match[1]) : 0
    }, { timeout: 10000 }).toBeGreaterThan(0)

    await page.getByRole('button', { name: /^Stop$/ }).click()
    const summary = page.getByTestId('summary-card')
    await expect(summary).toBeVisible({ timeout: 10000 })
    await expect(summary).toContainText('STOPPED')

    // Leave the page clean for any later spec.
    await page.getByRole('button', { name: /New run/ }).click()
  })
})
