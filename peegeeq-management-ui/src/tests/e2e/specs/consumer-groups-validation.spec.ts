import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Consumer Groups Page – Create Modal Form Validation
 *
 * Covers gap:
 *   3. Form validations in the create consumer group modal are untested.
 *
 * Tests client-side required-field validation (Form.Item rules) and a
 * successful creation flow.  Duplicate-name enforcement is backend-only and
 * the backend does not guarantee a rejection, so that path is not tested here.
 *
 * Depends on setup-prerequisite (SETUP_ID must exist) and a queue being available.
 */
test.describe.configure({ mode: 'serial' })

test.describe('Consumer Groups – Create Modal Form Validation', () => {

    let queueName = ''

    // ── 0. Setup ──────────────────────────────────────────────────────────────

    test('00 setup: create a queue for the consumer group tests', async ({ page }) => {
        test.setTimeout(60000)

        queueName = `cg_val_queue_${Date.now()}`
        const qResp = await page.request.post(
            '/api/v1/management/queues',
            { data: { setupId: SETUP_ID, name: queueName, type: 'native' } }
        )
        if (!qResp.ok()) {
            throw new Error(`Create queue failed: ${qResp.status()} ${await qResp.text()}`)
        }

        await page.waitForTimeout(1000)
        console.log(`Created queue "${queueName}"`)
    })

    // ── 1. Required-field validation ──────────────────────────────────────────

    test('01 submitting empty form shows required-field validation errors', async ({ page }) => {
        await page.goto('/consumer-groups')
        await page.waitForLoadState('load')

        await page.getByTestId('create-group-btn').click()
        const modal = page.locator('.ant-modal')
        await expect(modal).toBeVisible()

        // Click OK without filling any fields
        await modal.locator('.ant-btn-primary').click()

        // Ant Design Form renders .ant-form-item-explain-error for each failed rule
        const errors = modal.locator('.ant-form-item-explain-error')
        await expect(errors.first()).toBeVisible({ timeout: 5000 })
        const errorCount = await errors.count()
        expect(errorCount, 'At least one required-field error must appear').toBeGreaterThanOrEqual(1)

        // Modal must remain open — validation failure must not close it
        await expect(modal).toBeVisible()

        // Dismiss
        await page.keyboard.press('Escape')
        await expect(modal).not.toBeVisible({ timeout: 3000 })
    })

    test('02 group name field shows error when left empty', async ({ page }) => {
        await page.goto('/consumer-groups')
        await page.waitForLoadState('load')

        await page.getByTestId('create-group-btn').click()
        const modal = page.locator('.ant-modal')
        await expect(modal).toBeVisible()

        // Fill setup and queue but leave group name empty
        await selectAntOption(modal.getByTestId('create-group-setup-select'), SETUP_ID)
        await page.waitForTimeout(300)
        await modal.getByTestId('create-group-queue-input').fill(queueName)

        await modal.locator('.ant-btn-primary').click()

        // At least one required-field error must appear (group name is required)
        await expect(modal.locator('.ant-form-item-explain-error').first()).toBeVisible({ timeout: 5000 })

        await page.keyboard.press('Escape')
    })

    // ── 3. Valid submission succeeds ──────────────────────────────────────────

    test('03 valid submission closes the modal and shows success toast', async ({ page }) => {
        await page.goto('/consumer-groups')
        await page.waitForLoadState('load')

        await page.getByTestId('create-group-btn').click()
        const modal = page.locator('.ant-modal')
        await expect(modal).toBeVisible()

        const groupName = `cg_valid_${Date.now()}`
        await modal.getByLabel('Group Name').fill(groupName)

        // Setup must be selected before queue name to avoid onValuesChange reset
        await selectAntOption(modal.getByTestId('create-group-setup-select'), SETUP_ID)
        await page.waitForTimeout(300)
        await modal.getByTestId('create-group-queue-input').fill(queueName)

        await modal.locator('.ant-btn-primary').click()

        // Success: modal closes
        await expect(modal).not.toBeVisible({ timeout: 10000 })

        console.log(`Created consumer group "${groupName}"`)
    })
})
