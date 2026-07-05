import { Locator, expect } from '@playwright/test';

export async function selectAntOption(select: Locator, optionText: string | RegExp) {
  await expect(select).toBeVisible();
  await expect(select).toBeEnabled();
  await select.click();

  const page = select.page();

  // Step 1: wait for the dropdown to open (any visible dropdown, not filtered by text).
  // The dropdown may initially be in a loading state (async options fetch not complete),
  // so we do NOT filter by option text here — that would return <element(s) not found>
  // while loading, causing an immediate failure instead of a wait.
  const openDropdown = page.locator('.ant-select-dropdown')
    .filter({ hasNot: page.locator('.ant-slide-up-leave') })
    .filter({ hasNot: page.locator('.ant-slide-up-leave-active') })
    .filter({ hasNot: page.locator('.ant-select-dropdown-hidden') })
    .last(); // highest z-index / most recently opened

  await expect(openDropdown).toBeVisible();

  // Step 1b: if the Select is searchable (showSearch), type the option text to filter.
  // With long option lists antd virtualizes the dropdown and only renders the visible
  // slice, so a specific far-down option is never in the DOM and toBeVisible() times out.
  // Filtering brings the target into the rendered set. No-op (guarded) for non-searchable
  // selects, whose search input is not editable, so existing callers are unaffected.
  if (typeof optionText === 'string') {
    const searchInput = select.locator('.ant-select-selection-search-input');
    if (await searchInput.isEditable().catch(() => false)) {
      await searchInput.fill(optionText);
    }
  }

  // Step 2: wait for the specific option to appear inside the open dropdown.
  // Use a generous timeout (15s) to allow async option loading to complete.
  const targetOption = openDropdown
    .locator('.ant-select-item-option-content')
    .filter({ hasText: optionText })
    .first();

  await expect(targetOption).toBeVisible({ timeout: 15000 });
  await targetOption.click();

  // Ensure dropdown closes after selection
  await expect(openDropdown).toBeHidden({ timeout: 5000 });
}
