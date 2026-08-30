import { test, expect, type Page } from '@playwright/test'

type PageContract = {
  name: string
  path: string
  rootTestId: string
  checks: Array<{ name: string; verify: (page: Page) => Promise<void> }>
}

const contracts: PageContract[] = [
  {
    name: 'Setups list',
    path: '/setups',
    rootTestId: 'setups-page',
    checks: [
      { name: 'keeps the page root visible', verify: async (page) => expect(page.getByTestId('setups-page')).toBeVisible() },
      { name: 'keeps the Setups heading visible', verify: async (page) => expect(page.getByRole('heading', { level: 2, name: 'Setups' })).toBeVisible() },
      { name: 'keeps Refresh available', verify: async (page) => expect(page.getByTestId('refresh-setups-button')).toBeVisible() },
      { name: 'keeps Connect setup available', verify: async (page) => expect(page.getByTestId('connect-setup-button')).toBeVisible() },
      { name: 'keeps the Setups navigation item selected', verify: async (page) => expect(page.locator('.ant-menu-item-selected')).toHaveText('Setups') },
    ],
  },
  {
    name: 'Connect setup',
    path: '/setups/connect',
    rootTestId: 'connect-setup-page',
    checks: [
      { name: 'keeps the page root visible', verify: async (page) => expect(page.getByTestId('connect-setup-page')).toBeVisible() },
      { name: 'keeps the connect heading visible', verify: async (page) => expect(page.getByRole('heading', { level: 3, name: 'Connect to Existing Setup' })).toBeVisible() },
      { name: 'keeps Back available', verify: async (page) => expect(page.getByTestId('back-button')).toBeVisible() },
      { name: 'keeps Connect available', verify: async (page) => expect(page.getByTestId('connect-button')).toBeVisible() },
      { name: 'retains the non-destructive warning content', verify: async (page) => expect(page.getByTestId('connect-setup-page')).toContainText('Non-destructive connect') },
    ],
  },
]

test.describe('Setup and target entry contracts', () => {
  for (const contract of contracts) {
    for (const check of contract.checks) {
      test(`${contract.name}: ${check.name}`, async ({ page }) => {
        await page.goto(contract.path)
        await expect(page.getByTestId(contract.rootTestId)).toBeVisible()
        await check.verify(page)
      })
    }
  }
})
