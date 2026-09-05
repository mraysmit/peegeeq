import { expect, test } from '@playwright/test'

test('browser report preserves the real outcome', async ({ page }) => {
  await page.setContent('<h1>Report contract</h1>')
  await expect(page.getByRole('heading')).toHaveText(
    process.env.PEEGEEQ_REPORT_CONTRACT_FAIL === '1' ? 'Intentional failure' : 'Report contract'
  )
})
