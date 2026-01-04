import { test, expect } from '../page-objects'

test.describe('Visualization Tab Smoke Test', () => {
  test('should navigate to visualization tab without crashing', async ({ page }) => {
    // Navigate to Event Stores page
    await page.goto('/event-stores')

    // Click on Visualization tab
    const vizTab = page.getByRole('tab', { name: /visualization/i })
    await expect(vizTab).toBeVisible()
    await vizTab.click()

    // Verify tab content is visible and not empty
    // Use data-testid for robust selection instead of text content which might change
    const setupSelect = page.getByTestId('viz-setup-select')
    await expect(setupSelect).toBeVisible()
    
    // Verify the inner tabs (Causation Tree / Aggregate Stream) are rendered
    await expect(page.getByRole('tab', { name: /causation tree/i })).toBeVisible()
    await expect(page.getByRole('tab', { name: /aggregate stream/i })).toBeVisible()
  })
})
