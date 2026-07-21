import { test, expect } from '@playwright/test'
import type { Route } from '@playwright/test'

/**
 * Database Setups — per-row details-failure path.
 *
 * A setup whose GET /api/v1/setups/:setupId details call fails must render
 * honestly: status UNAVAILABLE with unknown ("—") counts — never a
 * healthy-looking "ACTIVE / 0 queues" row (no-error-swallowing rule).
 * The same defect was fixed in utilities-ui SetupsPage (commit ce0798a8);
 * this spec covers the management-ui sweep of that defect class.
 *
 * Standalone: no TestContainers dependency — all backend traffic is intercepted.
 *
 * ── NO-MOCK POLICY EXCEPTION (fault injection) ─────────────────────────────
 * The project rule is "no mocking": tests hit the real backend. This spec is
 * covered by the sanctioned exception (decision 2026-06-15, see
 * api-error-paths.spec.ts). A healthy backend will not fail a details lookup
 * for a setup it just listed, so the only way to exercise this UI path is to
 * INJECT the failure. This is deliberate fault injection, NOT data mocking —
 * do not copy this pattern to stand in for real data the backend can serve.
 */

test.describe('Database Setups — details lookup failure', () => {
  test.beforeEach(async ({ page }) => {
    // List: two setups. `*` does not cross `/`, so this glob only matches the
    // list endpoint, never the per-setup details endpoint below.
    await page.route('**/api/v1/setups', (route: Route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ count: 2, setupIds: ['good-setup', 'broken-setup'] }),
      })
    })

    // Details: good-setup succeeds, broken-setup fails with 500.
    await page.route('**/api/v1/setups/*', (route: Route) => {
      if (route.request().url().endsWith('/good-setup')) {
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            setupId: 'good-setup',
            status: 'ACTIVE',
            queueFactories: ['orders', 'events'],
            eventStores: ['audit'],
          }),
        })
      } else {
        route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'details fetch failed' }),
        })
      }
    })
  })

  test('failed details row renders UNAVAILABLE with unknown counts, not a fabricated healthy row', async ({ page }) => {
    await page.goto('/database-setups')

    const table = page.getByTestId('database-setups-table')
    const brokenRow = table.locator('.ant-table-row').filter({ hasText: 'broken-setup' })
    const goodRow = table.locator('.ant-table-row').filter({ hasText: 'good-setup' })

    await expect(brokenRow).toBeVisible({ timeout: 10000 })
    await expect(goodRow).toBeVisible()

    // The failed row must NOT masquerade as a healthy empty setup.
    await expect(brokenRow.locator('.ant-tag').filter({ hasText: 'UNAVAILABLE' })).toBeVisible()
    await expect(brokenRow.locator('.ant-tag').filter({ hasText: 'ACTIVE' })).toHaveCount(0)

    // Its counts are unknown, not zero: two em-dash tags (queues + event stores).
    await expect(brokenRow.locator('.ant-tag').filter({ hasText: '—' })).toHaveCount(2)
    await expect(brokenRow.locator('.ant-tag').filter({ hasText: /^0$/ })).toHaveCount(0)

    // The healthy row is unaffected: ACTIVE with real counts.
    await expect(goodRow.locator('.ant-tag').filter({ hasText: 'ACTIVE' })).toBeVisible()
    await expect(goodRow.locator('.ant-tag').filter({ hasText: /^2$/ })).toBeVisible() // queues
    await expect(goodRow.locator('.ant-tag').filter({ hasText: /^1$/ })).toBeVisible() // event stores
  })
})
