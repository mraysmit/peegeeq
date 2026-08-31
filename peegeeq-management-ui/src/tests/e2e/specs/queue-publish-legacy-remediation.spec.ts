import { type Locator, type Page, type TestInfo } from '@playwright/test'
import * as fs from 'fs'
import { test, expect } from '../page-objects'

const API_BASE_URL = 'http://127.0.0.1:8088'

interface DbConnectionInfo {
  host: string
  port: number
  username: string
  password: string
}

async function captureEvidence(target: Locator, testInfo: TestInfo, name: string): Promise<void> {
  await expect(target).toBeVisible()
  const screenshotPath = testInfo.outputPath(`${name}.png`)
  await target.screenshot({ path: screenshotPath, animations: 'disabled' })
  await testInfo.attach(`${name}.png`, { path: screenshotPath, contentType: 'image/png' })
}

test.describe.configure({ mode: 'serial' })

test.describe('Queue publish contract and legacy routes remediation', () => {
  const stamp = Date.now()
  const setupId = `legacypublish${stamp}`
  const queueName = 'advanced-queue'
  const combinedLegacyQueueName = 'legacyqueue'
  const databaseName = `e2e_management_publish_${stamp}`

  test.beforeAll(async ({ request }) => {
    const db = JSON.parse(fs.readFileSync('testcontainers-db.json', 'utf8')) as DbConnectionInfo
    const response = await request.post(`${API_BASE_URL}/api/v1/database-setup/create`, {
      data: {
        setupId,
        databaseConfig: {
          host: db.host,
          port: db.port,
          databaseName,
          username: db.username,
          password: db.password,
          schema: 'public',
          templateDatabase: 'template0',
          encoding: 'UTF8',
        },
        queues: [
          { queueName, implementationType: 'native', maxRetries: 3, visibilityTimeout: 30 },
          { queueName: combinedLegacyQueueName, implementationType: 'native', maxRetries: 3, visibilityTimeout: 30 },
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
    const response = await request.delete(`${API_BASE_URL}/api/v1/setups/${setupId}`)
    if (!response.ok() && response.status() !== 404) {
      throw new Error(`Cleanup failed: ${response.status()} ${await response.text()}`)
    }
  })

  async function openPublishModal(page: Page): Promise<Locator> {
    await page.goto(`/queues/${setupId}/${queueName}`)
    await expect(page.getByRole('tablist')).toBeVisible({ timeout: 15_000 })
    await page.getByRole('tab', { name: /messages/i }).click()
    await page.getByRole('button', { name: 'Publish Message' }).click()
    const modal = page.locator('.ant-modal').filter({ hasText: 'Publish Message' })
    await expect(modal).toBeVisible()
    return modal
  }

  test('advanced publish serializes payload, headers, priority, and delay and reaches the real queue', async ({ page }, testInfo) => {
    const modal = await openPublishModal(page)
    const payload = { operation: 'advanced-publish', nested: { enabled: true }, sequence: 42 }
    const headers = { correlationId: `corr-${stamp}`, tenant: 'north', source: 'management-ui' }

    await modal.getByLabel('Message Payload (JSON)').fill(JSON.stringify(payload))
    await modal.getByLabel('Priority (0-10)').fill('9')
    await modal.getByLabel('Delay (seconds)').fill('1')
    await modal.getByLabel('Custom Headers (JSON)').fill(JSON.stringify(headers))

    const requestPromise = page.waitForRequest((request) =>
      request.method() === 'POST'
      && request.url().endsWith(`/api/v1/queues/${setupId}/${queueName}/messages`)
    )
    const responsePromise = page.waitForResponse((response) =>
      response.request().method() === 'POST'
      && response.url().endsWith(`/api/v1/queues/${setupId}/${queueName}/messages`)
    )
    await modal.getByRole('button', { name: 'Publish', exact: true }).click()

    const publishRequest = await requestPromise
    expect(publishRequest.postDataJSON()).toEqual({ payload, headers, priority: 9, delaySeconds: 1 })
    expect((await responsePromise).ok()).toBe(true)
    await expect(page.locator('.ant-message-success')).toContainText('Message published successfully')

    await expect.poll(async () => {
      const response = await page.request.get(
        `${API_BASE_URL}/api/v1/queues/${setupId}/${queueName}/messages?count=50`
      )
      if (!response.ok()) return false
      type BrowsedMessage = { payload?: unknown; headers?: Record<string, string> }
      const body = await response.json() as { messages?: BrowsedMessage[] } | BrowsedMessage[]
      const messages = Array.isArray(body) ? body : (body.messages ?? [])
      return messages.some((entry) =>
        String(entry.payload).includes('advanced-publish')
        && entry.headers?.correlationId === headers.correlationId
      )
    }, { timeout: 15_000, intervals: [250, 500, 1000] }).toBe(true)

    await captureEvidence(page.getByTestId('queue-details-tabs'), testInfo, 'advanced-publish-completed')
  })

  test('malformed payload JSON is surfaced and never sent', async ({ page }, testInfo) => {
    const modal = await openPublishModal(page)
    let publishRequests = 0
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().endsWith(`/queues/${setupId}/${queueName}/messages`)) {
        publishRequests += 1
      }
    })

    await modal.getByLabel('Message Payload (JSON)').fill('{"broken":')
    await modal.getByRole('button', { name: 'Publish', exact: true }).click()

    const error = page.locator('.ant-message-error').filter({ hasText: 'Invalid JSON format for payload' })
    await expect(error).toBeVisible()
    await expect(modal).toBeVisible()
    expect(publishRequests).toBe(0)
    await captureEvidence(error, testInfo, 'invalid-payload-rejected')
  })

  test('malformed header JSON is surfaced and never sent', async ({ page }) => {
    const modal = await openPublishModal(page)
    let publishRequests = 0
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().endsWith(`/queues/${setupId}/${queueName}/messages`)) {
        publishRequests += 1
      }
    })

    await modal.getByLabel('Message Payload (JSON)').fill('{"valid":true}')
    await modal.getByLabel('Custom Headers (JSON)').fill('{"broken":')
    await modal.getByRole('button', { name: 'Publish', exact: true }).click()

    await expect(page.locator('.ant-message-error')).toContainText('Invalid JSON format for headers')
    await expect(modal).toBeVisible()
    expect(publishRequests).toBe(0)
  })

  test('headers require a JSON object whose values are strings and invalid shapes are never sent', async ({ page }) => {
    const modal = await openPublishModal(page)
    let publishRequests = 0
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().endsWith(`/queues/${setupId}/${queueName}/messages`)) {
        publishRequests += 1
      }
    })

    await modal.getByLabel('Message Payload (JSON)').fill('{"valid":true}')
    for (const invalidHeaders of ['[]', 'null', '{"retry":3}']) {
      await modal.getByLabel('Custom Headers (JSON)').fill(invalidHeaders)
      await modal.getByRole('button', { name: 'Publish', exact: true }).click()
      await expect(page.locator('.ant-message-error').last()).toContainText(
        'Headers must be a JSON object with string values'
      )
    }
    expect(publishRequests).toBe(0)
    await expect(modal).toBeVisible()
  })

  test('required payload validation keeps the publish operation local', async ({ page }) => {
    const modal = await openPublishModal(page)
    let publishRequests = 0
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().endsWith(`/queues/${setupId}/${queueName}/messages`)) {
        publishRequests += 1
      }
    })

    await modal.getByRole('button', { name: 'Publish', exact: true }).click()
    await expect(modal.getByText('Please enter message payload')).toBeVisible()
    await expect(page.locator('.ant-message-error')).toContainText('Please correct the highlighted publish fields')
    expect(publishRequests).toBe(0)
  })

  test('backend publish failure is surfaced with its cause and keeps the completed form open', async ({ page }) => {
    const modal = await openPublishModal(page)
    await page.route(`**/api/v1/queues/${setupId}/${queueName}/messages`, (route) =>
      route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"publisher offline"}' })
    )
    await modal.getByLabel('Message Payload (JSON)').fill('{"valid":true}')
    await modal.getByRole('button', { name: 'Publish', exact: true }).click()

    await expect(page.locator('.ant-message-error')).toContainText('Failed to publish message:')
    await expect(page.locator('.ant-message-error')).toContainText('503')
    await expect(modal).toBeVisible()
    await expect(modal.getByLabel('Message Payload (JSON)')).toHaveValue('{"valid":true}')
  })

  test('legacy queue list renders real data and its queue link forwards to the maintained details route', async ({ page }, testInfo) => {
    await page.goto('/queues-old')
    const table = page.locator('.ant-table')
    await expect(table).toBeVisible({ timeout: 15_000 })
    const row = table.locator('tr').filter({ hasText: queueName })
    await expect(row).toBeVisible()
    await expect(row.getByRole('link', { name: queueName })).toHaveAttribute(
      'href',
      `/queues/${setupId}/${queueName}`
    )
    await captureEvidence(row, testInfo, 'legacy-queue-list-row')
    await row.getByRole('link', { name: queueName }).click()
    await expect(page).toHaveURL(new RegExp(`/queues/${setupId}/${queueName}$`))
    await expect(page.getByRole('tablist')).toBeVisible()
  })

  test('legacy queue detail route resolves its old combined identifier and returns through Back to Queues', async ({ page }, testInfo) => {
    await page.goto(`/queues-old/${setupId}-${combinedLegacyQueueName}`)
    await expect(page.getByRole('heading', { name: `Queue: ${combinedLegacyQueueName}` })).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('.ant-tag').getByText(setupId, { exact: true })).toBeVisible()
    await expect(page.getByRole('tab', { name: /actions/i })).toBeVisible()
    await captureEvidence(page.locator('.ant-card').first(), testInfo, 'legacy-queue-details')

    await page.getByRole('button', { name: 'Back to Queues' }).click()
    await expect(page).toHaveURL(/\/queues$/)
  })

  test('explicit legacy detail URL preserves a hyphenated queue name on the maintained details page', async ({ page }, testInfo) => {
    await page.goto(`/queues-old/${setupId}/${queueName}`)
    await expect(page.getByRole('heading', { name: queueName, exact: true })).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('.ant-tag').getByText(setupId, { exact: true })).toBeVisible()
    await expect(page.getByRole('tab', { name: /messages/i })).toBeVisible()
    await captureEvidence(page.locator('[data-testid="queue-details-tabs"]'), testInfo, 'legacy-explicit-hyphenated-queue')
  })
})
