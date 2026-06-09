import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Consumer Groups Page – Duplicate Name Validation
 *
 * Covers gap:
 *   3. Form validation in the create consumer group modal for duplicate names
 *      is untested.
 *
 * Strategy: create a consumer group via the API, then attempt to create a second
 * group with the same name via the UI.  The backend returns a 4xx error and the
 * component shows an error toast. The modal must remain open.
 *
 * Depends on setup-prerequisite (SETUP_ID must exist) and a queue being available.
 */
test.describe.configure({ mode: 'serial' })

test.describe('Consumer Groups – Duplicate Name Validation', () => {

    let queueName = ''
    let groupName = ''

    // ── 0. Setup ──────────────────────────────────────────────────────────────

    test('00 setup: create queue and first consumer group', async ({ page }) => {
        test.setTimeout(60000)

        // Create a dedicated queue
        queueName = `cg_val_queue_${Date.now()}`
        const qResp = await page.request.post(
            '/api/v1/management/queues',
            { data: { setupId: SETUP_ID, name: queueName, type: 'native' } }
        )
        if (!qResp.ok()) {
            throw new Error(`Create queue failed: ${qResp.status()} ${await qResp.text()}`)
        }

        // Brief pause for queue init
        await page.waitForTimeout(1000)

        // Create the first consumer group via API
        groupName = `cg_dup_${Date.now()}`
        const cgResp = await page.request.post(
            '/api/v1/management/consumer-groups',
            { data: { name: groupName, setup: SETUP_ID, queueName } }
        )
        if (!cgResp.ok()) {
            throw new Error(`Create consumer group failed: ${cgResp.status()} ${await cgResp.text()}`)
        }

        console.log(`Created queue "${queueName}" and consumer group "${groupName}"`)
    })

    // ── 1. Duplicate name produces error ─────────────────────────────────────

    test('01 submitting a duplicate group name shows an error toast and keeps modal open', async ({ page }) => {
        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        await page.getByTestId('create-group-btn').click()
        const modal = page.locator('.ant-modal')
        await expect(modal).toBeVisible()

        // Fill the same name as the group already created
        await modal.getByLabel('Group Name').fill(groupName)
        await modal.getByTestId('create-group-queue-input').fill(queueName)

        // Select the setup
        await selectAntOption(modal.getByTestId('create-group-setup-select'), SETUP_ID)

        // Submit
        await modal.locator('.ant-btn-primary').click()

        // Backend returns an error (duplicate) — error toast should appear
        await expect(page.locator('.ant-message-error').first()).toBeVisible({ timeout: 10000 })

        // Modal must remain open (user must correct the error, not lose their input)
        await expect(modal).toBeVisible()

        // Dismiss modal
        await page.keyboard.press('Escape')
        await expect(modal).not.toBeVisible({ timeout: 3000 })
    })

    // ── 2. Unique name succeeds ───────────────────────────────────────────────

    test('02 submitting a unique group name closes the modal and shows success', async ({ page }) => {
        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        await page.getByTestId('create-group-btn').click()
        const modal = page.locator('.ant-modal')
        await expect(modal).toBeVisible()

        const uniqueName = `cg_unique_${Date.now()}`
        await modal.getByLabel('Group Name').fill(uniqueName)
        await modal.getByTestId('create-group-queue-input').fill(queueName)
        await selectAntOption(modal.getByTestId('create-group-setup-select'), SETUP_ID)

        await modal.locator('.ant-btn-primary').click()

        // On success the modal closes
        await expect(modal).not.toBeVisible({ timeout: 10000 })

        console.log(`Created unique consumer group "${uniqueName}"`)
    })
})
