import { test, expect, type Locator, type Page, type TestInfo } from '@playwright/test'

type StateRun = (page: Page, testInfo: TestInfo) => Promise<void>

let declaredTests = 0

function stateTest(name: string, run: StateRun): void {
  declaredTests += 1
  test(name, async ({ page }, testInfo) => run(page, testInfo))
}

async function capture(page: Page, target: Locator, name: string, testInfo: TestInfo): Promise<void> {
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

type ControlCase = {
  name: string
  path: string
  locate: (page: Page) => Locator
  prepare?: (page: Page) => Promise<void>
}

const controls: ControlCase[] = [
  { name: 'Overview navigation', path: '/', locate: (p) => p.getByTestId('nav-overview') },
  { name: 'Tools navigation', path: '/', locate: (p) => p.getByTestId('nav-tools') },
  { name: 'Setups navigation', path: '/', locate: (p) => p.getByTestId('nav-setups') },
  { name: 'Generator navigation', path: '/', locate: (p) => p.getByTestId('nav-generator') },
  { name: 'Schedules navigation', path: '/', locate: (p) => p.getByTestId('nav-generator-schedules') },
  { name: 'Templates navigation', path: '/', locate: (p) => p.getByTestId('nav-generator-templates') },
  { name: 'Value Lists navigation', path: '/', locate: (p) => p.getByTestId('nav-generator-value-lists') },
  { name: 'Overview Refresh', path: '/', locate: (p) => p.getByTestId('refresh-button') },
  { name: 'Overview Connect setup', path: '/', locate: (p) => p.getByTestId('connect-setup-button') },
  { name: 'Setups Refresh', path: '/setups', locate: (p) => p.getByTestId('refresh-setups-button') },
  { name: 'Setups Connect setup', path: '/setups', locate: (p) => p.getByTestId('connect-setup-button') },
  { name: 'Connect Back', path: '/setups/connect', locate: (p) => p.getByTestId('back-button') },
  { name: 'Connect Cancel', path: '/setups/connect', locate: (p) => p.getByRole('button', { name: 'Cancel' }) },
  { name: 'Connect submit', path: '/setups/connect', locate: (p) => p.getByTestId('connect-button') },
  { name: 'Connection details disclosure', path: '/setups/connect', locate: (p) => p.locator('.ant-collapse-header') },
  { name: 'Scenario Import', path: '/tools', locate: (p) => p.getByRole('button', { name: 'Import' }) },
  { name: 'Scenario generator guidance', path: '/tools', locate: (p) => p.getByTestId('tools-page').getByRole('link', { name: 'Message Generator' }) },
  { name: 'New Template', path: '/generator/templates', locate: (p) => p.getByRole('button', { name: 'New Template' }) },
  { name: 'Template Import', path: '/generator/templates', locate: (p) => p.getByRole('button', { name: 'Import' }) },
  { name: 'New List', path: '/generator/value-lists', locate: (p) => p.getByRole('button', { name: 'New List' }) },
  { name: 'Value List Import', path: '/generator/value-lists', locate: (p) => p.getByRole('button', { name: 'Import JSON file' }) },
  { name: 'Schedules tab', path: '/generator/schedules', locate: (p) => p.getByRole('tab', { name: 'Schedules' }) },
  { name: 'Run history tab', path: '/generator/schedules', locate: (p) => p.getByRole('tab', { name: 'Run history' }) },
  { name: 'Schedule Templates tab', path: '/generator/schedules', locate: (p) => p.getByRole('tab', { name: 'Templates' }) },
  { name: 'Schedule Import', path: '/generator/schedules', locate: (p) => p.getByRole('button', { name: 'Import' }) },
  { name: 'Template New working copy', path: '/generator', locate: (p) => p.getByRole('button', { name: 'New', exact: true }) },
  { name: 'Template Save', path: '/generator', locate: (p) => p.getByRole('button', { name: 'Save', exact: true }) },
  { name: 'Template Export', path: '/generator', locate: (p) => p.getByRole('button', { name: 'Export', exact: true }) },
  { name: 'Add header', path: '/generator', locate: (p) => p.getByRole('button', { name: 'Add header' }) },
  { name: 'Placeholder reference', path: '/generator', locate: (p) => p.getByText('Placeholder reference', { exact: true }) },
  { name: 'Generator Preview', path: '/generator', locate: (p) => p.getByRole('button', { name: 'Preview', exact: true }) },
  {
    name: 'Value List editor Save', path: '/generator/value-lists',
    prepare: async (p) => { await p.getByRole('button', { name: 'New List' }).click() },
    locate: (p) => p.getByRole('button', { name: 'Save', exact: true }),
  },
  {
    name: 'Value List editor Cancel', path: '/generator/value-lists',
    prepare: async (p) => { await p.getByRole('button', { name: 'New List' }).click() },
    locate: (p) => p.getByRole('button', { name: 'Cancel', exact: true }),
  },
  {
    name: 'Scenario import Cancel', path: '/tools',
    prepare: async (p) => { await p.getByRole('button', { name: 'Import' }).click() },
    locate: (p) => p.getByRole('button', { name: 'Cancel' }),
  },
  {
    name: 'Template import Cancel', path: '/generator/templates',
    prepare: async (p) => { await p.getByRole('button', { name: 'Import' }).click() },
    locate: (p) => p.getByRole('button', { name: 'Cancel' }),
  },
  {
    name: 'Value List import Cancel', path: '/generator/value-lists',
    prepare: async (p) => { await p.getByRole('button', { name: 'Import JSON file' }).click() },
    locate: (p) => p.getByRole('button', { name: 'Cancel' }),
  },
  {
    name: 'Schedule import Cancel', path: '/generator/schedules',
    prepare: async (p) => { await p.getByRole('button', { name: 'Import' }).click() },
    locate: (p) => p.getByRole('button', { name: 'Cancel' }),
  },
  {
    name: 'Profile Add phase', path: '/generator',
    prepare: async (p) => { await selectMode(p, 'Profile') },
    locate: (p) => p.getByRole('button', { name: 'Add phase' }),
  },
  {
    name: 'Profile Remove phase', path: '/generator',
    prepare: async (p) => { await selectMode(p, 'Profile') },
    locate: (p) => p.getByRole('button', { name: 'Remove phase 1' }),
  },
  {
    name: 'Connect SSL checkbox', path: '/setups/connect',
    prepare: async (p) => { await p.getByText('Connection details').click() },
    locate: (p) => p.getByRole('checkbox', { name: 'Enable SSL' }),
  },
  { name: 'Flat rate mode control', path: '/generator', locate: (p) => p.getByTestId('generator-mode').getByText('Flat rate', { exact: true }) },
  { name: 'Profile mode control', path: '/generator', locate: (p) => p.getByTestId('generator-mode').getByText('Profile', { exact: true }) },
  { name: 'Ramp mode control', path: '/generator', locate: (p) => p.getByTestId('generator-mode').getByText('Ramp', { exact: true }) },
  { name: 'Compare mode control', path: '/generator', locate: (p) => p.getByTestId('generator-mode').getByText('Compare', { exact: true }) },
  { name: 'Exerciser mode control', path: '/generator', locate: (p) => p.getByTestId('generator-mode').getByText('Delay / Prio / FIFO', { exact: true }) },
]

if (controls.length !== 45) throw new Error(`Expected 45 interactive controls, found ${controls.length}`)

test.describe('Functional state screenshots — control interaction states', () => {
  for (const control of controls) {
    for (const state of ['default', 'hover', 'focus'] as const) {
      stateTest(`${control.name}: ${state}`, async (page, testInfo) => {
        await page.goto(control.path)
        await control.prepare?.(page)
        const target = control.locate(page)
        await expect(target).toBeVisible()
        if (state === 'hover') await target.hover()
        if (state === 'focus') await target.focus()
        await capture(page, target, `${control.name}-${state}`, testInfo)
      })
    }
  }
})

const modes = [
  { name: 'Flat rate', controls: 'rate-controls', result: 'progress-panel' },
  { name: 'Profile', controls: 'profile-phases-editor', result: 'profile-results-panel' },
  { name: 'Ramp', controls: 'ramp-controls', result: 'ramp-attribution-empty' },
  { name: 'Compare', controls: 'rate-controls', result: 'compare-results-empty' },
  { name: 'Delay / Prio / FIFO', controls: 'exerciser-controls', result: 'manifest-empty' },
  { name: 'Trace seed', controls: 'trace-controls', result: 'trace-empty' },
]

test.describe('Functional state screenshots — complete generator modes', () => {
  for (const mode of modes) {
    stateTest(`${mode.name}: selected state`, async (page, testInfo) => {
      await page.goto('/generator')
      await selectMode(page, mode.name)
      await capture(
        page,
        page.getByTestId('generator-mode').getByText(mode.name, { exact: true }),
        `${mode.name}-selected`,
        testInfo
      )
    })
    stateTest(`${mode.name}: control surface`, async (page, testInfo) => {
      await page.goto('/generator')
      await selectMode(page, mode.name)
      await capture(page, page.getByTestId(mode.controls), `${mode.name}-controls`, testInfo)
    })
    stateTest(`${mode.name}: result surface`, async (page, testInfo) => {
      await page.goto('/generator')
      await selectMode(page, mode.name)
      await capture(page, page.getByTestId(mode.result), `${mode.name}-results`, testInfo)
    })
  }
})

type FieldCase = {
  name: string
  path: string
  selector: string
  prepare?: (page: Page) => Promise<void>
  values?: number[]
}

const mode = (name: string) => async (page: Page) => selectMode(page, name)
const selectExerciserOption = (name: string) => async (page: Page) => {
  await selectMode(page, 'Delay / Prio / FIFO')
  await page.getByText(name, { exact: true }).click()
}
const selectTraceOption = (name: string, causation = false) => async (page: Page) => {
  await selectMode(page, 'Trace seed')
  await page.getByText(name, { exact: true }).click()
  if (causation) await page.getByRole('checkbox', { name: /Seed causation chains/ }).check()
}

const numericFields: FieldCase[] = [
  { name: 'rate', path: '/generator', selector: '#rate-controls-rate' },
  { name: 'duration', path: '/generator', selector: '#rate-controls-durationSecs' },
  { name: 'maximum batch size', path: '/generator', selector: '#rate-controls-maxBatchSize' },
  { name: 'warning threshold', path: '/generator', selector: '#rate-controls-warnThreshold' },
  { name: 'consecutive error guard', path: '/generator', selector: '#rate-controls-maxConsecErrors' },
  { name: 'template priority', path: '/generator', selector: '#template-editor-priority' },
  { name: 'template delay', path: '/generator', selector: '#template-editor-delay' },
  { name: 'preview message index', path: '/generator', selector: '#generator-preview-index' },
  { name: 'profile phase rate', path: '/generator', selector: '[id^="phase-rate-"]', prepare: mode('Profile') },
  { name: 'profile phase duration', path: '/generator', selector: '[id^="phase-duration-"]', prepare: mode('Profile') },
  { name: 'ramp start rate', path: '/generator', selector: '#ramp-startRate', prepare: mode('Ramp') },
  { name: 'ramp step size', path: '/generator', selector: '#ramp-stepRate', prepare: mode('Ramp') },
  { name: 'ramp step duration', path: '/generator', selector: '#ramp-stepSecs', prepare: mode('Ramp') },
  { name: 'ramp maximum rate', path: '/generator', selector: '#ramp-maxRate', prepare: mode('Ramp') },
  { name: 'ramp error threshold', path: '/generator', selector: '#ramp-errorRatePercent', prepare: mode('Ramp') },
  { name: 'exerciser fixed delay', path: '/generator', selector: '#exerciser-delay-seconds', prepare: selectExerciserOption('Fixed delay') },
  { name: 'exerciser random delay maximum', path: '/generator', selector: '#exerciser-delay-max', prepare: selectExerciserOption('Random delay') },
  { name: 'exerciser per-index step', path: '/generator', selector: '#exerciser-ramp-step', prepare: selectExerciserOption('Per-index ramp') },
  { name: 'exerciser per-index cap', path: '/generator', selector: '#exerciser-ramp-cap', prepare: selectExerciserOption('Per-index ramp') },
  { name: 'exerciser fixed priority', path: '/generator', selector: '#exerciser-priority', prepare: selectExerciserOption('Fixed priority') },
  { name: 'exerciser round-robin group count', path: '/generator', selector: '#exerciser-group-count', prepare: selectExerciserOption('Round-robin groups') },
  { name: 'trace every-N interval', path: '/generator', selector: '#trace-every-n', prepare: selectTraceOption('Every N messages') },
  { name: 'trace causation children', path: '/generator', selector: '#trace-children', prepare: selectTraceOption('One per run', true) },
  {
    name: 'database port', path: '/setups/connect', selector: '#port',
    values: [1, 1024, 5432, 8080, 65535],
    prepare: async (page) => { await page.getByText('Connection details').click() },
  },
]

if (numericFields.length !== 24) throw new Error(`Expected 24 numeric fields, found ${numericFields.length}`)

test.describe('Functional state screenshots — numeric field values', () => {
  for (const field of numericFields) {
    for (const value of field.values ?? [1, 2, 5, 8, 10]) {
      stateTest(`${field.name}: value ${value}`, async (page, testInfo) => {
        await page.goto(field.path)
        await field.prepare?.(page)
        const input = page.locator(field.selector)
        await input.fill(String(value))
        await input.press('Tab')
        await expect(input).toHaveValue(String(value))
        await capture(page, input, `${field.name}-${value}`, testInfo)
      })
    }
  }
})

type TextFieldCase = FieldCase & { values: string[] }
const ordinaryText = ['a', 'orders', 'orders.v1', 'with space', 'unicode-队列']

const textFields: TextFieldCase[] = [
  { name: 'template name', path: '/generator', selector: '#template-editor-name', values: ordinaryText },
  { name: 'message type', path: '/generator', selector: '#template-editor-message-type', values: ordinaryText },
  { name: 'template group', path: '/generator', selector: '#template-editor-group', values: ordinaryText },
  {
    name: 'template payload', path: '/generator', selector: '#template-editor-payload',
    values: ['{}', '[]', '{"id":1}', '{"nested":{"ok":true}}', '{"items":[1,2,3]}'],
  },
  { name: 'connect setup ID', path: '/setups/connect', selector: '#setupId', values: ordinaryText },
  { name: 'connect database name', path: '/setups/connect', selector: '#databaseName', values: ordinaryText },
  { name: 'connect password', path: '/setups/connect', selector: '#password', values: ['x', 'secret', 'with space', 'p@ssw0rd!', '密码-安全'] },
  {
    name: 'connect host', path: '/setups/connect', selector: '#host', values: ['localhost', '127.0.0.1', 'db.internal', 'postgres.example.test', '::1'],
    prepare: async (page) => { await page.getByText('Connection details').click() },
  },
  {
    name: 'connect username', path: '/setups/connect', selector: '#username', values: ordinaryText,
    prepare: async (page) => { await page.getByText('Connection details').click() },
  },
  {
    name: 'connect schema', path: '/setups/connect', selector: '#schema', values: ['public', 'pgq', 'orders', 'tenant_01', '队列'],
    prepare: async (page) => { await page.getByText('Connection details').click() },
  },
  {
    name: 'value list name', path: '/generator/value-lists', selector: '#value-list-name', values: ordinaryText,
    prepare: async (page) => { await page.getByRole('button', { name: 'New List' }).click() },
  },
  {
    name: 'value list values', path: '/generator/value-lists', selector: '#value-list-values',
    values: ['alpha', 'alpha\nbeta', 'alpha\n\nbeta', '1\n2\n3', '上海\nLondon\n東京'],
    prepare: async (page) => { await page.getByRole('button', { name: 'New List' }).click() },
  },
  { name: 'profile phase label', path: '/generator', selector: '[id^="phase-label-"]', values: ordinaryText, prepare: mode('Profile') },
  { name: 'exerciser single group name', path: '/generator', selector: '#exerciser-group-name', values: ordinaryText, prepare: selectExerciserOption('Single group') },
]

if (textFields.length !== 14) throw new Error(`Expected 14 text fields, found ${textFields.length}`)

test.describe('Functional state screenshots — text field values', () => {
  for (const field of textFields) {
    for (const value of field.values) {
      stateTest(`${field.name}: ${JSON.stringify(value)}`, async (page, testInfo) => {
        await page.goto(field.path)
        await field.prepare?.(page)
        const input = page.locator(field.selector)
        await input.fill(value)
        await expect(input).toHaveValue(value)
        await capture(page, input, `${field.name}-value`, testInfo)
      })
    }
  }
})

const optionCases = [
  { family: 'exerciser delay', mode: 'Delay / Prio / FIFO', option: 'Fixed delay' },
  { family: 'exerciser delay', mode: 'Delay / Prio / FIFO', option: 'Random delay' },
  { family: 'exerciser delay', mode: 'Delay / Prio / FIFO', option: 'Per-index ramp' },
  { family: 'exerciser priority', mode: 'Delay / Prio / FIFO', option: 'Fixed priority' },
  { family: 'exerciser priority', mode: 'Delay / Prio / FIFO', option: 'Round-robin 1–10' },
  { family: 'exerciser group', mode: 'Delay / Prio / FIFO', option: 'Single group' },
  { family: 'exerciser group', mode: 'Delay / Prio / FIFO', option: 'Round-robin groups' },
  { family: 'exerciser group', mode: 'Delay / Prio / FIFO', option: 'Per-key from value list' },
  { family: 'trace correlation', mode: 'Trace seed', option: 'One per run' },
  { family: 'trace correlation', mode: 'Trace seed', option: 'One per batch' },
  { family: 'trace correlation', mode: 'Trace seed', option: 'Every N messages' },
  { family: 'ramp stopping', mode: 'Ramp', option: 'Error rate exceeds threshold' },
  { family: 'ramp stopping', mode: 'Ramp', option: 'Acked-rate plateau' },
] as const

test.describe('Functional state screenshots — strategy options', () => {
  for (const optionCase of optionCases) {
    stateTest(`${optionCase.family}: ${optionCase.option}`, async (page, testInfo) => {
      await page.goto('/generator')
      await selectMode(page, optionCase.mode)
      await page.getByText(optionCase.option, { exact: true }).click()
      const radio = page.getByRole('radio', { name: optionCase.option })
      await expect(radio).toBeChecked()
      await capture(page, radio, `${optionCase.family}-${optionCase.option}`, testInfo)
    })
  }

  for (const enabled of [false, true]) {
    stateTest(`trace causation: ${enabled ? 'enabled' : 'disabled'}`, async (page, testInfo) => {
      await page.goto('/generator')
      await selectMode(page, 'Trace seed')
      const checkbox = page.getByRole('checkbox', { name: /Seed causation chains/ })
      if (enabled) await checkbox.check()
      else await checkbox.uncheck()
      await expect(checkbox).toBeChecked({ checked: enabled })
      await capture(page, checkbox, `trace-causation-${enabled}`, testInfo)
    })
  }
})

const placeholders = [
  '{{messageId}}',
  '{{sequenceId}}',
  '{{uuid}}',
  '{{timestamp}}',
  '{{unixMs}}',
  '{{index}}',
  '{{random:500}}',
  '{{randomAlpha:12}}',
  '{{list:first_names}}',
  '{{correlationId}}',
  '{{runId}}',
]

test.describe('Functional state screenshots — every template placeholder', () => {
  for (const placeholder of placeholders) {
    stateTest(`${placeholder}: editor state`, async (page, testInfo) => {
      await page.goto('/generator')
      const payload = page.locator('#template-editor-payload')
      await payload.fill(JSON.stringify({ value: placeholder }))
      await payload.blur()
      await expect(page.getByTestId('payload-error')).toHaveCount(0)
      await capture(page, payload, `${placeholder}-editor`, testInfo)
    })

    stateTest(`${placeholder}: resolved preview`, async (page, testInfo) => {
      await page.goto('/generator')
      await page.locator('#template-editor-payload').fill(JSON.stringify({ value: placeholder }))
      await page.getByRole('button', { name: 'Preview', exact: true }).click()
      await capture(page, page.getByTestId('preview-modal'), `${placeholder}-preview`, testInfo)
    })
  }
})

const invalidPayloads = [
  '{', '}', '[', ']', '{"a":}', '{a:1}', '{"a":undefined}', '{"a":NaN}',
  '{"a":Infinity}', '{"a":,}', '{"a":1,}', '[1,]', '[,1]', 'not-json',
  '"unterminated', '{"nested":{"a":}}', '{"a":"bad\\u"}', '{{random:}}',
  '{{randomAlpha:}}', '{"a": {{unknown}}}',
]

test.describe('Functional state screenshots — payload validation failures', () => {
  for (const [index, payloadValue] of invalidPayloads.entries()) {
    stateTest(`invalid payload ${index + 1}: ${payloadValue}`, async (page, testInfo) => {
      await page.goto('/generator')
      const payload = page.locator('#template-editor-payload')
      await payload.fill(payloadValue)
      await payload.blur()
      await capture(page, page.getByTestId('payload-error'), `invalid-payload-${index + 1}`, testInfo)
    })
  }
})

test.describe('Functional state screenshots — template headers', () => {
  for (let count = 1; count <= 10; count += 1) {
    stateTest(`header rows: ${count} added`, async (page, testInfo) => {
      await page.goto('/generator')
      for (let index = 0; index < count; index += 1) {
        await page.getByRole('button', { name: 'Add header' }).click()
      }
      await expect(page.locator('[data-testid^="header-key-"]')).toHaveCount(count)
      await capture(page, page.locator('[data-testid^="header-key-"]').last(), `headers-${count}`, testInfo)
    })
  }

  const headerPairs = [
    ['content-type', 'application/json'], ['x-request-id', '{{uuid}}'],
    ['x-run-id', '{{runId}}'], ['x-correlation-id', '{{correlationId}}'],
    ['x-sequence', '{{sequenceId}}'], ['x-message', '{{messageId}}'],
    ['x-created', '{{timestamp}}'], ['x-index', '{{index}}'],
    ['x-random', '{{randomAlpha:8}}'], ['x-owner', 'utilities-ui'],
  ]
  for (const [key, value] of headerPairs) {
    stateTest(`header value: ${key}`, async (page, testInfo) => {
      await page.goto('/generator')
      await page.getByRole('button', { name: 'Add header' }).click()
      await page.getByTestId('header-key-0').fill(key)
      await page.getByTestId('header-value-0').fill(value)
      await expect(page.getByTestId('header-value-0')).toHaveValue(value)
      await capture(page, page.getByTestId('header-value-0'), `header-${key}`, testInfo)
    })
  }

  stateTest('duplicate header warning', async (page, testInfo) => {
    await page.goto('/generator')
    await page.getByRole('button', { name: 'Add header' }).click()
    await page.getByRole('button', { name: 'Add header' }).click()
    await page.getByTestId('header-key-0').fill('x-duplicate')
    await page.getByTestId('header-key-1').fill('x-duplicate')
    await capture(page, page.getByTestId('duplicate-header-warning'), 'duplicate-header-warning', testInfo)
  })

  for (let count = 1; count <= 5; count += 1) {
    stateTest(`remove header from ${count} row configuration`, async (page, testInfo) => {
      await page.goto('/generator')
      for (let index = 0; index < count; index += 1) {
        await page.getByRole('button', { name: 'Add header' }).click()
      }
      await page.getByTestId(`header-remove-${count - 1}`).click()
      await expect(page.locator('[data-testid^="header-key-"]')).toHaveCount(count - 1)
      await capture(page, page.getByTestId('template-editor'), `header-remove-${count}`, testInfo)
    })
  }
})

const valueTexts = [
  '', 'alpha', 'alpha\nbeta', 'alpha\nbeta\ngamma', 'alpha\n\nbeta',
  '1\n2\n3\n4', 'true\nfalse', '上海\nLondon\n東京', 'a\na\nb', ' leading \ntrailing ',
]

test.describe('Functional state screenshots — value-list workflows', () => {
  for (const [index, values] of valueTexts.entries()) {
    stateTest(`value-list parsing state ${index + 1}`, async (page, testInfo) => {
      await page.goto('/generator/value-lists')
      await page.getByRole('button', { name: 'New List' }).click()
      await page.locator('#value-list-values').fill(values)
      await capture(page, page.getByTestId('value-count'), `value-list-count-${index + 1}`, testInfo)
    })
  }

  const savedLists = [
    ['first_names', 'Ada\nGrace'],
    ['regions', 'apac\nemea\namer'],
    ['priorities', 'low\nnormal\nhigh'],
    ['unicode_values', '上海\n東京'],
    ['single_value', 'only'],
  ]
  for (const [name, values] of savedLists) {
    stateTest(`save value list: ${name}`, async (page, testInfo) => {
      await page.goto('/generator/value-lists')
      await page.getByRole('button', { name: 'New List' }).click()
      await page.locator('#value-list-name').fill(name)
      await page.locator('#value-list-values').fill(values)
      await page.getByRole('button', { name: 'Save', exact: true }).click()
      await capture(page, page.getByTestId(`list-count-${name}`), `saved-list-${name}`, testInfo)
    })
  }

  stateTest('value list rejects a blank name', async (page, testInfo) => {
    await page.goto('/generator/value-lists')
    await page.getByRole('button', { name: 'New List' }).click()
    await page.locator('#value-list-values').fill('alpha')
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await capture(page, page.getByTestId('panel-error'), 'value-list-blank-name', testInfo)
  })

  stateTest('value list rejects a whitespace-only name', async (page, testInfo) => {
    await page.goto('/generator/value-lists')
    await page.getByRole('button', { name: 'New List' }).click()
    await page.locator('#value-list-name').fill('   ')
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await capture(page, page.getByTestId('panel-error'), 'value-list-whitespace-name', testInfo)
  })

  stateTest('value list rejects a duplicate name', async (page, testInfo) => {
    await page.goto('/generator/value-lists')
    await page.getByRole('button', { name: 'New List' }).click()
    await page.locator('#value-list-name').fill('duplicate')
    await page.locator('#value-list-values').fill('one')
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await page.getByRole('button', { name: 'New List' }).click()
    await page.locator('#value-list-name').fill('duplicate')
    await page.locator('#value-list-values').fill('two')
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await capture(page, page.getByTestId('panel-error'), 'value-list-duplicate-name', testInfo)
  })

  stateTest('value list editor cancellation', async (page, testInfo) => {
    await page.goto('/generator/value-lists')
    await page.getByRole('button', { name: 'New List' }).click()
    await page.locator('#value-list-name').fill('discard-me')
    await page.getByRole('button', { name: 'Cancel', exact: true }).click()
    await expect(page.getByTestId('value-list-panel')).toHaveCount(0)
    await capture(page, page.getByTestId('value-list-manager-page'), 'value-list-cancelled', testInfo)
  })

  stateTest('value list import file chooser', async (page, testInfo) => {
    await page.goto('/generator/value-lists')
    await page.getByRole('button', { name: 'Import JSON file' }).click()
    await capture(page, page.getByTestId('import-file-dialog'), 'value-list-file-chooser', testInfo)
  })
})

const importCases = [
  { name: 'scenario', path: '/tools', button: 'Import' },
  { name: 'template', path: '/generator/templates', button: 'Import' },
  { name: 'value list', path: '/generator/value-lists', button: 'Import JSON file' },
  { name: 'schedule', path: '/generator/schedules', button: 'Import' },
]

test.describe('Functional state screenshots — imports, tabs, and connection validation', () => {
  for (const importCase of importCases) {
    stateTest(`${importCase.name} import dialog state`, async (page, testInfo) => {
      await page.goto(importCase.path)
      await page.getByRole('button', { name: importCase.button }).click()
      await capture(page, page.getByTestId('import-file-dialog'), `${importCase.name}-import`, testInfo)
    })
  }

  for (const tab of ['Schedules', 'Run history', 'Templates']) {
    stateTest(`Scheduled Runs tab content: ${tab}`, async (page, testInfo) => {
      await page.goto('/generator/schedules')
      const target = page.getByRole('tab', { name: tab })
      await target.click()
      await expect(target).toHaveAttribute('aria-selected', 'true')
      await capture(page, page.getByTestId('scheduled-runs-page'), `schedule-tab-${tab}`, testInfo)
    })
  }

  const requiredFields = [
    { name: 'setup ID', missing: '#setupId', message: 'Please enter the setup ID' },
    { name: 'database name', missing: '#databaseName', message: 'Please enter the database name' },
    { name: 'database password', missing: '#password', message: 'Please enter the database password' },
  ]
  for (const field of requiredFields) {
    stateTest(`Connect validation: missing ${field.name}`, async (page, testInfo) => {
      await page.goto('/setups/connect')
      if (field.missing !== '#setupId') await page.locator('#setupId').fill('existing')
      if (field.missing !== '#databaseName') await page.locator('#databaseName').fill('postgres')
      if (field.missing !== '#password') await page.locator('#password').fill('secret')
      await page.getByTestId('connect-button').click()
      await capture(page, page.getByText(field.message), `connect-missing-${field.name}`, testInfo)
    })
  }

  stateTest('Connect validation: all required fields missing', async (page, testInfo) => {
    await page.goto('/setups/connect')
    await page.getByTestId('connect-button').click()
    await expect(page.locator('.ant-form-item-explain-error')).toHaveCount(3)
    await capture(page, page.getByTestId('connect-setup-page'), 'connect-all-required-missing', testInfo)
  })

  stateTest('Connect option: SSL enabled', async (page, testInfo) => {
    await page.goto('/setups/connect')
    await page.getByText('Connection details').click()
    const checkbox = page.getByRole('checkbox', { name: 'Enable SSL' })
    await checkbox.check()
    await capture(page, checkbox, 'connect-ssl-enabled', testInfo)
  })

  stateTest('Connect option: connection details expanded', async (page, testInfo) => {
    await page.goto('/setups/connect')
    await page.getByText('Connection details').click()
    await capture(page, page.locator('.ant-collapse-content'), 'connect-details-expanded', testInfo)
  })

  stateTest('Overview refresh completed state', async (page, testInfo) => {
    await page.goto('/')
    await page.getByTestId('refresh-button').click()
    await expect(page.getByTestId('no-setups')).toBeVisible()
    await capture(page, page.getByTestId('overview-page'), 'overview-refreshed', testInfo)
  })
})

test.describe('Functional state screenshots — rate advisory', () => {
  stateTest('rate above warning threshold', async (page, testInfo) => {
    await page.goto('/generator')
    await page.locator('#rate-controls-warnThreshold').fill('5')
    await page.locator('#rate-controls-rate').fill('10')
    await page.locator('#rate-controls-rate').press('Tab')
    await capture(page, page.getByTestId('rate-warning'), 'rate-warning-visible', testInfo)
  })
})

if (declaredTests !== 461) {
  throw new Error(`Expected exactly 461 functional state screenshot tests, found ${declaredTests}`)
}
