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
    return this.page.getByTestId('database-setups-table')
  }

  /**
   * Get create setup button (card header only — avoids ambiguity with modal submit)
   */
  getCreateButton(): Locator {
    return this.page.getByTestId('database-setups-create-btn')
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
   * Get the "Connect to Existing" button (card header)
   */
  getConnectButton(): Locator {
    return this.page.getByTestId('database-setups-connect-btn')
  }

  /**
   * Open the "Connect to Existing Setup" modal
   */
  async clickConnect() {
    await this.getConnectButton().click()
    await this.waitForModal('Connect to Existing Setup')
  }

  /**
   * Connect to an existing database setup through the UI modal. Fields are scoped to the
   * connect modal so they never collide with the create modal's identically-labelled fields.
   */
  async connectSetup(config: {
    setupId: string
    host: string
    port: number
    databaseName: string
    username: string
    password: string
    schema?: string
  }) {
    await this.clickConnect()

    const modal = this.page.locator('.ant-modal').filter({ hasText: 'Connect to Existing Setup' })
    await modal.getByLabel('Setup ID').fill(config.setupId)
    await modal.getByLabel('Host').fill(config.host)
    await modal.getByLabel('Port').fill(config.port.toString())
    await modal.getByLabel('Database Name').fill(config.databaseName)
    await modal.getByLabel('Username').fill(config.username)
    await modal.getByLabel('Password').fill(config.password)
    if (config.schema) {
      await modal.getByLabel('Schema').fill(config.schema)
    }

    await this.clickModalButton('Connect')
    await this.waitForSuccessMessage()
    await this.page.locator('.ant-modal').waitFor({ state: 'hidden', timeout: 5000 })
  }

  /**
   * Get the action menu trigger button for a specific setup row
   */
  getActionButton(setupId: string): Locator {
    return this.page.getByTestId(`setup-action-btn-${setupId}`)
  }

  /**
   * Get the empty-state alert shown when no setups exist
   */
  getEmptyStateAlert(): Locator {
    return this.page.getByTestId('no-setups-alert')
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
    // Data may still be loading from the API after the table element appears;
    // wait up to 5 s for the specific row before falling back to an instant count.
    if (await row.count() > 0) return true
    try {
      await row.waitFor({ state: 'visible', timeout: 5000 })
      return true
    } catch {
      return false
    }
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

