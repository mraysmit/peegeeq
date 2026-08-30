import { test, expect, type Page } from '@playwright/test'

const modes = [
  { name: 'Flat rate', controls: 'rate-controls', result: 'progress-panel' },
  { name: 'Profile', controls: 'profile-phases-editor', result: 'profile-results-panel' },
  { name: 'Ramp', controls: 'ramp-controls', result: 'ramp-attribution-empty' },
  { name: 'Delay / Prio / FIFO', controls: 'exerciser-controls', result: 'manifest-empty' },
  { name: 'Trace seed', controls: 'trace-controls', result: 'trace-empty' },
]

async function selectMode(page: Page, name: string) {
  const radio = page.getByRole('radio', { name })
  // Ant Design visually hides the native radio input. DOM activation still
  // follows the component's real onChange path without depending on label
  // placement outside a narrow viewport.
  await radio.evaluate((element: HTMLInputElement) => element.click())
  return radio
}

test.describe('Generator mode contracts', () => {
  for (const mode of modes) {
    test(`${mode.name}: selects the requested mode`, async ({ page }) => {
      await page.goto('/generator')
      const radio = await selectMode(page, mode.name)
      await expect(radio).toBeChecked()
    })

    test(`${mode.name}: renders its dedicated controls`, async ({ page }) => {
      await page.goto('/generator')
      await selectMode(page, mode.name)
      await expect(page.getByTestId(mode.controls)).toBeVisible()
    })

    test(`${mode.name}: renders its result or report surface`, async ({ page }) => {
      await page.goto('/generator')
      await selectMode(page, mode.name)
      await expect(page.getByTestId(mode.result)).toBeVisible()
    })
  }
})
