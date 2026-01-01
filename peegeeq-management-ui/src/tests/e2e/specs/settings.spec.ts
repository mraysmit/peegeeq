import { test, expect } from '@playwright/test'

test.describe('Settings', () => {
  test.describe('Navigation', () => {
    test('should navigate to settings from user menu', async ({ page }) => {
      await page.goto('/')
      // Wait for header to be visible instead of networkidle (Overview has persistent WebSocket/SSE)
      await page.getByTestId('app-header').waitFor({ state: 'visible' })

      // Click user menu
      await page.getByTestId('user-menu-btn').click()

      // Click settings menu item
      await page.getByText('Settings').click()

      // Should navigate to settings page
      await expect(page).toHaveURL('/settings')
      await expect(page.getByText('Backend Connection')).toBeVisible()
    })
  })

  test.describe('Backend Configuration', () => {
    test('should show connection status in header', async ({ page }) => {
      await page.goto('/')
      // Wait for header to be visible instead of networkidle (Overview has persistent WebSocket/SSE)
      await page.getByTestId('app-header').waitFor({ state: 'visible' })

      // Connection status should be visible
      const connectionStatus = page.getByTestId('connection-status')
      await expect(connectionStatus).toBeVisible()

      // Should show either Online or Offline
      const statusText = await connectionStatus.textContent()
      expect(statusText).toMatch(/Online|Offline/)
    })

    test('should display settings form with default values', async ({ page }) => {
      await page.goto('/settings')
      await page.waitForLoadState('load')

      // Form should be visible
      await expect(page.getByTestId('settings-form')).toBeVisible()

      // API URL input should have default value
      const apiUrlInput = page.getByTestId('api-url-input')
      await expect(apiUrlInput).toBeVisible()
      await expect(apiUrlInput).toHaveValue('http://127.0.0.1:8080')

      // WebSocket URL input should have default value
      const wsUrlInput = page.getByTestId('ws-url-input')
      await expect(wsUrlInput).toBeVisible()
      await expect(wsUrlInput).toHaveValue('ws://127.0.0.1:8080')
    })

    test('should test connection successfully', async ({ page }) => {
      await page.goto('/settings')
      await page.waitForLoadState('load')

      // Click test connection button
      await page.getByTestId('test-connection-btn').click()

      // Should show success message
      const testResult = page.getByTestId('test-result')
      await expect(testResult).toBeVisible()
      await expect(testResult).toContainText('Connection Successful')
    })

    test('should save configuration', async ({ page }) => {
      await page.goto('/settings')
      await page.waitForLoadState('load')

      // Change API URL
      const apiUrlInput = page.getByTestId('api-url-input')
      await apiUrlInput.clear()
      await apiUrlInput.fill('http://localhost:9090')

      // Save configuration
      await page.getByTestId('save-settings-btn').click()

      // Should show success message
      await expect(page.getByText(/Configuration saved successfully/)).toBeVisible()

      // Reload page and verify saved value
      await page.reload()
      await expect(page.getByTestId('api-url-input')).toHaveValue('http://localhost:9090')

      // Reset to defaults for cleanup
      await page.getByTestId('reset-settings-btn').click()
      await expect(page.getByTestId('api-url-input')).toHaveValue('http://127.0.0.1:8080')
    })

    test('should reset configuration to defaults', async ({ page }) => {
      await page.goto('/settings')
      await page.waitForLoadState('load')

      // Change API URL
      const apiUrlInput = page.getByTestId('api-url-input')
      await apiUrlInput.clear()
      await apiUrlInput.fill('http://custom-server.com')

      // Reset configuration
      await page.getByTestId('reset-settings-btn').click()

      // Should show info message
      await expect(page.getByText(/Configuration reset to defaults/)).toBeVisible()

      // Should have default value
      await expect(apiUrlInput).toHaveValue('http://127.0.0.1:8080')
    })

    test('should validate required fields', async ({ page }) => {
      await page.goto('/settings')
      await page.waitForLoadState('load')

      // Clear API URL
      const apiUrlInput = page.getByTestId('api-url-input')
      await apiUrlInput.clear()

      // Try to save
      await page.getByTestId('save-settings-btn').click()

      // Should show validation error
      await expect(page.getByText('Please enter the API URL')).toBeVisible()
    })

    test('should validate URL format', async ({ page }) => {
      await page.goto('/settings')
      await page.waitForLoadState('load')

      // Enter invalid URL
      const apiUrlInput = page.getByTestId('api-url-input')
      await apiUrlInput.clear()
      await apiUrlInput.fill('not-a-valid-url')

      // Try to save
      await page.getByTestId('save-settings-btn').click()

      // Should show validation error
      await expect(page.getByText('Please enter a valid URL')).toBeVisible()
    })

    test('should show warning when testing without URL', async ({ page }) => {
      await page.goto('/settings')
      await page.waitForLoadState('load')

      // Clear API URL
      const apiUrlInput = page.getByTestId('api-url-input')
      await apiUrlInput.clear()

      // Try to test connection
      await page.getByTestId('test-connection-btn').click()

      // Should show warning message
      await expect(page.getByText(/Please enter an API URL first/)).toBeVisible()
    })
  })
})

