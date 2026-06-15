import { test, expect } from '../page-objects'

/**
 * Database Setups – Empty State (real backend, NO mock)
 *
 * The empty state is the genuine first-run condition: a fresh PeeGeeQ system has
 * zero database setups. The global setup (global-setup-testcontainers.ts) deletes
 * every existing setup at the start of every run, so the backend genuinely reports
 * an empty list at this point.
 *
 * This spec is registered as project `0-setup-empty-state` with NO dependencies and
 * is placed FIRST in playwright.config.ts, so it runs before `3c-setup-prerequisite`
 * (or any standalone spec) creates a setup. It asserts the real
 * GET /api/v1/setups empty response and the resulting empty-state alert — no
 * route interception.
 *
 * If this test fails with "expected length 0", it means a setup existed when it ran
 * — i.e. it did not run first. That is a real ordering signal, not a mock artifact.
 */
test.describe('Database Setups – Empty State', () => {

  test('shows the empty-state alert when the system has no setups', async ({ page, databaseSetupsPage }) => {
    // The real backend reports zero setups at first run (global setup cleaned them up)
    const resp = await page.request.get('/api/v1/setups')
    expect(resp.ok()).toBeTruthy()
    const body = await resp.json()
    expect(body.setupIds ?? []).toHaveLength(0)

    await page.goto('/database-setups')
    await page.waitForLoadState('networkidle')

    // The page renders the empty-state alert because the real list is empty
    await expect(databaseSetupsPage.getEmptyStateAlert()).toBeVisible({ timeout: 10000 })

    // And there are no rows in the table
    await expect(databaseSetupsPage.getSetupsTable().locator('tbody .ant-table-row')).toHaveCount(0)
  })
})
