import { test, expect, type Page } from '@playwright/test'

type Contract = {
  name: string
  path: string
  root: string
  checks: Array<{ name: string; verify: (page: Page) => Promise<void> }>
}

const contracts: Contract[] = [
  {
    name: 'Scenario tools',
    path: '/tools',
    root: 'tools-page',
    checks: [
      { name: 'page root', verify: async (page) => expect(page.getByTestId('tools-page')).toBeVisible() },
      { name: 'heading', verify: async (page) => expect(page.getByRole('heading', { name: 'Generation Tools' })).toBeVisible() },
      { name: 'import action', verify: async (page) => expect(page.getByRole('button', { name: 'Import' })).toBeVisible() },
      { name: 'empty scenario state', verify: async (page) => expect(page.getByTestId('scenarios-empty')).toBeAttached() },
      { name: 'generator guidance link', verify: async (page) => expect(page.getByTestId('tools-page').getByRole('link', { name: 'Message Generator' })).toBeVisible() },
    ],
  },
  {
    name: 'Scheduled runs',
    path: '/generator/schedules',
    root: 'scheduled-runs-page',
    checks: [
      { name: 'page root', verify: async (page) => expect(page.getByTestId('scheduled-runs-page')).toBeVisible() },
      { name: 'heading', verify: async (page) => expect(page.getByRole('heading', { name: 'Scheduled Runs' })).toBeVisible() },
      { name: 'browser-only scheduling notice', verify: async (page) => expect(page.getByTestId('scheduled-runs-page')).toContainText('fire only while this app is open') },
      { name: 'three workflow tabs', verify: async (page) => expect(page.getByRole('tab')).toHaveCount(3) },
      { name: 'empty schedules state', verify: async (page) => expect(page.getByTestId('schedules-empty')).toBeAttached() },
    ],
  },
]

test.describe('Scenario and scheduling contracts', () => {
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

test.describe('Scenario and scheduling interactions', () => {
  test('scenario guidance opens the Message Generator', async ({ page }) => {
    await page.goto('/tools')
    await page.getByTestId('tools-page').getByRole('link', { name: 'Message Generator' }).click()
    await expect(page).toHaveURL(/\/generator$/)
  })

  test('scenario Import opens the file dialog', async ({ page }) => {
    await page.goto('/tools')
    await page.getByRole('button', { name: 'Import' }).click()
    await expect(page.locator('.ant-modal-title')).toHaveText('Import scenarios')
    await expect(page.getByTestId('import-file-dialog')).toBeVisible()
  })

  test('Run history tab exposes its result filter', async ({ page }) => {
    await page.goto('/generator/schedules')
    await page.getByRole('tab', { name: 'Run history' }).click()
    await expect(page.getByTestId('history-result-filter')).toBeVisible()
  })

  test('Templates tab exposes its documented empty state', async ({ page }) => {
    await page.goto('/generator/schedules')
    await page.getByRole('tab', { name: 'Templates' }).click()
    await expect(page.getByTestId('templates-empty')).toBeVisible()
  })

  test('schedule Import opens the file dialog', async ({ page }) => {
    await page.goto('/generator/schedules')
    await page.getByRole('button', { name: 'Import' }).click()
    await expect(page.locator('.ant-modal-title')).toHaveText('Import schedules')
    await expect(page.getByTestId('import-file-dialog')).toBeVisible()
  })
})
