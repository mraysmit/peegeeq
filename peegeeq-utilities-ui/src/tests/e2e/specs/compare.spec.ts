import { test, expect } from '../page-objects'
import * as fs from 'fs'

/**
 * Native-vs-Outbox comparison mode E2E — real backend, no mocks (G.2d).
 *
 * Provisions its own setup with ONE NATIVE and ONE OUTBOX queue, then drives
 * Compare mode end to end: mode switch → both targets resolved with their real
 * implementation types → a REAL simultaneous run publishing to both queues →
 * side-by-side acknowledged counts the BACKEND confirmed → the verdict line.
 *
 * This is the only automated coverage of three things unit tests cannot reach:
 *
 * - the page driving TWO engines at once against a live backend
 * - Stop routing to BOTH engines (a comparison cannot start without a backend,
 *   so no unit probe can bite on that wiring)
 * - the refusals asserted WITH both targets selected — without a backend the
 *   unit tests have no targets, which is exactly what made the equivalent
 *   Profile assertions vacuous (the G.3c finding)
 *
 * The load is deliberately tiny (5 msg/s for 2 s per side) so the run is a few
 * seconds.
 */

const API_BASE_URL = 'http://127.0.0.1:8088'
const SCHEMA = 'public'
const NATIVE_QUEUE = 'cmp_native'
const OUTBOX_QUEUE = 'cmp_outbox'

interface DbConnectionInfo {
  host: string
  port: number
  username: string
  password: string
}

function readDbConfig(): DbConnectionInfo {
  const raw = JSON.parse(fs.readFileSync('testcontainers-db.json', 'utf8'))
  return { host: raw.host, port: raw.port, username: raw.username, password: raw.password }
}

test.describe.configure({ mode: 'serial' })

test.describe('Native-vs-Outbox comparison mode', () => {
  const stamp = Date.now()
  const SETUP_ID = `e2e-compare-${stamp}`
  const DB_NAME = `e2e_compare_db_${stamp}`

  test.beforeAll(async ({ request }) => {
    const db = readDbConfig()
    const create = await request.post(`${API_BASE_URL}/api/v1/database-setup/create`, {
      data: {
        setupId: SETUP_ID,
        databaseConfig: {
          host: db.host,
          port: db.port,
          databaseName: DB_NAME,
          username: db.username,
          password: db.password,
          schema: SCHEMA,
          templateDatabase: 'template0',
          encoding: 'UTF8',
        },
        // One queue of EACH implementation type — the whole point of the mode.
        // ConfigParser accepts implementationType per queue ("native"/"outbox").
        queues: [
          { queueName: NATIVE_QUEUE, implementationType: 'native', maxRetries: 3, visibilityTimeout: 30 },
          { queueName: OUTBOX_QUEUE, implementationType: 'outbox', maxRetries: 3, visibilityTimeout: 30 },
        ],
        eventStores: [],
      },
      timeout: 120000,
    })
    if (!create.ok()) {
      throw new Error(`Provision (create) failed: ${create.status()} ${await create.text()}`)
    }

    // The comparison is meaningless if both queues came back the same type, so
    // the provisioning assumption is verified rather than trusted.
    const listed = await request.get(`${API_BASE_URL}/api/v1/setups/${SETUP_ID}/queues`)
    const body = await listed.json()
    const types = new Map<string, string>(
      (body.queueDetails ?? []).map((q: { name: string; implementationType: string }) => [
        q.name,
        q.implementationType,
      ])
    )
    if (types.get(NATIVE_QUEUE) !== 'native' || types.get(OUTBOX_QUEUE) !== 'outbox') {
      throw new Error(
        `Provisioned queue types are not native+outbox: ${JSON.stringify([...types.entries()])}`
      )
    }
  })

  test.afterAll(async ({ request }) => {
    await request.delete(`${API_BASE_URL}/api/v1/setups/${SETUP_ID}`)
  })

  /** Switch to Compare mode and point BOTH rows at this spec's own setup. */
  async function enterCompareOnOurSetup(page: import('@playwright/test').Page): Promise<void> {
    await page.getByTestId('generator-mode').getByText('Compare', { exact: true }).click()
    await expect(page.getByTestId('compare-targets')).toBeVisible({ timeout: 20000 })

    for (const side of ['native', 'outbox'] as const) {
      const row = page.getByTestId(`compare-row-${side}`)
      const current = row.locator(
        `.ant-select:has(#compare-${side}-setup) .ant-select-selection-item`
      )
      // Other specs leave their own setups behind, so the first setup is not
      // necessarily ours. It MAY already be ours, though — CompareTargets
      // auto-selects the first — and clicking an already-selected antd option
      // races the dropdown closing itself. Only open it when a change is needed.
      // TYPE-TO-FILTER then ENTER, never a dropdown-wide click: antd virtualizes long
      // option lists, so under -Pall-tests (thirteen prior projects' setups in the reused
      // container) this spec's own setup is not even in the DOM until filtered (surfaced
      // 2026-08-09; the select gained showSearch for exactly this). Enter takes the
      // filtered list's active option, which keeps the interaction scoped to the select
      // being edited. A `.ant-select-dropdown:visible` locator does NOT: the first row's
      // dropdown is still in the DOM while the second row's is open, so getByTitle matched
      // the same setup in both and failed on strict mode (2026-08-10). The full timestamped
      // SETUP_ID filters to exactly one option, and the assertion below proves which one
      // was taken.
      if ((await current.textContent()) !== SETUP_ID) {
        await row.locator(`.ant-select:has(#compare-${side}-setup)`).click()
        await page.keyboard.type(SETUP_ID)
        await page.keyboard.press('Enter')
      }
      await expect(current).toHaveText(SETUP_ID)
    }
  }

  /** The label antd shows for a row's selected QUEUE (the row's second select). */
  function selectedQueue(page: import('@playwright/test').Page, side: 'native' | 'outbox') {
    return page
      .getByTestId(`compare-row-${side}`)
      .locator('.ant-select:has(#compare-' + side + '-queue) .ant-select-selection-item')
  }

  async function setSharedLoad(
    page: import('@playwright/test').Page,
    rate: number,
    durationSecs: number
  ): Promise<void> {
    await page.getByLabel('Rate (msg/s)').fill(String(rate))
    await page.getByLabel('Duration (seconds)').fill(String(durationSecs))
  }

  test('runs both queues at once and reports side-by-side acknowledged counts', async ({ page }) => {
    test.setTimeout(180000)
    page.on('pageerror', (error) => console.error('Page error:', error.message))

    await page.goto('/generator')
    await enterCompareOnOurSetup(page)

    // Each row auto-selects the queue of ITS OWN type — the behaviour that stops
    // both sides landing on the same queue. The selected label lives in antd's
    // selection-item span; the queue select is the second one in the row.
    await expect(selectedQueue(page, 'native')).toHaveText(new RegExp(NATIVE_QUEUE))
    await expect(selectedQueue(page, 'outbox')).toHaveText(new RegExp(OUTBOX_QUEUE))

    await setSharedLoad(page, 5, 2)
    await page.getByLabel(/Message type/i).fill('e2e.compare')
    await page.getByLabel(/Payload/i).fill('{"id":"{{messageId}}"}')

    await page.getByRole('button', { name: /^Start$/ }).click()

    // Both sides settle. These counts are the BACKEND's acknowledgements, so
    // this asserts real publishing to two queues at once, not local counters.
    await expect(page.getByTestId('compare-native-acked')).toHaveText('10', { timeout: 120000 })
    await expect(page.getByTestId('compare-outbox-acked')).toHaveText('10')
    // Both sides have now stopped, but the report is held back until the final
    // database sample accounts for the run — a window measured at ~9 s here.
    // Through it the panel must NOT still claim the sides are running.
    //
    // This is the only assertion that can bite the page's `onSideSettled`
    // wiring: deleting it leaves the count at zero for ever, and no unit test
    // sees that — the page cannot start a comparison without a backend, and the
    // panel's own tests are handed the count directly. Same blind spot as the
    // CompareTargets onChange defect (§4.4 of the G.2 handoff).
    //
    // If PostgreSQL ever made the statistics visible immediately this would
    // fail rather than pass quietly, which is the correct signal: the settle
    // poll would then be unnecessary.
    await expect(page.getByTestId('compare-live-note')).toContainText(
      /waiting for the database statistics/i,
      { timeout: 30000 }
    )

    // The report is published only after the final database sample accounts for
    // the run, so it can lag both sides settling by up to the settle deadline.
    await expect(page.getByTestId('compare-native-status')).toHaveText('COMPLETED', {
      timeout: 40000,
    })
    await expect(page.getByTestId('compare-outbox-status')).toHaveText('COMPLETED')

    // Requested is derived from the shared load, identical for both sides.
    await expect(page.getByTestId('compare-native-requested')).toHaveText('10')
    await expect(page.getByTestId('compare-outbox-requested')).toHaveText('10')

    // The §4A database churn, which is the comparison only the DB layer can
    // give. Asserted because the suite previously passed while the outbox side
    // read "10 acknowledged, 0 rows inserted": PostgreSQL flushes each
    // backend's statistics on a rate limit, and the two publish paths commit on
    // different connections, so a single final sample can catch one side and
    // miss the other. A "0" here is that defect; a "—" means the counters never
    // reconciled within the settle deadline.
    //
    // Exact counts are safe: this spec provisions its own database, so nothing
    // else writes these tables. They also pin the table mapping — mapped to the
    // queue-named table (an inert marker) both figures would read 0.
    await expect(page.getByTestId('compare-native-churnInserted')).toHaveText('10')
    await expect(page.getByTestId('compare-outbox-churnInserted')).toHaveText('10')

    // A verdict exists and does NOT refuse — both sides completed.
    await expect(page.getByTestId('compare-verdict')).toBeVisible()
    await expect(page.getByTestId('compare-verdict')).not.toContainText('No verdict')
  })

  test('Stop reaches BOTH engines and the verdict then refuses to name a winner', async ({ page }) => {
    test.setTimeout(180000)
    page.on('pageerror', (error) => console.error('Page error:', error.message))

    await page.goto('/generator')
    await enterCompareOnOurSetup(page)

    // Long enough to stop inside.
    await setSharedLoad(page, 5, 60)
    await page.getByLabel(/Message type/i).fill('e2e.compare.stop')
    await page.getByLabel(/Payload/i).fill('{"id":"{{messageId}}"}')

    await page.getByRole('button', { name: /^Start$/ }).click()
    // The live panel appears only while both sides are running.
    await expect(page.getByTestId('compare-live-note')).toBeVisible({ timeout: 30000 })

    await page.getByRole('button', { name: /^Stop$/ }).click()

    // BOTH sides stopped: a Stop that reached only one engine would leave the
    // other publishing for the rest of the minute.
    await expect(page.getByTestId('compare-native-status')).toHaveText('STOPPED', { timeout: 60000 })
    await expect(page.getByTestId('compare-outbox-status')).toHaveText('STOPPED')
    // A stopped pair did not carry the same load, so no winner may be named.
    await expect(page.getByTestId('compare-verdict')).toContainText('No verdict')
  })

  test('refuses to schedule or save a comparison, WITH both targets selected', async ({ page }) => {
    test.setTimeout(120000)
    page.on('pageerror', (error) => console.error('Page error:', error.message))

    await page.goto('/generator')
    await enterCompareOnOurSetup(page)

    // Targets ARE selected here, so these disabled states come from the refusal
    // reasons themselves — the assertion the unit tests cannot make.
    await expect(selectedQueue(page, 'native')).toHaveText(new RegExp(NATIVE_QUEUE))
    await expect(selectedQueue(page, 'outbox')).toHaveText(new RegExp(OUTBOX_QUEUE))
    await expect(page.getByRole('button', { name: /Schedule/ })).toBeDisabled()
    await expect(page.getByTestId('scenario-save-as')).toBeDisabled()
    // Start, by contrast, is armed: the pair is valid.
    await expect(page.getByRole('button', { name: /^Start$/ })).toBeEnabled()
  })

  test('refuses a pair pointing at the same queue, naming it', async ({ page }) => {
    test.setTimeout(120000)
    page.on('pageerror', (error) => console.error('Page error:', error.message))

    await page.goto('/generator')
    await enterCompareOnOurSetup(page)

    // Point the outbox row at the NATIVE queue: one queue cannot be both sides.
    const outboxRow = page.getByTestId('compare-row-outbox')
    await outboxRow.locator('.ant-select:has(#compare-outbox-queue)').click()
    await page.locator('.ant-select-dropdown:visible').getByTitle(`${NATIVE_QUEUE} (native)`).click()

    const start = page.getByRole('button', { name: /^Start$/ })
    await expect(start).toBeDisabled()
    // The reason is carried in the tooltip, not left as a silent dead button.
    await start.hover({ force: true })
    await expect(page.getByRole('tooltip')).toContainText(/same queue/i)
  })
})
