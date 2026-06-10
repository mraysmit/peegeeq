import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Scope Selector Persistence Tests
 *
 * Verifies that the selected setup (and queue) persists across page navigation
 * and browser reloads via localStorage.
 *
 * localStorage keys written by managementStore:
 *   pgq-selected-setup  — active setup ID
 *   pgq-selected-queue  — active queue name
 *
 * Prerequisite: database-setup.spec.ts runs first and creates SETUP_ID.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Scope Selector - State Persistence', () => {

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

    test('should persist setup selection across page navigation', async ({ page }) => {
        // Select a setup on the Queues page
        await page.goto('/')
        await page.getByTestId('nav-queues').click()
        await page.waitForLoadState('load')

        const queuesSetupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(queuesSetupSelector, SETUP_ID)
        await expect(queuesSetupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)

        // Navigate to Event Stores page
        await page.getByTestId('nav-event-stores').click()
        await page.waitForLoadState('load')

        // Setup selector on Event Stores page should still reflect the previous selection
        const eventStoresSetupSelector = page.getByTestId('setup-scope-selector')
        await expect(eventStoresSetupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)
    })

    test('should persist setup selection across browser reload', async ({ page }) => {
        // Select a setup on the Overview page
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)
        await expect(setupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)

        // Reload the page — localStorage should restore the selection
        await page.reload()
        await page.waitForLoadState('load')

        const setupSelectorAfterReload = page.getByTestId('setup-scope-selector')
        await expect(setupSelectorAfterReload.locator('.ant-select-selection-item')).toContainText(SETUP_ID)
    })

    test('should write selection to localStorage immediately', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await expect(setupSelector).toBeVisible()

        // Wait for SetupScopeBar to finish loading its options (async fetch)
        await expect(setupSelector.locator('.ant-select-selection-item, .ant-select-selection-placeholder')).toBeVisible({ timeout: 10000 })

        // Clear any existing selection so onChange will fire when we re-select
        await setupSelector.hover()
        const clearBtn = setupSelector.locator('.ant-select-clear')
        if (await clearBtn.isVisible({ timeout: 1000 }).catch(() => false)) {
            await clearBtn.click()
            // Store should now write null to localStorage
            await expect
                .poll(() => page.evaluate(() => localStorage.getItem('pgq-selected-setup')), { timeout: 3000 })
                .toBeNull()
        }

        // Now select SETUP_ID — this must trigger setSelectedSetup → localStorage write
        await selectAntOption(setupSelector, SETUP_ID)

        // localStorage must contain the selected setup ID
        const storedSetup = await page.evaluate(() => localStorage.getItem('pgq-selected-setup'))
        expect(storedSetup).toBe(SETUP_ID)
    })
})
