import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Event Stores Page – Scope Selector Row Count Tests
 *
 * Covers gap:
 *   2. E2E tests select setups in the scope bar but do not assert that the
 *      listed event store rows update to match the selected setup.
 *
 * Strategy: create two event stores under SETUP_ID via the UI, then:
 *   - With no setup selected: all event stores visible
 *   - With SETUP_ID selected: only that setup's stores visible
 *   - With SETUP_ID cleared: row count reverts to showing all stores
 *
 * Depends on setup-prerequisite (SETUP_ID must exist).
 */
test.describe.configure({ mode: 'serial' })

test.describe('Event Stores – Scope Selector Row Count', () => {

    const storeName1 = `ess_scope_a_${Date.now()}`
    const storeName2 = `ess_scope_b_${Date.now()}`

    // ── 0. Setup ──────────────────────────────────────────────────────────────

    test('00 setup: create two event stores under SETUP_ID', async ({ page }) => {
        test.setTimeout(120000)

        for (const name of [storeName1, storeName2]) {
            await page.goto('/event-stores')
            await page.getByRole('button', { name: /create event store/i }).click()
            await expect(page.locator('.ant-modal')).toBeVisible()

            await page.locator('#name').fill(name)
            await page.getByTestId('refresh-setups-btn').click()
            await page.waitForTimeout(600)
            await selectAntOption(page.locator('.ant-modal .ant-select:has(#setupId)'), SETUP_ID)

            await page.locator('.ant-modal .ant-btn-primary').click()
            await expect(page.locator('.ant-modal')).not.toBeVisible({ timeout: 30000 })
            await expect(page.locator('.ant-message-success').first()).toBeVisible({ timeout: 10000 })

            console.log(`Created event store: ${name}`)
        }
    })

    // ── 1. Baseline – no setup selected ──────────────────────────────────────

    test('01 without a setup selected the event stores table shows all stores', async ({ page }) => {
        await page.goto('/event-stores')
        await page.waitForLoadState('networkidle')

        // Clear any scope selection left from previous tests
        const setupSelector = page.getByTestId('setup-scope-selector')
        await setupSelector.hover()
        const clearBtn = setupSelector.locator('.ant-select-clear')
        if (await clearBtn.isVisible()) {
            await clearBtn.click()
        }

        // Table header counts all stores — at least the two we created
        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        await expect(rows.first()).toBeVisible({ timeout: 10000 })

        const totalCount = await rows.count()
        expect(totalCount).toBeGreaterThanOrEqual(2)

        console.log(`Total event stores without scope filter: ${totalCount}`)
    })

    // ── 2. With SETUP_ID selected ─────────────────────────────────────────────

    test("02 selecting SETUP_ID filters the table to that setup's stores", async ({ page }) => {
        await page.goto('/event-stores')
        await page.waitForLoadState('networkidle')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        await expect(rows.first()).toBeVisible({ timeout: 10000 })

        // Every row must belong to SETUP_ID
        const rowCount = await rows.count()
        for (let i = 0; i < rowCount; i++) {
            // The setup ID is rendered as a blue tag in each row
            await expect(rows.nth(i).locator('.ant-tag').filter({ hasText: SETUP_ID })).toBeVisible()
        }

        // Both stores we created for SETUP_ID must appear
        await expect(rows.filter({ hasText: storeName1 })).toBeVisible()
        await expect(rows.filter({ hasText: storeName2 })).toBeVisible()

        console.log(`Event stores for setup "${SETUP_ID}": ${rowCount}`)
    })

    // ── 3. Clearing the scope restores all stores ──────────────────────────────

    test('03 clearing the setup selection restores the full event store list', async ({ page }) => {
        await page.goto('/event-stores')
        await page.waitForLoadState('networkidle')

        const setupSelector = page.getByTestId('setup-scope-selector')

        // Apply filter
        await selectAntOption(setupSelector, SETUP_ID)
        await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 10000 })
        const filteredCount = await page.locator('.ant-table-tbody tr.ant-table-row').count()

        // Clear filter
        await setupSelector.hover()
        const clearBtn = setupSelector.locator('.ant-select-clear')
        await expect(clearBtn).toBeVisible()
        await clearBtn.click()

        // Row count should be ≥ filtered count (all stores visible again)
        await page.waitForTimeout(500)
        const totalCount = await page.locator('.ant-table-tbody tr.ant-table-row').count()
        expect(totalCount).toBeGreaterThanOrEqual(filteredCount)

        console.log(`After clearing scope: ${filteredCount} → ${totalCount} rows`)
    })
})
