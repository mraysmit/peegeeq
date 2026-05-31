import { Page, Locator } from '@playwright/test'
import { BasePage } from './BasePage'

/**
 * Setups Page Object — Setups list page (/setups).
 *
 * Wraps interactions with the SetupsPage component:
 * setup table, Create Setup navigation, detail modal, and delete.
 */
export class SetupsPage extends BasePage {
  constructor(page: Page) {
    super(page)
  }

  async goto() {
    await this.page.goto('/setups')
    await this.page.waitForLoadState('load')
  }

  // ── Page root ─────────────────────────────────────────────────────────────────

  getPageRoot(): Locator {
    return this.page.getByTestId('setups-page')
  }

  getHeading(): Locator {
    return this.page.getByRole('heading', { name: /^Setups$/i })
  }

  // ── Table ─────────────────────────────────────────────────────────────────────

  getSetupsTable(): Locator {
    return this.page.getByTestId('setups-table').locator('.ant-table')
  }

  getNoSetupsAlert(): Locator {
    return this.page.getByTestId('no-setups-alert')
  }

  async setupExists(setupId: string): Promise<boolean> {
    const row = this.page.locator(`tr:has-text("${setupId}")`)
    return (await row.count()) > 0
  }

  async getSetupCount(): Promise<number> {
    return this.getSetupsTable().locator('tbody tr').count()
  }

  // ── Buttons ───────────────────────────────────────────────────────────────────

  getCreateSetupButton(): Locator {
    return this.page.getByTestId('create-setup-button')
  }

  getRefreshButton(): Locator {
    return this.page.getByTestId('refresh-setups-button')
  }

  getViewDetailsButton(setupId: string): Locator {
    return this.page.getByTestId(`view-details-${setupId}`)
  }

  getDeleteButton(setupId: string): Locator {
    return this.page.getByTestId(`delete-${setupId}`)
  }

  getSetupRow(setupId: string): Locator {
    return this.page.locator(`tr:has-text("${setupId}")`)
  }

  // ── Detail page (/setups/:setupId) ─────────────────────────────────────────────

  async gotoDetail(setupId: string) {
    await this.page.goto(`/setups/${setupId}`)
    await this.page.waitForLoadState('load')
  }

  getDetailPageRoot(): Locator {
    return this.page.getByTestId('setup-detail-page')
  }

  getDetailDescriptions(): Locator {
    return this.page.getByTestId('setup-detail-descriptions')
  }

  getDetailQueues(): Locator {
    return this.page.getByTestId('setup-detail-queues')
  }

  getDetailEventStores(): Locator {
    return this.page.getByTestId('setup-detail-event-stores')
  }

  getDetailRefreshButton(): Locator {
    return this.page.getByTestId('refresh-detail-button')
  }

  getDetailDeleteButton(): Locator {
    return this.page.getByTestId('delete-detail-button')
  }

  getDetailBackButton(): Locator {
    return this.page.getByTestId('back-button')
  }
}
