import { test, expect } from '../page-objects'
import type { Page, Route } from '@playwright/test'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Consumer Groups Page - Setup + Queue Scope Selector Tests
 * + Action Menu Status-Conditional Rendering Tests
 *
 * Tests that the Setup Scope Selector and the dependent Queue Scope Selector
 * on the Consumer Groups page are visible and work correctly, and that action
 * menu items are shown or hidden according to group status.
 *
 * Prerequisite: database-setup.spec.ts runs first and creates SETUP_ID.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Consumer Groups - Setup + Queue Scope Selectors', () => {

    // Shared state — populated in test 00 and used across the suite
    let cgQueue = ''
    let cgActive = ''
    let cgPause = ''
    let cgDelete = ''

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

    // ── 0. Setup ──────────────────────────────────────────────────────────────

    test('00 setup: create queue, publish messages, and create consumer groups', async ({ page }) => {
        test.setTimeout(120000)

        const ts = Date.now()
        cgQueue  = `cg_scope_queue_${ts}`
        cgActive = `cg_scope_active_${ts}`
        cgPause  = `cg_scope_pause_${ts}`
        cgDelete = `cg_scope_delete_${ts}`

        // Create queue
        const qResp = await page.request.post('/api/v1/management/queues', {
            data: { setupId: SETUP_ID, name: cgQueue, type: 'native' },
        })
        if (!qResp.ok()) throw new Error(`Create queue failed: ${qResp.status()} ${await qResp.text()}`)

        await page.waitForTimeout(1000)

        // Publish 1000 messages so a backfill takes long enough to observe IN_PROGRESS
        for (let i = 0; i < 1000; i++) {
            await page.request.post(`/api/v1/queues/${SETUP_ID}/${cgQueue}/publish`, {
                data: { payload: { seq: i } },
            })
        }

        // Create consumer groups
        for (const name of [cgActive, cgPause, cgDelete]) {
            const resp = await page.request.post('/api/v1/management/consumer-groups', {
                data: { name, setup: SETUP_ID, queueName: cgQueue },
            })
            if (!resp.ok()) throw new Error(`Create group "${name}" failed: ${resp.status()} ${await resp.text()}`)
        }

        console.log(`Queue: ${cgQueue}, groups: ${cgActive}, ${cgPause}, ${cgDelete}`)
    })

    // ── Scope selector tests ──────────────────────────────────────────────────

    test('should display setup and queue scope selectors on consumer groups page', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-consumer-groups').click()
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
        await page.getByTestId('nav-consumer-groups').click()
        await page.waitForLoadState('networkidle')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await setupSelector.hover()
        const clearBtn = setupSelector.locator('.ant-select-clear')
        if (await clearBtn.isVisible()) {
            await clearBtn.click()
        }

        const queueSelector = page.getByTestId('queue-scope-selector')
        await expect(queueSelector).toHaveClass(/ant-select-disabled/)
    })

    test('should allow selecting a setup and then a queue', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-consumer-groups').click()
        await page.waitForLoadState('networkidle')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        await expect(setupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)

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

        await setupSelector.hover()
        const clearBtn = setupSelector.locator('.ant-select-clear')
        await expect(clearBtn).toBeVisible()
        await clearBtn.click()

        await expect(setupSelector.locator('.ant-select-selection-placeholder')).toBeVisible()

        const queueSelector = page.getByTestId('queue-scope-selector')
        await expect(queueSelector).toHaveClass(/ant-select-disabled/)
    })

    test('should show consumer groups table and apply setup filter', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-consumer-groups').click()
        await page.waitForLoadState('networkidle')

        const cgTable = page.getByTestId('consumer-groups-table')
        await expect(cgTable).toBeVisible()

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        await expect(cgTable).toBeVisible()
    })

    test('create group modal setup field should pre-fill from active selection', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-consumer-groups').click()
        await page.waitForLoadState('networkidle')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)
        await expect(setupSelector.locator('.ant-select-selection-item')).toContainText(SETUP_ID)

        await page.getByTestId('create-group-btn').click()
        await expect(page.locator('.ant-modal')).toBeVisible()

        const modalSetupSelect = page.locator('.ant-modal').getByTestId('create-group-setup-select')
        await expect(modalSetupSelect.locator('.ant-select-selection-item')).toContainText(SETUP_ID)

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

        await page.locator('.ant-modal .ant-btn-primary').click()

        const modal = page.locator('.ant-modal')
        await expect(modal.locator('.ant-form-item-explain-error').first()).toBeVisible({ timeout: 5000 })

        await page.keyboard.press('Escape')
    })

    test('create group should call POST API and refresh table', async ({ page }) => {
        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        const postRequests: string[] = []
        await page.route('**/management/consumer-groups', route => {
            if (route.request().method() === 'POST') {
                postRequests.push(route.request().url())
            }
            return route.continue()
        })

        await page.getByTestId('create-group-btn').click()
        await expect(page.locator('.ant-modal')).toBeVisible()

        const groupName = `test-group-${Date.now()}`
        await page.locator('.ant-modal').getByLabel('Group Name').fill(groupName)

        const modalSetupAncestor = page.locator('.ant-modal').getByTestId('create-group-setup-select')
        await selectAntOption(modalSetupAncestor, SETUP_ID)

        // Fill the queue name LAST: the modal's async setup load / setup selection re-renders the
        // form and clears a queue-name fill made before it (Group Name, filled once up-front, is not
        // affected). Fill after the setup select has settled, and assert the value actually stuck.
        const queueInput = page.getByTestId('create-group-queue-input')
        await queueInput.fill(cgQueue)
        await expect(queueInput).toHaveValue(cgQueue)

        await page.locator('.ant-modal .ant-btn-primary').click()

        await page.waitForTimeout(1500)
        expect(postRequests.length, 'POST /management/consumer-groups was not called').toBeGreaterThanOrEqual(1)
    })

    // ── Action menu tests – status-conditional rendering ───────────────────────
    //
    // Uses real consumer groups created in setup test 00.
    // Groups are located by name in the table; no response stubbing.

    /**
     * Navigate to /consumer-groups and open the action dropdown for a specific group.
     * Returns the visible dropdown locator.
     */
    async function openActionMenu(page: any, groupName: string) {
        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        const row = page.locator('.ant-table-row').filter({ hasText: groupName })
        await expect(row).toBeVisible({ timeout: 10000 })

        const actionBtn = row.getByRole('button').last()
        await expect(actionBtn).toBeVisible()
        await actionBtn.click()

        const dropdown = page.locator('.ant-dropdown').filter({ hasNot: page.locator('.ant-dropdown-hidden') }).last()
        await expect(dropdown).toBeVisible({ timeout: 3000 })
        return dropdown
    }

    test('action menu for active group shows Pause and Start Backfill, not Resume', async ({ page }) => {
        const dropdown = await openActionMenu(page, cgActive)

        await expect(dropdown.getByText('Pause Group')).toBeVisible()
        await expect(dropdown.getByText('Start Backfill')).toBeVisible()
        await expect(dropdown.getByText('Resume Group')).not.toBeVisible()

        await page.keyboard.press('Escape')
    })

    test('action menu for paused group shows Resume and Start Backfill, not Pause', async ({ page }) => {
        // Pause the group via API before checking the menu
        const pauseResp = await page.request.post(
            `/api/v1/management/consumer-groups/${SETUP_ID}/${cgQueue}/${cgPause}/pause`
        )
        if (!pauseResp.ok()) throw new Error(`Pause failed: ${pauseResp.status()} ${await pauseResp.text()}`)

        const dropdown = await openActionMenu(page, cgPause)

        await expect(dropdown.getByText('Resume Group')).toBeVisible()
        await expect(dropdown.getByText('Start Backfill')).toBeVisible()
        await expect(dropdown.getByText('Pause Group')).not.toBeVisible()

        await page.keyboard.press('Escape')
    })

    /**
     * Intercept GET /api/v1/management/consumer-groups and inject a single group
     * with an IN_PROGRESS backfill (400 of 1000 messages processed).
     *
     * ── NO-MOCK POLICY EXCEPTION (hard-to-produce-state injection) ──────────
     * Sanctioned exception to the no-mock rule (decision 2026-06-15). A real
     * backfill runs as a single 10k-message batch with no inter-batch delay, so
     * its IN_PROGRESS window is too short to observe reliably through the UI.
     * There is no real-backend way to hold the UI on the IN_PROGRESS state, so
     * the listing response is injected to exercise the IN_PROGRESS rendering
     * path. This is deliberate state injection, NOT data mocking. The real
     * listing fields (backfillStatus, progress counts, timestamps) are covered
     * backend-side by ManagementApiIntegrationTest test 20.
     */
    async function routeBackfillInProgressListing(page: Page, groupName: string, queueName: string) {
        const now = new Date().toISOString()
        const listing = {
            consumerGroups: [{
                name: groupName,
                setup: SETUP_ID,
                queueName,
                implementationType: 'NATIVE_QUEUE',
                members: 1,
                status: 'active',
                partition: 0,
                lag: 0,
                subscribedAt: now,
                lastActiveAt: now,
                lastHeartbeatAt: now,
                backfillStatus: 'IN_PROGRESS',
                backfillProcessedMessages: 400,
                backfillTotalMessages: 1000,
                backfillStartedAt: now,
                backfillCompletedAt: null,
                createdAt: now,
            }],
        }
        await page.route('**/api/v1/management/consumer-groups', (route: Route) => {
            if (route.request().method() === 'GET') {
                route.fulfill({
                    status: 200,
                    contentType: 'application/json',
                    body: JSON.stringify(listing),
                })
            } else {
                route.continue()
            }
        })
    }

    test('action menu for group with backfill IN_PROGRESS hides Start Backfill', async ({ page }) => {
        const stubGroup = `cg_backfill_stub_${Date.now()}`
        await routeBackfillInProgressListing(page, stubGroup, cgQueue)

        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        const row = page.locator('.ant-table-row').filter({ hasText: stubGroup })
        await expect(row).toBeVisible({ timeout: 10000 })

        // The Backfill column renders the IN_PROGRESS tag
        await expect(row.locator('.ant-tag').filter({ hasText: 'IN_PROGRESS' })).toBeVisible()

        const actionBtn = row.getByRole('button').last()
        await actionBtn.click()

        const dropdown = page.locator('.ant-dropdown').filter({ hasNot: page.locator('.ant-dropdown-hidden') }).last()
        await expect(dropdown).toBeVisible({ timeout: 3000 })

        // Active group keeps Pause, but Start Backfill is hidden while IN_PROGRESS
        await expect(dropdown.getByText('Pause Group')).toBeVisible()
        await expect(dropdown.getByText('Start Backfill')).not.toBeVisible()

        await page.keyboard.press('Escape')
    })

    test('details modal shows backfill progress bar while IN_PROGRESS', async ({ page }) => {
        const stubGroup = `cg_backfill_stub_${Date.now()}`
        await routeBackfillInProgressListing(page, stubGroup, cgQueue)

        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        const row = page.locator('.ant-table-row').filter({ hasText: stubGroup })
        await expect(row).toBeVisible({ timeout: 10000 })

        const actionBtn = row.getByRole('button').last()
        await actionBtn.click()

        const dropdown = page.locator('.ant-dropdown').filter({ hasNot: page.locator('.ant-dropdown-hidden') }).last()
        await dropdown.getByText('View Details').click()

        const modal = page.locator('.ant-modal')
        await expect(modal).toBeVisible()
        await expect(modal.locator('.ant-modal-title')).toContainText('Consumer Group Details')

        // Backfill Details card shows the IN_PROGRESS tag and progress counts
        await expect(modal.getByText('Backfill Details')).toBeVisible()
        await expect(modal.locator('.ant-tag').filter({ hasText: 'IN_PROGRESS' }).first()).toBeVisible()

        // Progress bar renders at processed/total = 400/1000 = 40%
        const progress = modal.locator('.ant-progress')
        await expect(progress).toBeVisible()
        await expect(modal.locator('.ant-progress-text')).toHaveText('40%')

        await modal.locator('.ant-btn').filter({ hasText: 'Close' }).click()
        await expect(modal).not.toBeVisible({ timeout: 3000 })
    })

    test('clicking Pause Group calls POST .../pause endpoint', async ({ page }) => {
        // Ensure cgActive is in active state (resume if it was paused by a prior test)
        const statusResp = await page.request.get('/api/v1/management/consumer-groups')
        const body = await statusResp.json()
        const group = body.consumerGroups?.find((g: any) => g.name === cgActive || g.groupName === cgActive)
        if (group?.status === 'paused') {
            await page.request.post(
                `/api/v1/management/consumer-groups/${SETUP_ID}/${cgQueue}/${cgActive}/resume`
            )
        }

        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        const pauseRequests: string[] = []
        page.on('request', req => {
            if (req.method() === 'POST' && req.url().includes('/pause')) {
                pauseRequests.push(req.url())
            }
        })

        const row = page.locator('.ant-table-row').filter({ hasText: cgActive })
        await expect(row).toBeVisible({ timeout: 10000 })

        const actionBtn = row.getByRole('button').last()
        await actionBtn.click()

        const dropdown = page.locator('.ant-dropdown').filter({ hasNot: page.locator('.ant-dropdown-hidden') }).last()
        await dropdown.getByText('Pause Group').click()

        await page.waitForTimeout(1000)
        expect(pauseRequests.length, 'POST .../pause was not called').toBeGreaterThanOrEqual(1)
        expect(pauseRequests[0]).toContain('/pause')
    })

    test('clicking Resume Group calls POST .../resume endpoint', async ({ page }) => {
        // Ensure cgPause is in paused state
        await page.request.post(
            `/api/v1/management/consumer-groups/${SETUP_ID}/${cgQueue}/${cgPause}/pause`
        )

        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        const resumeRequests: string[] = []
        page.on('request', req => {
            if (req.method() === 'POST' && req.url().includes('/resume')) {
                resumeRequests.push(req.url())
            }
        })

        const row = page.locator('.ant-table-row').filter({ hasText: cgPause })
        await expect(row).toBeVisible({ timeout: 10000 })

        const actionBtn = row.getByRole('button').last()
        await actionBtn.click()

        const dropdown = page.locator('.ant-dropdown').filter({ hasNot: page.locator('.ant-dropdown-hidden') }).last()
        await dropdown.getByText('Resume Group').click()

        await page.waitForTimeout(1000)
        expect(resumeRequests.length, 'POST .../resume was not called').toBeGreaterThanOrEqual(1)
        expect(resumeRequests[0]).toContain('/resume')
    })

    test('clicking Start Backfill calls POST .../backfill endpoint', async ({ page }) => {
        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        const backfillRequests: string[] = []
        page.on('request', req => {
            if (req.method() === 'POST' && req.url().includes('/backfill')) {
                backfillRequests.push(req.url())
            }
        })

        // Wait for any ongoing backfill on cgPause to finish so "Start Backfill" is visible
        const row = page.locator('.ant-table-row').filter({ hasText: cgPause })
        await expect(row).toBeVisible({ timeout: 10000 })

        // Poll until Start Backfill becomes available (backfillStatus != IN_PROGRESS)
        await expect(async () => {
            const actionBtn = row.getByRole('button').last()
            await actionBtn.click()
            const dropdown = page.locator('.ant-dropdown').filter({ hasNot: page.locator('.ant-dropdown-hidden') }).last()
            await expect(dropdown.getByText('Start Backfill')).toBeVisible({ timeout: 2000 })
        }).toPass({ timeout: 30000 })

        const dropdown = page.locator('.ant-dropdown').filter({ hasNot: page.locator('.ant-dropdown-hidden') }).last()
        await dropdown.getByText('Start Backfill').click()

        await page.waitForTimeout(1000)
        expect(backfillRequests.length, 'POST .../backfill was not called').toBeGreaterThanOrEqual(1)
        expect(backfillRequests[0]).toContain('/backfill')
    })

    test('clicking Delete Group calls DELETE endpoint after confirmation', async ({ page }) => {
        await page.goto('/consumer-groups')
        await page.waitForLoadState('networkidle')

        const deleteRequests: string[] = []
        page.on('request', req => {
            if (req.method() === 'DELETE' && req.url().includes('consumer-groups')) {
                deleteRequests.push(req.url())
            }
        })

        const row = page.locator('.ant-table-row').filter({ hasText: cgDelete })
        await expect(row).toBeVisible({ timeout: 10000 })

        const actionBtn = row.getByRole('button').last()
        await actionBtn.click()

        const dropdown = page.locator('.ant-dropdown').filter({ hasNot: page.locator('.ant-dropdown-hidden') }).last()
        await dropdown.getByText('Delete Group').click()

        const confirmBtn = page.locator('.ant-modal-confirm .ant-btn-dangerous')
        await expect(confirmBtn).toBeVisible({ timeout: 3000 })
        await confirmBtn.click()

        // A success toast must appear after deletion (Phase 7)
        await expect(
            page.locator('.ant-message-success').filter({ hasText: cgDelete }).first()
        ).toBeVisible({ timeout: 10000 })

        await page.waitForTimeout(1000)
        expect(deleteRequests.length, 'DELETE request was not called').toBeGreaterThanOrEqual(1)
        expect(deleteRequests[0]).toContain(cgDelete)
    })

    test('clicking View Details opens the details modal', async ({ page }) => {
        const dropdown = await openActionMenu(page, cgActive)
        await dropdown.getByText('View Details').click()

        const modal = page.locator('.ant-modal')
        await expect(modal).toBeVisible()
        await expect(modal.locator('.ant-modal-title')).toContainText('Consumer Group Details')
        await expect(modal.getByText(cgActive)).toBeVisible()

        await modal.locator('.ant-btn').filter({ hasText: 'Close' }).click()
        await expect(modal).not.toBeVisible({ timeout: 3000 })
    })
})
