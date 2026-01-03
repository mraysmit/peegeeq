/**
 * Form Helper Functions for E2E Tests
 * 
 * Provides utility functions for interacting with Ant Design forms
 * which use custom components that require special handling.
 */

import { Page, Locator } from '@playwright/test'

/**
 * Fill an Ant Design Input field
 */
export async function fillAntInput(page: Page, label: string, value: string): Promise<void> {
  // Try multiple selector strategies
  const selectors = [
    `input[placeholder*="${label}" i]`,
    `.ant-form-item:has-text("${label}") input`,
    `input[id*="${label.toLowerCase().replace(/\s+/g, '-')}"]`,
    `.ant-input:near(:text("${label}"))`
  ]

  for (const selector of selectors) {
    try {
      const input = page.locator(selector).first()
      if (await input.isVisible({ timeout: 2000 })) {
        await input.fill(value)
        return
      }
    } catch (error) {
      // Try next selector
    }
  }

  // Fallback: find by visible label
  const formItem = page.locator(`.ant-form-item:has-text("${label}")`).first()
  const input = formItem.locator('input').first()
  await input.fill(value)
}

/**
 * Select an option from an Ant Design Select dropdown
 */
export async function selectAntOption(page: Page, label: string, option: string): Promise<void> {
  // Find the select by label
  const formItem = page.locator(`.ant-form-item:has-text("${label}")`).first()
  const selector = formItem.locator('.ant-select-selector').first()
  
  // Click to open dropdown
  await selector.click()
  
  // Wait for dropdown option to be visible
  const optionLocator = page.locator(`.ant-select-item:has-text("${option}")`).first()
  await optionLocator.waitFor({ state: 'visible' })
  
  // Click the option in the dropdown
  await optionLocator.click()
  
  // Wait for dropdown to close
  await optionLocator.waitFor({ state: 'hidden' })
}

/**
 * Select an option from an Ant Design Select by placeholder
 */
export async function selectAntOptionByPlaceholder(page: Page, placeholder: string, option: string): Promise<void> {
  // Find select by placeholder in the selector
  const selector = page.locator(`.ant-select:has(.ant-select-selector[title="${placeholder}"]) .ant-select-selector`).first()
  
  // Click to open dropdown
  await selector.click()
  
  // Wait for dropdown option to be visible
  const optionLocator = page.locator(`.ant-select-item:has-text("${option}")`).first()
  await optionLocator.waitFor({ state: 'visible' })
  
  // Click the option
  await optionLocator.click()
  
  // Wait for dropdown to close
  await optionLocator.waitFor({ state: 'hidden' })
}

/**
 * Click a button in an Ant Design modal
 */
export async function clickModalButton(page: Page, buttonText: string): Promise<void> {
  const button = page.locator(`.ant-modal .ant-btn:has-text("${buttonText}")`).first()
  await button.click()
}

/**
 * Wait for Ant Design modal to be visible
 */
export async function waitForModal(page: Page, title?: string): Promise<Locator> {
  const modal = page.locator('.ant-modal').first()
  await modal.waitFor({ state: 'visible' })
  
  if (title) {
    await page.locator(`.ant-modal-title:has-text("${title}")`).waitFor({ state: 'visible' })
  }
  
  return modal
}

/**
 * Wait for Ant Design modal to be hidden
 */
export async function waitForModalHidden(page: Page): Promise<void> {
  await page.locator('.ant-modal').first().waitFor({ state: 'hidden', timeout: 5000 })
}

/**
 * Get validation error message from Ant Design form
 */
export async function getValidationError(page: Page, fieldLabel?: string): Promise<string | null> {
  let errorLocator: Locator
  
  if (fieldLabel) {
    const formItem = page.locator(`.ant-form-item:has-text("${fieldLabel}")`).first()
    errorLocator = formItem.locator('.ant-form-item-explain-error').first()
  } else {
    errorLocator = page.locator('.ant-form-item-explain-error').first()
  }
  
  try {
    await errorLocator.waitFor({ state: 'visible', timeout: 2000 })
    return await errorLocator.textContent()
  } catch (error) {
    return null
  }
}

/**
 * Check if Ant Design table has data
 */
export async function hasTableData(page: Page): Promise<boolean> {
  const emptyRow = page.locator('.ant-table-placeholder')
  const dataRows = page.locator('.ant-table-tbody tr:not(.ant-table-placeholder)')
  
  try {
    await emptyRow.waitFor({ state: 'visible', timeout: 2000 })
    return false
  } catch {
    const count = await dataRows.count()
    return count > 0
  }
}

/**
 * Wait for Ant Design table to load data
 */
export async function waitForTableData(page: Page, timeoutMs: number = 10000): Promise<void> {
  await page.waitForSelector('.ant-table-tbody tr:not(.ant-table-placeholder)', { 
    state: 'visible',
    timeout: timeoutMs 
  })
}

/**
 * Get row count from Ant Design table
 */
export async function getTableRowCount(page: Page): Promise<number> {
  const rows = page.locator('.ant-table-tbody tr:not(.ant-table-placeholder)')
  return await rows.count()
}

/**
 * Click action button in table row
 */
export async function clickTableRowAction(page: Page, rowIndex: number, actionText: string): Promise<void> {
  const row = page.locator('.ant-table-tbody tr:not(.ant-table-placeholder)').nth(rowIndex)
  
  // Click the actions dropdown
  const actionButton = row.locator('.ant-dropdown-trigger, button:has-text("Actions")').first()
  await actionButton.click()
  
  // Wait for dropdown menu item
  const menuItem = page.locator(`.ant-dropdown-menu-item:has-text("${actionText}")`).first()
  await menuItem.waitFor({ state: 'visible' })
  
  // Click the action
  await menuItem.click()
}
