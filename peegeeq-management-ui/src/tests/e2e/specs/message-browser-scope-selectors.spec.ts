import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Message Browser Page - Setup + Queue Scope Selector Tests
 *
 * Tests that the Setup Scope Selector and the dependent Queue Scope Selector
 * on the Message Browser page are visible and work correctly.
 *
 * Prerequisite: database-setup.spec.ts runs first and creates SETUP_ID.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Message Browser - Setup + Queue Scope Selectors', () => {

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

    test('should display setup and queue scope selectors on message browser page', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-messages').click()
        await page.waitForLoadState('networkidle')

        const scopeBar = page.getByTestId('scope-bar')
        await expect(scopeBar).toBeVisible()

        const setupSelector = page.getByTestId('setup-scope-selector')
        await expect(setupSelector).toBeVisible()

        const queueSelector = page.getByTestId('queue-scope-selector')
        await expect(queueSelector).toBeVisible()
    })

    test('queue selector should be disabled until a setup is chosen', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-messages').click()
        await page.waitForLoadState('networkidle')

        // First clear any pre-selected setup from localStorage
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

    test('should allow selecting a setup on message browser page', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-messages').click()
        await page.waitForLoadState('networkidle')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        await expect(setupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)

        // Queue selector should become enabled
        const queueSelector = page.getByTestId('queue-scope-selector')
        await expect(queueSelector).not.toHaveAttribute('aria-disabled', 'true')
    })

    test('clearing setup should also disable queue selector', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-messages').click()
        await page.waitForLoadState('networkidle')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)
        await expect(setupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)

        // Clear setup
        await setupSelector.hover()
        const clearBtn = setupSelector.locator('.ant-select-clear')
        await expect(clearBtn).toBeVisible()
        await clearBtn.click()

        await expect(setupSelector.locator('.ant-select-selection-placeholder')).toBeVisible()

        const queueSelector = page.getByTestId('queue-scope-selector')
        await expect(queueSelector).toHaveClass(/ant-select-disabled/)
    })

    test('quick filters should not contain setup or queue selects', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-messages').click()
        await page.waitForLoadState('networkidle')

        // The setup-scope-selector exists exactly once — in the scope bar
        const setupSelectors = page.getByTestId('setup-scope-selector')
        await expect(setupSelectors).toHaveCount(1)

        // The queue-scope-selector exists exactly once — in the scope bar
        const queueSelectors = page.getByTestId('queue-scope-selector')
        await expect(queueSelectors).toHaveCount(1)
    })
})
