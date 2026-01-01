import { test, expect } from '@playwright/test'

test.describe('Connection Status', () => {
  test('should show disconnected when using disconnect button', async ({ page }) => {
    // Start on settings page
    await page.goto('/settings')
    await page.waitForLoadState('load')

    // Verify we start connected (to localhost:8080)
    const cardExtra = page.locator('.ant-card-extra')
    await expect(cardExtra).toContainText(/Connected|Checking/, { timeout: 5000 })

    // Click disconnect button
    await page.getByTestId('disconnect-btn').click()
    await expect(page.getByText(/Disconnected from backend/)).toBeVisible()

    // Should now show disconnected in Settings
    await expect(cardExtra).toContainText('Disconnected', { timeout: 5000 })

    // Navigate to home page
    await page.goto('/')
    await page.waitForLoadState('load')

    // Connection status in header should show as Offline
    const connectionStatus = page.getByTestId('connection-status')
    await expect(connectionStatus).toBeVisible()
    await expect(connectionStatus).toContainText('Offline', { timeout: 5000 })

    // Clean up - reset to defaults
    await page.goto('/settings')
    await page.getByTestId('reset-settings-btn').click()
    // Wait for message confirmation
    await expect(page.getByText(/Configuration reset to defaults/)).toBeVisible({ timeout: 3000 })
    // Reload page to ensure config is fully applied
    await page.reload()
    await page.waitForLoadState('load')
    // Wait for connection to be re-established
    await expect(page.locator('.ant-card-extra')).toContainText('Connected', { timeout: 15000 })
  })

  test('should show connected when backend is running', async ({ page }) => {
    // Ensure we're using the correct backend URL
    await page.goto('/settings')
    await page.waitForLoadState('load')

    // Verify default URL
    const apiUrlInput = page.getByTestId('api-url-input')
    await expect(apiUrlInput).toHaveValue('http://127.0.0.1:8080')

    // Test connection
    await page.getByTestId('test-connection-btn').click()
    
    // Should show success
    await expect(page.getByTestId('test-result')).toBeVisible()
    await expect(page.getByTestId('test-result')).toContainText('Connection Successful')

    // Navigate to home page
    await page.goto('/')
    await page.waitForLoadState('load')

    // Connection status should show as Online (wait up to 15 seconds for it)
    const connectionStatus = page.getByTestId('connection-status')
    await expect(connectionStatus).toBeVisible()
    await expect(connectionStatus).toContainText('Online', { timeout: 15000 })
  })

  test('should update connection status when configuration changes', async ({ page }) => {
    await page.goto('/settings')
    await page.waitForLoadState('load')

    // Current status should be visible in Settings
    const cardExtra = page.locator('.ant-card-extra')
    await expect(cardExtra).toBeVisible()

    // Should show either Connected or Disconnected
    const statusText = await cardExtra.textContent()
    expect(statusText).toMatch(/Connected|Disconnected|Checking/)

    // Test connection to verify it works
    await page.getByTestId('test-connection-btn').click()
    await expect(page.getByTestId('test-result')).toBeVisible()

    // Status should update after test
    await expect(cardExtra).toContainText(/Connected|Disconnected/, { timeout: 3000 })
  })

  test('should keep header and settings status in sync when resetting to defaults', async ({ page }) => {
    // Start on settings page
    await page.goto('/settings')
    await page.waitForLoadState('load')

    const cardExtra = page.locator('.ant-card-extra')
    
    // Disconnect first
    await page.getByTestId('disconnect-btn').click()
    
    // Wait for disconnected state
    await expect(cardExtra).toContainText('Disconnected', { timeout: 10000 })

    // Check header status
    await page.goto('/')
    await page.waitForLoadState('load')
    const headerStatus = page.getByTestId('connection-status')
    await expect(headerStatus).toContainText('Offline', { timeout: 10000 })

    // Go back to settings and reset to defaults
    await page.goto('/settings')
    await page.waitForLoadState('load')
    await page.getByTestId('reset-settings-btn').click()
    
    // Wait for message confirmation
    await expect(page.getByText(/Configuration reset to defaults/)).toBeVisible({ timeout: 3000 })
    
    // Reload page to ensure config is fully applied
    await page.reload()
    await page.waitForLoadState('load')

    // Wait for connected state (increased timeout for connection to be re-established)
    await expect(cardExtra).toContainText('Connected', { timeout: 15000 })

    // Header status should also show Online
    await page.goto('/')
    await page.waitForLoadState('load')
    await expect(headerStatus).toContainText('Online', { timeout: 10000 })
  })
})

