import { Page, Locator } from '@playwright/test'
import { BasePage } from './BasePage'

/**
 * Overview Page Object.
 *
 * The Overview (/) page is a per-setup browser: a selectable list of setups at
 * the top, and the selected setup's queues, consumer groups, and event stores
 * beneath it. No global aggregates or charts.
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

  getRefreshButton(): Locator {
    return this.page.getByTestId('refresh-button')
  }

  getCreateSetupButton(): Locator {
    return this.page.getByTestId('create-setup-button')
  }

  /** The selectable setups list (present when at least one setup exists). */
  getSetupsList(): Locator {
    return this.page.getByTestId('setups-list')
  }

  /** The empty-state alert (present when no setups exist). */
  getNoSetupsAlert(): Locator {
    return this.page.getByTestId('no-setups')
  }

  /** The selected setup's detail panel (present when a setup is selected). */
  getSetupDetail(): Locator {
    return this.page.getByTestId('setup-detail')
  }

  getErrorAlert(): Locator {
    return this.page.getByTestId('overview-error')
  }
}
