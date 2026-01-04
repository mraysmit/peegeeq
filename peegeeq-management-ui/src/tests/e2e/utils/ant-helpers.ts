import { Locator, expect } from '@playwright/test';

export async function selectAntOption(select: Locator, optionText: string | RegExp) {
  await expect(select).toBeVisible();
  await expect(select).toBeEnabled();
  await select.click();

  const page = select.page();

  // Wait for the dropdown to appear.
  // We prioritize finding a VISIBLE dropdown.
  // Ant Design dropdowns are usually appended to the body.
  const dropdown = page.locator('.ant-select-dropdown')
    .filter({ hasNot: page.locator('.ant-slide-up-leave') })
    .filter({ hasNot: page.locator('.ant-slide-up-leave-active') })
    .filter({ hasNot: page.locator('.ant-select-dropdown-hidden') })
    .filter({ hasText: optionText }) // Ensure it contains our option
    .last(); // If multiple, usually the last one is the active one (highest z-index/most recent)

  await expect(dropdown).toBeVisible();

  const option = dropdown
    .locator('.ant-select-item-option-content')
    .filter({ hasText: optionText });

  // Use .first() to handle potential duplicates and ensure we click a visible one
  const targetOption = option.first();
  await expect(targetOption).toBeVisible();
  await targetOption.click();

  // Optional: ensure it closes
  await expect(dropdown).toBeHidden({ timeout: 5000 });
}
