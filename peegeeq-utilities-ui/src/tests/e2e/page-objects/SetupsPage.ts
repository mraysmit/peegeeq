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

  getConnectSetupButton(): Locator {
    return this.page.getByTestId('connect-setup-button')
  }

  getRefreshButton(): Locator {
    return this.page.getByTestId('refresh-setups-button')
  }

  getViewDetailsButton(setupId: string): Locator {
    return this.page.getByTestId(`view-details-${setupId}`)
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

  // ── Detail page — queues section ────────────────────────────────────────────

  /**
   * The "Create queue" button in the queues section of the setup detail page.
   * Navigates to /setups/:setupId/queues/new.
   */
  getDetailCreateQueueButton(): Locator {
    return this.getDetailQueues().getByTestId('create-queue-button')
  }

  /**
   * The list row for a named queue within the setup detail queues section.
   */
  getQueueRow(queueName: string): Locator {
    return this.getDetailQueues().locator('.ant-list-item').filter({ hasText: queueName })
  }

  /**
   * The implementation-type Tag shown next to a named queue.
   */
  getQueueTypeTag(queueName: string): Locator {
    return this.getQueueRow(queueName).locator('.ant-tag')
  }

  /**
   * The per-row delete button for a named queue.
   */
  getDeleteQueueButton(queueName: string): Locator {
    return this.page.getByTestId(`delete-queue-${queueName}`)
  }
}
