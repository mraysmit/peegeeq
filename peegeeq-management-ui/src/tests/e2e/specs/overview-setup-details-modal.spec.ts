import { test, expect } from '@playwright/test'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Overview Page - Setup Details Modal Tests
 *
 * Tests the "..." button next to the Setup dropdown that opens a Modal
 * showing technical details (status, queues, event stores) for the
 * currently selected setup.
 *
 * Prerequisite: 3c-setup-prerequisite creates SETUP_ID before this project runs.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Overview - Setup Details Modal', () => {

    test.beforeEach(async ({ page }) => {
        page.on('console', msg => {
            if (msg.type() === 'error') {
                console.error('❌ Browser console error:', msg.text())
            }
        })
        page.on('pageerror', error => {
            console.error('❌ Page error:', error.message)
        })
    })

    test('details button should not be visible when no setup is selected', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        // Clear any persisted selection by clearing local storage
        await page.evaluate(() => {
            localStorage.removeItem('pgq-selected-setup')
            localStorage.removeItem('pgq-selected-queue')
        })
        await page.reload()
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await expect(setupSelector).toBeVisible()

        // If auto-selected (single setup), clear it
        const selectionItem = setupSelector.locator('.ant-select-selection-item')
        if (await selectionItem.isVisible()) {
            await setupSelector.hover()
            const clearBtn = setupSelector.locator('.ant-select-clear')
            if (await clearBtn.isVisible()) {
                await clearBtn.click()
                await expect(setupSelector.locator('.ant-select-selection-placeholder')).toBeVisible()
            }
        }

        // Details button should not be present when no setup is selected
        const detailsBtn = page.locator('[title="View setup details"]')
        await expect(detailsBtn).not.toBeVisible()
    })

    test('details button should appear after selecting a setup', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)
        await expect(setupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)

        // Details button should now be visible
        const detailsBtn = page.locator('[title="View setup details"]')
        await expect(detailsBtn).toBeVisible()
        await expect(detailsBtn).toContainText('...')
    })

    test('clicking details button should open the setup details modal', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        const detailsBtn = page.locator('[title="View setup details"]')
        await expect(detailsBtn).toBeVisible()
        await detailsBtn.click()

        // Modal should open with title containing the setup ID
        const modal = page.locator('.ant-modal')
        await expect(modal).toBeVisible()
        await expect(modal.locator('.ant-modal-title')).toContainText(SETUP_ID)
    })

    test('modal should show setup ID in descriptions table', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        await page.locator('[title="View setup details"]').click()

        const modal = page.locator('.ant-modal')
        await expect(modal).toBeVisible()

        // Wait for setup details to finish loading
        await expect(modal.getByText('Loading setup details')).not.toBeVisible({ timeout: 10000 })

        // The label cell should be present and the content cell should show the setup ID
        await expect(modal.locator('.ant-descriptions-item-label').filter({ hasText: 'Setup ID' })).toBeVisible()
        await expect(modal.locator('.ant-descriptions-item-content').first()).toContainText(SETUP_ID)
    })

    test('modal should show a status tag for the setup', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        await page.locator('[title="View setup details"]').click()

        const modal = page.locator('.ant-modal')
        await expect(modal).toBeVisible()

        // Wait for setup details to finish loading
        await expect(modal.getByText('Loading setup details')).not.toBeVisible({ timeout: 10000 })

        // Status label should be present and a Tag rendered next to it
        await expect(modal.locator('.ant-descriptions-item-label').filter({ hasText: 'Status' })).toBeVisible()
        await expect(modal.locator('.ant-descriptions-item-content .ant-tag').first()).toBeVisible()
    })

    test('modal should show database connection details', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        await page.locator('[title="View setup details"]').click()

        const modal = page.locator('.ant-modal')
        await expect(modal).toBeVisible()

        // Wait for setup details to finish loading
        await expect(modal.getByText('Loading setup details')).not.toBeVisible({ timeout: 10000 })

        // Host, Port, Database Name, and Schema rows should be present
        await expect(modal.locator('.ant-descriptions-item-label').filter({ hasText: 'Host' })).toBeVisible()
        await expect(modal.locator('.ant-descriptions-item-label').filter({ hasText: 'Port' })).toBeVisible()
        await expect(modal.locator('.ant-descriptions-item-label').filter({ hasText: 'Database Name' })).toBeVisible()
        await expect(modal.locator('.ant-descriptions-item-label').filter({ hasText: 'Schema' })).toBeVisible()
    })

    test('modal should list queues for the setup', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        await page.locator('[title="View setup details"]').click()

        const modal = page.locator('.ant-modal')
        await expect(modal).toBeVisible()

        // Wait for setup details to finish loading
        await expect(modal.getByText('Loading setup details')).not.toBeVisible({ timeout: 10000 })

        // Queues label row should be present
        await expect(modal.locator('.ant-descriptions-item-label').filter({ hasText: /Queues/ })).toBeVisible()
    })

    test('modal should list event stores for the setup', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        await page.locator('[title="View setup details"]').click()

        const modal = page.locator('.ant-modal')
        await expect(modal).toBeVisible()

        // Wait for setup details to finish loading
        await expect(modal.getByText('Loading setup details')).not.toBeVisible({ timeout: 10000 })

        // Event Stores label row should be present
        await expect(modal.locator('.ant-descriptions-item-label').filter({ hasText: /Event Stores/ })).toBeVisible()
    })

    test('modal should close when Close button is clicked', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        await page.locator('[title="View setup details"]').click()

        const modal = page.locator('.ant-modal')
        await expect(modal).toBeVisible()

        // Click the Close button in the modal footer
        await modal.locator('.ant-modal-footer button').click()
        await expect(modal).not.toBeVisible()
    })

    test('modal should close when X button is clicked', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        await page.locator('[title="View setup details"]').click()

        const modal = page.locator('.ant-modal')
        await expect(modal).toBeVisible()

        // Click the X close button in the modal header
        await modal.locator('.ant-modal-close').click()
        await expect(modal).not.toBeVisible()
    })

    test('details button should disappear after setup is cleared', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        const detailsBtn = page.locator('[title="View setup details"]')
        await expect(detailsBtn).toBeVisible()

        // Clear the setup selection
        await setupSelector.hover()
        const clearBtn = setupSelector.locator('.ant-select-clear')
        await expect(clearBtn).toBeVisible()
        await clearBtn.click()

        await expect(setupSelector.locator('.ant-select-selection-placeholder')).toBeVisible()

        // Details button should no longer be visible
        await expect(detailsBtn).not.toBeVisible()
    })
})
