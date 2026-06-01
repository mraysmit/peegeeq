import { test, expect } from '../page-objects'

test.describe('Visualization Smoke Test', () => {
  test('should show causation tree and aggregate stream cards without tabs', async ({ page }) => {
    await page.goto('/event-visualization')

    // Visualization sections are now always-visible stacked cards — no tab click needed
    // Ant Design Card titles render in div.ant-card-head-title, not semantic <h1>/<h2> elements
    await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Causation Tree' })).toBeVisible()
    await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Aggregate Stream' })).toBeVisible()
  })
})
