import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Consumer Groups Page - Setup + Queue Scope Selector Tests
 *
 * Tests that the Setup Scope Selector and the dependent Queue Scope Selector
 * on the Consumer Groups page are visible and work correctly.
 *
 * Prerequisite: database-setup.spec.ts runs first and creates SETUP_ID.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Consumer Groups - Setup + Queue Scope Selectors', () => {

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

    test('should display setup and queue scope selectors on consumer groups page', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-consumer-groups').click()
        await page.waitForLoadState('networkidle')

        const scopeBar = page.getByTestId('scope-bar')
        await expect(scopeBar).toBeVisible()

        // Both setup and queue selectors should be rendered
        const setupSelector = page.getByTestId('setup-scope-selector')
        await expect(setupSelector).toBeVisible()

        const queueSelector = page.getByTestId('queue-scope-selector')
        await expect(queueSelector).toBeVisible()
    })

    test('queue selector should be disabled until a setup is chosen', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-consumer-groups').click()
        await page.waitForLoadState('networkidle')

        // First clear any pre-selected setup (localStorage may have carried one over)
        const setupSelector = page.getByTestId('setup-scope-selector')
        await setupSelector.hover()
        const clearBtn = setupSelector.locator('.ant-select-clear')
        if (await clearBtn.isVisible()) {
            await clearBtn.click()
        }

        const queueSelector = page.getByTestId('queue-scope-selector')
        // Ant Design expresses disabled state via the ant-select-disabled CSS class on the outer wrapper
        await expect(queueSelector).toHaveClass(/ant-select-disabled/)
    })

    test('should allow selecting a setup and then a queue', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-consumer-groups').click()
        await page.waitForLoadState('networkidle')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        await expect(setupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)

        // Queue selector should now be enabled
        const queueSelector = page.getByTestId('queue-scope-selector')
        await expect(queueSelector).not.toHaveAttribute('aria-disabled', 'true')
    })

    test('clearing setup should also clear queue selector', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-consumer-groups').click()
        await page.waitForLoadState('networkidle')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)
        await expect(setupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)

        // Clear setup
        await setupSelector.hover()
        const clearBtn = setupSelector.locator('.ant-select-clear')
        await expect(clearBtn).toBeVisible()
        await clearBtn.click()

        // Setup selector reverts to placeholder
        await expect(setupSelector.locator('.ant-select-selection-placeholder')).toBeVisible()

        // Queue selector reverts to disabled
        const queueSelector = page.getByTestId('queue-scope-selector')
        await expect(queueSelector).toHaveClass(/ant-select-disabled/)
    })

    test('should show consumer groups table and apply setup filter', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-consumer-groups').click()
        await page.waitForLoadState('networkidle')

        // Consumer groups table should be present (may be empty if no groups exist)
        const cgTable = page.getByTestId('consumer-groups-table')
        await expect(cgTable).toBeVisible()

        // Selecting a setup should keep the table visible (filtering in place)
        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        await expect(cgTable).toBeVisible()
    })

    test('create group modal setup field should pre-fill from active selection', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-consumer-groups').click()
        await page.waitForLoadState('networkidle')

        // Select a setup first
        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)
        await expect(setupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)

        // Open the create modal
        await page.getByTestId('create-group-btn').click()
        await expect(page.locator('.ant-modal')).toBeVisible()

        // The setup field should be pre-filled with the active selection
        const modalSetupSelect = page.getByTestId('create-group-setup-select')
        await expect(modalSetupSelect.locator('.ant-select-selection-item')).toContainText(SETUP_ID)

        // Dismiss modal without creating
        await page.locator('.ant-modal .ant-btn:not(.ant-btn-primary)').click()
        await expect(page.locator('.ant-modal')).not.toBeVisible()
    })
})
