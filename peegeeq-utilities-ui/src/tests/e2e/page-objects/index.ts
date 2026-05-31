import { test as base } from '@playwright/test'
import { BasePage } from './BasePage'
import { OverviewPage } from './OverviewPage'
import { GeneratorPage } from './GeneratorPage'
import { SetupsPage } from './SetupsPage'
import { CreateQueuePage } from './CreateQueuePage'

/**
 * Extended Playwright test fixture that injects page objects.
 *
 * Import { test, expect } from this module instead of '@playwright/test'
 * whenever a spec needs page object fixtures.
 */
export const test = base.extend<{
  basePage: BasePage
  overviewPage: OverviewPage
  generatorPage: GeneratorPage
  setupsPage: SetupsPage
  createQueuePage: CreateQueuePage
}>({
  basePage: async ({ page }, use) => {
    await use(new BasePage(page))
  },

  overviewPage: async ({ page }, use) => {
    await use(new OverviewPage(page))
  },

  generatorPage: async ({ page }, use) => {
    await use(new GeneratorPage(page))
  },

  setupsPage: async ({ page }, use) => {
    await use(new SetupsPage(page))
  },

  createQueuePage: async ({ page }, use) => {
    await use(new CreateQueuePage(page))
  },
})

export { expect } from '@playwright/test'
