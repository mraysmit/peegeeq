import { Page, Locator, expect } from '@playwright/test'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Base Page Object for utilities-ui e2e tests.
 *
 * Provides common navigation and interaction helpers used by all page objects.
 * Follows the same pattern as peegeeq-management-ui BasePage.
 */
export class BasePage {
  constructor(protected page: Page) {}

  /**
   * Navigate to a page using the sidebar nav links (identified by data-testid).
   */
  async navigateTo(
    pageName:
      | 'overview'
      | 'tools'
      | 'setups'
      | 'generator'
      | 'generator-templates'
      | 'generator-value-lists'
  ) {
    const navMap: Record<string, string> = {
      'overview': 'nav-overview',
      'tools': 'nav-tools',
      'setups': 'nav-setups',
      'generator': 'nav-generator',
      'generator-templates': 'nav-generator-templates',
      'generator-value-lists': 'nav-generator-value-lists',
    }
    await this.page.getByTestId(navMap[pageName]).click()
    await this.page.waitForLoadState('load')
  }

  /**
   * Check that the application shell is rendered.
   */
  async isLayoutVisible(): Promise<boolean> {
    return await this.page.getByTestId('app-layout').isVisible()
  }

  /**
   * Get the sidebar element.
   */
  getSidebar(): Locator {
    return this.page.getByTestId('app-sidebar')
  }

  /**
   * Get the logo / brand name element.
   */
  getLogo(): Locator {
    return this.page.getByTestId('app-logo')
  }

  /**
   * Wait for a named Ant Design modal to appear.
   */
  async waitForModal(title?: string) {
    const modal = this.page.locator('.ant-modal')
    await modal.waitFor({ state: 'visible' })
    if (title) {
      await this.page
        .locator(`.ant-modal-title:has-text("${title}")`)
        .waitFor({ state: 'visible' })
    }
  }

  /**
   * Click a button in the Ant Design modal footer by its visible text.
   */
  async clickModalButton(buttonText: string) {
    await this.page
      .locator(`.ant-modal-footer button:has-text("${buttonText}")`)
      .click()
  }

  /**
   * Fill an Ant Design Input identified by data-testid.
   */
  async fillInput(testId: string, value: string) {
    await this.page.getByTestId(testId).fill(value)
  }

  /**
   * Select an Ant Design Select option using the robust ant-helpers helper.
   */
  async selectOption(testId: string, optionText: string | RegExp) {
    const select = this.page.getByTestId(testId)
    await selectAntOption(select, optionText)
  }

  /**
   * Wait for an Ant Design success message to appear.
   */
  async waitForSuccessMessage(messageText?: string) {
    if (messageText) {
      await this.page
        .locator('.ant-message-success')
        .filter({ hasText: messageText })
        .first()
        .waitFor({ state: 'visible' })
    } else {
      await this.page
        .locator('.ant-message-success')
        .first()
        .waitFor({ state: 'visible' })
    }
  }

  /**
   * Wait for an Ant Design error message to appear.
   */
  async waitForErrorMessage(messageText?: string) {
    if (messageText) {
      await this.page
        .locator('.ant-message-error')
        .filter({ hasText: messageText })
        .first()
        .waitFor({ state: 'visible' })
    } else {
      await this.page
        .locator('.ant-message-error')
        .first()
        .waitFor({ state: 'visible' })
    }
  }

  /**
   * Wait for an Ant Design Spin loading indicator to disappear.
   */
  async waitForSpinnerToHide(timeout = 10000) {
    await this.page
      .locator('.ant-spin')
      .waitFor({ state: 'hidden', timeout })
      .catch(() => {
        // Spinner may not appear if data loads quickly — not an error.
      })
  }
}
