import { test, expect, type Locator, type Page, type TestInfo } from '@playwright/test'
import fs from 'node:fs'

const API_BASE_URL = 'http://127.0.0.1:8088'
const SCHEMA = 'public'
const NATIVE_QUEUE = 'remediation_native'
const OUTBOX_QUEUE = 'remediation_outbox'

interface DbConnectionInfo {
  host: string
  port: number
  username: string
  password: string
}

function readDbConfig(): DbConnectionInfo {
  const raw = JSON.parse(fs.readFileSync('testcontainers-db.json', 'utf8')) as DbConnectionInfo
  return { host: raw.host, port: raw.port, username: raw.username, password: raw.password }
}

async function captureEvidence(target: Locator, testInfo: TestInfo, name: string): Promise<void> {
  await expect(target).toBeVisible()
  const screenshotPath = testInfo.outputPath(`${name}.png`)
  await target.screenshot({ path: screenshotPath, animations: 'disabled' })
  await testInfo.attach(`${name}.png`, { path: screenshotPath, contentType: 'image/png' })
}

test.describe.configure({ mode: 'serial' })

test.describe('Generator output and degraded states', () => {
  const stamp = Date.now()
  const SETUP_ID = `e2e-output-remediation-${stamp}`
  const DB_NAME = `e2e_output_remediation_${stamp}`

  test.beforeAll(async ({ request }) => {
    const db = readDbConfig()
    const response = await request.post(`${API_BASE_URL}/api/v1/database-setup/create`, {
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
        queues: [
          { queueName: NATIVE_QUEUE, implementationType: 'native', maxRetries: 3, visibilityTimeout: 30 },
          { queueName: OUTBOX_QUEUE, implementationType: 'outbox', maxRetries: 3, visibilityTimeout: 30 },
        ],
        eventStores: [],
      },
      timeout: 120_000,
    })
    if (!response.ok()) {
      throw new Error(`Provision failed: ${response.status()} ${await response.text()}`)
    }
  })

  test.afterAll(async ({ request }) => {
    const response = await request.delete(`${API_BASE_URL}/api/v1/setups/${SETUP_ID}`)
    if (!response.ok() && response.status() !== 404) {
      throw new Error(`Cleanup failed: ${response.status()} ${await response.text()}`)
    }
  })

  async function selectTarget(page: Page, queueName: string): Promise<void> {
    const queueType = queueName === NATIVE_QUEUE ? 'native' : 'outbox'
    const queueLabel = `${queueName} (${queueType})`
    await page.goto('/generator')
    await expect(page.locator('#target-setup-select')).toBeVisible({ timeout: 15_000 })
    await page.locator('.ant-select:has(#target-setup-select)').click()
    await page.keyboard.type(SETUP_ID)
    await page.keyboard.press('Enter')
    await expect(page.locator('.ant-select:has(#target-setup-select) .ant-select-selection-item')).toHaveText(SETUP_ID)

    await expect(page.locator('#target-queue-select')).toBeVisible({ timeout: 15_000 })
    const currentQueue = page.locator('.ant-select:has(#target-queue-select) .ant-select-selection-item')
    if ((await currentQueue.textContent()) !== queueLabel) {
      await page.locator('.ant-select:has(#target-queue-select)').click()
      await page.locator('.ant-select-dropdown').getByTitle(queueLabel).click()
    }
    await expect(currentQueue).toHaveText(queueLabel)
  }

  async function enterCompare(page: Page): Promise<void> {
    await page.getByTestId('generator-mode').getByText('Compare', { exact: true }).click()
    await expect(page.getByTestId('compare-targets')).toBeVisible({ timeout: 20_000 })
    for (const side of ['native', 'outbox'] as const) {
      const row = page.getByTestId(`compare-row-${side}`)
      const current = row.locator(`.ant-select:has(#compare-${side}-setup) .ant-select-selection-item`)
      if ((await current.textContent()) !== SETUP_ID) {
        await row.locator(`.ant-select:has(#compare-${side}-setup)`).click()
        await page.keyboard.type(SETUP_ID)
        await page.keyboard.press('Enter')
      }
      await expect(current).toHaveText(SETUP_ID)
    }
  }

  test('completed run downloads the exact immutable summary', async ({ page }, testInfo) => {
    test.setTimeout(90_000)
    await selectTarget(page, NATIVE_QUEUE)
    await page.getByLabel(/Rate \(msg\/s\)/i).fill('5')
    await page.getByLabel(/Duration \(seconds\)/i).fill('2')
    await page.getByLabel(/Message type/i).fill('e2e.download-summary')
    await page.getByLabel(/Payload/i).fill('{"id":"{{messageId}}"}')
    await page.getByRole('button', { name: /^Start$/ }).click()

    const summaryCard = page.getByTestId('summary-card')
    await expect(summaryCard).toContainText('COMPLETED', { timeout: 30_000 })
    const downloadPromise = page.waitForEvent('download')
    await summaryCard.getByRole('button', { name: 'Download results' }).click()
    const download = await downloadPromise
    expect(download.suggestedFilename()).toMatch(/^run-.+\.json$/)
    expect(await download.failure()).toBeNull()
    const downloadPath = await download.path()
    expect(downloadPath).toBeTruthy()
    const exported = JSON.parse(fs.readFileSync(downloadPath!, 'utf8')) as Record<string, unknown>
    expect(exported).toMatchObject({
      totalSent: 10,
      totalAttempted: 10,
      targetTotal: 10,
      totalErrors: 0,
      finalStatus: 'completed',
    })
    expect(typeof exported.runId).toBe('string')
    await captureEvidence(summaryCard, testInfo, 'generator-downloaded-summary')
  })

  test('missing value list is named in preview and preserved in the frozen schedule warning', async ({ page }, testInfo) => {
    await selectTarget(page, NATIVE_QUEUE)
    await page.getByLabel(/Payload/i).fill('{"customer":"{{list:missing_customers}}"}')
    await page.getByRole('button', { name: /^Preview$/ }).click()
    await expect(page.getByTestId('missing-lists-warning')).toContainText('missing_customers')
    await expect(page.getByTestId('preview-modal').locator('pre')).toContainText('"customer": ""')
    await page.locator('.ant-modal-footer').getByRole('button', { name: 'Close' }).click()

    await page.getByRole('button', { name: /Schedule…/ }).click()
    const scheduleWarning = page.getByTestId('schedule-missing-lists')
    await expect(scheduleWarning).toContainText('missing_customers')
    await expect(scheduleWarning).toContainText('will resolve to "" at run time')
    await captureEvidence(scheduleWarning, testInfo, 'schedule-missing-value-list')
  })

  test('trace report copies every ID and surfaces clipboard rejection', async ({ page, context }, testInfo) => {
    test.setTimeout(90_000)
    await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: 'http://localhost:3001' })
    await selectTarget(page, NATIVE_QUEUE)
    await page.getByTestId('generator-mode').getByText('Trace seed', { exact: true }).click()
    await page.getByLabel(/New id every \(messages\)/i).fill('5')
    await page.getByLabel(/Children per parent/i).fill('1')
    await page.getByLabel(/Rate \(msg\/s\)/i).fill('5')
    await page.getByLabel(/Duration \(seconds\)/i).fill('2')
    await page.getByLabel(/Message type/i).fill('e2e.trace-copy')
    await page.getByRole('button', { name: /^Start$/ }).click()
    await expect(page.getByTestId('trace-panel')).toBeVisible({ timeout: 30_000 })

    await page.getByTestId('trace-copy').click()
    await expect(page.locator('.ant-message-success')).toContainText('Copied 2 correlation ids')
    const copied = await page.evaluate(() => navigator.clipboard.readText())
    const displayedIds = await page
      .locator('[data-testid^="trace-row-"] td:first-child')
      .allTextContents()
    expect(displayedIds).toHaveLength(2)
    expect(copied.trim().split(/\r?\n/)).toEqual(displayedIds)

    await page.evaluate(() => {
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: { writeText: () => Promise.reject(new Error('clipboard denied by policy')) },
      })
    })
    await page.getByTestId('trace-copy').click()
    const error = page.locator('.ant-message-error').filter({ hasText: 'clipboard denied by policy' })
    await expect(error).toBeVisible()
    await captureEvidence(error, testInfo, 'trace-clipboard-rejection')
  })

  test('comparison completes while a failed queue-telemetry read remains visible and named', async ({ page }, testInfo) => {
    test.setTimeout(120_000)
    await page.route(`**/api/v1/queues/${SETUP_ID}/${NATIVE_QUEUE}/stats`, (route) =>
      route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"telemetry offline"}' })
    )
    await page.goto('/generator')
    await enterCompare(page)
    await page.getByLabel('Rate (msg/s)').fill('5')
    await page.getByLabel('Duration (seconds)').fill('2')
    await page.getByRole('button', { name: /^Start$/ }).click()

    const errors = page.getByTestId('compare-telemetry-errors')
    await expect(errors).toBeVisible({ timeout: 90_000 })
    await expect(errors).toContainText(NATIVE_QUEUE)
    await expect(errors).toContainText('503')
    await expect(page.getByTestId('compare-results-panel')).toBeVisible()
    await captureEvidence(errors, testInfo, 'compare-telemetry-read-failure')
  })
})
