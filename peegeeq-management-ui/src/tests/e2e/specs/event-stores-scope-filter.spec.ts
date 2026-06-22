import { test, expect } from '../page-objects'
import type { Page } from '@playwright/test'
import { SETUP_ID, TEST_SCHEMA } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'
import * as fs from 'fs'

/**
 * Event Stores Page – Scope Selector Row Count Tests
 *
 * Covers gap:
 *   2. E2E tests select setups in the scope bar but do not assert that the listed
 *      event store rows update to match the selected setup.
 *
 * Why a SECOND setup: with only the shared 'default' setup every store belongs to it,
 * so selecting 'default' can never change the row set — the filter is unobservable. We
 * create a dedicated setup plus one store under each, so a scope selection must INCLUDE
 * that scope's store and EXCLUDE the other's.
 *
 * Pagination: the AntD table shows 10 rows/page. A freshly-created store can land on a
 * later page (not in the DOM), so before any row assertion we raise the page size to
 * render every matching row — otherwise a present row reads as "not visible" and an
 * absent-from-page row falsely reads as "filtered out".
 *
 * Depends on setup-prerequisite (SETUP_ID must exist).
 */
test.describe.configure({ mode: 'serial' })

const SCOPE_SETUP_ID = 'ess_scope_setup'
const stamp = Date.now()
const storeDefault = `ess_scope_default_${stamp}` // created under SETUP_ID ('default')
const storeScoped = `ess_scope_scoped_${stamp}`   // created under SCOPE_SETUP_ID

/** Create one event store under a specific setup via the Create Event Store modal. */
async function createEventStore(page: Page, name: string, setupId: string) {
    await page.goto('/event-stores')
    await page.getByRole('button', { name: /create event store/i }).click()
    await expect(page.locator('.ant-modal')).toBeVisible()

    await page.locator('#name').fill(name)
    // Refresh the setup list so a just-created setup is selectable. selectAntOption waits
    // for the option to load, so no fixed delay is needed.
    await page.getByTestId('refresh-setups-btn').click()
    await selectAntOption(page.locator('.ant-modal .ant-select:has(#setupId)'), setupId)

    await page.locator('.ant-modal .ant-btn-primary').click()
    await expect(page.locator('.ant-modal')).not.toBeVisible({ timeout: 30000 })
    await expect(page.locator('.ant-message-success').first()).toBeVisible({ timeout: 10000 })
}

/**
 * Raise the AntD table page size so every matching row is rendered before asserting —
 * otherwise a row on page 2 reads as "not visible" and an absent row reads as "filtered out".
 *
 * NOTE: this deliberately does NOT use the shared `selectAntOption` helper. After the scope
 * selector is used, its dropdown stays mounted as `.ant-select-dropdown-hidden`, and
 * `selectAntOption`'s `filter({ hasNot: '.ant-select-dropdown-hidden' })` does NOT exclude it
 * (filter({hasNot}) checks descendants, not the element's own class) — so its `.last()` lands
 * on the hidden dropdown. A CSS `:not(.ant-select-dropdown-hidden)` selector excludes
 * self-hidden elements correctly, which is required here.
 */
async function showAllRows(page: Page) {
    // Rows load asynchronously after navigation — page.waitForLoadState('load') does NOT wait
    // for the API row fetch. Wait for the table to actually have rows first; otherwise the
    // size-changer isn't mounted yet and the guard below would no-op.
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible()

    const sizeChanger = page.locator('.ant-pagination-options .ant-select')
    if (!(await sizeChanger.isVisible().catch(() => false))) return // single page — nothing to do

    await sizeChanger.click()
    // CSS :not() correctly excludes self-hidden dropdowns (a closed scope-selector dropdown).
    const openDropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')
    await expect(openDropdown).toBeVisible()
    await openDropdown.getByText('100 / page', { exact: true }).click()

    // Confirm the size actually applied. The selection-item holds ONLY the current value
    // (asserting against the whole .ant-select would wrongly include every option label).
    await expect(sizeChanger.locator('.ant-select-selection-item')).toHaveText('100 / page')
}

/** Clear any scope selection left over from a previous test. */
async function clearScope(page: Page) {
    const setupSelector = page.getByTestId('setup-scope-selector')
    await setupSelector.hover()
    const clearBtn = setupSelector.locator('.ant-select-clear')
    if (await clearBtn.isVisible().catch(() => false)) {
        await clearBtn.click()
    }
}

test.describe('Event Stores – Scope Selector Row Count', () => {

    // ── 0. Setup: a second setup + one store under each ──────────────────────────

    test('00 setup: create a dedicated setup and one store under each setup', async ({ page, databaseSetupsPage }) => {
        test.setTimeout(180000)

        // Create the dedicated second setup (idempotent) using the TestContainers DB.
        const dbConfig = JSON.parse(fs.readFileSync('testcontainers-db.json', 'utf8'))
        // Load the app shell first so the sidebar exists — databaseSetupsPage.goto()
        // navigates by clicking the sidebar nav item (mirrors setup-prerequisite.spec.ts).
        await page.goto('/')
        await databaseSetupsPage.goto()
        if (!(await databaseSetupsPage.setupExists(SCOPE_SETUP_ID))) {
            await databaseSetupsPage.createSetup({
                setupId: SCOPE_SETUP_ID,
                host: dbConfig.host,
                port: dbConfig.port,
                databaseName: `e2e_scope_db_${stamp}`,
                username: dbConfig.username,
                password: dbConfig.password,
                schema: TEST_SCHEMA
            })
        }

        await createEventStore(page, storeDefault, SETUP_ID)
        await createEventStore(page, storeScoped, SCOPE_SETUP_ID)
    })

    // ── 1. No scope selected – stores from both setups visible ───────────────────

    test('01 without a setup selected the table shows stores from both setups', async ({ page }) => {
        await page.goto('/event-stores')
        await page.waitForLoadState('load')
        await clearScope(page)
        await showAllRows(page)

        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        await expect(rows.filter({ hasText: storeDefault })).toBeVisible()
        await expect(rows.filter({ hasText: storeScoped })).toBeVisible()
    })

    // ── 2. SETUP_ID selected – only its stores, the other setup's store excluded ──

    test("02 selecting SETUP_ID shows only that setup's stores", async ({ page }) => {
        await page.goto('/event-stores')
        await page.waitForLoadState('load')

        await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)
        await showAllRows(page)

        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        // The 'default' store is present; the other setup's store is filtered OUT.
        await expect(rows.filter({ hasText: storeDefault })).toBeVisible()
        await expect(rows.filter({ hasText: storeScoped })).toHaveCount(0)

        // Every visible row must belong to SETUP_ID (rendered as a tag in the row).
        const rowCount = await rows.count()
        for (let i = 0; i < rowCount; i++) {
            await expect(rows.nth(i).locator('.ant-tag').filter({ hasText: SETUP_ID })).toBeVisible()
        }
    })

    // ── 3. Dedicated setup selected – only its store ─────────────────────────────

    test('03 selecting the dedicated setup shows only its store', async ({ page }) => {
        await page.goto('/event-stores')
        await page.waitForLoadState('load')

        await selectAntOption(page.getByTestId('setup-scope-selector'), SCOPE_SETUP_ID)
        await showAllRows(page)

        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        await expect(rows.filter({ hasText: storeScoped })).toBeVisible()
        await expect(rows.filter({ hasText: storeDefault })).toHaveCount(0)
    })

    // ── 4. Clearing the scope restores stores from both setups ───────────────────

    test('04 clearing the setup selection restores stores from both setups', async ({ page }) => {
        await page.goto('/event-stores')
        await page.waitForLoadState('load')

        await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)
        await showAllRows(page)
        await expect(
            page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: storeScoped })
        ).toHaveCount(0)

        await clearScope(page)
        await showAllRows(page)

        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        await expect(rows.filter({ hasText: storeDefault })).toBeVisible()
        await expect(rows.filter({ hasText: storeScoped })).toBeVisible()
    })
})
