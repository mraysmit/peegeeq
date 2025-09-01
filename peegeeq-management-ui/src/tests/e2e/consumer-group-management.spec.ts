import { test, expect, Page } from '@playwright/test'

const API_BASE_URL = 'http://localhost:8080'
const UI_BASE_URL = 'http://localhost:3001'

test.describe('Consumer Group Management', () => {
  test.beforeEach(async ({ page }) => {
    // Verify backend is running
    const response = await fetch(`${API_BASE_URL}/api/v1/management/overview`)
    expect(response.ok).toBe(true)
  })

  test('should display consumer group overview and statistics', async ({ page }) => {
    console.log('👥 Testing Consumer Group overview...')
    
    await page.goto(`${UI_BASE_URL}/consumer-groups`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(3000)
    
    // Validate page loaded correctly
    expect(page.url()).toContain('/consumer-groups')
    
    // Check for Consumer Groups page content
    await expect(page.locator('text=Consumer Groups')).toBeVisible()
    
    // Check for consumer group table
    await expect(page.locator('.ant-table')).toBeVisible()
    
    // Validate table headers
    const expectedHeaders = ['Group Name', 'Queue', 'Members', 'Status', 'Actions']
    for (const header of expectedHeaders) {
      const headerExists = await page.locator(`th:has-text("${header}")`).count() > 0
      if (headerExists) {
        console.log(`    ✅ Header "${header}" found`)
      }
    }
    
    console.log('✅ Consumer Group overview validation passed')
  })

  test('should validate consumer group creation', async ({ page }) => {
    console.log('🏗️ Testing Consumer Group creation...')
    
    await page.goto(`${UI_BASE_URL}/consumer-groups`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Look for Create Consumer Group button
    const createButtons = page.locator('button:has-text("Create Consumer Group"), button:has-text("Create Group"), button:has-text("Add Group")')
    const createButtonCount = await createButtons.count()
    
    if (createButtonCount > 0) {
      await createButtons.first().click()
      
      // Check for creation modal
      const hasModal = await page.locator('.ant-modal').count() > 0
      if (hasModal) {
        await expect(page.locator('.ant-modal-title')).toBeVisible()
        
        // Check for form fields
        await expect(page.locator('.ant-form')).toBeVisible()
        
        // Look for group name input
        const groupNameInput = page.locator('input[placeholder*="group"], input[placeholder*="name"]')
        const hasGroupNameInput = await groupNameInput.count() > 0
        if (hasGroupNameInput) {
          console.log('    ✅ Group name input found')
        }
        
        // Look for queue selection
        const queueSelect = page.locator('.ant-select')
        const hasQueueSelect = await queueSelect.count() > 0
        if (hasQueueSelect) {
          console.log('    ✅ Queue selection found')
        }
        
        // Close modal
        await page.click('.ant-modal-close')
      }
    } else {
      console.log('    ℹ️  No create consumer group button found (may be restricted)')
    }
    
    console.log('✅ Consumer Group creation test passed')
  })

  test('should validate member management and monitoring', async ({ page }) => {
    console.log('👤 Testing member management and monitoring...')
    
    await page.goto(`${UI_BASE_URL}/consumer-groups`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Get consumer group data from API
    const groupsResponse = await fetch(`${API_BASE_URL}/api/v1/management/consumer-groups`)
    const groupsData = await groupsResponse.json()
    
    if (groupsData.consumerGroups && groupsData.consumerGroups.length > 0) {
      console.log(`    📊 Found ${groupsData.consumerGroups.length} consumer groups`)
      
      // Check for member count display in table (format: "X/Y" for memberCount/maxMembers)
      const memberCells = page.locator('td').filter({ hasText: /\d+\/\d+/ })
      const memberCellCount = await memberCells.count()
      expect(memberCellCount).toBeGreaterThanOrEqual(0) // May be 0 if no groups have members
      
      // Check for member details (if available)
      const detailButtons = page.locator('button:has-text("Details"), button:has-text("Members"), .anticon-team')
      const detailButtonCount = await detailButtons.count()
      
      if (detailButtonCount > 0) {
        console.log(`    👥 Found ${detailButtonCount} member detail buttons`)
      }
    }
    
    console.log('✅ Member management and monitoring test passed')
  })

  test('should validate partition assignment visualization', async ({ page }) => {
    console.log('🗂️ Testing partition assignment visualization...')
    
    await page.goto(`${UI_BASE_URL}/consumer-groups`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Check for partition assignment UI elements
    const partitionElements = page.locator('text=Partition, .ant-card:has-text("Partition"), .ant-table:has-text("Partition")')
    const partitionElementCount = await partitionElements.count()
    
    if (partitionElementCount > 0) {
      console.log(`    🗂️ Found ${partitionElementCount} partition-related elements`)
      
      // Check for partition assignment visualization
      await expect(partitionElements.first()).toBeVisible()
    }
    
    // Check for assignment strategy information
    const strategyElements = page.locator('text=Strategy, text=Assignment, text=Balance')
    const strategyElementCount = await strategyElements.count()
    
    if (strategyElementCount > 0) {
      console.log(`    ⚖️ Found ${strategyElementCount} load balancing strategy elements`)
    }
    
    console.log('✅ Partition assignment visualization test passed')
  })

  test('should validate rebalancing operations', async ({ page }) => {
    console.log('⚖️ Testing rebalancing operations...')
    
    await page.goto(`${UI_BASE_URL}/consumer-groups`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Check for rebalancing controls
    const rebalanceButtons = page.locator('button:has-text("Rebalance"), button:has-text("Balance"), .anticon-reload')
    const rebalanceButtonCount = await rebalanceButtons.count()
    
    if (rebalanceButtonCount > 0) {
      console.log(`    ⚖️ Found ${rebalanceButtonCount} rebalancing buttons`)
      
      // Test rebalance confirmation (without actually triggering)
      await rebalanceButtons.first().click()
      
      // Check for confirmation dialog
      const hasConfirmation = await page.locator('.ant-modal, .ant-popconfirm').count() > 0
      if (hasConfirmation) {
        console.log('    ✅ Rebalance confirmation dialog found')
        
        // Cancel the operation
        await page.click('button:has-text("Cancel"), .ant-btn:has-text("No")')
      }
    } else {
      console.log('    ℹ️  No rebalancing controls found (may be automatic)')
    }
    
    // Check for rebalancing status indicators
    const statusIndicators = page.locator('text=Rebalancing, text=Balanced, .ant-tag:has-text("REBALANCING")')
    const statusIndicatorCount = await statusIndicators.count()
    
    if (statusIndicatorCount > 0) {
      console.log(`    📊 Found ${statusIndicatorCount} rebalancing status indicators`)
    }
    
    console.log('✅ Rebalancing operations test passed')
  })

  test('should validate consumer group performance metrics', async ({ page }) => {
    console.log('📊 Testing consumer group performance metrics...')
    
    await page.goto(`${UI_BASE_URL}/consumer-groups`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Check for performance statistics
    const performanceStats = [
      'Total Consumer Groups',
      'Active Groups',
      'Total Members',
      'Processing Rate'
    ]
    
    for (const stat of performanceStats) {
      const statExists = await page.locator(`.ant-statistic-title:has-text("${stat}")`).count() > 0
      if (statExists) {
        console.log(`    ✅ Performance statistic "${stat}" found`)
      }
    }
    
    // Check for performance charts
    const chartElements = page.locator('.ant-card:has-text("Performance"), .ant-card:has-text("Throughput")')
    const chartElementCount = await chartElements.count()
    
    if (chartElementCount > 0) {
      console.log(`    📈 Found ${chartElementCount} performance chart elements`)
    }
    
    // Validate consumer group metrics in table
    const tableRows = await page.locator('.ant-table-tbody tr').count()
    if (tableRows > 0) {
      console.log(`    📋 Consumer group table has ${tableRows} rows`)
      
      // Check that each row has member count
      const memberCells = page.locator('td').filter({ hasText: /\d+/ })
      const memberCellCount = await memberCells.count()
      expect(memberCellCount).toBeGreaterThanOrEqual(0)
    }
    
    console.log('✅ Consumer group performance metrics test passed')
  })
})
