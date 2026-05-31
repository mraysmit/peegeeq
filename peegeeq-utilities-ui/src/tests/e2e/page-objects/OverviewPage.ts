import { Page, Locator } from '@playwright/test'
import { BasePage } from './BasePage'

/**
 * Overview Page Object.
 *
 * Handles interactions with the Overview (/) page.
 */
export class OverviewPage extends BasePage {
  constructor(page: Page) {
    super(page)
  }

  async goto() {
    await this.navigateTo('overview')
  }

  getHeading(): Locator {
    return this.page.getByRole('heading', { name: /system overview/i })
  }

  getSystemStatusInfo(): Locator {
    return this.page.getByTestId('system-status-info')
  }

  getSystemStatusAlert(): Locator {
    return this.page.getByTestId('system-status-alert')
  }

  getRefreshButton(): Locator {
    return this.page.getByRole('button', { name: /refresh/i })
  }

  getQueueTable(): Locator {
    return this.page.locator('.ant-table').first()
  }
}
