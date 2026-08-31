import { test, expect, type Locator, type Page, type TestInfo } from '@playwright/test'
import fs from 'node:fs'

const TEMPLATE_NAME = 'E2E lifecycle template'

async function captureEvidence(
  page: Page,
  testInfo: TestInfo,
  name: string,
  target: Locator = page.locator('body')
): Promise<void> {
  await expect(target).toBeVisible()
  const screenshotPath = testInfo.outputPath(`${name}.png`)
  await target.screenshot({ path: screenshotPath, animations: 'disabled' })
  await testInfo.attach(`${name}.png`, { path: screenshotPath, contentType: 'image/png' })
}

async function createTemplate(
  page: Page,
  name: string,
  messageType: string,
  payload = '{"id":"{{messageId}}"}'
): Promise<void> {
  await page.goto('/generator')
  await expect(page.getByTestId('template-editor')).toBeVisible()
  await page.getByLabel(/^Name$/).fill(name)
  await page.getByLabel(/Message type/i).fill(messageType)
  await page.getByLabel(/Payload/i).fill(payload)
  await page.getByRole('button', { name: /^Save$/ }).click()
  await expect(page.locator('.ant-message-success')).toContainText(`Template "${name}" saved`)
}

async function createValueList(page: Page, name: string, values: string): Promise<void> {
  await page.goto('/generator/value-lists')
  await page.getByRole('button', { name: 'New List' }).click()
  await page.getByLabel('List name').fill(name)
  await page.getByLabel('Values (one per line)').fill(values)
  await page.getByRole('button', { name: /^Save$/ }).click()
  await expect(page.getByTestId(`list-count-${name}`)).toBeVisible()
}

async function importValueList(page: Page, name: string, values: unknown[]): Promise<void> {
  await page.getByRole('button', { name: 'Import JSON file' }).click()
  await page.getByTestId('value-list-import-input').setInputFiles({
    name: `${name}.json`,
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(values)),
  })
}

async function openTemplateImport(page: Page): Promise<void> {
  await page
    .getByTestId('template-manager-page')
    .locator('button')
    .filter({ hasText: /^Import$/ })
    .click()
}

test.describe('Template lifecycle — semantic browser journeys', () => {
  test('duplicate → edit → save → delete original persists the independent copy', async ({ page }, testInfo) => {
    await createTemplate(page, TEMPLATE_NAME, 'lifecycle.v1')

    await page.goto('/generator/templates')
    const originalRow = page.getByRole('row').filter({
      has: page.getByRole('link', { name: TEMPLATE_NAME, exact: true }),
    })
    await originalRow.locator('[data-testid^="template-duplicate-"]').click()

    const copyName = `${TEMPLATE_NAME} (copy)`
    const copyRow = page.getByRole('row').filter({
      has: page.getByRole('link', { name: copyName, exact: true }),
    })
    await expect(copyRow).toContainText('lifecycle.v1')
    await copyRow.locator('[data-testid^="template-edit-"]').click()
    await expect(page).toHaveURL(/\/generator$/)
    await expect(page.getByLabel(/^Name$/)).toHaveValue(copyName)

    await page.getByLabel(/Message type/i).fill('lifecycle.copy.v2')
    await page.getByRole('button', { name: /^Save$/ }).click()
    await expect(page.locator('.ant-message-success')).toContainText(`Template "${copyName}" saved`)

    await page.goto('/generator/templates')
    await expect(page.getByRole('row').filter({ hasText: copyName })).toContainText('lifecycle.copy.v2')
    const persistedOriginalRow = page.getByRole('row').filter({
      has: page.getByRole('link', { name: TEMPLATE_NAME, exact: true }),
    })
    await persistedOriginalRow.locator('[data-testid^="template-delete-"]').click()
    await page.locator('.ant-popconfirm').getByRole('button', { name: /^Delete$/ }).click()
    await expect(page.getByRole('link', { name: TEMPLATE_NAME, exact: true })).not.toBeVisible()

    await page.reload()
    const persistedCopy = page.getByRole('row').filter({
      has: page.getByRole('link', { name: copyName, exact: true }),
    })
    await expect(persistedCopy).toContainText('lifecycle.copy.v2')
    await captureEvidence(page, testInfo, 'template-independent-copy', persistedCopy)
  })

  test('manager export downloads the complete persisted template', async ({ page }, testInfo) => {
    await createTemplate(page, 'E2E exported template', 'exported.v1', '{"order":"{{uuid}}"}')
    await page.goto('/generator/templates')
    const row = page.getByRole('row').filter({
      has: page.getByRole('link', { name: 'E2E exported template', exact: true }),
    })

    const downloadPromise = page.waitForEvent('download')
    await row.locator('[data-testid^="template-export-"]').click()
    const download = await downloadPromise
    expect(download.suggestedFilename()).toBe('E2E exported template.json')
    expect(await download.failure()).toBeNull()
    const downloadPath = await download.path()
    expect(downloadPath).toBeTruthy()
    const exported = JSON.parse(fs.readFileSync(downloadPath!, 'utf8')) as Record<string, unknown>
    expect(exported).toMatchObject({
      name: 'E2E exported template',
      messageType: 'exported.v1',
      payloadSchema: '{"order":"{{uuid}}"}',
      priority: 5,
      delaySeconds: 0,
    })
    expect(typeof exported.id).toBe('string')
    await captureEvidence(page, testInfo, 'template-exported-row', row)
  })

  test('import adds a valid template and rejects a duplicate ID without overwriting it', async ({ page }, testInfo) => {
    const now = new Date().toISOString()
    const imported = {
      id: 'e2e-imported-template-id',
      name: 'E2E imported template',
      messageType: 'imported.v1',
      payloadSchema: '{"source":"import"}',
      headers: { source: 'playwright' },
      priority: 4,
      delaySeconds: 2,
      createdAt: now,
      updatedAt: now,
    }

    await page.goto('/generator/templates')
    await openTemplateImport(page)
    await page.getByTestId('template-import-input').setInputFiles({
      name: 'template.json',
      mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify(imported)),
    })
    const importedRow = page.getByRole('row').filter({
      has: page.getByRole('link', { name: imported.name, exact: true }),
    })
    await expect(importedRow).toContainText('imported.v1')

    await openTemplateImport(page)
    await page.getByTestId('template-import-input').setInputFiles({
      name: 'duplicate-template.json',
      mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify({ ...imported, name: 'Overwrite attempt', messageType: 'imported.v2' })),
    })
    const warning = page.locator('.ant-message-warning').filter({ hasText: imported.id })
    await expect(warning).toBeVisible()
    await expect(importedRow).toContainText('imported.v1')
    await expect(page.getByText('Overwrite attempt', { exact: true })).toHaveCount(0)
    await captureEvidence(page, testInfo, 'template-duplicate-import-warning', warning)
  })

  test('malformed template import surfaces a named error and persists nothing', async ({ page }, testInfo) => {
    await page.goto('/generator/templates')
    await openTemplateImport(page)
    await page.getByTestId('template-import-input').setInputFiles({
      name: 'broken-template.json',
      mimeType: 'application/json',
      buffer: Buffer.from('not-json'),
    })

    const error = page.locator('.ant-message-error').filter({ hasText: 'broken-template.json: not valid JSON' })
    await expect(error).toBeVisible()
    await expect(page.getByTestId('template-table').locator('tbody .ant-table-row')).toHaveCount(0)
    await page.reload()
    await expect(page.getByTestId('template-table').locator('tbody .ant-table-row')).toHaveCount(0)
    await captureEvidence(page, testInfo, 'template-malformed-import', page.getByTestId('template-manager-page'))
  })

  test('dirty working copy requires confirmation and Cancel preserves the edits', async ({ page }, testInfo) => {
    await createTemplate(page, 'E2E dirty template', 'dirty.v1')
    await page.getByLabel(/Message type/i).fill('dirty.unsaved.v2')
    await page.getByRole('button', { name: /^New$/ }).click()

    const firstConfirm = page.locator('.ant-modal-confirm')
    await expect(firstConfirm).toContainText('Unsaved changes')
    await expect(firstConfirm).toContainText('Discard the current edits?')
    await firstConfirm.getByRole('button', { name: /^Cancel$/ }).click()
    await expect(page.getByLabel(/Message type/i)).toHaveValue('dirty.unsaved.v2')

    await page.getByRole('button', { name: /^New$/ }).click()
    const secondConfirm = page.locator('.ant-modal-confirm')
    await expect(secondConfirm).toBeVisible()
    await captureEvidence(page, testInfo, 'template-unsaved-confirmation', secondConfirm)
    await secondConfirm.getByRole('button', { name: /^OK$/ }).click()
    await expect(page.getByLabel(/^Name$/)).toHaveValue('Untitled')
    await expect(page.getByLabel(/Message type/i)).not.toHaveValue('dirty.unsaved.v2')
  })
})

test.describe('Value-list lifecycle — semantic browser journeys', () => {
  test('edit and rename persist trimmed values while removing the old storage key', async ({ page }, testInfo) => {
    await createValueList(page, 'old_names', 'Ada\nGrace')
    await page.getByTestId('list-edit-old_names').click()
    await page.getByLabel('List name').fill('renamed_names')
    await page.getByLabel('Values (one per line)').fill('  Ada  \n\nGrace\nLinus\n  ')
    await expect(page.getByTestId('value-count')).toHaveText('3 values')
    await page.getByRole('button', { name: /^Save$/ }).click()

    await expect(page.getByTestId('list-count-old_names')).toHaveCount(0)
    await expect(page.getByTestId('list-count-renamed_names')).toHaveText('3')
    await expect(page.getByTestId('list-preview-renamed_names')).toContainText('Ada, Grace, Linus')
    await page.reload()
    await expect(page.getByTestId('list-count-old_names')).toHaveCount(0)
    await expect(page.getByTestId('list-count-renamed_names')).toHaveText('3')
    await captureEvidence(
      page,
      testInfo,
      'value-list-renamed-and-persisted',
      page.getByRole('row').filter({ hasText: 'renamed_names' })
    )
  })

  test('rename collision keeps both original lists unchanged', async ({ page }, testInfo) => {
    await createValueList(page, 'first_list', 'one')
    await page.getByRole('button', { name: 'New List' }).click()
    await page.getByLabel('List name').fill('second_list')
    await page.getByLabel('Values (one per line)').fill('two')
    await page.getByRole('button', { name: /^Save$/ }).click()

    await page.getByTestId('list-edit-first_list').click()
    await page.getByLabel('List name').fill('second_list')
    await page.getByRole('button', { name: /^Save$/ }).click()
    const error = page.getByTestId('panel-error')
    await expect(error).toContainText('A list named "second_list" already exists')
    await expect(page.getByTestId('list-count-first_list')).toHaveText('1')
    await expect(page.getByTestId('list-count-second_list')).toHaveText('1')
    await captureEvidence(page, testInfo, 'value-list-rename-collision', error)

    await page.reload()
    await expect(page.getByTestId('list-preview-first_list')).toHaveText('one')
    await expect(page.getByTestId('list-preview-second_list')).toHaveText('two')
  })

  test('referenced-list deletion warns, Cancel preserves it, and confirmed deletion affects preview', async ({ page }, testInfo) => {
    await createValueList(page, 'customer_names', 'Ada\nGrace')
    await createTemplate(page, TEMPLATE_NAME, 'customer.generated', '{"name":"{{list:customer_names}}"}')
    await page.getByRole('button', { name: 'Add header' }).click()
    await page.getByTestId('header-key-0').fill('customer')
    await page.getByTestId('header-value-0').fill('{{list:customer_names}}')
    await page.getByRole('button', { name: /^Save$/ }).click()

    await page.goto('/generator/value-lists')
    await page.getByTestId('list-delete-customer_names').click()
    const warning = page.getByTestId('delete-references-warning')
    await expect(warning).toContainText(TEMPLATE_NAME)
    await expect(warning).toContainText('{{list:customer_names}}')
    await captureEvidence(page, testInfo, 'value-list-reference-warning', warning)
    await page.locator('.ant-modal-confirm').getByRole('button', { name: /^Cancel$/ }).click()
    await expect(page.getByTestId('list-count-customer_names')).toHaveText('2')

    await page.getByTestId('list-delete-customer_names').click()
    await page.locator('.ant-modal-confirm').getByRole('button', { name: /^Delete$/ }).click()
    await expect(page.getByTestId('list-count-customer_names')).toHaveCount(0)
    await page.reload()
    await expect(page.getByTestId('list-count-customer_names')).toHaveCount(0)

    await page.goto('/generator/templates')
    await page.getByRole('link', { name: TEMPLATE_NAME, exact: true }).click()
    await page.getByRole('button', { name: /^Preview$/ }).click()
    const previewWarning = page.getByTestId('missing-lists-warning')
    await expect(previewWarning).toContainText('customer_names')
    await expect(page.getByTestId('preview-modal').locator('pre')).toContainText('"name": ""')
    await expect(page.getByTestId('preview-modal').locator('pre')).toContainText('"customer": ""')
  })

  test('import collision Merge, Overwrite, and Cancel have distinct persisted semantics', async ({ page }, testInfo) => {
    await createValueList(page, 'first_names', 'Mark\nJanet')

    await importValueList(page, 'first_names', ['Mark', 'Dave'])
    const collisionModal = page.getByRole('dialog', { name: 'List "first_names" already exists' })
    await expect(collisionModal).toContainText('Overwrite replaces the existing 2 value(s)')
    await collisionModal.getByRole('button', { name: /^Merge$/ }).click()
    await expect(page.getByTestId('list-count-first_names')).toHaveText('3')
    await expect(page.getByTestId('list-preview-first_names')).toHaveText('Mark, Janet, Dave')

    await importValueList(page, 'first_names', ['Solo'])
    await expect(collisionModal).toBeVisible()
    await collisionModal.getByRole('button', { name: /^Overwrite$/ }).click()
    await expect(page.getByTestId('list-count-first_names')).toHaveText('1')
    await expect(page.getByTestId('list-preview-first_names')).toHaveText('Solo')

    await importValueList(page, 'first_names', ['Ignored'])
    await expect(collisionModal).toBeVisible()
    await collisionModal.getByRole('button', { name: /^Cancel$/ }).click()
    await expect(page.getByTestId('list-count-first_names')).toHaveText('1')
    await expect(page.getByTestId('list-preview-first_names')).toHaveText('Solo')
    await page.reload()
    await expect(page.getByTestId('list-preview-first_names')).toHaveText('Solo')
    await captureEvidence(
      page,
      testInfo,
      'value-list-collision-resolution',
      page.getByRole('row').filter({ hasText: 'first_names' })
    )
  })

  test('malformed import is rejected while numeric values are explicitly coerced and persisted', async ({ page }, testInfo) => {
    await page.goto('/generator/value-lists')
    await page.getByRole('button', { name: 'Import JSON file' }).click()
    await page.getByTestId('value-list-import-input').setInputFiles({
      name: 'broken.json',
      mimeType: 'application/json',
      buffer: Buffer.from('not-json'),
    })
    await expect(page.locator('.ant-message-error').filter({ hasText: 'broken.json: not valid JSON' })).toBeVisible()
    await expect(page.getByTestId('list-count-broken')).toHaveCount(0)

    await importValueList(page, 'numbers', [1, 2, 'three'])
    const warning = page.locator('.ant-message-warning').filter({ hasText: 'coerced 2 numeric value(s) to strings' })
    await expect(warning).toBeVisible()
    await expect(page.getByTestId('list-count-numbers')).toHaveText('3')
    await expect(page.getByTestId('list-preview-numbers')).toHaveText('1, 2, three')
    await captureEvidence(page, testInfo, 'value-list-numeric-coercion', warning)
    await page.reload()
    await expect(page.getByTestId('list-preview-numbers')).toHaveText('1, 2, three')
  })
})
