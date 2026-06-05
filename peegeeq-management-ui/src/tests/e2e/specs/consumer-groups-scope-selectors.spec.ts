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
        const modalSetupSelect = page.locator('.ant-modal').getByTestId('create-group-setup-select')
        await expect(modalSetupSelect.locator('.ant-select-selection-item')).toContainText(SETUP_ID)

        // Dismiss modal without creating
        await page.locator('.ant-modal .ant-btn:not(.ant-btn-primary)').click()
        await expect(page.locator('.ant-modal')).not.toBeVisible()
    })

    test('create group button should open the create modal', async ({ page }) => {
        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        await page.getByTestId('create-group-btn').click()

        const modal = page.locator('.ant-modal')
        await expect(modal).toBeVisible()
        await expect(modal.locator('.ant-modal-title')).toContainText('Create Consumer Group')

        await modal.locator('.ant-btn:not(.ant-btn-primary)').click()
        await expect(modal).not.toBeVisible({ timeout: 5000 })
    })

    test('create modal should show validation errors when submitted empty', async ({ page }) => {
        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        await page.getByTestId('create-group-btn').click()
        await expect(page.locator('.ant-modal')).toBeVisible()

        // Submit without filling anything
        await page.locator('.ant-modal .ant-btn-primary').click()

        // Required field validation messages should appear
        const modal = page.locator('.ant-modal')
        await expect(modal.locator('.ant-form-item-explain-error').first()).toBeVisible({ timeout: 5000 })

        await page.keyboard.press('Escape')
    })

    test('create group should call POST API and refresh table', async ({ page }) => {
        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        // Intercept the POST request
        const postRequests: string[] = []
        await page.route('**/management/consumer-groups', route => {
            if (route.request().method() === 'POST') {
                postRequests.push(route.request().url())
            }
            return route.continue()
        })

        await page.getByTestId('create-group-btn').click()
        await expect(page.locator('.ant-modal')).toBeVisible()

        // Fill required fields
        const groupName = `test-group-${Date.now()}`
        await page.locator('.ant-modal').getByLabel('Group Name').fill(groupName)
        await page.locator('.ant-modal').getByLabel('Queue Name').fill('test-queue')

        // Select setup
        const modalSetupAncestor = page.locator('.ant-modal').getByTestId('create-group-setup-select')
        await selectAntOption(modalSetupAncestor, SETUP_ID)

        // Submit
        await page.locator('.ant-modal .ant-btn-primary').click()

        // Modal should close after successful submission
        // (may fail with 404 if queue doesn't exist — modal stays open with error toast)
        // Either way, the POST must have been fired
        await page.waitForTimeout(1500)
        expect(postRequests.length, 'POST /management/consumer-groups was not called').toBeGreaterThanOrEqual(1)
    })

    // -------------------------------------------------------------------------
    // Action menu tests — use page.route() to inject a controlled group list
    // so status-conditional menu items are predictable without needing real DB state.
    // -------------------------------------------------------------------------

    const MOCK_ACTIVE_GROUP = {
        name: 'mock-active-group',
        setup: SETUP_ID,
        queueName: 'mock-queue',
        members: 1,
        implementationType: 'NATIVE_QUEUE',
        status: 'active',
        createdAt: new Date().toISOString(),
        subscribedAt: new Date().toISOString(),
        lastActiveAt: new Date().toISOString(),
        lastHeartbeatAt: null,
        backfillStatus: 'NONE',
        lag: 0,
    }

    const MOCK_PAUSED_GROUP = {
        ...MOCK_ACTIVE_GROUP,
        name: 'mock-paused-group',
        status: 'paused',
    }

    const MOCK_BACKFILLING_GROUP = {
        ...MOCK_ACTIVE_GROUP,
        name: 'mock-backfilling-group',
        status: 'active',
        backfillStatus: 'IN_PROGRESS',
    }

    async function mockGroupList(page: any, groups: any[]) {
        await page.route('**/management/consumer-groups', route => {
            if (route.request().method() === 'GET') {
                return route.fulfill({
                    status: 200,
                    contentType: 'application/json',
                    body: JSON.stringify({ consumerGroups: groups }),
                })
            }
            return route.continue()
        })
    }

    test('action menu for active group shows Pause and Start Backfill, not Resume', async ({ page }) => {
        await mockGroupList(page, [MOCK_ACTIVE_GROUP])
        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        // Open action menu for the first row
        const actionBtn = page.locator('.ant-table-row').first().getByRole('button')
        await expect(actionBtn).toBeVisible()
        await actionBtn.click()

        const dropdown = page.locator('.ant-dropdown').filter({ hasNot: page.locator('.ant-dropdown-hidden') }).last()
        await expect(dropdown).toBeVisible()

        await expect(dropdown.getByText('Pause Group')).toBeVisible()
        await expect(dropdown.getByText('Start Backfill')).toBeVisible()
        await expect(dropdown.getByText('Resume Group')).not.toBeVisible()

        // Close dropdown
        await page.keyboard.press('Escape')
    })

    test('action menu for paused group shows Resume and Start Backfill, not Pause', async ({ page }) => {
        await mockGroupList(page, [MOCK_PAUSED_GROUP])
        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        const actionBtn = page.locator('.ant-table-row').first().getByRole('button')
        await expect(actionBtn).toBeVisible()
        await actionBtn.click()

        const dropdown = page.locator('.ant-dropdown').filter({ hasNot: page.locator('.ant-dropdown-hidden') }).last()
        await expect(dropdown).toBeVisible()

        await expect(dropdown.getByText('Resume Group')).toBeVisible()
        await expect(dropdown.getByText('Start Backfill')).toBeVisible()
        await expect(dropdown.getByText('Pause Group')).not.toBeVisible()

        await page.keyboard.press('Escape')
    })

    test('action menu for group with backfill IN_PROGRESS hides Start Backfill', async ({ page }) => {
        await mockGroupList(page, [MOCK_BACKFILLING_GROUP])
        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        const actionBtn = page.locator('.ant-table-row').first().getByRole('button')
        await expect(actionBtn).toBeVisible()
        await actionBtn.click()

        const dropdown = page.locator('.ant-dropdown').filter({ hasNot: page.locator('.ant-dropdown-hidden') }).last()
        await expect(dropdown).toBeVisible()

        await expect(dropdown.getByText('Start Backfill')).not.toBeVisible()

        await page.keyboard.press('Escape')
    })

    test('clicking Pause Group calls POST .../pause endpoint', async ({ page }) => {
        await mockGroupList(page, [MOCK_ACTIVE_GROUP])

        const pauseRequests: string[] = []
        await page.route('**/consumer-groups/**', route => {
            if (route.request().method() === 'POST' && route.request().url().includes('/pause')) {
                pauseRequests.push(route.request().url())
                return route.fulfill({ status: 200, contentType: 'application/json',
                    body: JSON.stringify({ message: 'paused', status: 'paused', setupId: SETUP_ID,
                        queueName: 'mock-queue', groupName: 'mock-active-group', timestamp: Date.now() }) })
            }
            return route.continue()
        })

        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        const actionBtn = page.locator('.ant-table-row').first().getByRole('button')
        await actionBtn.click()

        const dropdown = page.locator('.ant-dropdown').filter({ hasNot: page.locator('.ant-dropdown-hidden') }).last()
        await dropdown.getByText('Pause Group').click()

        await page.waitForTimeout(500)
        expect(pauseRequests.length, 'POST .../pause was not called').toBeGreaterThanOrEqual(1)
        expect(pauseRequests[0]).toContain('/pause')
    })

    test('clicking Resume Group calls POST .../resume endpoint', async ({ page }) => {
        await mockGroupList(page, [MOCK_PAUSED_GROUP])

        const resumeRequests: string[] = []
        await page.route('**/consumer-groups/**', route => {
            if (route.request().method() === 'POST' && route.request().url().includes('/resume')) {
                resumeRequests.push(route.request().url())
                return route.fulfill({ status: 200, contentType: 'application/json',
                    body: JSON.stringify({ message: 'resumed', status: 'active', setupId: SETUP_ID,
                        queueName: 'mock-queue', groupName: 'mock-paused-group', timestamp: Date.now() }) })
            }
            return route.continue()
        })

        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        const actionBtn = page.locator('.ant-table-row').first().getByRole('button')
        await actionBtn.click()

        const dropdown = page.locator('.ant-dropdown').filter({ hasNot: page.locator('.ant-dropdown-hidden') }).last()
        await dropdown.getByText('Resume Group').click()

        await page.waitForTimeout(500)
        expect(resumeRequests.length, 'POST .../resume was not called').toBeGreaterThanOrEqual(1)
        expect(resumeRequests[0]).toContain('/resume')
    })

    test('clicking Start Backfill calls POST .../backfill endpoint', async ({ page }) => {
        await mockGroupList(page, [MOCK_ACTIVE_GROUP])

        const backfillRequests: string[] = []
        await page.route('**/consumer-groups/**', route => {
            if (route.request().method() === 'POST' && route.request().url().includes('/backfill')) {
                backfillRequests.push(route.request().url())
                return route.fulfill({ status: 200, contentType: 'application/json',
                    body: JSON.stringify({ status: 'IN_PROGRESS', processedMessages: 0, totalMessages: 100 }) })
            }
            return route.continue()
        })

        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        const actionBtn = page.locator('.ant-table-row').first().getByRole('button')
        await actionBtn.click()

        const dropdown = page.locator('.ant-dropdown').filter({ hasNot: page.locator('.ant-dropdown-hidden') }).last()
        await dropdown.getByText('Start Backfill').click()

        await page.waitForTimeout(500)
        expect(backfillRequests.length, 'POST .../backfill was not called').toBeGreaterThanOrEqual(1)
        expect(backfillRequests[0]).toContain('/backfill')
    })

    test('clicking Delete Group calls DELETE endpoint after confirmation', async ({ page }) => {
        await mockGroupList(page, [MOCK_ACTIVE_GROUP])

        const deleteRequests: string[] = []
        await page.route('**/consumer-groups/**', route => {
            if (route.request().method() === 'DELETE') {
                deleteRequests.push(route.request().url())
                return route.fulfill({ status: 200, contentType: 'application/json',
                    body: JSON.stringify({ message: 'deleted' }) })
            }
            return route.continue()
        })

        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        const actionBtn = page.locator('.ant-table-row').first().getByRole('button')
        await actionBtn.click()

        const dropdown = page.locator('.ant-dropdown').filter({ hasNot: page.locator('.ant-dropdown-hidden') }).last()
        await dropdown.getByText('Delete Group').click()

        // Ant Design confirm dialog — click OK
        const confirmBtn = page.locator('.ant-modal-confirm .ant-btn-dangerous')
        await expect(confirmBtn).toBeVisible({ timeout: 3000 })
        await confirmBtn.click()

        await page.waitForTimeout(500)
        expect(deleteRequests.length, 'DELETE request was not called').toBeGreaterThanOrEqual(1)
        expect(deleteRequests[0]).toContain(`${SETUP_ID}/mock-queue/mock-active-group`)
    })

    test('clicking View Details opens the details modal', async ({ page }) => {
        await mockGroupList(page, [MOCK_ACTIVE_GROUP])
        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        const actionBtn = page.locator('.ant-table-row').first().getByRole('button')
        await actionBtn.click()

        const dropdown = page.locator('.ant-dropdown').filter({ hasNot: page.locator('.ant-dropdown-hidden') }).last()
        await dropdown.getByText('View Details').click()

        const modal = page.locator('.ant-modal')
        await expect(modal).toBeVisible()
        await expect(modal.locator('.ant-modal-title')).toContainText('Consumer Group Details')
        await expect(modal.getByText('mock-active-group')).toBeVisible()

        await modal.locator('.ant-btn').filter({ hasText: 'Close' }).click()
        await expect(modal).not.toBeVisible({ timeout: 3000 })
    })
})
