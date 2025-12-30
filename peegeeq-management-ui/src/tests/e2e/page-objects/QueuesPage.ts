import { Page, Locator } from '@playwright/test'
import { BasePage } from './BasePage'

/**
 * Queues Page Object
 * Handles interactions with the Queues list page
 */
export class QueuesPage extends BasePage {
  constructor(page: Page) {
    super(page)
  }

  /**
   * Navigate to Queues page
   */
  async goto() {
    await this.navigateTo('queues')
  }

  /**
   * Get queues table
   */
  getQueuesTable(): Locator {
    return this.page.getByTestId('queues-table')
  }

  /**
   * Get create queue button
   */
  getCreateButton(): Locator {
    return this.page.getByTestId('create-queue-btn')
  }

  /**
   * Get refresh button
   */
  getRefreshButton(): Locator {
    return this.page.getByTestId('refresh-queues-btn')
  }

  /**
   * Click create queue button
   */
  async clickCreate() {
    await this.getCreateButton().click()
    await this.waitForModal('Create Queue')
  }

  /**
   * Click refresh button
   */
  async clickRefresh() {
    await this.getRefreshButton().click()
  }

  /**
   * Create a new queue
   */
  async createQueue(name: string, setup: string) {
    await this.clickCreate()
    
    // Fill form
    await this.fillInput('queue-name-input', name)
    await this.selectOption('queue-setup-select', setup)
    
    // Submit
    await this.clickModalButton('OK')
    
    // Wait for modal to close
    await this.page.locator('.ant-modal').waitFor({ state: 'hidden', timeout: 5000 })
  }

  /**
   * Get queue count from table
   */
  async getQueueCount(): Promise<number> {
    await this.waitForTableLoad('queues-table')
    return await this.getTableRowCount('queues-table')
  }

  /**
   * Find queue row by name
   */
  getQueueRow(queueName: string): Locator {
    return this.page.locator(`tr:has-text("${queueName}")`)
  }

  /**
   * Check if queue exists in table
   */
  async queueExists(queueName: string): Promise<boolean> {
    await this.waitForTableLoad('queues-table')
    const row = this.getQueueRow(queueName)
    return await row.isVisible().catch(() => false)
  }

  /**
   * Click on queue name to view details
   */
  async viewQueueDetails(queueName: string) {
    const row = this.getQueueRow(queueName)
    await row.locator('a').first().click()
    await this.page.waitForLoadState('networkidle')
  }

  /**
   * Delete a queue
   */
  async deleteQueue(queueName: string) {
    const row = this.getQueueRow(queueName)
    
    // Click more actions button
    await row.locator('button[aria-label="more"]').click()
    
    // Click delete option
    await this.page.locator('.ant-dropdown-menu-item:has-text("Delete")').click()
    
    // Confirm deletion
    await this.waitForModal()
    await this.clickModalButton('OK')
    
    // Wait for deletion to complete
    await this.page.waitForTimeout(1000)
  }

  /**
   * Search for queues
   */
  async search(searchText: string) {
    const searchInput = this.page.locator('input[placeholder*="Search"]')
    await searchInput.fill(searchText)
    await this.page.waitForTimeout(500) // Debounce
  }

  /**
   * Filter by queue type
   */
  async filterByType(types: string[]) {
    // Click type filter dropdown
    const typeFilter = this.page.locator('.ant-select').first()
    await typeFilter.click()
    
    // Select options
    for (const type of types) {
      await this.page.locator(`.ant-select-item-option-content:has-text("${type}")`).click()
    }
    
    // Click outside to close dropdown
    await this.page.keyboard.press('Escape')
  }

  /**
   * Clear all filters
   */
  async clearFilters() {
    const clearButton = this.page.locator('button:has-text("Clear")')
    if (await clearButton.isVisible()) {
      await clearButton.click()
    }
  }
}

