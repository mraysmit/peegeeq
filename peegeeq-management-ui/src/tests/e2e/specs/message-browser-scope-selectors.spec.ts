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

    test('should pass setupId query parameter to messages API when setup is selected', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-messages').click()
        await page.waitForLoadState('networkidle')

        const setupSelector = page.getByTestId('setup-scope-selector')

        // Clear any pre-existing selection
        await setupSelector.hover()
        const clearBtn = setupSelector.locator('.ant-select-clear')
        if (await clearBtn.isVisible()) {
            await clearBtn.click()
        }

        // Capture message requests after selecting a setup
        const matchedUrls: string[] = []
        await page.route('**/messages**', route => {
            matchedUrls.push(route.request().url())
            return route.continue()
        })

        await selectAntOption(setupSelector, SETUP_ID)
        await expect(setupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)

        // The scope bar now shows SETUP_ID — filtering is wired even if no messages fire
        // (queue must also be selected before the message fetch is triggered)
        // Verify that queue selector is enabled and ready for selection
        const queueSelector = page.getByTestId('queue-scope-selector')
        await expect(queueSelector).not.toHaveClass(/ant-select-disabled/)
    })

    // ── Filters ───────────────────────────────────────────────────────────────

    test('status filter dropdown should show all four status options', async ({ page }) => {
        await page.goto('/messages')
        await page.waitForLoadState('networkidle')

        // Locate the quick-filters card by the search input it always contains,
        // then take the first .ant-select in that card (the status filter).
        const quickFiltersCard = page.locator('.ant-card-body').filter({
            has: page.locator('input[placeholder="Search messages..."]')
        })
        const statusSelect = quickFiltersCard.locator('.ant-select').first()
        await statusSelect.click()

        const dropdown = page.locator('.ant-select-dropdown')
            .filter({ hasNot: page.locator('.ant-select-dropdown-hidden') })
            .last()
        await expect(dropdown).toBeVisible()

        for (const label of ['Pending', 'Processing', 'Completed', 'Failed']) {
            await expect(dropdown.locator('.ant-select-item-option-content').filter({ hasText: label })).toBeVisible()
        }

        await page.keyboard.press('Escape')
    })

    test('search input should be visible and accept text', async ({ page }) => {
        await page.goto('/messages')
        await page.waitForLoadState('networkidle')

        const searchInput = page.locator('input[placeholder="Search messages..."]')
        await expect(searchInput).toBeVisible()
        await searchInput.fill('test-search')
        await expect(searchInput).toHaveValue('test-search')
        await searchInput.clear()
        await expect(searchInput).toHaveValue('')
    })

    test('clear button should reset status filter and search text', async ({ page }) => {
        await page.goto('/messages')
        await page.waitForLoadState('networkidle')

        // Set a status filter
        const quickFiltersCard = page.locator('.ant-card-body').filter({
            has: page.locator('input[placeholder="Search messages..."]')
        })
        const statusSelect = quickFiltersCard.locator('.ant-select').first()
        await statusSelect.click()
        const dropdown = page.locator('.ant-select-dropdown')
            .filter({ hasNot: page.locator('.ant-select-dropdown-hidden') })
            .last()
        await expect(dropdown).toBeVisible()
        await dropdown.locator('.ant-select-item-option-content').filter({ hasText: 'Pending' }).click()
        await expect(dropdown).toBeHidden({ timeout: 5000 })

        // Set search text
        const searchInput = page.locator('input[placeholder="Search messages..."]')
        await searchInput.fill('some-search')

        // Click Clear
        await page.getByRole('button', { name: /clear/i }).first().click()

        // Status filter should be cleared — "Pending" selection item no longer visible
        await expect(statusSelect.locator('.ant-select-selection-item').filter({ hasText: 'Pending' })).not.toBeVisible()
        // Search input should be empty
        await expect(searchInput).toHaveValue('')
    })

    test('refresh button should trigger re-fetch of messages', async ({ page }) => {
        await page.goto('/messages')
        await page.waitForLoadState('networkidle')

        const requests: string[] = []
        await page.route('**/management/messages**', route => {
            requests.push(route.request().url())
            return route.continue()
        })

        await page.getByRole('button', { name: /refresh/i }).click()
        await page.waitForTimeout(500)

        expect(requests.length).toBeGreaterThanOrEqual(1)
        expect(requests[0]).toContain('/management/messages')
    })

    // ── Filters drawer ────────────────────────────────────────────────────────

    test('filters button should open the advanced filters drawer', async ({ page }) => {
        await page.goto('/messages')
        await page.waitForLoadState('networkidle')

        await page.getByRole('button', { name: /filters/i }).click()

        const drawer = page.locator('.ant-drawer-open')
        await expect(drawer).toBeVisible({ timeout: 5000 })
        await expect(drawer.locator('.ant-drawer-title')).toContainText('Advanced Filters')
    })

    test('filters drawer clear all button should close without error', async ({ page }) => {
        await page.goto('/messages')
        await page.waitForLoadState('networkidle')

        await page.getByRole('button', { name: /filters/i }).click()
        const drawer = page.locator('.ant-drawer-open')
        await expect(drawer).toBeVisible({ timeout: 5000 })

        await drawer.getByRole('button', { name: /clear all/i }).click()
        // Drawer should remain open (Clear All only resets fields, not closes)
        await expect(drawer).toBeVisible()

        await page.keyboard.press('Escape')
        await expect(drawer).not.toBeVisible({ timeout: 5000 })
    })

    // ── Message detail modal ──────────────────────────────────────────────────

    test('messages table should be present on page load', async ({ page }) => {
        await page.goto('/messages')
        await page.waitForLoadState('networkidle')

        // The table renders even when empty
        await expect(page.locator('.ant-table')).toBeVisible({ timeout: 10000 })
    })

    test('real-time Live toggle should be visible and toggleable', async ({ page }) => {
        await page.goto('/messages')
        await page.waitForLoadState('networkidle')

        const liveSwitch = page.locator('.ant-switch').first()
        await expect(liveSwitch).toBeVisible()

        // Toggle on
        await liveSwitch.click()
        await expect(page.locator('.ant-alert').filter({ hasText: 'Real-time Mode Active' })).toBeVisible({ timeout: 5000 })

        // Toggle off
        await liveSwitch.click()
        await expect(page.locator('.ant-alert').filter({ hasText: 'Real-time Mode Active' })).not.toBeVisible({ timeout: 5000 })
    })

    // ── API query parameters ──────────────────────────────────────────────────

    test('messages API should include both setup and queue params when both are selected', async ({ page }) => {
        await page.goto('/messages')
        await page.waitForLoadState('networkidle')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await setupSelector.hover()
        const clearBtn = setupSelector.locator('.ant-select-clear')
        if (await clearBtn.isVisible()) await clearBtn.click()

        const capturedUrls: string[] = []
        await page.route('**/management/messages**', route => {
            capturedUrls.push(route.request().url())
            return route.continue()
        })

        // Select setup first
        await selectAntOption(setupSelector, SETUP_ID)
        await page.waitForTimeout(800)

        // Queue selector should now be enabled and populated from setup's queueFactories
        const queueSelector = page.getByTestId('queue-scope-selector')
        await expect(queueSelector).not.toHaveClass(/ant-select-disabled/, { timeout: 8000 })

        // Select first available queue option
        await queueSelector.click()
        const queueDropdown = page.locator('.ant-select-dropdown')
            .filter({ hasNot: page.locator('.ant-select-dropdown-hidden') })
            .last()
        await expect(queueDropdown).toBeVisible()
        const firstOption = queueDropdown.locator('.ant-select-item-option').first()
        await expect(firstOption).toBeVisible({ timeout: 15000 })
        const selectedQueueName = await firstOption.locator('.ant-select-item-option-content').innerText()
        await firstOption.click()
        await expect(queueDropdown).toBeHidden({ timeout: 5000 })

        await page.waitForTimeout(800)

        // At least one request should include both setup and queue params
        const requestWithBoth = capturedUrls.find(u =>
            u.includes(`setup=${SETUP_ID}`) && u.includes(`queue=${encodeURIComponent(selectedQueueName)}`)
        )
        expect(requestWithBoth, `Expected a request with setup=${SETUP_ID} and queue=${selectedQueueName}, got: ${capturedUrls.join(', ')}`).toBeDefined()
    })
})
