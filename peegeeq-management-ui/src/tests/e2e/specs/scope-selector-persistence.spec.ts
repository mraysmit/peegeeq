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
        await page.waitForLoadState('networkidle')

        const queuesSetupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(queuesSetupSelector, SETUP_ID)
        await expect(queuesSetupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)

        // Navigate to Event Stores page
        await page.getByTestId('nav-event-stores').click()
        await page.waitForLoadState('networkidle')

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

        // Verify no setup is stored yet (or clear it)
        await page.evaluate(() => localStorage.removeItem('pgq-selected-setup'))

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        // Check that localStorage now contains the selected setup ID
        const storedSetup = await page.evaluate(() => localStorage.getItem('pgq-selected-setup'))
        expect(storedSetup).toBe(SETUP_ID)
    })
})
