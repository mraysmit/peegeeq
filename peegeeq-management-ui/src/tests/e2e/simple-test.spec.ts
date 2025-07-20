import { test, expect } from '@playwright/test'

/**
 * Simple UI Test to validate basic functionality
 */

test.describe('Simple UI Test', () => {
  
  test('should load the application and show basic elements', async ({ page }) => {
    // Navigate to the application
    await page.goto('/')
    
    // Wait for the page to load
    await page.waitForLoadState('networkidle')
    
    // Check that the main layout is visible
    await expect(page.locator('.ant-layout.ant-layout-has-sider')).toBeVisible()
    
    // Check that the sidebar menu is visible
    await expect(page.locator('.ant-menu')).toBeVisible()
    
    // Check that we can see some content (statistics)
    await expect(page.locator('.ant-statistic')).toHaveCount(4)
    
    // Check that the page title is correct
    await expect(page).toHaveTitle(/PeeGeeQ/)
    
    console.log('✅ Basic application loading test passed!')
  })

  test('should be able to click menu items', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    
    // Wait for menu to be visible
    await expect(page.locator('.ant-menu')).toBeVisible()
    
    // Try to click on Queues menu item
    const queuesLink = page.locator('.ant-menu a[href="/queues"]')
    await expect(queuesLink).toBeVisible()
    await queuesLink.click()
    
    // Check that URL changed
    await expect(page.url()).toContain('/queues')
    
    console.log('✅ Menu navigation test passed!')
  })

  test('should display some data on overview page', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    
    // Wait for statistics to load
    await page.waitForTimeout(3000)
    
    // Check for statistics cards
    const statistics = page.locator('.ant-statistic')
    await expect(statistics).toHaveCount(4)
    
    // Check for specific statistic titles
    await expect(page.locator('text=Total Queues')).toBeVisible()
    
    console.log('✅ Data display test passed!')
  })
})
