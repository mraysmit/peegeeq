import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import * as fs from 'fs'
import { Locator } from '@playwright/test'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Event Visualization Tests
 */

test.describe('Event Visualization', () => {
  
  test('should visualize causation tree and aggregate stream', async ({ page }) => {
    test.setTimeout(180000) // 3 minutes

    // --- 1. Setup Database ---
    const dbConfig = JSON.parse(fs.readFileSync('testcontainers-db.json', 'utf8'))
    await page.goto('/database-setups')
    
    const setupExists = await page.locator(`tr:has-text("${SETUP_ID}")`).count() > 0
    if (!setupExists) {
      await page.getByRole('button', { name: /create setup/i }).click()
      await page.getByLabel(/setup id/i).fill(SETUP_ID)
      await page.getByLabel(/host/i).fill(dbConfig.host)
      await page.getByLabel(/port/i).fill(String(dbConfig.port))
      await page.getByLabel(/database name/i).fill(`e2e_viz_${Date.now()}`)
      await page.getByLabel(/username/i).fill(dbConfig.username)
      await page.getByLabel(/password/i).fill(dbConfig.password)
      await page.getByLabel(/schema/i).fill('public')
      await page.locator('.ant-modal .ant-btn-primary').click()
      await expect(page.locator('.ant-modal')).not.toBeVisible()
    }

    // --- 2. Create Event Store ---
    await page.goto('/event-stores')
    const eventStoreName = `viz-store-${Date.now()}`
    
    await page.getByRole('button', { name: /create event store/i }).click()
    await page.getByLabel(/event store name/i).fill(eventStoreName)
    
    await page.getByTestId('refresh-setups-btn').click()
    const setupSelect = page.locator('#setupId').locator('..')
    await selectAntOption(setupSelect, SETUP_ID)
    
    await page.locator('.ant-modal .ant-btn-primary').click()
    await expect(page.locator('.ant-modal')).not.toBeVisible()
    await expect(page.locator(`tr:has-text("${eventStoreName}")`)).toBeVisible()

    // --- 3. Post Events (Chain) ---
    await page.getByRole('tab', { name: /events/i }).click()
    // Advanced toggle is handled in postEvent helper

    const correlationId = `corr-${Date.now()}`
    const aggregateId = `agg-${Date.now()}`
    let rootEventId = ''
    let childEventId = ''

    // Helper to post and get ID
    const postEvent = async (type: string, causeId?: string) => {
      // Select Setup (if not already selected or cleared)
      // The form clears after submit, so we must re-select
      
      // Scope to the Post Event form card to avoid ambiguity with hidden modals
      const postForm = page.locator('.ant-card', { hasText: 'Post Event' })
      
      // Ensure Advanced section is visible
      const advancedBtn = postForm.getByRole('button', { name: /show advanced/i })
      if (await advancedBtn.isVisible()) {
        await advancedBtn.click()
      }
      
      const setupSelect = postForm.locator('#setupId').locator('..')
      await selectAntOption(setupSelect, SETUP_ID)
      
      const eventStoreSelect = postForm.locator('#eventStoreName').locator('..')
      await selectAntOption(eventStoreSelect, eventStoreName)
      
      await postForm.locator('#eventType').fill(type)
      await postForm.locator('#eventData').fill(JSON.stringify({ msg: 'test' }))
      
      await postForm.locator('#aggregateId').fill(aggregateId)
      await postForm.locator('#correlationId').fill(correlationId)
      
      if (causeId) {
        await postForm.locator('#causationId').fill(causeId)
      }
      
      await postForm.getByRole('button', { name: /post event/i }).click()
      
      // Capture ID from toast: "Event '...' posted successfully ... (ID: <uuid>)"
      const toast = page.locator('.ant-message-notice-content')
        .filter({ hasText: type })
        .first()
      await expect(toast).toBeVisible()
      const text = await toast.innerText()
      const match = text.match(/ID: ([a-f0-9-]+)/)
      if (!match) throw new Error(`Could not extract ID from toast: ${text}`)
      
      // Wait for toast to disappear to avoid overlap
      await expect(toast).not.toBeVisible()
      
      return match[1]
    }

    // Post Root
    rootEventId = await postEvent('RootEvent')

    // Post Child
    childEventId = await postEvent('ChildEvent', rootEventId)

    // Post Grandchild
    await postEvent('GrandChildEvent', childEventId)

    // --- 4. Test Visualization Tab ---
    await page.getByRole('tab', { name: /visualization/i }).click()

    // Select Setup/Store in Visualization Tab
    // Use specific test IDs defined in EventStores.tsx
    const vizSetupSelect = page.getByTestId('viz-setup-select')
    await selectAntOption(vizSetupSelect, SETUP_ID)
    
    const vizEventStoreSelect = page.getByTestId('viz-eventstore-select')
    await selectAntOption(vizEventStoreSelect, eventStoreName)

    // --- 5. Test Causation Tree ---
    await expect(page.getByRole('tab', { name: /causation tree/i })).toBeVisible()
    
    await page.getByPlaceholder(/enter correlation id/i).fill(correlationId)
    await page.getByRole('button', { name: /trace/i }).click()

    // Verify Tree Nodes
    // We expect 3 nodes. The tree renders titles with event types.
    // Use regex to ensure exact match at start of string to avoid "ChildEvent" matching "GrandChildEvent"
    await expect(page.locator('.ant-tree-treenode').filter({ hasText: /^RootEvent/ })).toBeVisible()
    await expect(page.locator('.ant-tree-treenode').filter({ hasText: /^ChildEvent/ })).toBeVisible()
    await expect(page.locator('.ant-tree-treenode').filter({ hasText: /^GrandChildEvent/ })).toBeVisible()

    // --- 6. Test Aggregate Stream ---
    await page.getByRole('tab', { name: /aggregate stream/i }).click()
    
    // Click Refresh List
    await page.getByRole('button', { name: /refresh list/i }).click()
    
    // Verify Aggregate ID appears in the list
    const aggRow = page.locator('tr').filter({ hasText: aggregateId })
    await expect(aggRow).toBeVisible()
    
    // Click View Stream
    await aggRow.getByText('View Stream').click()
    
    // Verify Events in Stream Table (Right side)
    // We should see all 3 events
    // Scope to the Aggregate Stream tab content to avoid matching the Causation Tree
    const streamTabContent = page.locator('.ant-tabs-tabpane-active')
    const streamTable = streamTabContent.locator('.ant-card').filter({ hasText: `Stream: ${aggregateId}` })
    
    await expect(streamTable.getByText('RootEvent', { exact: true })).toBeVisible()
    await expect(streamTable.getByText('ChildEvent', { exact: true })).toBeVisible()
    await expect(streamTable.getByText('GrandChildEvent', { exact: true })).toBeVisible()
  })
})
