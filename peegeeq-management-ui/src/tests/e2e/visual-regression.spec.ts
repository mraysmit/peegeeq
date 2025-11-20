import { test, expect } from '@playwright/test'

/**
 * Visual Regression Tests for PeeGeeQ Management UI
 * 
 * These tests ensure that:
 * - UI components render correctly and consistently
 * - Layout remains stable across different screen sizes
 * - Visual elements maintain their appearance
 * - No unexpected visual changes occur
 * 
 * Note: Visual regression tests may need baseline updates when:
 * - UI components are intentionally changed
 * - Data format changes affect rendering
 * - Font rendering differs across environments
 * 
 * To update baselines: npx playwright test --update-snapshots
 */

test.describe('PeeGeeQ Management UI - Visual Regression Tests', () => {
  
  test.beforeEach(async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    
    // Wait for main layout to be stable
    await expect(page.locator('.ant-layout.ant-layout-has-sider')).toBeVisible()
    
    // Wait for any loading states to complete and animations to settle
    await page.waitForTimeout(3000)
  })

  test.describe('Layout and Navigation Screenshots', () => {
    test('should render main layout correctly', async ({ page }) => {
      // Take screenshot of the main layout
      await expect(page).toHaveScreenshot('main-layout.png', {
        fullPage: true,
        animations: 'disabled'
      })
    })

    test('should render sidebar navigation correctly', async ({ page }) => {
      // Focus on sidebar
      const sidebar = page.locator('.ant-layout-sider')
      await expect(sidebar).toHaveScreenshot('sidebar-navigation.png')
    })

    test('should render header correctly', async ({ page }) => {
      // Focus on header
      const header = page.locator('.ant-layout-header')
      await expect(header).toHaveScreenshot('header-layout.png')
    })
  })

  test.describe('Page-specific Visual Tests', () => {
    test('should render Overview page correctly', async ({ page }) => {
      await page.click('text=Overview')
      await page.waitForLoadState('networkidle')
      
      // Wait for statistics to load
      await expect(page.locator('.ant-statistic')).toHaveCount(4, { timeout: 10000 })
      
      // Take screenshot of overview page
      await expect(page).toHaveScreenshot('overview-page.png', {
        fullPage: true,
        animations: 'disabled'
      })
    })

    test('should render Queues page correctly', async ({ page }) => {
      await page.click('text=Queues')
      await page.waitForLoadState('networkidle')
      
      // Wait for table to load
      await expect(page.locator('.ant-table-tbody').first()).toBeVisible()
      
      // Take screenshot of queues page
      await expect(page).toHaveScreenshot('queues-page.png', {
        fullPage: true,
        animations: 'disabled'
      })
    })

    test('should render Consumer Groups page correctly', async ({ page }) => {
      await page.click('text=Consumer Groups')
      await page.waitForLoadState('networkidle')
      
      // Wait for content to load
      await expect(page.locator('.ant-table-tbody').first()).toBeVisible()
      
      // Take screenshot
      await expect(page).toHaveScreenshot('consumer-groups-page.png', {
        fullPage: true,
        animations: 'disabled'
      })
    })

    test('should render Event Stores page correctly', async ({ page }) => {
      await page.click('text=Event Stores')
      await page.waitForLoadState('networkidle')
      
      // Wait for content to load
      await expect(page.locator('.ant-table-tbody').first()).toBeVisible()
      
      // Take screenshot
      await expect(page).toHaveScreenshot('event-stores-page.png', {
        fullPage: true,
        animations: 'disabled'
      })
    })

    test('should render Message Browser page correctly', async ({ page }) => {
      await page.click('text=Message Browser')
      await page.waitForLoadState('networkidle')
      
      // Wait for content to load
      await expect(page.locator('.ant-table-tbody').first()).toBeVisible()
      
      // Take screenshot
      await expect(page).toHaveScreenshot('message-browser-page.png', {
        fullPage: true,
        animations: 'disabled'
      })
    })
  })

  test.describe('Modal and Component Screenshots', () => {
    test('should render create queue modal correctly', async ({ page }) => {
      await page.click('text=Queues')
      await page.waitForLoadState('networkidle')
      
      // Open create queue modal
      await page.click('button:has-text("Create Queue")')
      await expect(page.locator('.ant-modal')).toBeVisible()
      
      // Take screenshot of modal
      await expect(page.locator('.ant-modal')).toHaveScreenshot('create-queue-modal.png')
    })

    test('should render create consumer group modal correctly', async ({ page }) => {
      await page.click('text=Consumer Groups')
      await page.waitForLoadState('networkidle')
      
      // Open create group modal
      await page.click('button:has-text("Create Group")')
      await expect(page.locator('.ant-modal')).toBeVisible()
      
      // Take screenshot of modal
      await expect(page.locator('.ant-modal')).toHaveScreenshot('create-consumer-group-modal.png')
    })

    test('should render advanced search correctly', async ({ page }) => {
      await page.click('text=Message Browser')
      await page.waitForLoadState('networkidle')
      
      // Check if Advanced Search button exists
      const advancedSearchButton = page.locator('button:has-text("Advanced Search")')
      const buttonExists = await advancedSearchButton.count() > 0
      
      if (!buttonExists) {
        // Skip test if Advanced Search feature doesn't exist yet
        test.skip()
        return
      }
      
      // Open advanced search
      await page.click('button:has-text("Advanced Search")')
      
      // Wait for advanced search to expand
      await page.waitForTimeout(500)
      
      // Take screenshot of advanced search area
      const searchArea = page.locator('.ant-card:has-text("Advanced Search")')
      await expect(searchArea).toHaveScreenshot('advanced-search.png')
    })
  })

  test.describe('Responsive Design Screenshots', () => {
    test('should render correctly on tablet viewport', async ({ page }) => {
      await page.setViewportSize({ width: 768, height: 1024 })
      await page.reload()
      await page.waitForLoadState('networkidle')
      
      // Take screenshot at tablet size
      await expect(page).toHaveScreenshot('tablet-layout.png', {
        fullPage: true,
        animations: 'disabled'
      })
    })

    test('should render correctly on mobile viewport', async ({ page }) => {
      await page.setViewportSize({ width: 375, height: 667 })
      await page.reload()
      await page.waitForLoadState('networkidle')
      
      // Take screenshot at mobile size
      await expect(page).toHaveScreenshot('mobile-layout.png', {
        fullPage: true,
        animations: 'disabled'
      })
    })

    test('should render sidebar collapsed on mobile', async ({ page }) => {
      await page.setViewportSize({ width: 375, height: 667 })
      await page.reload()
      await page.waitForLoadState('networkidle')
      
      // Check sidebar is collapsed
      const sidebar = page.locator('.ant-layout-sider')
      await expect(sidebar).toHaveScreenshot('mobile-sidebar-collapsed.png')
    })
  })

  test.describe('Data Table Screenshots', () => {
    test('should render queue table with data correctly', async ({ page }) => {
      await page.click('text=Queues')
      await page.waitForLoadState('networkidle')
      
      // Wait for table to load with data
      await expect(page.locator('.ant-table-tbody tr').first()).toBeVisible({ timeout: 10000 })
      
      // Take screenshot of table
      const table = page.locator('.ant-table').first()
      await expect(table).toHaveScreenshot('queue-table.png')
    })

    test('should render consumer groups table correctly', async ({ page }) => {
      await page.click('text=Consumer Groups')
      await page.waitForLoadState('networkidle')
      
      // Wait for table to load
      await expect(page.locator('.ant-table-tbody').first()).toBeVisible()

      // Take screenshot of table
      const table = page.locator('.ant-table').first()
      await expect(table).toHaveScreenshot('consumer-groups-table.png')
    })

    test('should render message browser table correctly', async ({ page }) => {
      await page.click('text=Message Browser')
      await page.waitForLoadState('networkidle')
      
      // Wait for table to load
      await expect(page.locator('.ant-table-tbody').first()).toBeVisible()

      // Take screenshot of table
      const table = page.locator('.ant-table').first()
      await expect(table).toHaveScreenshot('message-browser-table.png')
    })
  })

  test.describe('Statistics and Charts Screenshots', () => {
    test('should render statistics cards correctly', async ({ page }) => {
      await page.click('text=Overview')
      await page.waitForLoadState('networkidle')
      
      // Wait for statistics to load
      await expect(page.locator('.ant-statistic')).toHaveCount(4, { timeout: 10000 })
      
      // Take screenshot of statistics section
      const statsSection = page.locator('.ant-row:has(.ant-statistic)')
      await expect(statsSection).toHaveScreenshot('statistics-cards.png')
    })

    test('should render connection status indicator correctly', async ({ page }) => {
      // Focus on connection status in header
      const connectionStatus = page.locator('.ant-badge')
      await expect(connectionStatus).toHaveScreenshot('connection-status.png')
    })
  })

  test.describe('Form and Input Screenshots', () => {
    test('should render form validation states correctly', async ({ page }) => {
      await page.click('text=Queues')
      await page.waitForLoadState('networkidle')
      
      // Open create queue modal
      await page.click('button:has-text("Create Queue")')
      await expect(page.locator('.ant-modal')).toBeVisible()
      
      // Try to submit empty form to trigger validation
      await page.click('.ant-modal button:has-text("OK")')
      
      // Wait for validation errors to appear (use .first() to avoid strict mode violation)
      await expect(page.locator('.ant-form-item-explain-error').first()).toBeVisible()
      
      // Take screenshot of form with validation errors
      await expect(page.locator('.ant-modal')).toHaveScreenshot('form-validation-errors.png')
    })

    test('should render filled form correctly', async ({ page }) => {
      await page.click('text=Queues')
      await page.waitForLoadState('networkidle')
      
      // Open create queue modal
      await page.click('button:has-text("Create Queue")')
      await expect(page.locator('.ant-modal')).toBeVisible()
      
      // Fill form - use more robust selectors for Ant Design components
      const modal = page.locator('.ant-modal')
      
      // Fill queue name input
      await modal.locator('input').first().fill('visual-test-queue')
      
      // Select setup option using Ant Design select
      await modal.locator('.ant-select-selector').first().click()
      await page.waitForTimeout(300)
      await page.click('.ant-select-item:has-text("Production")')
      
      // Select durability option
      await modal.locator('.ant-select-selector').nth(1).click()
      await page.waitForTimeout(300)
      await page.click('.ant-select-item:has-text("Durable")')

      await page.waitForTimeout(500)
      
      // Take screenshot of filled form
      await expect(modal).toHaveScreenshot('filled-form.png')
    })
  })

  test.describe('Loading States Screenshots', () => {
    test('should render loading states correctly', async ({ page }) => {
      // Navigate to a page and capture loading state if possible
      await page.click('text=Queues')
      
      // Try to capture loading state (this might be too fast to catch)
      const loadingSpinner = page.locator('.ant-spin')
      if (await loadingSpinner.isVisible()) {
        await expect(loadingSpinner).toHaveScreenshot('loading-spinner.png')
      }
    })
  })

  test.describe('Error States Screenshots', () => {
    test('should render empty table states correctly', async ({ page }) => {
      // This test assumes there might be empty states
      await page.click('text=Message Browser')
      await page.waitForLoadState('networkidle')
      
      // If table is empty, capture the empty state
      const emptyState = page.locator('.ant-empty')
      if (await emptyState.isVisible()) {
        await expect(emptyState).toHaveScreenshot('empty-table-state.png')
      }
    })
  })
})
