import { Page, Locator } from '@playwright/test'
import { BasePage } from './BasePage'

/**
 * Database Setups Page Object
 * Handles interactions with the Database Setups page
 */
export class DatabaseSetupsPage extends BasePage {
  constructor(page: Page) {
    super(page)
  }

  /**
   * Navigate to Database Setups page
   */
  async goto() {
    await this.navigateTo('database-setups')
  }

  /**
   * Get setups table
   */
  getSetupsTable(): Locator {
    return this.page.locator('.ant-table').first()
  }

  /**
   * Get create setup button
   */
  getCreateButton(): Locator {
    return this.page.getByRole('button', { name: /create setup/i })
  }

  /**
   * Click create setup button
   */
  async clickCreate() {
    await this.getCreateButton().click()
    await this.waitForModal('Create Database Setup')
  }

  /**
   * Create a new database setup
   */
  async createSetup(config: {
    setupId: string
    host: string
    port: number
    databaseName: string
    username: string
    password: string
    schema?: string
  }) {
    await this.clickCreate()
    
    // Fill form
    await this.page.getByLabel('Setup ID').fill(config.setupId)
    await this.page.getByLabel('Host').fill(config.host)
    await this.page.getByLabel('Port').fill(config.port.toString())
    await this.page.getByLabel('Database Name').fill(config.databaseName)
    await this.page.getByLabel('Username').fill(config.username)
    await this.page.getByLabel('Password').fill(config.password)
    
    if (config.schema) {
      await this.page.getByLabel('Schema').fill(config.schema)
    }
    
    // Submit
    await this.clickModalButton('Create Setup')
    
    // Wait for success message
    await this.waitForSuccessMessage()
    
    // Wait for modal to close
    await this.page.locator('.ant-modal').waitFor({ state: 'hidden', timeout: 5000 })
  }

  /**
   * Check if a setup exists
   */
  async setupExists(setupId: string): Promise<boolean> {
    // Wait for table to be visible
    await this.getSetupsTable().waitFor({ state: 'visible', timeout: 10000 }).catch(() => {
      // Table might not exist if there are no setups
    })

    const row = this.page.locator(`tr:has-text("${setupId}")`)
    return await row.count() > 0
  }

  /**
   * Get setup count from table
   */
  async getSetupCount(): Promise<number> {
    const table = this.getSetupsTable()
    const rows = table.locator('tbody tr')
    return await rows.count()
  }
}

