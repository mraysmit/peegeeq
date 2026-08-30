import { test, expect, type Page } from '@playwright/test'

type Contract = {
  name: string
  path: string
  root: string
  checks: Array<{ name: string; verify: (page: Page) => Promise<void> }>
}

const contracts: Contract[] = [
  {
    name: 'Template Manager',
    path: '/generator/templates',
    root: 'template-manager-page',
    checks: [
      { name: 'page root', verify: async (page) => expect(page.getByTestId('template-manager-page')).toBeVisible() },
      { name: 'heading', verify: async (page) => expect(page.getByRole('heading', { name: 'Template Manager' })).toBeVisible() },
      { name: 'new-template action', verify: async (page) => expect(page.getByRole('button', { name: 'New Template' })).toBeVisible() },
      { name: 'import action', verify: async (page) => expect(page.getByRole('button', { name: 'Import' })).toBeVisible() },
      { name: 'template table', verify: async (page) => expect(page.getByTestId('template-table')).toBeVisible() },
    ],
  },
  {
    name: 'Value List Manager',
    path: '/generator/value-lists',
    root: 'value-list-manager-page',
    checks: [
      { name: 'page root', verify: async (page) => expect(page.getByTestId('value-list-manager-page')).toBeVisible() },
      { name: 'heading', verify: async (page) => expect(page.getByRole('heading', { name: 'Value List Manager' })).toBeVisible() },
      { name: 'new-list action', verify: async (page) => expect(page.getByRole('button', { name: 'New List' })).toBeVisible() },
      { name: 'import action', verify: async (page) => expect(page.getByRole('button', { name: 'Import JSON file' })).toBeVisible() },
      { name: 'value-list table', verify: async (page) => expect(page.getByTestId('value-list-table')).toBeVisible() },
    ],
  },
]

test.describe('Template and value-list contracts', () => {
  for (const contract of contracts) {
    for (const check of contract.checks) {
      test(`${contract.name}: ${check.name}`, async ({ page }) => {
        await page.goto(contract.path)
        await expect(page.getByTestId(contract.root)).toBeVisible()
        await check.verify(page)
      })
    }
  }
})
