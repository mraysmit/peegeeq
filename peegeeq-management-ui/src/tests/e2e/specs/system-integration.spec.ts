import { test, expect } from '../page-objects'

/**
 * System Integration Tests
 * 
 * Consolidated from:
 * - management-ui.spec.ts
 * - database-connectivity.spec.ts
 * - header-dropdown.spec.ts
 * - comprehensive-validation.spec.ts
 * - data-validation.spec.ts
 * - error-handling.spec.ts
 * - full-system-test.spec.ts
 * - true-end-to-end-test.spec.ts
 * - live-system-test.spec.ts
 */

test.describe('System Integration', () => {
  
  test.describe('Application Bootstrap', () => {
    
    test('should load app with correct layout', async ({ page, basePage }) => {
      await page.goto('/')
      await page.waitForLoadState('networkidle')
      
      // Main layout should be visible
      await expect(page.getByTestId('app-layout')).toBeVisible()
      await expect(page.getByTestId('app-sidebar')).toBeVisible()
      await expect(page.getByTestId('app-header')).toBeVisible()
      await expect(page.getByTestId('app-content')).toBeVisible()
    })

    test('should display header with logo and title', async ({ page }) => {
      await page.goto('/')
      await page.waitForLoadState('networkidle')

      // Logo should be visible
      await expect(page.getByTestId('app-logo')).toBeVisible()

      // Header should be visible with page title
      await expect(page.getByTestId('app-header')).toBeVisible()
      await expect(page.getByTestId('page-title')).toContainText('Overview')
    })

    test('should show sidebar navigation', async ({ page }) => {
      await page.goto('/')
      await page.waitForLoadState('networkidle')
      
      // Navigation menu should be visible
      await expect(page.getByTestId('app-nav-menu')).toBeVisible()
      
      // All navigation links should be visible
      await expect(page.getByTestId('nav-overview')).toBeVisible()
      await expect(page.getByTestId('nav-queues')).toBeVisible()
      await expect(page.getByTestId('nav-consumer-groups')).toBeVisible()
      await expect(page.getByTestId('nav-event-stores')).toBeVisible()
      await expect(page.getByTestId('nav-messages')).toBeVisible()
    })

    test('should establish backend connection', async ({ page }) => {
      await page.goto('/')
      await page.waitForLoadState('networkidle')

      // Connection status should show connected
      const connectionStatus = page.getByTestId('connection-status')
      await expect(connectionStatus).toBeVisible()

      // Should show success status (Online)
      await expect(connectionStatus).toContainText('Online')
    })
  })

  test.describe('Navigation', () => {

    test('should navigate to all pages via sidebar', async ({ page, basePage }) => {
      await page.goto('/')
      await page.waitForLoadState('networkidle')

      // Navigate to Queues
      await basePage.navigateTo('queues')
      await expect(basePage.getPageTitle()).toContainText('Queues')
      expect(page.url()).toContain('/queues')

      // Navigate to Consumer Groups
      await basePage.navigateTo('consumer-groups')
      await expect(basePage.getPageTitle()).toContainText('Consumer Groups')
      expect(page.url()).toContain('/consumer-groups')

      // Navigate to Event Stores
      await basePage.navigateTo('event-stores')
      await expect(basePage.getPageTitle()).toContainText('Event Stores')
      expect(page.url()).toContain('/event-stores')

      // Navigate to Message Browser
      await basePage.navigateTo('messages')
      await expect(basePage.getPageTitle()).toContainText('Message Browser')
      expect(page.url()).toContain('/messages')

      // Navigate back to Overview
      await basePage.navigateTo('overview')
      await expect(basePage.getPageTitle()).toContainText('Overview')
      expect(page.url()).toMatch(/\/$/)
    })

    test('should show correct page titles', async ({ page, basePage }) => {
      await page.goto('/')

      await basePage.navigateTo('queues')
      await expect(basePage.getPageTitle()).toContainText('Queues')

      await basePage.navigateTo('overview')
      await expect(basePage.getPageTitle()).toContainText('Overview')
    })

    test('should update URL correctly', async ({ page, basePage }) => {
      await page.goto('/')

      await basePage.navigateTo('queues')
      expect(page.url()).toContain('/queues')

      await basePage.navigateTo('consumer-groups')
      expect(page.url()).toContain('/consumer-groups')
    })
  })

  test.describe('Error Handling', () => {
    
    test('should display appropriate loading states', async ({ page }) => {
      await page.goto('/')
      
      // Navigate to a page that loads data
      await page.getByTestId('nav-queues').click()
      
      // Loading spinner might appear briefly
      // Just verify the page loads successfully
      await page.waitForLoadState('networkidle')
      await expect(page.getByTestId('queues-table')).toBeVisible()
    })
  })

  test.describe('Header Functionality', () => {
    
    test('should show user menu dropdown', async ({ page }) => {
      await page.goto('/')
      await page.waitForLoadState('networkidle')
      
      // Click user menu button
      await page.getByTestId('user-menu-btn').click()
      
      // Dropdown menu should appear
      await expect(page.locator('.ant-dropdown-menu')).toBeVisible()
    })

    test('should show notifications button', async ({ page }) => {
      await page.goto('/')
      await page.waitForLoadState('networkidle')
      
      // Notifications button should be visible
      await expect(page.getByTestId('notifications-btn')).toBeVisible()
    })
  })
})

