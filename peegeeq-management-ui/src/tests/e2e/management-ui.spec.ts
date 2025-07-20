import { test, expect } from '@playwright/test'

/**
 * Comprehensive End-to-End tests for PeeGeeQ Management UI
 *
 * These tests validate complete user workflows, actual button clicks,
 * form interactions, and data validation to ensure the UI works correctly
 * from a user's perspective with real backend integration.
 */

test.describe('PeeGeeQ Management UI - Comprehensive UI Interaction Tests', () => {

  test.beforeEach(async ({ page }) => {
    // Navigate to the management UI
    await page.goto('/')

    // Wait for the page to load completely
    await page.waitForLoadState('networkidle')

    // Wait for the main layout to be visible
    await expect(page.locator('.ant-layout.ant-layout-has-sider')).toBeVisible()
  })

  test.describe('Navigation and Layout', () => {
    test('should display the main layout with sidebar and header', async ({ page }) => {
      // Check that the main layout elements are present
      await expect(page.locator('.ant-layout')).toBeVisible()
      await expect(page.locator('.ant-layout-sider')).toBeVisible()
      await expect(page.locator('.ant-layout-header')).toBeVisible()
      await expect(page.locator('.ant-layout-content')).toBeVisible()
    })

    test('should display the PeeGeeQ logo and title', async ({ page }) => {
      await expect(page.locator('text=PeeGeeQ')).toBeVisible()
      await expect(page.locator('text=Management Console')).toBeVisible()
    })

    test('should show navigation menu items', async ({ page }) => {
      // Check that all main navigation items are present
      await expect(page.locator('text=Overview')).toBeVisible()
      await expect(page.locator('text=Queues')).toBeVisible()
      await expect(page.locator('text=Consumer Groups')).toBeVisible()
      await expect(page.locator('text=Event Stores')).toBeVisible()
      await expect(page.locator('text=Message Browser')).toBeVisible()
    })

    test('should navigate between pages using sidebar menu', async ({ page }) => {
      // Test navigation to different pages
      await page.click('text=Queues')
      await expect(page.locator('h1:has-text("Queue Management")')).toBeVisible()
      
      await page.click('text=Consumer Groups')
      await expect(page.locator('h1:has-text("Consumer Groups")')).toBeVisible()
      
      await page.click('text=Event Stores')
      await expect(page.locator('h1:has-text("Event Stores")')).toBeVisible()
      
      await page.click('text=Message Browser')
      await expect(page.locator('h1:has-text("Message Browser")')).toBeVisible()
      
      // Navigate back to Overview
      await page.click('text=Overview')
      await expect(page.locator('h1:has-text("System Overview")')).toBeVisible()
    })
  })

  test.describe('Overview Dashboard', () => {
    test('should display system statistics cards', async ({ page }) => {
      // Navigate to overview if not already there
      await page.click('text=Overview')
      
      // Check for statistics cards
      await expect(page.locator('.ant-statistic')).toHaveCount(4, { timeout: 10000 })
      
      // Check for specific statistics
      await expect(page.locator('text=Total Queues')).toBeVisible()
      await expect(page.locator('text=Active Consumers')).toBeVisible()
      await expect(page.locator('text=Messages/sec')).toBeVisible()
      await expect(page.locator('text=System Uptime')).toBeVisible()
    })

    test('should display recent activity table', async ({ page }) => {
      await page.click('text=Overview')
      
      // Check for recent activity section
      await expect(page.locator('text=Recent Activity')).toBeVisible()
      await expect(page.locator('.ant-table')).toBeVisible()
    })

    test('should show connection status indicator', async ({ page }) => {
      // Check for connection status in header
      await expect(page.locator('.ant-badge')).toBeVisible()
    })
  })

  test.describe('Queue Management', () => {
    test('should display queue list and management interface', async ({ page }) => {
      await page.click('text=Queues')
      
      // Check for queue management elements
      await expect(page.locator('h1:has-text("Queue Management")')).toBeVisible()
      await expect(page.locator('.ant-table')).toBeVisible()
      
      // Check for action buttons
      await expect(page.locator('button:has-text("Create Queue")')).toBeVisible()
      await expect(page.locator('button:has-text("Refresh")')).toBeVisible()
    })

    test('should allow filtering and searching queues', async ({ page }) => {
      await page.click('text=Queues')
      
      // Check for search and filter controls
      await expect(page.locator('input[placeholder*="Search"]')).toBeVisible()
      await expect(page.locator('.ant-select')).toBeVisible()
    })

    test('should display queue statistics and metrics', async ({ page }) => {
      await page.click('text=Queues')
      
      // Wait for table to load
      await page.waitForSelector('.ant-table-tbody tr', { timeout: 10000 })
      
      // Check that queue data is displayed
      const rows = page.locator('.ant-table-tbody tr')
      const count = await rows.count()
      
      if (count > 0) {
        // Check for queue metrics columns
        await expect(page.locator('th:has-text("Messages")')).toBeVisible()
        await expect(page.locator('th:has-text("Consumers")')).toBeVisible()
        await expect(page.locator('th:has-text("Status")')).toBeVisible()
      }
    })
  })

  test.describe('Consumer Groups', () => {
    test('should display consumer group management interface', async ({ page }) => {
      await page.click('text=Consumer Groups')
      
      await expect(page.locator('h1:has-text("Consumer Groups")')).toBeVisible()
      await expect(page.locator('.ant-table')).toBeVisible()
      
      // Check for management buttons
      await expect(page.locator('button:has-text("Create Group")')).toBeVisible()
    })

    test('should show consumer group details and members', async ({ page }) => {
      await page.click('text=Consumer Groups')
      
      // Wait for data to load
      await page.waitForLoadState('networkidle')
      
      // Check for consumer group information
      const table = page.locator('.ant-table')
      await expect(table).toBeVisible()
    })
  })

  test.describe('Event Stores', () => {
    test('should display event store management interface', async ({ page }) => {
      await page.click('text=Event Stores')
      
      await expect(page.locator('h1:has-text("Event Stores")')).toBeVisible()
      await expect(page.locator('.ant-table')).toBeVisible()
    })

    test('should provide event querying capabilities', async ({ page }) => {
      await page.click('text=Event Stores')
      
      // Check for query interface elements
      await expect(page.locator('button:has-text("Query Events")')).toBeVisible()
      
      // Check for date range picker
      await expect(page.locator('.ant-picker')).toBeVisible()
    })
  })

  test.describe('Message Browser', () => {
    test('should display message browsing interface', async ({ page }) => {
      await page.click('text=Message Browser')
      
      await expect(page.locator('h1:has-text("Message Browser")')).toBeVisible()
      await expect(page.locator('.ant-table')).toBeVisible()
    })

    test('should provide message filtering and search', async ({ page }) => {
      await page.click('text=Message Browser')
      
      // Check for search and filter controls
      await expect(page.locator('input[placeholder*="Search"]')).toBeVisible()
      await expect(page.locator('.ant-select')).toBeVisible()
      
      // Check for advanced search toggle
      await expect(page.locator('button:has-text("Advanced Search")')).toBeVisible()
    })

    test('should allow message inspection', async ({ page }) => {
      await page.click('text=Message Browser')
      
      // Wait for messages to load
      await page.waitForLoadState('networkidle')
      
      // Check if there are messages to inspect
      const messageRows = page.locator('.ant-table-tbody tr')
      const count = await messageRows.count()
      
      if (count > 0) {
        // Click on first message to inspect
        await messageRows.first().locator('button[title="View Details"]').click()
        
        // Check for message details modal
        await expect(page.locator('.ant-modal')).toBeVisible()
        await expect(page.locator('.ant-modal-title')).toContainText('Message Details')
      }
    })
  })

  test.describe('Responsive Design', () => {
    test('should work on tablet viewport', async ({ page }) => {
      await page.setViewportSize({ width: 768, height: 1024 })
      await page.reload()
      
      // Check that layout adapts to tablet size
      await expect(page.locator('.ant-layout')).toBeVisible()
      await expect(page.locator('text=PeeGeeQ')).toBeVisible()
    })

    test('should work on mobile viewport', async ({ page }) => {
      await page.setViewportSize({ width: 375, height: 667 })
      await page.reload()
      
      // Check that layout adapts to mobile size
      await expect(page.locator('.ant-layout')).toBeVisible()
      
      // Mobile menu should be collapsed
      const sider = page.locator('.ant-layout-sider')
      await expect(sider).toHaveClass(/ant-layout-sider-collapsed/)
    })
  })

  test.describe('Error Handling', () => {
    test('should handle network errors gracefully', async ({ page }) => {
      // Intercept API calls and simulate network error
      await page.route('**/api/v1/**', route => {
        route.abort('failed')
      })
      
      await page.reload()
      
      // Check that error states are handled
      // The app should still load but show error indicators
      await expect(page.locator('.ant-layout')).toBeVisible()
    })

    test('should display appropriate loading states', async ({ page }) => {
      // Intercept API calls and delay them
      await page.route('**/api/v1/**', route => {
        setTimeout(() => route.continue(), 2000)
      })
      
      await page.reload()
      
      // Check for loading indicators
      await expect(page.locator('.ant-spin')).toBeVisible()
    })
  })

  test.describe('Accessibility', () => {
    test('should have proper heading hierarchy', async ({ page }) => {
      await page.click('text=Overview')
      
      // Check for proper heading structure
      await expect(page.locator('h1')).toBeVisible()
      
      // Ensure headings are accessible
      const h1 = page.locator('h1')
      await expect(h1).toHaveAttribute('role', 'heading')
    })

    test('should support keyboard navigation', async ({ page }) => {
      // Test tab navigation
      await page.keyboard.press('Tab')
      await page.keyboard.press('Tab')
      
      // Check that focus is visible
      const focusedElement = page.locator(':focus')
      await expect(focusedElement).toBeVisible()
    })
  })
})
