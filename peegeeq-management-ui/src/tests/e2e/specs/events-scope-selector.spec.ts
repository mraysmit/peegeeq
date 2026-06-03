import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Events Page - Setup Scope Selector Tests
 *
 * The Events page mounts a SetupScopeBar (scope-bar / setup-scope-selector)
 * AND retains a legacy inline setup selector (query-setup-select) that also
 * reads selectedSetupId from the global store.  Both selectors should be
 * present and the SetupScopeBar should be the primary scope control.
 *
 * Prerequisite: database-setup.spec.ts runs first and creates SETUP_ID.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Events Page - Setup Scope Selector', () => {

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

    test('should display SetupScopeBar on the events page', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-events').click()
        await page.waitForLoadState('networkidle')

        // Scope bar container is present
        const scopeBar = page.getByTestId('scope-bar')
        await expect(scopeBar).toBeVisible()

        // Setup selector rendered inside the scope bar
        const setupSelector = page.getByTestId('setup-scope-selector')
        await expect(setupSelector).toBeVisible()
    })

    test('should also have a legacy inline query-setup-select alongside SetupScopeBar', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-events').click()
        await page.waitForLoadState('networkidle')

        // SetupScopeBar selector
        await expect(page.getByTestId('setup-scope-selector')).toBeVisible()

        // Legacy inline selector still rendered (both drive the same store state)
        await expect(page.getByTestId('query-setup-select')).toBeVisible()
    })

    test('should allow selecting a setup via SetupScopeBar on events page', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-events').click()
        await page.waitForLoadState('networkidle')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        await expect(setupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)
    })
})
