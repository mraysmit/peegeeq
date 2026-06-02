import { test, expect } from '@playwright/test'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Overview Page - Setup Details Panel Tests
 *
 * Tests the inline setup details panel that appears below the SetupScopeBar
 * when a setup is selected, showing technical details (status, queues,
 * event stores) for the currently selected setup.
 *
 * Prerequisite: 3c-setup-prerequisite creates SETUP_ID before this project runs.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Overview - Setup Details Panel', () => {

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

    test('setup details panel should not be visible when no setup is selected', async ({ page }) => {
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

        // Details panel should not be present when no setup is selected
        const panel = page.getByTestId('setup-details-panel')
        await expect(panel).not.toBeVisible()
    })

    test('setup details panel should appear after selecting a setup', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)
        await expect(setupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)

        // Details panel should now be visible with the setup title
        const panel = page.getByTestId('setup-details-panel')
        await expect(panel).toBeVisible()
        await expect(panel.locator('.ant-card-head-title')).toContainText(SETUP_ID)
    })

    test('panel should show setup ID in descriptions table', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        const panel = page.getByTestId('setup-details-panel')
        await expect(panel).toBeVisible()

        // Wait for setup details to finish loading
        await expect(panel.getByText('Loading setup details')).not.toBeVisible({ timeout: 10000 })

        // The label cell should be present and the content cell should show the setup ID
        await expect(panel.locator('.ant-descriptions-item-label').filter({ hasText: 'Setup ID' })).toBeVisible()
        await expect(panel.locator('.ant-descriptions-item-content').first()).toContainText(SETUP_ID)
    })

    test('panel should show a status tag for the setup', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        const panel = page.getByTestId('setup-details-panel')
        await expect(panel).toBeVisible()

        // Wait for setup details to finish loading
        await expect(panel.getByText('Loading setup details')).not.toBeVisible({ timeout: 10000 })

        // Status label should be present and a Tag rendered next to it
        await expect(panel.locator('.ant-descriptions-item-label').filter({ hasText: 'Status' })).toBeVisible()
        await expect(panel.locator('.ant-descriptions-item-content .ant-tag').first()).toBeVisible()
    })

    test('panel should show database connection details when provided by backend', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        const panel = page.getByTestId('setup-details-panel')
        await expect(panel).toBeVisible()

        // Wait for setup details to finish loading
        await expect(panel.getByText('Loading setup details')).not.toBeVisible({ timeout: 10000 })

        // Descriptions table must be rendered with at least some rows
        const descriptions = panel.locator('.ant-descriptions')
        await expect(descriptions).toBeVisible()
        const itemCount = await descriptions.locator('.ant-descriptions-item').count()
        expect(itemCount).toBeGreaterThan(0)

        // Connection detail rows (Host, Port, Database Name, Schema) are optional —
        // the backend only includes them when getDatabaseConfig succeeds for this setup.
        // If Host is shown, all four connection fields must be shown together.
        const hostRowCount = await panel.locator('.ant-descriptions-item-label').filter({ hasText: 'Host' }).count()
        if (hostRowCount > 0) {
            await expect(panel.locator('.ant-descriptions-item-label').filter({ hasText: 'Port' })).toBeVisible()
            await expect(panel.locator('.ant-descriptions-item-label').filter({ hasText: 'Database Name' })).toBeVisible()
            await expect(panel.locator('.ant-descriptions-item-label').filter({ hasText: 'Schema' })).toBeVisible()
        }
    })

    test('panel should list queues for the setup', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        const panel = page.getByTestId('setup-details-panel')
        await expect(panel).toBeVisible()

        // Wait for setup details to finish loading
        await expect(panel.getByText('Loading setup details')).not.toBeVisible({ timeout: 10000 })

        // Queues label row should be present
        await expect(panel.locator('.ant-descriptions-item-label').filter({ hasText: /Queues/ })).toBeVisible()
    })

    test('panel should list event stores for the setup', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        const panel = page.getByTestId('setup-details-panel')
        await expect(panel).toBeVisible()

        // Wait for setup details to finish loading
        await expect(panel.getByText('Loading setup details')).not.toBeVisible({ timeout: 10000 })

        // Event Stores label row should be present
        await expect(panel.locator('.ant-descriptions-item-label').filter({ hasText: /Event Stores/ })).toBeVisible()
    })

    test('setup details panel should disappear after setup is cleared', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        const panel = page.getByTestId('setup-details-panel')
        await expect(panel).toBeVisible()

        // Clear the setup selection
        await setupSelector.hover()
        const clearBtn = setupSelector.locator('.ant-select-clear')
        await expect(clearBtn).toBeVisible()
        await clearBtn.click()

        await expect(setupSelector.locator('.ant-select-selection-placeholder')).toBeVisible()

        // Details panel should no longer be visible
        await expect(panel).not.toBeVisible()
    })
})
