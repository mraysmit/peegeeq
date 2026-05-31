import { test, expect } from '../page-objects'

test.describe('Visualization Smoke Test', () => {
  test('should show causation tree and aggregate stream cards without tabs', async ({ page }) => {
    await page.goto('/event-stores')

    // Visualization sections are now always-visible stacked cards — no tab click needed
    await expect(page.getByRole('heading', { name: /causation tree/i })).toBeVisible()
    await expect(page.getByRole('heading', { name: /aggregate stream/i })).toBeVisible()
  })
})
