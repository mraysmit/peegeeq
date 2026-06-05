import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Events Page - Setup Scope Selector Tests
 *
 * The Events page uses an inline query-setup-select (inside the Query Events card)
 * as its primary setup selector.  There is no shared SetupScopeBar on this page —
 * it was removed because the page has its own contextual selectors inside each card.
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

    test('should display the inline query-setup-select on the events page', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-events').click()
        await page.waitForLoadState('networkidle')

        // The Query Events card has its own inline setup selector
        await expect(page.getByTestId('query-setup-select')).toBeVisible()

        // There is no shared SetupScopeBar on this page
        await expect(page.getByTestId('scope-bar')).not.toBeVisible()
    })

    test('should NOT show a SetupScopeBar on the events page', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-events').click()
        await page.waitForLoadState('networkidle')

        // scope-bar / setup-scope-selector are shared-layout selectors; events page does not use them
        await expect(page.getByTestId('setup-scope-selector')).not.toBeVisible()
    })

    test('should allow selecting a setup via the inline query-setup-select', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-events').click()
        await page.waitForLoadState('networkidle')

        const setupSelector = page.getByTestId('query-setup-select')
        await selectAntOption(setupSelector, SETUP_ID)

        await expect(setupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)
    })
})
