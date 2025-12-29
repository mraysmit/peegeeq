import { Page, Locator } from '@playwright/test'

/**
 * Base Page Object
 * Provides common functionality for all page objects
 */
export class BasePage {
  constructor(protected page: Page) {}

  /**
   * Navigate to a specific page using sidebar navigation
   */
  async navigateTo(pageName: 'overview' | 'queues' | 'consumer-groups' | 'event-stores' | 'messages' | 'database-setups') {
    const navMap = {
      'overview': 'nav-overview',
      'queues': 'nav-queues',
      'consumer-groups': 'nav-consumer-groups',
      'event-stores': 'nav-event-stores',
      'messages': 'nav-messages',
      'database-setups': 'nav-database-setups',
    }
    
    await this.page.getByTestId(navMap[pageName]).click()
    await this.page.waitForLoadState('networkidle')
  }

  /**
   * Get page title
   */
  getPageTitle(): Locator {
    return this.page.getByTestId('page-title')
  }

  /**
   * Check if app layout is visible
   */
  async isLayoutVisible(): Promise<boolean> {
    return await this.page.getByTestId('app-layout').isVisible()
  }

  /**
   * Get connection status
   */
  getConnectionStatus(): Locator {
    return this.page.getByTestId('connection-status')
  }

  /**
   * Click refresh button in header
   */
  async clickRefresh() {
    await this.page.getByTestId('refresh-btn').click()
  }

  /**
   * Wait for a modal to appear
   */
  async waitForModal(title?: string) {
    const modal = this.page.locator('.ant-modal')
    await modal.waitFor({ state: 'visible' })
    
    if (title) {
      await this.page.locator(`.ant-modal-title:has-text("${title}")`).waitFor({ state: 'visible' })
    }
  }

  /**
   * Click modal button (OK, Cancel, etc.)
   */
  async clickModalButton(buttonText: string) {
    await this.page.locator(`.ant-modal-footer button:has-text("${buttonText}")`).click()
  }

  /**
   * Wait for table to load
   */
  async waitForTableLoad(testId: string) {
    const table = this.page.getByTestId(testId)
    await table.waitFor({ state: 'visible' })
    // Wait for loading spinner to disappear
    await this.page.locator('.ant-spin').waitFor({ state: 'hidden', timeout: 10000 }).catch(() => {
      // Spinner might not appear if data loads quickly
    })
  }

  /**
   * Get table row count
   */
  async getTableRowCount(testId: string): Promise<number> {
    const table = this.page.getByTestId(testId)
    const rows = table.locator('tbody tr')
    return await rows.count()
  }

  /**
   * Fill Ant Design Input
   */
  async fillInput(testId: string, value: string) {
    await this.page.getByTestId(testId).fill(value)
  }

  /**
   * Select Ant Design Select option
   */
  async selectOption(testId: string, optionText: string) {
    await this.page.getByTestId(testId).click()
    await this.page.locator(`.ant-select-item-option-content:has-text("${optionText}")`).click()
  }

  /**
   * Wait for success message
   */
  async waitForSuccessMessage(messageText?: string) {
    const notification = this.page.locator('.ant-message-success')
    await notification.waitFor({ state: 'visible' })
    
    if (messageText) {
      await this.page.locator(`.ant-message-success:has-text("${messageText}")`).waitFor({ state: 'visible' })
    }
  }

  /**
   * Wait for error message
   */
  async waitForErrorMessage(messageText?: string) {
    const notification = this.page.locator('.ant-message-error')
    await notification.waitFor({ state: 'visible' })
    
    if (messageText) {
      await this.page.locator(`.ant-message-error:has-text("${messageText}")`).waitFor({ state: 'visible' })
    }
  }
}

