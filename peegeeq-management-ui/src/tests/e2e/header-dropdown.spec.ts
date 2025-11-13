import { test, expect } from '@playwright/test'

const UI_BASE_URL = 'http://localhost:3001'

test.describe('Header User Dropdown Menu Tests', () => {
  test.beforeEach(async ({ page }) => {
    // Navigate to the application
    await page.goto(UI_BASE_URL)
    await page.waitForLoadState('networkidle')
  })

  test('should display user dropdown menu with valid items', async ({ page }) => {
    console.log('🔽 Testing user dropdown menu...')
    
    // Click on the user dropdown button
    const userButton = page.locator('button:has-text("Admin")')
    await expect(userButton).toBeVisible()
    await userButton.click()
    
    // Wait for dropdown menu to appear
    await page.waitForTimeout(500)
    
    // Verify dropdown is visible
    const dropdown = page.locator('.ant-dropdown:visible')
    await expect(dropdown).toBeVisible()
    
    // Verify valid menu items exist
    const profileItem = page.locator('.ant-dropdown-menu-item:has-text("Profile")')
    const settingsItem = page.locator('.ant-dropdown-menu-item:has-text("Settings")')
    const logoutItem = page.locator('.ant-dropdown-menu-item:has-text("Logout")')
    
    await expect(profileItem).toBeVisible()
    await expect(settingsItem).toBeVisible()
    await expect(logoutItem).toBeVisible()
    
    console.log('    ✅ Profile menu item is visible')
    console.log('    ✅ Settings menu item is visible')
    console.log('    ✅ Logout menu item is visible')
  })

  test('should validate that demo item does not exist (invalid)', async ({ page }) => {
    console.log('❌ Testing that invalid "Demo" menu item does not exist...')
    
    // Click on the user dropdown button
    const userButton = page.locator('button:has-text("Admin")')
    await expect(userButton).toBeVisible()
    await userButton.click()
    
    // Wait for dropdown menu to appear
    await page.waitForTimeout(500)
    
    // Verify dropdown is visible
    const dropdown = page.locator('.ant-dropdown:visible')
    await expect(dropdown).toBeVisible()
    
    // Verify that "Demo" menu item does NOT exist
    const demoItem = page.locator('.ant-dropdown-menu-item:has-text("Demo")')
    const demoItemCount = await demoItem.count()
    
    // Assert that demo item does not exist
    expect(demoItemCount).toBe(0)
    
    console.log('    ✅ Confirmed: "Demo" menu item does not exist (as expected)')
    console.log('    ✅ Validation passed: Invalid menu item correctly absent')
  })

  test('should click Profile menu item', async ({ page }) => {
    console.log('👤 Testing Profile menu item click...')
    
    // Click on the user dropdown button
    const userButton = page.locator('button:has-text("Admin")')
    await userButton.click()
    await page.waitForTimeout(500)
    
    // Click on Profile menu item
    const profileItem = page.locator('.ant-dropdown-menu-item:has-text("Profile")')
    await expect(profileItem).toBeVisible()
    await profileItem.click()
    
    // Wait for any action to complete
    await page.waitForTimeout(500)
    
    // Note: Currently Profile doesn't navigate anywhere (just console.log in code)
    // In a real implementation, you would verify navigation or modal opening
    console.log('    ✅ Profile menu item clicked successfully')
  })

  test('should click Settings menu item', async ({ page }) => {
    console.log('⚙️ Testing Settings menu item click...')
    
    // Click on the user dropdown button
    const userButton = page.locator('button:has-text("Admin")')
    await userButton.click()
    await page.waitForTimeout(500)
    
    // Click on Settings menu item
    const settingsItem = page.locator('.ant-dropdown-menu-item:has-text("Settings")')
    await expect(settingsItem).toBeVisible()
    await settingsItem.click()
    
    // Wait for any action to complete
    await page.waitForTimeout(500)
    
    // Note: Currently Settings doesn't navigate anywhere (just console.log in code)
    // In a real implementation, you would verify navigation or modal opening
    console.log('    ✅ Settings menu item clicked successfully')
  })

  test('should verify menu items have correct icons', async ({ page }) => {
    console.log('🎨 Testing menu item icons...')
    
    // Click on the user dropdown button
    const userButton = page.locator('button:has-text("Admin")')
    await userButton.click()
    await page.waitForTimeout(500)
    
    // Verify icons are present for each menu item
    const profileIcon = page.locator('.ant-dropdown-menu-item:has-text("Profile") .anticon-user')
    const settingsIcon = page.locator('.ant-dropdown-menu-item:has-text("Settings") .anticon-setting')
    const logoutIcon = page.locator('.ant-dropdown-menu-item:has-text("Logout") .anticon-logout')
    
    await expect(profileIcon).toBeVisible()
    await expect(settingsIcon).toBeVisible()
    await expect(logoutIcon).toBeVisible()
    
    console.log('    ✅ Profile icon is visible')
    console.log('    ✅ Settings icon is visible')
    console.log('    ✅ Logout icon is visible')
  })

  test('should verify divider exists between Settings and Logout', async ({ page }) => {
    console.log('➖ Testing menu divider...')
    
    // Click on the user dropdown button
    const userButton = page.locator('button:has-text("Admin")')
    await userButton.click()
    await page.waitForTimeout(500)
    
    // Verify divider exists
    const divider = page.locator('.ant-dropdown-menu-item-divider')
    await expect(divider).toBeVisible()
    
    console.log('    ✅ Menu divider is visible between Settings and Logout')
  })
})

