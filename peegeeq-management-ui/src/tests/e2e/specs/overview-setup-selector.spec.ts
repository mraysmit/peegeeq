import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Overview Page - Setup Scope Selector Tests
 *
 * Tests that the Setup Scope Selector on the Overview page is visible,
 * allows selecting a setup to scope the queue table, and reverts to
 * "All setups" when cleared.
 *
 * Prerequisite: database-setup.spec.ts runs first (alphabetically) and
 * creates SETUP_ID so the selector has at least one option.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Overview - Setup Scope Selector', () => {

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

    test('should display setup scope selector on overview page', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        // The scope bar container should be visible
        const scopeBar = page.getByTestId('scope-bar')
        await expect(scopeBar).toBeVisible()

        // The setup selector should be visible inside the scope bar
        const setupSelector = page.getByTestId('setup-scope-selector')
        await expect(setupSelector).toBeVisible()

        // Should show "All setups" placeholder when nothing is pre-selected
        // (The selector may auto-select if exactly one setup exists, so we only
        //  assert visibility rather than the placeholder text here)
        await expect(setupSelector).toBeVisible()
    })

    test('should allow selecting a setup from the scope selector', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await expect(setupSelector).toBeVisible()

        // Select the default setup
        await selectAntOption(setupSelector, SETUP_ID)

        // Selector should now display the selected setup ID
        await expect(setupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)
    })

    test('should keep queue overview table visible when setup is selected', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        const queueTable = page.getByTestId('queue-overview-table')

        // Queue table is visible before any selection
        await expect(queueTable).toBeVisible()

        // Select the default setup to apply scope filter
        await selectAntOption(setupSelector, SETUP_ID)

        // Queue table should still be visible (scoped, not removed)
        await expect(queueTable).toBeVisible()
    })

    test('should revert to all queues when selector is cleared', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')

        // Select the default setup first
        await selectAntOption(setupSelector, SETUP_ID)
        await expect(setupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)

        // Hover to reveal the allowClear button, then click it
        await setupSelector.hover()
        const clearBtn = setupSelector.locator('.ant-select-clear')
        await expect(clearBtn).toBeVisible()
        await clearBtn.click()

        // Selector should revert to placeholder (no selection active)
        await expect(setupSelector.locator('.ant-select-selection-placeholder')).toBeVisible()

        // Queue table should still be visible with unfiltered data
        const queueTable = page.getByTestId('queue-overview-table')
        await expect(queueTable).toBeVisible()
    })

    test('should gate overview display on setup selection', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')

        // Clear any pre-existing selection so the "no setup" state is guaranteed
        await setupSelector.hover()
        const clearBtn = setupSelector.locator('.ant-select-clear')
        if (await clearBtn.isVisible()) {
            await clearBtn.click()
        }

        // When no setup is selected the overview shows the "no setup" placeholder
        await expect(page.getByTestId('no-setup-info')).toBeVisible()
        await expect(page.getByTestId('selected-setup-info')).not.toBeVisible()

        // After selecting a setup the overview switches to the selected-setup view
        await selectAntOption(setupSelector, SETUP_ID)
        await expect(page.getByTestId('selected-setup-tag')).toContainText(SETUP_ID)
        await expect(page.getByTestId('no-setup-info')).not.toBeVisible()
    })
})
