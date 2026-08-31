import { test, expect, type Locator, type Page, type TestInfo } from '@playwright/test'
import fs from 'node:fs'

type Seed = {
  schedules: Array<Record<string, unknown>>
  history: Array<Record<string, unknown>>
  templates?: Array<Record<string, unknown>>
}

function template(id: string, name: string): Record<string, unknown> {
  const now = new Date().toISOString()
  return {
    id,
    name,
    messageType: 'e2e.scheduled',
    payloadSchema: '{"id":"{{messageId}}"}',
    headers: {},
    priority: 5,
    delaySeconds: 0,
    createdAt: now,
    updatedAt: now,
  }
}

function config(name: string): Record<string, unknown> {
  return {
    setupId: 'e2e-frozen-setup',
    queueName: 'e2e-frozen-queue',
    rate: 5,
    durationSecs: 2,
    maxBatchSize: 10,
    warnThreshold: 500,
    maxConsecErrors: 3,
    template: template(`template-${name}`, `${name} payload`),
    previewIndex: 1,
  }
}

function schedule(id: string, name: string, consumed = false): Record<string, unknown> {
  const now = new Date().toISOString()
  const runAt = new Date(Date.now() + 3_600_000).toISOString()
  return {
    id,
    name,
    config: config(name),
    schedule: { kind: 'once', runAt },
    enabled: !consumed,
    nextRunAt: consumed ? null : runAt,
    createdAt: now,
    updatedAt: now,
  }
}

function summary(runId: string): Record<string, unknown> {
  return {
    totalSent: 10,
    totalAttempted: 10,
    targetTotal: 10,
    avgRate: 5,
    durationMs: 2000,
    totalErrors: 0,
    finalStatus: 'completed',
    runId,
    errors: [],
  }
}

function historyRecord(
  id: string,
  scheduleId: string,
  scheduleName: string,
  result: 'completed' | 'missed'
): Record<string, unknown> {
  const frozen = config(scheduleName)
  return {
    id,
    scheduleId,
    scheduleName,
    target: { setupId: 'e2e-frozen-setup', queueName: 'e2e-frozen-queue' },
    outcome: {
      at: new Date().toISOString(),
      result,
      totalSent: result === 'completed' ? 10 : 0,
      totalErrors: 0,
      detail: result === 'missed' ? 'Application was closed' : undefined,
    },
    summary: result === 'completed' ? summary(`run-${id}`) : null,
    config: frozen,
  }
}

async function seed(page: Page, value: Seed): Promise<void> {
  await page.goto('/generator/schedules')
  await page.evaluate((seedValue) => {
    localStorage.setItem('peegeeq_generator_schedules', JSON.stringify(seedValue.schedules))
    localStorage.setItem('peegeeq_schedule_run_history', JSON.stringify(seedValue.history))
    localStorage.setItem('peegeeq_schedule_templates', JSON.stringify(seedValue.templates ?? []))
  }, value)
  await page.reload()
  await expect(page.getByTestId('scheduled-runs-page')).toBeVisible()
}

async function captureEvidence(
  target: Locator,
  testInfo: TestInfo,
  name: string
): Promise<void> {
  await expect(target).toBeVisible()
  const screenshotPath = testInfo.outputPath(`${name}.png`)
  await target.screenshot({ path: screenshotPath, animations: 'disabled' })
  await testInfo.attach(`${name}.png`, { path: screenshotPath, contentType: 'image/png' })
}

function localDatetime(offsetMs: number): string {
  const date = new Date(Date.now() + offsetMs)
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

test.describe('Scheduled Runs — persisted operations', () => {
  test('enable toggle and timing validation persist, and valid timing revives a consumed one-shot', async ({ page }, testInfo) => {
    const consumed = schedule('schedule-edit', 'Editable schedule', true)
    await seed(page, { schedules: [consumed], history: [] })
    const row = page.getByTestId('schedule-row-Editable schedule')
    const enabled = page.getByTestId('schedule-enabled-schedule-edit')
    await expect(enabled).not.toBeChecked()

    await page.getByTestId('schedule-edit-timing-schedule-edit').click()
    const runAt = page.locator('#edit-timing-run-at')
    await runAt.fill('')
    await page.getByRole('button', { name: 'Save timing' }).click()
    await expect(page.getByText('A time is required.')).toBeVisible()

    await runAt.fill(localDatetime(-3_600_000))
    await page.getByRole('button', { name: 'Save timing' }).click()
    await expect(page.getByText('The time must be in the future.')).toBeVisible()

    const savedLocalTime = localDatetime(7_200_000)
    const expectedRunAt = new Date(savedLocalTime).toISOString()
    await runAt.fill(savedLocalTime)
    await page.getByRole('button', { name: 'Save timing' }).click()
    await expect(page.getByRole('dialog', { name: 'Edit timing' })).toHaveCount(0)
    await expect(enabled).toBeChecked()
    await expect(row).toContainText('(in ')

    const storedAfterSave = await page.evaluate(() => {
      const raw = localStorage.getItem('peegeeq_generator_schedules')
      return raw === null ? [] : JSON.parse(raw)
    }) as Array<{ enabled: boolean; nextRunAt: string | null; schedule: { kind: string; runAt?: string } }>
    expect(storedAfterSave).toHaveLength(1)
    expect(storedAfterSave[0]).toMatchObject({
      enabled: true,
      nextRunAt: expectedRunAt,
      schedule: { kind: 'once', runAt: expectedRunAt },
    })

    await page.reload()
    await expect(page.getByTestId('schedule-enabled-schedule-edit')).toBeChecked()
    await page.getByTestId('schedule-edit-timing-schedule-edit').click()
    await expect(page.locator('#edit-timing-run-at')).toHaveValue(savedLocalTime)
    await page.getByRole('button', { name: 'Cancel' }).click()

    await page.getByTestId('schedule-enabled-schedule-edit').click()
    await expect(page.getByTestId('schedule-enabled-schedule-edit')).not.toBeChecked()
    await page.reload()
    await expect(page.getByTestId('schedule-enabled-schedule-edit')).not.toBeChecked()
    await expect(page.getByTestId('schedule-row-Editable schedule')).toContainText('—')
    await captureEvidence(page.getByTestId('schedule-row-Editable schedule'), testInfo, 'schedule-disabled-persisted')
  })

  test('Export all downloads every complete schedule snapshot', async ({ page }, testInfo) => {
    await seed(page, {
      schedules: [schedule('schedule-alpha', 'Alpha schedule'), schedule('schedule-beta', 'Beta schedule')],
      history: [],
    })

    const downloadPromise = page.waitForEvent('download')
    await page.getByRole('button', { name: 'Export all' }).click()
    const download = await downloadPromise
    expect(download.suggestedFilename()).toBe('schedules.json')
    expect(await download.failure()).toBeNull()
    const downloadPath = await download.path()
    expect(downloadPath).toBeTruthy()
    const exported = JSON.parse(fs.readFileSync(downloadPath!, 'utf8')) as Array<Record<string, unknown>>
    expect(exported.map((entry) => entry.id)).toEqual(['schedule-alpha', 'schedule-beta'])
    expect(exported[0]).toMatchObject({
      name: 'Alpha schedule',
      enabled: true,
      config: { setupId: 'e2e-frozen-setup', queueName: 'e2e-frozen-queue', rate: 5, durationSecs: 2 },
    })
    await captureEvidence(page.getByTestId('scheduled-runs-page'), testInfo, 'schedules-export-all')
  })

  test('history search and result filter narrow rows; summary download and re-schedule use frozen data', async ({ page }, testInfo) => {
    const alpha = historyRecord('history-alpha', 'schedule-alpha', 'Alpha completed', 'completed')
    const beta = historyRecord('history-beta', 'schedule-beta', 'Beta missed', 'missed')
    await seed(page, { schedules: [], history: [alpha, beta] })
    await page.getByRole('tab', { name: 'Run history' }).click()

    const search = page.getByPlaceholder('Search by schedule name')
    await search.fill('Alpha')
    await expect(page.getByTestId('history-row-0')).toContainText('Alpha completed')
    await expect(page.locator('[data-testid^="history-row-"]')).toHaveCount(1)

    await search.fill('')
    await page.getByTestId('history-result-filter').locator('.ant-select-selector').click()
    await page.locator('.ant-select-dropdown').getByTitle('Missed').click()
    await expect(page.getByTestId('history-row-0')).toContainText('Beta missed')
    await expect(page.locator('[data-testid^="history-row-"]')).toHaveCount(1)

    await page.getByTestId('history-result-filter').locator('.ant-select-selector').click()
    await page.locator('.ant-select-dropdown').getByTitle('Completed').click()
    const completedRow = page.getByTestId('history-row-0')
    await expect(completedRow).toContainText('Alpha completed')

    const downloadPromise = page.waitForEvent('download')
    await completedRow.getByRole('button', { name: 'Download' }).click()
    const download = await downloadPromise
    expect(download.suggestedFilename()).toBe('run-history-alpha.json')
    expect(await download.failure()).toBeNull()
    const downloadPath = await download.path()
    expect(downloadPath).toBeTruthy()
    expect(JSON.parse(fs.readFileSync(downloadPath!, 'utf8'))).toMatchObject({
      totalSent: 10,
      totalAttempted: 10,
      finalStatus: 'completed',
      runId: 'run-history-alpha',
    })

    await completedRow.getByRole('button', { name: 'Re-schedule' }).click()
    await expect(page.getByTestId('schedule-capture-summary')).toContainText('e2e-frozen-setup / e2e-frozen-queue')
    await expect(page.getByTestId('schedule-capture-summary')).toContainText('5 msg/s × 2 s')
    await captureEvidence(page.getByTestId('schedule-run-modal'), testInfo, 'history-reschedule-frozen-config')
  })
})
