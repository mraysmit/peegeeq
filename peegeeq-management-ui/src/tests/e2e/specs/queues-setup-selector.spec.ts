import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Queues Page - Setup Scope Selector Tests
 *
 * Tests that the Setup Scope Selector on the Queues page is visible,
 * allows selecting a setup to scope the queue list, and reverts when cleared.
 *
 * Prerequisite: database-setup.spec.ts runs first and creates SETUP_ID.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Queues - Setup Scope Selector', () => {

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

    test('should display setup scope selector on queues page', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-queues').click()
        await page.waitForLoadState('networkidle')

        const scopeBar = page.getByTestId('scope-bar')
        await expect(scopeBar).toBeVisible()

        const setupSelector = page.getByTestId('setup-scope-selector')
        await expect(setupSelector).toBeVisible()
    })

    test('should allow selecting a setup on queues page', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-queues').click()
        await page.waitForLoadState('networkidle')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await expect(setupSelector).toBeVisible()

        await selectAntOption(setupSelector, SETUP_ID)

        await expect(setupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)
    })

    test('should keep queues table visible when setup is selected', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-queues').click()
        await page.waitForLoadState('networkidle')

        const setupSelector = page.getByTestId('setup-scope-selector')
        const queuesTable = page.getByTestId('queues-table')

        await expect(queuesTable).toBeVisible()

        await selectAntOption(setupSelector, SETUP_ID)

        await expect(queuesTable).toBeVisible()
    })

    test('should revert to all queues when selector is cleared', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-queues').click()
        await page.waitForLoadState('networkidle')

        const setupSelector = page.getByTestId('setup-scope-selector')

        await selectAntOption(setupSelector, SETUP_ID)
        await expect(setupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)

        await setupSelector.hover()
        const clearBtn = setupSelector.locator('.ant-select-clear')
        await expect(clearBtn).toBeVisible()
        await clearBtn.click()

        await expect(setupSelector.locator('.ant-select-selection-placeholder')).toBeVisible()

        const queuesTable = page.getByTestId('queues-table')
        await expect(queuesTable).toBeVisible()
    })

    test('should send setupId query parameter to API when setup is selected', async ({ page }) => {
        // Set up request watcher BEFORE navigation so auto-select requests are also captured.
        // waitForRequest is more reliable than page.route + networkidle because networkidle
        // may return before the auto-select triggers its RTK Query dispatch.
        const setupIdRequestPromise = page.waitForRequest(
            req => req.url().includes('/management/queues') && req.url().includes(`setupId=${SETUP_ID}`),
            { timeout: 15000 }
        )

        await page.goto('/')
        await page.getByTestId('nav-queues').click()
        await page.waitForLoadState('networkidle')

        // SetupScopeBar auto-selects the only available setup; if not auto-selected, select manually
        const setupSelector = page.getByTestId('setup-scope-selector')
        const alreadySelected = await setupSelector.locator('.ant-select-selection-item').isVisible()
        if (!alreadySelected) {
            await selectAntOption(setupSelector, SETUP_ID)
        }

        // Wait for the queues API call that includes setupId
        const request = await setupIdRequestPromise
        expect(request.url()).toContain(`setupId=${SETUP_ID}`)
    })
})
