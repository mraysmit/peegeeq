import { Page, Locator } from '@playwright/test'
import { BasePage } from './BasePage'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Generator Page Object — Message Generator page (/generator).
 *
 * Wraps all interactions with the TargetSelector component and the
 * Create Setup page (/generator/setup/new) navigated to from here.
 */
export class GeneratorPage extends BasePage {
  constructor(page: Page) {
    super(page)
  }

  async goto() {
    await this.page.goto('/generator')
    await this.page.waitForLoadState('load')
  }

  // ── Page heading ─────────────────────────────────────────────────────────────

  getHeading(): Locator {
    return this.page.getByRole('heading', { name: /queue message generator/i })
  }

  // ── Zone A — TargetSelector states ───────────────────────────────────────────

  getLoadingSpinner(): Locator {
    return this.page.getByTestId('loading-state')
  }

  getEmptyStateAlert(): Locator {
    return this.page.getByText(/No PeeGeeQ setup connected/i)
  }

  getConnectSetupButton(): Locator {
    return this.page.getByRole('button', { name: /Connect setup/i })
  }

  getNoQueuesAlert(): Locator {
    return this.page.getByText(/No queues found for this setup/i)
  }

  getQueuesPageLink(): Locator {
    return this.page.getByRole('link', { name: /Setups page/i })
  }

  // ── Zone A — Normal state ────────────────────────────────────────────────────

  getSetupSelect(): Locator {
    return this.page.locator('#target-setup-select')
  }

  getQueueSelect(): Locator {
    return this.page.locator('#target-queue-select')
  }

  getManageQueuesLink(): Locator {
    return this.page.getByText(/Manage queues/i)
  }

  async selectSetup(name: string) {
    await selectAntOption(this.getSetupSelect(), name)
  }

  async selectQueue(name: string) {
    await selectAntOption(this.getQueueSelect(), name)
  }

  // ── Create Setup page (/generator/setup/new) ─────────────────────────────────

  getCreateSetupPageRoot(): Locator {
    return this.page.getByTestId('create-setup-page')
  }

  getCreateSetupPageHeading(): Locator {
    return this.page.getByRole('heading', { name: /^Create Setup$/i })
  }

  getWizardSetupNameInput(): Locator {
    return this.page.getByLabel(/Setup name/i)
  }

  getWizardDatabaseNameInput(): Locator {
    return this.page.getByLabel(/Database name/i)
  }

  getWizardPasswordInput(): Locator {
    return this.page.getByLabel(/Database password/i)
  }

  getWizardCreateButton(): Locator {
    return this.page.getByTestId('create-button')
  }

  getWizardCancelButton(): Locator {
    return this.page.getByRole('button', { name: /^Cancel$/i })
  }

  async expandConnectionDetails() {
    await this.page.getByText('Connection details').click()
  }

  getWizardHostInput(): Locator {
    return this.page.getByLabel(/^Host$/i)
  }

  getWizardPortInput(): Locator {
    return this.page.getByLabel(/^Port$/i)
  }

  getWizardUsernameInput(): Locator {
    return this.page.getByLabel(/^Username$/i)
  }

  getWizardSchemaInput(): Locator {
    return this.page.getByLabel(/^Schema$/i)
  }

  // ── Inline error ──────────────────────────────────────────────────────────────

  getWizardErrorAlert(): Locator {
    return this.page.locator('.ant-alert-error')
  }
}
