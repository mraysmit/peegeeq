/**
 * Screenshot capture spec — regenerates all documentation screenshots.
 *
 * Run with:
 *   npx playwright test src/tests/e2e/specs/take-screenshots.spec.ts --headed --reporter=list
 *
 * Creates live data (queue, event store, 5 correlated events) then captures every page
 * and every meaningful functional state with real data visible.
 *
 * Screenshots are written to:
 *   docs-design/peegeeq-management-ui/screenshots/
 */
import { test, expect, Page } from '@playwright/test'
import * as path from 'path'
import * as fs from 'fs'
import { fileURLToPath } from 'url'
import { selectAntOption } from '../utils/ant-helpers'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const DIR = path.resolve(__dirname, '../../../../../docs-design/peegeeq-management-ui/screenshots')
const STATE_FILE = path.join(process.cwd(), 'screenshots-state.json')
const SETUP_ID = 'default'

// Ensure output directory exists
if (!fs.existsSync(DIR)) fs.mkdirSync(DIR, { recursive: true })

function saveState(state: Record<string, string>): void {
  fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2))
}

function loadState(): Record<string, string> {
  try {
    if (fs.existsSync(STATE_FILE)) return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'))
  } catch { /* ignore */ }
  return {}
}

// ─────────────────────────────────────────────────────────────────────────────
// All tests must run sequentially so that shared state (queueName, etc.) is
// visible across tests within the same worker process.
// ─────────────────────────────────────────────────────────────────────────────

test.describe('PeeGeeQ UI Screenshots', () => {
  test.describe.configure({ mode: 'serial' })

  test.use({ viewport: { width: 1440, height: 900 } })
  test.setTimeout(180000)

  let queueName      = ''
  let eventStoreName = ''
  let correlationId  = ''
  let aggregateId    = ''

  /** Reload shared state from file — allows individual tests to be re-run. */
  test.beforeEach(async () => {
    const s = loadState()
    if (s.queueName)      queueName      = s.queueName
    if (s.eventStoreName) eventStoreName = s.eventStoreName
    if (s.correlationId)  correlationId  = s.correlationId
    if (s.aggregateId)    aggregateId    = s.aggregateId
  })

  /** Full-page screenshot after sidebar confirms app is rendered. */
  async function shot(page: Page, filename: string): Promise<void> {
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await page.waitForTimeout(1000)
    await page.screenshot({ path: path.join(DIR, filename) })
  }

  // ── 0. Create all live data ───────────────────────────────────────────────

  test('00 create data', async ({ page }) => {
    const dbConfig = JSON.parse(fs.readFileSync('testcontainers-db.json', 'utf8'))
    const ts = Date.now()
    queueName      = `orders_queue_${ts}`
    eventStoreName = `orders_store_${ts}`
    correlationId  = `corr-${ts}`
    aggregateId    = `order-agg-${ts}`

    // ── Create DB setup if needed ─────────────────────────────────────────
    await page.goto('/database-setups')
    await expect(page.locator('.ant-table')).toBeVisible({ timeout: 15000 })
    const setupExists = (await page.locator(`tr:has-text("${SETUP_ID}")`).count()) > 0
    if (!setupExists) {
      await page.getByRole('button', { name: /create setup/i }).click()
      await expect(page.locator('.ant-modal')).toBeVisible()
      await page.getByLabel('Setup ID').fill(SETUP_ID)
      await page.getByLabel('Host').fill(dbConfig.host)
      await page.getByLabel('Port').fill(String(dbConfig.port))
      await page.getByLabel('Database Name').fill(`screenshots_${ts}`)
      await page.getByLabel('Username').fill(dbConfig.username)
      await page.getByLabel('Password').fill(dbConfig.password)
      await page.getByLabel('Schema').fill('public')
      await page.getByRole('button', { name: /create setup/i }).last().click()
      await expect(page.locator('.ant-modal')).not.toBeVisible({ timeout: 120000 })
    }

    // ── Create queue ──────────────────────────────────────────────────────
    await page.goto('/queues')
    await page.getByTestId('create-queue-btn').click()
    await expect(page.locator('.ant-modal')).toBeVisible()
    await page.getByTestId('queue-name-input').fill(queueName)
    await page.getByTestId('refresh-setups-btn').click()
    await page.waitForTimeout(600)
    await selectAntOption(page.getByTestId('queue-setup-select'), SETUP_ID)
    await page.locator('.ant-modal .ant-btn-primary').click()
    await expect(page.locator('.ant-modal')).not.toBeVisible({ timeout: 15000 })

    // ── Create event store ────────────────────────────────────────────────
    await page.goto('/event-stores')
    await page.getByRole('button', { name: /create event store/i }).click()
    await expect(page.locator('.ant-modal')).toBeVisible()
    await page.locator('#name').fill(eventStoreName)
    await page.getByTestId('refresh-setups-btn').click()
    await page.waitForTimeout(600)
    // #setupId is the Ant Select hidden input in the modal's Setup form item
    await selectAntOption(page.locator('.ant-modal .ant-select:has(#setupId)'), SETUP_ID)
    await page.locator('.ant-modal .ant-btn-primary').click()
    await expect(page.locator('.ant-modal')).not.toBeVisible({ timeout: 30000 })

    // ── Post events with correlation chain ────────────────────────────────
    const postEvent = async (eventType: string, causeId?: string): Promise<string> => {
      await page.goto('/events')
      await expect(page.getByRole('button', { name: 'Post Event' })).toBeVisible({ timeout: 15000 })

      // Open advanced section to expose Event Sourcing fields
      const advBtn = page.getByRole('button', { name: /show advanced/i })
      if (await advBtn.isVisible()) await advBtn.click()
      await expect(page.locator('.ant-card-head-title').filter({ hasText: /event sourcing/i })).toBeVisible({ timeout: 8000 })

      // Scope selects to the Post Event card using :has() on the Ant Select container
      const postCard = page.locator('.ant-card').filter({ hasText: 'Post Event' }).first()
      await selectAntOption(postCard.locator('.ant-select:has(#setupId)'), SETUP_ID)
      await selectAntOption(postCard.locator('.ant-select:has(#eventStoreName)'), eventStoreName)
      await page.locator('#eventType').fill(eventType)
      await page.locator('#eventData').fill(JSON.stringify({ orderId: 'ORDER-001', amount: 99.99 }))
      await page.locator('#aggregateId').fill(aggregateId)
      await page.locator('#correlationId').fill(correlationId)
      if (causeId) await page.locator('#causationId').fill(causeId)

      await page.getByRole('button', { name: 'Post Event' }).click()

      const toast = page.locator('.ant-message-notice-content').first()
      await expect(toast).toBeVisible({ timeout: 15000 })
      const text = await toast.innerText()
      await expect(toast).not.toBeVisible({ timeout: 10000 })
      const match = text.match(/([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})/i)
      return match ? match[1] : ''
    }

    const id1 = await postEvent('OrderCreated')
    const id2 = await postEvent('OrderShipped',    id1)
    await       postEvent('PaymentProcessed', id2)
    await       postEvent('OrderCancelled')
    await       postEvent('CustomerRegistered')

    saveState({ queueName, eventStoreName, correlationId, aggregateId })
  })

  // ── 1. Overview ───────────────────────────────────────────────────────────

  test('01 overview', async ({ page }) => {
    await page.goto('/')
    await page.waitForTimeout(2500) // let charts render
    await shot(page, '01-overview.png')
  })

  test('01b overview - setup selected', async ({ page }) => {
    await page.goto('/')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)
    await page.waitForTimeout(1500) // let setup details fetch + charts refresh
    await shot(page, '01b-overview-setup-selected.png')
  })

  test('01c overview - setup details button', async ({ page }) => {
    await page.goto('/')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)
    await page.waitForTimeout(800)
    // Capture just the scope bar row so the button is clearly visible
    const scopeBar = page.getByTestId('scope-bar')
    await scopeBar.waitFor({ state: 'visible' })
    await scopeBar.screenshot({ path: path.join(DIR, '01c-overview-setup-details-button.png') })
  })

  test('01d overview - setup details panel', async ({ page }) => {
    await page.goto('/')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)
    await page.waitForTimeout(800)
    const panel = page.getByTestId('setup-details-panel')
    await expect(panel).toBeVisible()
    // Wait for details to load (loading placeholder disappears)
    await expect(panel.getByText('Loading setup details')).not.toBeVisible({ timeout: 10000 })
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(DIR, '01d-overview-setup-details-panel.png') })
  })

  test('02 header', async ({ page }) => {
    await page.goto('/')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await page.waitForTimeout(1000)
    await page.locator('.ant-layout-header').screenshot({ path: path.join(DIR, '02-header.png') })
  })

  test('03 ws-sse status', async ({ page }) => {
    await page.goto('/')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await page.waitForTimeout(1500)
    const banner = page.locator('[data-testid="system-status-info"]')
    if (await banner.isVisible()) {
      await banner.screenshot({ path: path.join(DIR, '03-ws-sse-status.png') })
    } else {
      await page.screenshot({ path: path.join(DIR, '03-ws-sse-status.png') })
    }
  })

  // ── 2. Queues ─────────────────────────────────────────────────────────────

  test('04 queues with data', async ({ page }) => {
    await page.goto('/queues')
    await expect(page.getByTestId('queues-table')).toBeVisible({ timeout: 15000 })
    await shot(page, '04-queues.png')
  })

  test('04b create queue modal', async ({ page }) => {
    await page.goto('/queues')
    await page.getByTestId('create-queue-btn').click()
    await expect(page.locator('.ant-modal')).toBeVisible()
    await page.waitForTimeout(600)
    await page.screenshot({ path: path.join(DIR, '04b-queues-create-queue-modal.png') })
    await page.keyboard.press('Escape')
  })

  test('04c queue details - overview tab', async ({ page }) => {
    await page.goto(`/queues/${SETUP_ID}/${queueName}`)
    await expect(page.getByTestId('queue-details-tabs')).toBeVisible({ timeout: 15000 })
    await shot(page, '04c-queue-details-overview.png')
  })

  test('04d queue details - consumers tab', async ({ page }) => {
    await page.goto(`/queues/${SETUP_ID}/${queueName}`)
    await expect(page.getByTestId('queue-details-tabs')).toBeVisible({ timeout: 15000 })
    await page.getByTestId('queue-details-tabs').getByRole('tab', { name: /consumers/i }).click()
    await page.waitForTimeout(600)
    await shot(page, '04d-queue-details-consumers.png')
  })

  test('04e queue details - messages tab', async ({ page }) => {
    await page.goto(`/queues/${SETUP_ID}/${queueName}`)
    await expect(page.getByTestId('queue-details-tabs')).toBeVisible({ timeout: 15000 })
    await page.getByTestId('queue-details-tabs').getByRole('tab', { name: /messages/i }).click()
    await page.waitForTimeout(600)
    await shot(page, '04e-queue-details-messages.png')
  })

  test('04f queue details - bindings tab', async ({ page }) => {
    await page.goto(`/queues/${SETUP_ID}/${queueName}`)
    await expect(page.getByTestId('queue-details-tabs')).toBeVisible({ timeout: 15000 })
    await page.getByTestId('queue-details-tabs').getByRole('tab', { name: /bindings/i }).click()
    await page.waitForTimeout(600)
    await shot(page, '04f-queue-details-bindings.png')
  })

  test('04g queue details - charts tab', async ({ page }) => {
    await page.goto(`/queues/${SETUP_ID}/${queueName}`)
    await expect(page.getByTestId('queue-details-tabs')).toBeVisible({ timeout: 15000 })
    await page.getByTestId('queue-details-tabs').getByRole('tab', { name: /charts/i }).click()
    await page.waitForTimeout(600)
    await shot(page, '04g-queue-details-charts.png')
  })

  // ── 3. Database Setups ────────────────────────────────────────────────────

  test('05 database setups', async ({ page }) => {
    await page.goto('/database-setups')
    await expect(page.locator('.ant-table')).toBeVisible({ timeout: 15000 })
    await shot(page, '05-database-setups.png')
  })

  test('06 create setup modal', async ({ page }) => {
    await page.goto('/database-setups')
    await page.getByRole('button', { name: /create setup/i }).click()
    await expect(page.locator('.ant-modal')).toBeVisible()
    await page.waitForTimeout(600)
    await page.screenshot({ path: path.join(DIR, '06-create-setup-modal.png') })
    await page.keyboard.press('Escape')
  })

  // ── 4. Event Stores ───────────────────────────────────────────────────────

  test('07 event stores', async ({ page }) => {
    await page.goto('/event-stores')
    await expect(page.locator('.ant-table')).toBeVisible({ timeout: 15000 })
    await shot(page, '07-event-stores.png')
  })

  test('07b event store - details modal', async ({ page }) => {
    await page.goto('/event-stores')
    await expect(page.locator('.ant-table')).toBeVisible({ timeout: 15000 })
    await page.waitForTimeout(600)
    // Open per-row actions dropdown and click View Details
    await page.locator('.ant-table-tbody tr.ant-table-row').first().locator('button').last().click()
    await expect(page.locator('.ant-dropdown:not(.ant-dropdown-hidden)')).toBeVisible({ timeout: 5000 })
    await page.locator('.ant-dropdown-menu-item').filter({ hasText: /view details/i }).click()
    await expect(page.locator('.ant-modal')).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(800)
    await page.screenshot({ path: path.join(DIR, '07b-event-store-details-modal.png') })
    await page.keyboard.press('Escape')
  })

  // ── 5. Events Page ────────────────────────────────────────────────────────

  test('07c events - post form basic', async ({ page }) => {
    await page.goto('/events')
    await expect(page.getByRole('button', { name: 'Post Event' })).toBeVisible({ timeout: 15000 })
    await shot(page, '07c-events-post-form.png')
  })

  test('07d events - post form with advanced open', async ({ page }) => {
    await page.goto('/events')
    await expect(page.getByRole('button', { name: 'Post Event' })).toBeVisible({ timeout: 15000 })
    await page.getByRole('button', { name: /show advanced/i }).click()
    await expect(page.locator('.ant-card-head-title').filter({ hasText: /temporal/i })).toBeVisible({ timeout: 8000 })
    await shot(page, '07d-events-post-form-advanced.png')
  })

  test('07e events - events loaded', async ({ page }) => {
    await page.goto('/events')
    await expect(page.getByRole('button', { name: 'Post Event' })).toBeVisible({ timeout: 15000 })
    await selectAntOption(page.getByTestId('query-setup-select'), SETUP_ID)
    await selectAntOption(page.getByTestId('query-eventstore-select'), eventStoreName)
    await page.getByRole('button', { name: 'Load Events' }).click()
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 20000 })
    await page.waitForTimeout(800)
    await shot(page, '07e-events-loaded.png')
  })

  test('07f events - filtered by event type', async ({ page }) => {
    await page.goto('/events')
    await expect(page.getByRole('button', { name: 'Post Event' })).toBeVisible({ timeout: 15000 })
    await selectAntOption(page.getByTestId('query-setup-select'), SETUP_ID)
    await selectAntOption(page.getByTestId('query-eventstore-select'), eventStoreName)
    await page.getByRole('button', { name: 'Load Events' }).click()
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 20000 })
    await page.getByPlaceholder('Event Type').fill('OrderCreated')
    await page.waitForTimeout(600)
    await shot(page, '07f-events-filtered.png')
  })

  test('07g events - event detail modal', async ({ page }) => {
    await page.goto('/events')
    await expect(page.getByRole('button', { name: 'Post Event' })).toBeVisible({ timeout: 15000 })
    await selectAntOption(page.getByTestId('query-setup-select'), SETUP_ID)
    await selectAntOption(page.getByTestId('query-eventstore-select'), eventStoreName)
    await page.getByRole('button', { name: 'Load Events' }).click()
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 20000 })
    // Open detail modal for the first event
    await page.locator('.ant-table-tbody tr.ant-table-row').first().getByRole('button').first().click()
    await expect(page.locator('.ant-modal')).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(800)
    await page.screenshot({ path: path.join(DIR, '07g-event-detail-modal.png') })
    await page.keyboard.press('Escape')
  })

  // ── 6. Consumer Groups / Messages / Settings ──────────────────────────────

  test('08 settings', async ({ page }) => {
    await page.goto('/settings')
    await page.waitForTimeout(1500)
    await shot(page, '08-settings.png')
  })

  test('09 consumer groups', async ({ page }) => {
    await page.goto('/consumer-groups')
    await page.waitForTimeout(1200)
    await shot(page, '09-consumer-groups.png')
  })

  test('10 message browser', async ({ page }) => {
    await page.goto('/messages')
    await page.waitForTimeout(1200)
    await shot(page, '10-message-browser.png')
  })

  // ── 7. Event Visualization ────────────────────────────────────────────────

  test('11 event visualization - empty state', async ({ page }) => {
    await page.goto('/event-visualization')
    await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Select Event Store' })).toBeVisible()
    await shot(page, '11-event-visualization-empty.png')
  })

  test('11b event visualization - store selected', async ({ page }) => {
    await page.goto('/event-visualization')
    await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Select Event Store' })).toBeVisible()
    await selectAntOption(page.getByTestId('viz-setup-select'), SETUP_ID)
    await selectAntOption(page.getByTestId('viz-eventstore-select'), eventStoreName)
    await expect(page.locator('.ant-card-head-title').filter({ hasText: /causation tree/i })).toBeVisible({ timeout: 10000 })
    await shot(page, '11b-event-visualization-store-selected.png')
  })

  test('12 event visualization - causation tree traced', async ({ page }) => {
    await page.goto('/event-visualization')
    await selectAntOption(page.getByTestId('viz-setup-select'), SETUP_ID)
    await selectAntOption(page.getByTestId('viz-eventstore-select'), eventStoreName)
    await expect(page.locator('.ant-card-head-title').filter({ hasText: /causation tree/i })).toBeVisible({ timeout: 10000 })
    await page.getByPlaceholder(/enter correlation id/i).fill(correlationId)
    await page.getByRole('button', { name: /trace/i }).click()
    // Wait for an actual visible tree node (aria-hidden helper nodes exist in the DOM
    // but are not visible — use ant-tree-node-content-wrapper to target real nodes)
    await expect(page.locator('.ant-tree-node-content-wrapper').first()).toBeVisible({ timeout: 20000 })
    await page.waitForTimeout(1000)
    await shot(page, '12-event-visualization-causation-tree.png')
  })

  test('12b event visualization - aggregate stream', async ({ page }) => {
    await page.goto('/event-visualization')
    await selectAntOption(page.getByTestId('viz-setup-select'), SETUP_ID)
    await selectAntOption(page.getByTestId('viz-eventstore-select'), eventStoreName)
    await expect(page.locator('.ant-card-head-title').filter({ hasText: /aggregate stream/i })).toBeVisible({ timeout: 10000 })
    await page.locator('.ant-card-head-title').filter({ hasText: /aggregate stream/i }).scrollIntoViewIfNeeded()
    await page.getByRole('button', { name: /refresh list/i }).click()
    const aggRow = page.locator('tr').filter({ hasText: aggregateId }).first()
    await expect(aggRow).toBeVisible({ timeout: 15000 })
    await aggRow.getByText('View Stream').click()
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 15000 })
    await page.waitForTimeout(800)
    await shot(page, '12b-event-visualization-aggregate-stream.png')
  })
})
