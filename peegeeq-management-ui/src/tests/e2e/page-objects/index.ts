import { test as base } from '@playwright/test'
import { BasePage } from './BasePage'
import { QueuesPage } from './QueuesPage'
import { DatabaseSetupsPage } from './DatabaseSetupsPage'

/**
 * Extended Playwright test with page object fixtures
 */
export const test = base.extend<{
  basePage: BasePage
  queuesPage: QueuesPage
  databaseSetupsPage: DatabaseSetupsPage
}>({
  basePage: async ({ page }, use) => {
    const basePage = new BasePage(page)
    await use(basePage)
  },

  queuesPage: async ({ page }, use) => {
    const queuesPage = new QueuesPage(page)
    await use(queuesPage)
  },

  databaseSetupsPage: async ({ page }, use) => {
    const databaseSetupsPage = new DatabaseSetupsPage(page)
    await use(databaseSetupsPage)
  },
})

export { expect } from '@playwright/test'

