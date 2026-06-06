import { test, expect } from '../page-objects'

test.describe('Visualization Smoke Tests', () => {
  test('should show Causation Tree heading on /causation-tree', async ({ page }) => {
    await page.goto('/causation-tree')
    await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Select Event Store' })).toBeVisible()
    await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Causation Tree' })).toBeVisible()
  })

  test('should show Aggregate Stream heading on /aggregate-stream', async ({ page }) => {
    await page.goto('/aggregate-stream')
    await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Select Event Store' })).toBeVisible()
    await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Aggregate Stream' })).toBeVisible()
  })
})
