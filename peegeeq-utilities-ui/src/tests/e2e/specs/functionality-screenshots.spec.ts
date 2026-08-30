import { test, expect, type Locator, type Page, type TestInfo } from '@playwright/test'

type CaptureCase = {
  name: string
  path: string
  target: (page: Page) => Locator
}

async function captureFunctionality(
  page: Page,
  target: Locator,
  name: string,
  testInfo: TestInfo
): Promise<void> {
  await expect(target).toBeVisible({ timeout: 15_000 })
  await target.scrollIntoViewIfNeeded()
  const elementPath = testInfo.outputPath('element.png')
  const viewportPath = testInfo.outputPath('viewport.png')
  await target.screenshot({ path: elementPath, animations: 'disabled' })
  await page.screenshot({ path: viewportPath, animations: 'disabled' })
  await testInfo.attach(`${name}-element.png`, {
    path: elementPath,
    contentType: 'image/png',
  })
  await testInfo.attach(`${name}-viewport.png`, {
    path: viewportPath,
    contentType: 'image/png',
  })
}

async function selectMode(page: Page, name: string): Promise<void> {
  const radio = page.getByRole('radio', { name })
  await radio.evaluate((element: HTMLInputElement) => element.click())
  await expect(radio).toBeChecked()
}

const staticCases: CaptureCase[] = [
  { name: 'application layout', path: '/', target: (page) => page.getByTestId('app-layout') },
  { name: 'application sidebar', path: '/', target: (page) => page.getByTestId('app-sidebar') },
  { name: 'PeeGeeQ Utilities logo', path: '/', target: (page) => page.getByTestId('app-logo') },
  { name: 'overview page', path: '/', target: (page) => page.getByTestId('overview-page') },
  { name: 'overview heading', path: '/', target: (page) => page.getByRole('heading', { name: 'System Overview' }) },
  { name: 'overview refresh action', path: '/', target: (page) => page.getByTestId('refresh-button') },
  { name: 'overview connect setup action', path: '/', target: (page) => page.getByTestId('connect-setup-button') },
  { name: 'overview no setups state', path: '/', target: (page) => page.getByTestId('no-setups') },
  { name: 'overview navigation item', path: '/', target: (page) => page.getByTestId('nav-overview') },
  { name: 'tools navigation item', path: '/', target: (page) => page.getByTestId('nav-tools') },
  { name: 'setups navigation item', path: '/', target: (page) => page.getByTestId('nav-setups') },
  { name: 'message generator navigation item', path: '/', target: (page) => page.getByTestId('nav-generator') },
  { name: 'scheduled runs navigation item', path: '/', target: (page) => page.getByTestId('nav-generator-schedules') },
  { name: 'templates navigation item', path: '/', target: (page) => page.getByTestId('nav-generator-templates') },
  { name: 'value lists navigation item', path: '/', target: (page) => page.getByTestId('nav-generator-value-lists') },

  { name: 'generation tools page', path: '/tools', target: (page) => page.getByTestId('tools-page') },
  { name: 'generation tools heading', path: '/tools', target: (page) => page.getByRole('heading', { name: 'Generation Tools' }) },
  { name: 'scenario import action', path: '/tools', target: (page) => page.getByRole('button', { name: 'Import' }) },
  { name: 'no saved scenarios state', path: '/tools', target: (page) => page.getByTestId('scenarios-empty') },
  { name: 'scenario usage guidance', path: '/tools', target: (page) => page.getByText(/A scenario is a saved run configuration/) },

  { name: 'setups page', path: '/setups', target: (page) => page.getByTestId('setups-page') },
  { name: 'setups heading', path: '/setups', target: (page) => page.getByRole('heading', { name: 'Setups', exact: true }) },
  { name: 'setups refresh action', path: '/setups', target: (page) => page.getByTestId('refresh-setups-button') },
  { name: 'setups connect action', path: '/setups', target: (page) => page.getByTestId('connect-setup-button') },
  { name: 'setups empty alert', path: '/setups', target: (page) => page.getByTestId('no-setups-alert') },
  { name: 'setups table', path: '/setups', target: (page) => page.getByTestId('setups-table') },
  { name: 'selected setups navigation state', path: '/setups', target: (page) => page.locator('.ant-menu-item-selected') },

  { name: 'connect setup page', path: '/setups/connect', target: (page) => page.getByTestId('connect-setup-page') },
  { name: 'connect setup heading', path: '/setups/connect', target: (page) => page.getByRole('heading', { name: 'Connect to Existing Setup' }) },
  { name: 'non-destructive connect warning', path: '/setups/connect', target: (page) => page.locator('.ant-alert').first() },
  { name: 'connect setup back action', path: '/setups/connect', target: (page) => page.getByTestId('back-button') },
  { name: 'connect setup cancel action', path: '/setups/connect', target: (page) => page.getByRole('button', { name: 'Cancel' }) },
  { name: 'connect setup submit action', path: '/setups/connect', target: (page) => page.getByTestId('connect-button') },
  { name: 'connection details disclosure', path: '/setups/connect', target: (page) => page.locator('.ant-collapse-header') },

  { name: 'message generator page', path: '/generator', target: (page) => page.getByTestId('generator-page') },
  { name: 'message generator heading', path: '/generator', target: (page) => page.getByRole('heading', { name: 'Queue Message Generator' }) },
  { name: 'generator mode selector', path: '/generator', target: (page) => page.getByTestId('generator-mode') },
  { name: 'scenario toolbar', path: '/generator', target: (page) => page.getByTestId('scenario-bar') },
  { name: 'scenario selector', path: '/generator', target: (page) => page.getByTestId('scenario-select') },
  { name: 'scenario load action', path: '/generator', target: (page) => page.getByTestId('scenario-load') },
  { name: 'scenario save as action', path: '/generator', target: (page) => page.getByTestId('scenario-save-as') },
  { name: 'scenario export action', path: '/generator', target: (page) => page.getByTestId('scenario-export') },
  { name: 'generator target zone', path: '/generator', target: (page) => page.getByTestId('zone-a') },
  { name: 'generator target empty state', path: '/generator', target: (page) => page.getByText('No PeeGeeQ setup connected') },
  { name: 'flat rate controls', path: '/generator', target: (page) => page.getByTestId('rate-controls') },
  { name: 'total message calculation', path: '/generator', target: (page) => page.getByTestId('total-messages') },
  { name: 'message template editor', path: '/generator', target: (page) => page.getByTestId('template-editor') },
  { name: 'generator actions', path: '/generator', target: (page) => page.getByTestId('generator-actions') },
  { name: 'generator progress panel', path: '/generator', target: (page) => page.getByTestId('progress-panel') },
  { name: 'generator idle run status', path: '/generator', target: (page) => page.getByTestId('run-status') },
  { name: 'generator progress bar', path: '/generator', target: (page) => page.getByTestId('run-progress') },
  { name: 'generator sent counter', path: '/generator', target: (page) => page.getByTestId('sent-counter') },
  { name: 'generator elapsed counter', path: '/generator', target: (page) => page.getByTestId('elapsed-counter') },
  { name: 'generator rate counter', path: '/generator', target: (page) => page.getByTestId('rate-counter') },
  { name: 'generator error counter', path: '/generator', target: (page) => page.getByTestId('error-counter') },

  { name: 'template manager page', path: '/generator/templates', target: (page) => page.getByTestId('template-manager-page') },
  { name: 'template manager heading', path: '/generator/templates', target: (page) => page.getByRole('heading', { name: 'Template Manager' }) },
  { name: 'new template action', path: '/generator/templates', target: (page) => page.getByRole('button', { name: 'New Template' }) },
  { name: 'template import action', path: '/generator/templates', target: (page) => page.getByRole('button', { name: 'Import' }) },
  { name: 'template table', path: '/generator/templates', target: (page) => page.getByTestId('template-table') },

  { name: 'value list manager page', path: '/generator/value-lists', target: (page) => page.getByTestId('value-list-manager-page') },
  { name: 'value list manager heading', path: '/generator/value-lists', target: (page) => page.getByRole('heading', { name: 'Value List Manager' }) },
  { name: 'new value list action', path: '/generator/value-lists', target: (page) => page.getByRole('button', { name: 'New List' }) },
  { name: 'value list import action', path: '/generator/value-lists', target: (page) => page.getByRole('button', { name: 'Import JSON file' }) },
  { name: 'value list table', path: '/generator/value-lists', target: (page) => page.getByTestId('value-list-table') },

  { name: 'scheduled runs page', path: '/generator/schedules', target: (page) => page.getByTestId('scheduled-runs-page') },
  { name: 'scheduled runs heading', path: '/generator/schedules', target: (page) => page.getByRole('heading', { name: 'Scheduled Runs' }) },
  { name: 'scheduled runs browser-only banner', path: '/generator/schedules', target: (page) => page.getByTestId('schedules-page-banner') },
  { name: 'schedules tab', path: '/generator/schedules', target: (page) => page.getByRole('tab', { name: 'Schedules' }) },
  { name: 'schedule import action', path: '/generator/schedules', target: (page) => page.getByRole('button', { name: 'Import' }) },
  { name: 'no schedules state', path: '/generator/schedules', target: (page) => page.getByTestId('schedules-empty') },
]

test.describe('Functionality screenshots — visible application surfaces', () => {
  for (const captureCase of staticCases) {
    test(captureCase.name, async ({ page }, testInfo) => {
      await page.goto(captureCase.path)
      await captureFunctionality(page, captureCase.target(page), captureCase.name, testInfo)
    })
  }
})

const modeCases = [
  { mode: 'Profile', name: 'profile phase editor', testId: 'profile-phases-editor' },
  { mode: 'Profile', name: 'profile phase row', selector: '[data-testid^="phase-row-"]' },
  { mode: 'Profile', name: 'profile total messages', testId: 'profile-total-messages' },
  { mode: 'Profile', name: 'profile total duration', testId: 'profile-total-duration' },
  { mode: 'Profile', name: 'profile results', testId: 'profile-results-panel' },
  { mode: 'Ramp', name: 'ramp controls', testId: 'ramp-controls' },
  { mode: 'Ramp', name: 'ramp plan preview', testId: 'ramp-plan-preview' },
  { mode: 'Ramp', name: 'ramp phase results', testId: 'profile-results-panel' },
  { mode: 'Ramp', name: 'ramp attribution empty state', testId: 'ramp-attribution-empty' },
  { mode: 'Delay / Prio / FIFO', name: 'exerciser controls', testId: 'exerciser-controls' },
  { mode: 'Delay / Prio / FIFO', name: 'exerciser override guidance', testId: 'exerciser-override-note' },
  { mode: 'Delay / Prio / FIFO', name: 'exerciser plan preview', testId: 'exerciser-plan-preview' },
  { mode: 'Delay / Prio / FIFO', name: 'exerciser group count control', text: 'Number of groups' },
  { mode: 'Delay / Prio / FIFO', name: 'exerciser rate controls', testId: 'rate-controls' },
  { mode: 'Delay / Prio / FIFO', name: 'exerciser manifest empty state', testId: 'manifest-empty' },
  { mode: 'Trace seed', name: 'trace controls', testId: 'trace-controls' },
  { mode: 'Trace seed', name: 'trace scheme summary', testId: 'trace-scheme-summary' },
  { mode: 'Trace seed', name: 'trace preview caveat', testId: 'trace-preview-caveat' },
  { mode: 'Trace seed', name: 'trace rate controls', testId: 'rate-controls' },
  { mode: 'Trace seed', name: 'trace report empty state', testId: 'trace-empty' },
  { mode: 'Compare', name: 'compare target empty state', text: 'No PeeGeeQ setup connected' },
  { mode: 'Compare', name: 'compare shared load controls', testId: 'rate-controls' },
  { mode: 'Compare', name: 'compare generator actions', testId: 'generator-actions' },
  { mode: 'Compare', name: 'compare results empty state', testId: 'compare-results-empty' },
] as const

test.describe('Functionality screenshots — generator modes', () => {
  for (const modeCase of modeCases) {
    test(modeCase.name, async ({ page }, testInfo) => {
      await page.goto('/generator')
      await selectMode(page, modeCase.mode)
      const target = 'testId' in modeCase
        ? page.getByTestId(modeCase.testId)
        : 'selector' in modeCase
          ? page.locator(modeCase.selector)
          : page.getByText(modeCase.text, { exact: true })
      await captureFunctionality(page, target, modeCase.name, testInfo)
    })
  }
})

test.describe('Functionality screenshots — interactive states', () => {
  test('connect setup ID entry', async ({ page }, testInfo) => {
    await page.goto('/setups/connect')
    const input = page.getByLabel('Setup ID')
    await input.fill('existing-orders')
    await captureFunctionality(page, input, 'connect-setup-id-entry', testInfo)
  })

  test('connect database name entry', async ({ page }, testInfo) => {
    await page.goto('/setups/connect')
    const input = page.getByLabel('Database name')
    await input.fill('orders_database')
    await captureFunctionality(page, input, 'connect-database-name-entry', testInfo)
  })

  test('connect password entry', async ({ page }, testInfo) => {
    await page.goto('/setups/connect')
    const input = page.getByLabel('Database password')
    await input.fill('not-a-real-password')
    await captureFunctionality(page, input, 'connect-password-entry', testInfo)
  })

  test('expanded connection details', async ({ page }, testInfo) => {
    await page.goto('/setups/connect')
    await page.getByText('Connection details').click()
    await captureFunctionality(page, page.locator('.ant-collapse-content'), 'expanded-connection-details', testInfo)
  })

  test('new value list editor', async ({ page }, testInfo) => {
    await page.goto('/generator/value-lists')
    await page.getByRole('button', { name: 'New List' }).click()
    await captureFunctionality(page, page.getByTestId('value-list-panel'), 'new-value-list-editor', testInfo)
  })

  test('value list live value count', async ({ page }, testInfo) => {
    await page.goto('/generator/value-lists')
    await page.getByRole('button', { name: 'New List' }).click()
    await page.getByLabel('Values (one per line)').fill('alpha\nbeta\ngamma')
    await expect(page.getByTestId('value-count')).toHaveText('3 values')
    await captureFunctionality(page, page.getByTestId('value-count'), 'value-list-live-count', testInfo)
  })

  test('value list validation error', async ({ page }, testInfo) => {
    await page.goto('/generator/value-lists')
    await page.getByRole('button', { name: 'New List' }).click()
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await captureFunctionality(page, page.getByTestId('panel-error'), 'value-list-validation-error', testInfo)
  })

  test('value list import dialog', async ({ page }, testInfo) => {
    await page.goto('/generator/value-lists')
    await page.getByRole('button', { name: 'Import JSON file' }).click()
    await captureFunctionality(page, page.getByTestId('import-file-dialog'), 'value-list-import-dialog', testInfo)
  })

  test('template import dialog', async ({ page }, testInfo) => {
    await page.goto('/generator/templates')
    await page.getByRole('button', { name: 'Import' }).click()
    await captureFunctionality(page, page.getByTestId('import-file-dialog'), 'template-import-dialog', testInfo)
  })

  test('new template generator handoff', async ({ page }, testInfo) => {
    await page.goto('/generator/templates')
    await page.getByRole('button', { name: 'New Template' }).click()
    await expect(page).toHaveURL(/\/generator$/)
    await captureFunctionality(page, page.getByTestId('template-editor'), 'new-template-generator-handoff', testInfo)
  })

  test('scenario import dialog', async ({ page }, testInfo) => {
    await page.goto('/tools')
    await page.getByRole('button', { name: 'Import' }).click()
    await captureFunctionality(page, page.getByTestId('import-file-dialog'), 'scenario-import-dialog', testInfo)
  })

  test('schedule import dialog', async ({ page }, testInfo) => {
    await page.goto('/generator/schedules')
    await page.getByRole('button', { name: 'Import' }).click()
    await captureFunctionality(page, page.getByTestId('import-file-dialog'), 'schedule-import-dialog', testInfo)
  })

  test('schedule run history filters', async ({ page }, testInfo) => {
    await page.goto('/generator/schedules')
    await page.getByRole('tab', { name: 'Run history' }).click()
    await captureFunctionality(page, page.getByTestId('history-result-filter'), 'schedule-run-history-filters', testInfo)
  })

  test('schedule templates empty state', async ({ page }, testInfo) => {
    await page.goto('/generator/schedules')
    await page.getByRole('tab', { name: 'Templates' }).click()
    await captureFunctionality(page, page.getByTestId('templates-empty'), 'schedule-templates-empty-state', testInfo)
  })
})
