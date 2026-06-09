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

  // Route RTK Query through the Vite proxy so all pages load correctly.
  // RTK Query defaults to http://127.0.0.1:8088 (direct backend) which bypasses
  // the proxy and causes CORS/routing issues. Setting apiUrl to localhost:3000
  // ensures all API calls go through the Vite proxy consistently.
  page: async ({ page }, use) => {
    await page.addInitScript(() => {
      localStorage.setItem('peegeeq_backend_config', JSON.stringify({
        apiUrl: 'http://localhost:3000',
        wsUrl:  'ws://localhost:3000',
      }))
    })
    await use(page)
  },

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

