import { Page, Locator } from '@playwright/test'
import { BasePage } from './BasePage'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Create Queue Page Object — Create Queue page (/setups/:setupId/queues/new).
 *
 * Wraps all interactions with the CreateQueuePage component:
 * queue-name field, implementation-type select, advanced settings collapse,
 * inline error alert, and the Back / Cancel / Create queue buttons.
 */
export class CreateQueuePage extends BasePage {
  constructor(page: Page) {
    super(page)
  }

  async goto(setupId: string) {
    await this.page.goto(`/setups/${setupId}/queues/new`)
    await this.page.waitForLoadState('load')
  }

  // ── Page root / heading ─────────────────────────────────────────────────────

  getPageRoot(): Locator {
    return this.page.getByTestId('create-queue-page')
  }

  getHeading(setupId: string): Locator {
    return this.page.getByRole('heading', { name: `Create Queue in ${setupId}` })
  }

  // ── Form fields ─────────────────────────────────────────────────────────────

  getQueueNameInput(): Locator {
    return this.page.getByLabel('Queue name')
  }

  getImplementationTypeSelect(): Locator {
    return this.page.getByTestId('implementation-type-select')
  }

  /**
   * The currently-selected implementation type text shown in the Select trigger.
   */
  getSelectedImplementationType(): Locator {
    return this.getImplementationTypeSelect().locator('.ant-select-selection-item')
  }

  async selectImplementationType(value: 'native' | 'outbox') {
    await selectAntOption(this.getImplementationTypeSelect(), value)
  }

  async fillQueueName(name: string) {
    await this.getQueueNameInput().fill(name)
  }

  // ── Advanced settings collapse ──────────────────────────────────────────────

  getAdvancedSettingsHeader(): Locator {
    return this.page.getByText('Advanced settings')
  }

  async expandAdvancedSettings() {
    await this.getAdvancedSettingsHeader().click()
  }

  getMaxRetriesInput(): Locator {
    return this.page.getByLabel('Max retries')
  }

  getVisibilityTimeoutInput(): Locator {
    return this.page.getByLabel('Visibility timeout (s)')
  }

  getBatchSizeInput(): Locator {
    return this.page.getByLabel('Batch size')
  }

  getDeadLetterCheckbox(): Locator {
    return this.page.getByText('Enable dead-letter queue')
  }

  getFifoCheckbox(): Locator {
    return this.page.getByText('FIFO ordering')
  }

  // ── Buttons ─────────────────────────────────────────────────────────────────

  getCreateButton(): Locator {
    return this.page.getByTestId('create-queue-button')
  }

  getCancelButton(): Locator {
    return this.page.getByRole('button', { name: /^Cancel$/ })
  }

  getBackButton(): Locator {
    return this.page.getByTestId('back-button')
  }

  // ── Inline error ────────────────────────────────────────────────────────────

  getErrorAlert(): Locator {
    return this.page.getByTestId('create-queue-error')
  }

  getValidationMessage(): Locator {
    return this.page.getByText('Please enter a queue name')
  }
}
