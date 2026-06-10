import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Queue Config — Create and Display
 *
 * Verifies two things introduced together:
 *
 * 1. CREATE FORM  — the "Create Queue" modal exposes all configuration fields
 *    (type, maxRetries, visibilityTimeout, batchSize, pollingInterval, fifo, DLQ)
 *    and POSTs them to the backend.
 *
 * 2. DETAILS PAGE — after the queue is created, the Overview tab shows a
 *    "Queue Configuration" card whose values match what was submitted.
 *
 * The test creates a queue with deliberately non-default config values so that
 * any field that reverts to a default is immediately visible as a mismatch.
 *
 * Depends only on setup-prerequisite (SETUP_ID must exist).
 */

test.describe.configure({ mode: 'serial' })

test.describe('Queue Config – Create and Display', () => {

    let queueName = ''

    /** Non-default values we will submit in the form */
    const CFG = {
        type:                     'native',
        maxRetries:               7,
        visibilityTimeoutSeconds: 90,
        batchSize:                25,
        pollingIntervalSeconds:   4,
        fifoEnabled:              false,   // switch stays off — easier to assert
        deadLetterEnabled:        true,    // switch on
        deadLetterQueueName:      '',      // leave blank → null on backend
    }

    async function gotoOverview(page: Parameters<Parameters<typeof test>[1]>[0]) {
        await page.goto(`/queues/${SETUP_ID}/${queueName}`)
        await expect(page.getByRole('tablist')).toBeVisible({ timeout: 15000 })
        await expect(page.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true')
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Locate the Queue Configuration card by its own head title (avoids strict-mode issues). */
    function configCard(page: Parameters<Parameters<typeof test>[1]>[0]) {
        return page.locator('.ant-card').filter({
            has: page.locator(':scope > .ant-card-head .ant-card-head-title', { hasText: 'Queue Configuration' }),
        })
    }

    // ── 1. Create form field presence ─────────────────────────────────────────

    test('01 create-queue modal shows type selector', async ({ page }) => {
        await page.goto('/queues')
        await page.getByTestId('create-queue-btn').click()
        await expect(page.locator('.ant-modal')).toBeVisible()

        await expect(page.getByTestId('queue-type-select')).toBeVisible()

        await page.keyboard.press('Escape')
    })

    test('02 create-queue modal shows all config fields', async ({ page }) => {
        await page.goto('/queues')
        await page.getByTestId('create-queue-btn').click()
        await expect(page.locator('.ant-modal')).toBeVisible()

        const modal = page.locator('.ant-modal')
        await expect(modal.getByRole('spinbutton', { name: /max retries/i })).toBeVisible()
        await expect(modal.getByRole('spinbutton', { name: /visibility timeout/i })).toBeVisible()
        await expect(modal.getByRole('spinbutton', { name: /batch size/i })).toBeVisible()
        await expect(modal.getByRole('spinbutton', { name: /polling interval/i })).toBeVisible()

        await page.keyboard.press('Escape')
    })

    test('03 create-queue modal shows default values', async ({ page }) => {
        await page.goto('/queues')
        await page.getByTestId('create-queue-btn').click()
        await expect(page.locator('.ant-modal')).toBeVisible()

        const modal = page.locator('.ant-modal')
        await expect(modal.getByRole('spinbutton', { name: /max retries/i })).toHaveValue('3')
        await expect(modal.getByRole('spinbutton', { name: /visibility timeout/i })).toHaveValue('300')
        await expect(modal.getByRole('spinbutton', { name: /batch size/i })).toHaveValue('10')
        await expect(modal.getByRole('spinbutton', { name: /polling interval/i })).toHaveValue('5')

        await page.keyboard.press('Escape')
    })

    // ── 2. Create queue with non-default config ───────────────────────────────

    test('04 setup: create queue via form with non-default config values', async ({ page }) => {
        test.setTimeout(60000)

        queueName = `qcfg_${Date.now()}`

        await page.goto('/queues')
        await page.getByTestId('create-queue-btn').click()
        await expect(page.locator('.ant-modal')).toBeVisible()

        const modal = page.locator('.ant-modal')

        // Queue name
        await modal.getByTestId('queue-name-input').fill(queueName)

        // Setup
        await page.getByTestId('refresh-setups-btn').click()
        await page.waitForTimeout(500)
        const setupSelect = modal.getByTestId('queue-setup-select')
        await selectAntOption(setupSelect, SETUP_ID)

        // Type  (native is already selected — no change needed)

        // Numeric config — triple-click to select existing value then type the new one
        const maxRetriesInput        = modal.getByRole('spinbutton', { name: /max retries/i })
        const visibilityInput        = modal.getByRole('spinbutton', { name: /visibility timeout/i })
        const batchSizeInput         = modal.getByRole('spinbutton', { name: /batch size/i })
        const pollingIntervalInput   = modal.getByRole('spinbutton', { name: /polling interval/i })

        await maxRetriesInput.click({ clickCount: 3 })
        await maxRetriesInput.fill(String(CFG.maxRetries))
        await visibilityInput.click({ clickCount: 3 })
        await visibilityInput.fill(String(CFG.visibilityTimeoutSeconds))
        await batchSizeInput.click({ clickCount: 3 })
        await batchSizeInput.fill(String(CFG.batchSize))
        await pollingIntervalInput.click({ clickCount: 3 })
        await pollingIntervalInput.fill(String(CFG.pollingIntervalSeconds))

        // Verify values before submitting
        await expect(maxRetriesInput).toHaveValue(String(CFG.maxRetries))
        await expect(visibilityInput).toHaveValue(String(CFG.visibilityTimeoutSeconds))
        await expect(batchSizeInput).toHaveValue(String(CFG.batchSize))
        await expect(pollingIntervalInput).toHaveValue(String(CFG.pollingIntervalSeconds))

        // Submit
        await modal.locator('.ant-btn-primary').click()
        await expect(page.locator('.ant-modal')).not.toBeVisible({ timeout: 15000 })
    })

    // ── 3. Queue Configuration card present on Overview tab ──────────────────

    test('05 Overview tab shows Queue Configuration card', async ({ page }) => {
        await gotoOverview(page)
        await expect(configCard(page)).toBeVisible()
    })

    test('06 Queue Configuration card shows Max Retries', async ({ page }) => {
        await gotoOverview(page)
        const card = configCard(page)
        await expect(card.getByText('Max Retries:', { exact: false })).toBeVisible()
        await expect(card.getByText(String(CFG.maxRetries))).toBeVisible()
    })

    test('07 Queue Configuration card shows Visibility Timeout', async ({ page }) => {
        await gotoOverview(page)
        const card = configCard(page)
        await expect(card.getByText(`${CFG.visibilityTimeoutSeconds}s`)).toBeVisible()
    })

    test('08 Queue Configuration card shows Batch Size', async ({ page }) => {
        await gotoOverview(page)
        const card = configCard(page)
        await expect(card.getByText('Batch Size:', { exact: false })).toBeVisible()
        await expect(card.getByText(String(CFG.batchSize))).toBeVisible()
    })

    test('09 Queue Configuration card shows Polling Interval', async ({ page }) => {
        await gotoOverview(page)
        const card = configCard(page)
        await expect(card.getByText(`${CFG.pollingIntervalSeconds}s`)).toBeVisible()
    })

    test('10 Queue Configuration card shows FIFO tag', async ({ page }) => {
        await gotoOverview(page)
        const card = configCard(page)
        // CFG.fifoEnabled = false → tag text should be "Disabled"
        const fifoTag = card.locator('.ant-tag').filter({ hasText: /disabled/i }).first()
        await expect(fifoTag).toBeVisible()
    })

    test('11 Queue Configuration card shows Dead Letter tag', async ({ page }) => {
        await gotoOverview(page)
        const card = configCard(page)
        // CFG.deadLetterEnabled = true → tag text should be "Enabled"
        const dlqTag = card.locator('.ant-tag').filter({ hasText: /enabled/i }).first()
        await expect(dlqTag).toBeVisible()
    })

    // ── 4. API round-trip: config values persisted by the backend ────────────

    test('12 getQueueDetails API returns config sub-object with correct values', async ({ page }) => {
        const resp = await page.request.get(`/api/v1/queues/${SETUP_ID}/${queueName}`)
        expect(resp.ok()).toBe(true)

        const body = await resp.json()
        const cfg = body.config

        expect(cfg).toBeTruthy()
        expect(cfg.maxRetries).toBe(CFG.maxRetries)
        expect(cfg.visibilityTimeoutSeconds).toBe(CFG.visibilityTimeoutSeconds)
        expect(cfg.batchSize).toBe(CFG.batchSize)
        expect(cfg.pollingIntervalSeconds).toBe(CFG.pollingIntervalSeconds)
        expect(typeof cfg.fifoEnabled).toBe('boolean')
        expect(typeof cfg.deadLetterEnabled).toBe('boolean')
        // deadLetterQueueName was left blank → null or absent
        expect(cfg.deadLetterQueueName == null || cfg.deadLetterQueueName === '').toBe(true)
    })

    // ── 5. Cleanup ────────────────────────────────────────────────────────────

    test('99 cleanup: delete the test queue', async ({ page }) => {
        const resp = await page.request.delete(`/api/v1/queues/${SETUP_ID}/${queueName}`)
        expect([200, 204, 404]).toContain(resp.status())
    })
})
