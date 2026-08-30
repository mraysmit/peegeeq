import { test, expect, type Page } from '@playwright/test'

const checks: Array<{ name: string; verify: (page: Page) => Promise<void> }> = [
  { name: 'generator page root', verify: async (page) => expect(page.getByTestId('generator-page')).toBeVisible() },
  { name: 'Queue Message Generator heading', verify: async (page) => expect(page.getByRole('heading', { name: 'Queue Message Generator' })).toBeVisible() },
  { name: 'mode selector', verify: async (page) => expect(page.getByTestId('generator-mode')).toBeVisible() },
  { name: 'six generator modes', verify: async (page) => expect(page.getByRole('radio')).toHaveCount(6) },
  { name: 'target zone', verify: async (page) => expect(page.getByTestId('zone-a')).toBeVisible() },
  { name: 'scenario controls', verify: async (page) => expect(page.getByTestId('scenario-bar')).toBeVisible() },
  { name: 'flat-rate controls', verify: async (page) => expect(page.getByTestId('rate-controls')).toBeVisible() },
  { name: 'template editor', verify: async (page) => expect(page.getByTestId('template-editor')).toBeVisible() },
  { name: 'generator actions', verify: async (page) => expect(page.getByTestId('generator-actions')).toBeVisible() },
  { name: 'idle progress panel', verify: async (page) => expect(page.getByTestId('progress-panel')).toBeVisible() },
]

test.describe('Generator core contracts', () => {
  for (const check of checks) {
    test(check.name, async ({ page }) => {
      await page.goto('/generator')
      await expect(page.getByTestId('generator-page')).toBeVisible()
      await check.verify(page)
    })
  }
})
