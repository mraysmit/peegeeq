import { Locator, expect } from '@playwright/test'

/**
 * Select an option from an Ant Design Select dropdown.
 *
 * Ant Design Select is not a native <select>; it renders a custom dropdown.
 * This helper:
 *   1. Clicks the trigger to open the dropdown.
 *   2. Waits for the dropdown to be visible (without filtering by text first,
 *      because the dropdown may still be loading options asynchronously).
 *   3. Waits for the specific option text to appear and clicks it.
 *   4. Confirms the dropdown closes.
 */
export async function selectAntOption(select: Locator, optionText: string | RegExp) {
  await expect(select).toBeVisible()
  await expect(select).toBeEnabled()
  await select.click()

  const page = select.page()

  // Step 1: wait for any visible dropdown (not one that is animating away or hidden).
  const openDropdown = page
    .locator('.ant-select-dropdown')
    .filter({ hasNot: page.locator('.ant-slide-up-leave') })
    .filter({ hasNot: page.locator('.ant-slide-up-leave-active') })
    .filter({ hasNot: page.locator('.ant-select-dropdown-hidden') })
    .last() // highest z-index / most recently opened

  await expect(openDropdown).toBeVisible()

  // Step 2: wait for the specific option to appear (allows async option loading).
  const targetOption = openDropdown
    .locator('.ant-select-item-option-content')
    .filter({ hasText: optionText })
    .first()

  await expect(targetOption).toBeVisible({ timeout: 15000 })
  await targetOption.click()

  // Ensure dropdown closes after selection.
  await expect(openDropdown).toBeHidden({ timeout: 5000 })
}
