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

  let queueName         = ''
  let eventStoreName    = ''
  let correlationId     = ''
  let aggregateId       = ''
  let consumerGroupName = ''

  /** Reload shared state from file — allows individual tests to be re-run. */
  test.beforeEach(async () => {
    const s = loadState()
    if (s.queueName)         queueName         = s.queueName
    if (s.eventStoreName)    eventStoreName    = s.eventStoreName
    if (s.correlationId)     correlationId     = s.correlationId
    if (s.aggregateId)       aggregateId       = s.aggregateId
    if (s.consumerGroupName) consumerGroupName = s.consumerGroupName
  })

  /** Full-page screenshot after sidebar confirms app is rendered.
   *
   * The app layout uses height:100vh with an internal scroll container, so
   * fullPage:true has no effect.  Instead we temporarily expand the viewport
   * to 2400 px tall (enough for any page), take the shot, then restore the
   * original size so subsequent interactions are unaffected.
   */
  async function shot(page: Page, filename: string): Promise<void> {
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    const vp = page.viewportSize() ?? { width: 1440, height: 900 }
    await page.setViewportSize({ width: vp.width, height: 1200 })
    await page.waitForTimeout(500)
    await page.screenshot({ path: path.join(DIR, filename) })
    await page.setViewportSize(vp)
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

    // ── Publish messages to queue (needed for Message Browser screenshots) ─
    const publishPayloads = [
      { orderId: 'ORD-001', amount: 49.99,  customer: 'Alice Johnson', status: 'new',        items: 1 },
      { orderId: 'ORD-002', amount: 129.00, customer: 'Bob Smith',     status: 'processing', items: 3 },
      { orderId: 'ORD-003', amount: 79.50,  customer: 'Carol White',   status: 'confirmed',  items: 2 },
      { orderId: 'ORD-004', amount: 249.99, customer: 'Dan Brown',     status: 'shipped',    items: 5 },
      { orderId: 'ORD-005', amount: 19.99,  customer: 'Eve Davis',     status: 'cancelled',  items: 1 },
    ]
    for (const payload of publishPayloads) {
      const r = await page.request.post(`/api/v1/queues/${SETUP_ID}/${queueName}/publish`, { data: { payload } })
      if (!r.ok()) console.warn(`Publish ${payload.orderId} failed: ${r.status()}`)
    }

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

    // ── Create consumer group via API ────────────────────────────────────
    consumerGroupName = `order-processors-${ts}`
    const cgResponse = await page.request.post('/api/v1/management/consumer-groups', {
      data: { name: consumerGroupName, setup: SETUP_ID, queueName },
      headers: { 'content-type': 'application/json' }
    })
    if (!cgResponse.ok()) {
      console.warn(`Consumer group creation returned ${cgResponse.status()}:`, await cgResponse.text())
    }

    saveState({ queueName, eventStoreName, correlationId, aggregateId, consumerGroupName })
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

  test('04h queue delete confirmation dialog', async ({ page }) => {
    await page.goto('/queues')
    await expect(page.getByTestId('queues-table')).toBeVisible({ timeout: 15000 })
    // Open the actions dropdown on the first queue row
    const firstRow = page.locator('.ant-table-tbody tr.ant-table-row').first()
    await firstRow.locator('.anticon-more').click()
    await expect(page.locator('.ant-dropdown:not(.ant-dropdown-hidden)')).toBeVisible({ timeout: 5000 })
    await page.locator('.ant-dropdown-menu-item').filter({ hasText: 'Delete Queue' }).click()
    // Capture the confirm dialog
    await expect(page.locator('.ant-modal-confirm')).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(DIR, '04h-queue-delete-confirm-dialog.png') })
    await page.keyboard.press('Escape')
  })

  test('04j queue details - get messages modal', async ({ page }) => {
    await page.goto(`/queues/${SETUP_ID}/${queueName}`)
    await expect(page.getByTestId('queue-details-tabs')).toBeVisible({ timeout: 15000 })
    await page.getByTestId('queue-details-tabs').getByRole('tab', { name: /messages/i }).click()
    await page.waitForTimeout(400)
    await page.getByRole('button', { name: /get messages/i }).click()
    await expect(page.locator('.ant-modal')).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(DIR, '04j-queue-details-get-messages-modal.png') })
    await page.keyboard.press('Escape')
  })

  test('04k queue details - actions menu open', async ({ page }) => {
    await page.goto(`/queues/${SETUP_ID}/${queueName}`)
    await expect(page.getByTestId('queue-details-tabs')).toBeVisible({ timeout: 15000 })
    await page.getByTestId('queue-actions-btn').click()
    const dropdown = page.locator('.ant-dropdown')
      .filter({ hasNot: page.locator('.ant-dropdown-hidden') })
      .last()
    await expect(dropdown).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(DIR, '04k-queue-details-actions-menu.png') })
    await page.keyboard.press('Escape')
  })

  test('04l queue details - pause confirm dialog', async ({ page }) => {
    await page.goto(`/queues/${SETUP_ID}/${queueName}`)
    await expect(page.getByTestId('queue-details-tabs')).toBeVisible({ timeout: 15000 })
    await page.getByTestId('queue-actions-btn').click()
    const dropdown = page.locator('.ant-dropdown')
      .filter({ hasNot: page.locator('.ant-dropdown-hidden') })
      .last()
    await expect(dropdown).toBeVisible({ timeout: 5000 })
    await dropdown.getByText('Pause Queue').click()
    await expect(page.locator('.ant-modal-confirm')).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(DIR, '04l-queue-details-pause-confirm.png') })
    await page.keyboard.press('Escape')
  })

  test('04m queue details - purge messages confirm dialog', async ({ page }) => {
    await page.goto(`/queues/${SETUP_ID}/${queueName}`)
    await expect(page.getByTestId('queue-details-tabs')).toBeVisible({ timeout: 15000 })
    await page.getByTestId('queue-actions-btn').click()
    const dropdown = page.locator('.ant-dropdown')
      .filter({ hasNot: page.locator('.ant-dropdown-hidden') })
      .last()
    await expect(dropdown).toBeVisible({ timeout: 5000 })
    await dropdown.getByText('Purge Messages').click()
    await expect(page.locator('.ant-modal-confirm')).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(DIR, '04m-queue-details-purge-confirm.png') })
    await page.keyboard.press('Escape')
  })

  test('04i queue create error toast (503)', async ({ page }) => {
    // Intercept POST to inject a 503 so the error toast is visible
    await page.route('**/api/v1/management/queues**', (route) => {
      if (route.request().method() === 'POST') {
        route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'Service unavailable', timestamp: new Date().toISOString() }),
        })
      } else {
        route.continue()
      }
    })
    await page.goto('/queues')
    await expect(page.getByTestId('create-queue-btn')).toBeVisible({ timeout: 15000 })
    await page.getByTestId('create-queue-btn').click()
    await expect(page.locator('.ant-modal')).toBeVisible()
    await page.getByTestId('queue-name-input').fill('demo-queue')
    await page.getByTestId('refresh-setups-btn').click()
    await page.waitForTimeout(600)
    await selectAntOption(page.getByTestId('queue-setup-select'), SETUP_ID)
    await page.locator('.ant-modal .ant-btn-primary').click()
    await expect(page.locator('.ant-message-error').filter({ hasText: 'Service unavailable' }).first()).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(DIR, '04i-queue-create-error-toast.png') })
    await page.unrouteAll()
    await page.keyboard.press('Escape')
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

  test('06b create setup error toast (503)', async ({ page }) => {
    // Intercept POST to inject a 503 so the error toast is visible
    await page.route('**/api/v1/database-setup/create', (route) => {
      route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Database connection failed', timestamp: new Date().toISOString() }),
      })
    })
    await page.goto('/database-setups')
    await expect(page.getByRole('button', { name: /create setup/i })).toBeVisible({ timeout: 15000 })
    await page.getByRole('button', { name: /create setup/i }).click()
    await expect(page.locator('.ant-modal')).toBeVisible()
    await page.getByLabel('Setup ID').fill('demo-setup')
    await page.getByLabel('Host').fill('localhost')
    await page.getByLabel('Port').fill('5432')
    await page.getByLabel('Database Name').fill('demodb')
    await page.getByLabel('Username').fill('demo')
    await page.getByLabel('Password').fill('demo')
    await page.locator('.ant-modal .ant-btn-primary').click()
    await expect(page.locator('.ant-message-error').filter({ hasText: 'Database connection failed' }).first()).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(DIR, '06b-create-setup-error-toast.png') })
    await page.unrouteAll()
    await page.keyboard.press('Escape')
  })

  test('06c delete setup error toast (503)', async ({ page }) => {
    // Intercept DELETE to inject a 503 so the error toast is visible
    await page.route('**/api/v1/database-setup/**', (route) => {
      if (route.request().method() === 'DELETE') {
        route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'Cannot delete setup: service unavailable', timestamp: new Date().toISOString() }),
        })
      } else {
        route.continue()
      }
    })
    await page.goto('/database-setups')
    await expect(page.locator('.ant-table')).toBeVisible({ timeout: 15000 })
    // Use the real setup row that already exists
    const row = page.locator('tr').filter({ hasText: SETUP_ID }).first()
    await expect(row).toBeVisible({ timeout: 10000 })
    await row.locator('.anticon-more').click()
    await expect(page.locator('.ant-dropdown:not(.ant-dropdown-hidden)')).toBeVisible({ timeout: 5000 })
    await page.locator('.ant-dropdown-menu-item').filter({ hasText: 'Delete' }).click()
    await expect(page.locator('.ant-modal-confirm-btns')).toBeVisible({ timeout: 5000 })
    await page.locator('.ant-modal-confirm-btns .ant-btn-dangerous').click()
    await expect(page.locator('.ant-message-error').filter({ hasText: 'Cannot delete setup' }).first()).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(DIR, '06c-delete-setup-error-toast.png') })
    await page.unrouteAll()
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

  test('07h events - filter by event type partial match', async ({ page }) => {
    await page.goto('/events')
    await expect(page.getByRole('button', { name: 'Post Event' })).toBeVisible({ timeout: 15000 })
    await selectAntOption(page.getByTestId('query-setup-select'), SETUP_ID)
    await selectAntOption(page.getByTestId('query-eventstore-select'), eventStoreName)
    await page.getByRole('button', { name: 'Load Events' }).click()
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 20000 })
    // Type partial name so the filter and results table are both captured
    await page.getByPlaceholder('Event Type').fill('Order')
    await page.waitForTimeout(600)
    await shot(page, '07h-events-filter-by-type.png')
    await page.getByPlaceholder('Event Type').clear()
  })

  test('07i events - filter by correlation ID', async ({ page }) => {
    await page.goto('/events')
    await expect(page.getByRole('button', { name: 'Post Event' })).toBeVisible({ timeout: 15000 })
    await selectAntOption(page.getByTestId('query-setup-select'), SETUP_ID)
    await selectAntOption(page.getByTestId('query-eventstore-select'), eventStoreName)
    await page.getByRole('button', { name: 'Load Events' }).click()
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 20000 })
    await page.getByPlaceholder('Correlation/Causation ID').fill(correlationId)
    await page.waitForTimeout(600)
    await shot(page, '07i-events-filter-by-correlation.png')
    await page.getByPlaceholder('Correlation/Causation ID').clear()
  })

  test('07j events - combined type and correlation filter', async ({ page }) => {
    await page.goto('/events')
    await expect(page.getByRole('button', { name: 'Post Event' })).toBeVisible({ timeout: 15000 })
    await selectAntOption(page.getByTestId('query-setup-select'), SETUP_ID)
    await selectAntOption(page.getByTestId('query-eventstore-select'), eventStoreName)
    await page.getByRole('button', { name: 'Load Events' }).click()
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 20000 })
    await page.getByPlaceholder('Event Type').fill('OrderCreated')
    await page.getByPlaceholder('Correlation/Causation ID').fill(correlationId)
    await page.waitForTimeout(600)
    await shot(page, '07j-events-filter-combined.png')
    await page.getByPlaceholder('Event Type').clear()
    await page.getByPlaceholder('Correlation/Causation ID').clear()
  })

  test('07k events - filter footer showing filtered count', async ({ page }) => {
    await page.goto('/events')
    await expect(page.getByRole('button', { name: 'Post Event' })).toBeVisible({ timeout: 15000 })
    await selectAntOption(page.getByTestId('query-setup-select'), SETUP_ID)
    await selectAntOption(page.getByTestId('query-eventstore-select'), eventStoreName)
    await page.getByRole('button', { name: 'Load Events' }).click()
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 20000 })
    await page.getByPlaceholder('Event Type').fill('OrderShipped')
    await page.waitForTimeout(600)
    await shot(page, '07k-events-filter-footer.png')
    await page.getByPlaceholder('Event Type').clear()
  })

  test('07l events - filter with no results (empty state)', async ({ page }) => {
    await page.goto('/events')
    await expect(page.getByRole('button', { name: 'Post Event' })).toBeVisible({ timeout: 15000 })
    await selectAntOption(page.getByTestId('query-setup-select'), SETUP_ID)
    await selectAntOption(page.getByTestId('query-eventstore-select'), eventStoreName)
    await page.getByRole('button', { name: 'Load Events' }).click()
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 20000 })
    // Type a value that matches nothing
    await page.getByPlaceholder('Event Type').fill('NonExistentEventType_xyz')
    await expect(page.locator('.ant-table-placeholder')).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(400)
    await shot(page, '07l-events-filter-empty-state.png')
    await page.getByPlaceholder('Event Type').clear()
  })

  test('07m events - aggregate type filter active', async ({ page }) => {
    await page.goto('/events')
    await expect(page.getByRole('button', { name: 'Post Event' })).toBeVisible({ timeout: 15000 })
    await selectAntOption(page.getByTestId('query-setup-select'), SETUP_ID)
    await selectAntOption(page.getByTestId('query-eventstore-select'), eventStoreName)
    await page.getByRole('button', { name: 'Load Events' }).click()
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 20000 })
    // Type a value — the seeded events have no aggregateType so 0 results are shown,
    // which clearly illustrates the filter input and empty-state together.
    await page.getByPlaceholder('Aggregate Type').fill('OrderAggregate')
    await expect(page.locator('.ant-table-placeholder')).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(400)
    await shot(page, '07m-events-aggregate-type-filter.png')
    await page.getByPlaceholder('Aggregate Type').clear()
  })

  test('07n events - date range filter active', async ({ page }) => {
    await page.goto('/events')
    await expect(page.getByRole('button', { name: 'Post Event' })).toBeVisible({ timeout: 15000 })
    await selectAntOption(page.getByTestId('query-setup-select'), SETUP_ID)
    await selectAntOption(page.getByTestId('query-eventstore-select'), eventStoreName)
    await page.getByRole('button', { name: 'Load Events' }).click()
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 20000 })
    // Set a past date range (2025) so all 2026 events are excluded — shows picker + empty state.
    const rangeStart = page.locator('.ant-picker-range').locator('input').first()
    await rangeStart.click()
    await rangeStart.fill('2025-01-01 00:00:00')
    await page.keyboard.press('Tab')
    const rangeEnd = page.locator('.ant-picker-range').locator('input').last()
    await rangeEnd.fill('2025-12-31 23:59:59')
    await page.keyboard.press('Enter')
    await expect(page.locator('.ant-table-placeholder')).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(400)
    await shot(page, '07n-events-date-range-filter.png')
    // Clear range picker
    await page.locator('.ant-picker-range .ant-picker-clear').click()
  })

  test('07o events - json validation error on post form', async ({ page }) => {
    await page.goto('/events')
    await expect(page.getByRole('button', { name: 'Post Event' })).toBeVisible({ timeout: 15000 })
    // Fill event type and invalid JSON so the rule validator fires on submit
    await page.locator('#eventType').fill('TestEvent')
    await page.locator('#eventData').fill('{ this is not valid json }')
    await page.getByRole('button', { name: 'Post Event' }).click()
    await expect(
      page.locator('.ant-form-item-explain-error').filter({ hasText: /valid JSON/i })
    ).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(400)
    await shot(page, '07o-events-json-validation-error.png')
  })

  // ── 6. Consumer Groups / Messages / Settings ──────────────────────────────

  test('08 settings', async ({ page }) => {
    await page.goto('/settings')
    await page.waitForTimeout(1500)
    await shot(page, '08-settings.png')
  })

  test('08b settings - REST ping success result', async ({ page }) => {
    await page.goto('/settings')
    await page.waitForTimeout(800)
    await page.getByTestId('ping-rest-btn').click()
    await expect(page.getByTestId('ping-rest-result')).toBeVisible({ timeout: 10000 })
    await page.waitForTimeout(400)
    await shot(page, '08b-settings-ping-rest-result.png')
  })

  test('08c settings - all three pings completed', async ({ page }) => {
    await page.goto('/settings')
    await page.waitForTimeout(800)
    // Trigger all three pings so every card shows a result
    await page.getByTestId('ping-rest-btn').click()
    await page.getByTestId('ping-ws-btn').click()
    await page.getByTestId('ping-sse-btn').click()
    await expect(page.getByTestId('ping-rest-result')).toBeVisible({ timeout: 10000 })
    await expect(page.getByTestId('ping-ws-result')).toBeVisible({ timeout: 10000 })
    await expect(page.getByTestId('ping-sse-result')).toBeVisible({ timeout: 10000 })
    await page.waitForTimeout(400)
    await shot(page, '08c-settings-all-pings-done.png')
  })

  test('08d settings - auto-ping enabled with interval visible', async ({ page }) => {
    await page.goto('/settings')
    await page.waitForTimeout(800)
    // Enable auto-ping on all three cards to show the interval inputs.
    // Use :scope > .ant-card-head so the outer "Connection Health Checks" card
    // (which contains these cards as descendants) is not also matched.
    const restCard = page.locator('.ant-card').filter({
      has: page.locator(':scope > .ant-card-head .ant-card-head-title', { hasText: 'REST API' }),
    })
    const wsCard = page.locator('.ant-card').filter({
      has: page.locator(':scope > .ant-card-head .ant-card-head-title', { hasText: 'WebSocket' }),
    })
    const sseCard = page.locator('.ant-card').filter({
      has: page.locator(':scope > .ant-card-head .ant-card-head-title', { hasText: 'Server-Sent Events' }),
    })
    await restCard.locator('.ant-switch').click()
    await wsCard.locator('.ant-switch').click()
    await sseCard.locator('.ant-switch').click()
    await page.waitForTimeout(400)
    await shot(page, '08d-settings-auto-ping-enabled.png')
    // Turn switches off so no timers run after the screenshot
    await restCard.locator('.ant-switch').click()
    await wsCard.locator('.ant-switch').click()
    await sseCard.locator('.ant-switch').click()
  })

  test('09 consumer groups', async ({ page }) => {
    await page.goto('/consumer-groups')
    await page.waitForTimeout(1200)
    await shot(page, '09-consumer-groups.png')
  })

  test('09b consumer groups - setup and queue selected', async ({ page }) => {
    await page.goto('/consumer-groups')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)
    await page.waitForTimeout(800)
    await selectAntOption(page.getByTestId('queue-scope-selector'), queueName)
    await page.waitForTimeout(1200)
    await shot(page, '09b-consumer-groups-queue-selected.png')
  })

  test('09c consumer groups - create group modal filled', async ({ page }) => {
    await page.goto('/consumer-groups')
    await expect(page.getByTestId('create-group-btn')).toBeVisible({ timeout: 10000 })
    await page.getByTestId('create-group-btn').click()
    await expect(page.locator('.ant-modal')).toBeVisible()
    // Fill the form with realistic data so the screenshot shows a non-empty modal
    await page.getByLabel('Group Name').fill('order-processors')
    await selectAntOption(page.locator('.ant-modal .ant-select').filter({ has: page.locator('[id="setupId"], [id^="rc_select"]') }).first(), SETUP_ID)
    await page.getByLabel('Queue Name').fill(queueName)
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(DIR, '09c-consumer-groups-create-modal.png') })
    await page.keyboard.press('Escape')
  })

  test('10 message browser', async ({ page }) => {
    await page.goto('/messages')
    await page.waitForTimeout(1200)
    await shot(page, '10-message-browser.png')
  })

  test('10b message browser - queue selected with messages', async ({ page }) => {
    await page.goto('/messages')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)
    await page.waitForTimeout(600)
    await selectAntOption(page.getByTestId('queue-scope-selector'), queueName)
    await page.waitForTimeout(1500)
    await shot(page, '10b-message-browser-queue-selected.png')
  })

  test('10c message browser - filters drawer open', async ({ page }) => {
    await page.goto('/messages')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await page.getByRole('button', { name: /filters/i }).click()
    await expect(page.locator('.ant-drawer-open')).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(DIR, '10c-message-browser-filters-drawer.png') })
    await page.keyboard.press('Escape')
  })

  // Helper: navigate to /messages with setup + queue selected and wait for message rows.
  // Extracted inline because Playwright test helpers cannot be standalone async functions
  // that reference `page` — each test calls this pattern directly.

  test('10d message browser - messages table with data', async ({ page }) => {
    await page.goto('/messages')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)
    await page.waitForTimeout(600)
    await selectAntOption(page.getByTestId('queue-scope-selector'), queueName)
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 15000 })
    await page.waitForTimeout(600)
    await shot(page, '10d-message-browser-with-messages.png')
  })

  test('10e message browser - control bar (Live toggle, Filters, Clear, Refresh, badge)', async ({ page }) => {
    await page.goto('/messages')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)
    await page.waitForTimeout(600)
    await selectAntOption(page.getByTestId('queue-scope-selector'), queueName)
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 15000 })
    // Capture just the controls card (Message Browser heading + all buttons)
    await page.locator('.ant-card').filter({ has: page.getByTestId('live-switch') })
      .screenshot({ path: path.join(DIR, '10e-message-browser-controls-bar.png') })
  })

  test('10f message browser - live mode active banner', async ({ page }) => {
    await page.goto('/messages')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await page.locator('[data-testid="live-switch"]').click()
    await expect(page.locator('[data-testid="live-alert"]')).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(600)
    await shot(page, '10f-message-browser-live-mode.png')
    await page.locator('[data-testid="live-switch"]').click()
  })

  test('10g message browser - quick filters card', async ({ page }) => {
    await page.goto('/messages')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)
    await page.waitForTimeout(600)
    await selectAntOption(page.getByTestId('queue-scope-selector'), queueName)
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 15000 })
    // Capture the Quick Filters card showing Message Status dropdown + Search input
    const quickFiltersCard = page.getByTestId('quick-filters-card')
    await expect(quickFiltersCard).toBeVisible({ timeout: 5000 })
    await quickFiltersCard.screenshot({ path: path.join(DIR, '10g-message-browser-quick-filters.png') })
  })

  test('10h message browser - status dropdown open showing all options', async ({ page }) => {
    await page.goto('/messages')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)
    await page.waitForTimeout(600)
    await selectAntOption(page.getByTestId('queue-scope-selector'), queueName)
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 15000 })
    // Open the Status dropdown without selecting — captures all 4 options visible
    await page.getByTestId('status-filter-select').click()
    await expect(page.locator('.ant-select-dropdown').last()).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(300)
    await page.screenshot({ path: path.join(DIR, '10h-message-browser-status-dropdown.png') })
    await page.keyboard.press('Escape')
  })

  test('10i message browser - status filter applied with filtered results', async ({ page }) => {
    await page.goto('/messages')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)
    await page.waitForTimeout(600)
    await selectAntOption(page.getByTestId('queue-scope-selector'), queueName)
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 15000 })
    const statusSelect = page.getByTestId('status-filter-select')
    await selectAntOption(statusSelect, 'Pending')
    await page.waitForTimeout(400)
    await shot(page, '10i-message-browser-status-filter.png')
  })

  test('10j message browser - search text filters the table', async ({ page }) => {
    await page.goto('/messages')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)
    await page.waitForTimeout(600)
    await selectAntOption(page.getByTestId('queue-scope-selector'), queueName)
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 15000 })
    // Search for "Alice" — matches the first seeded message payload
    await page.getByPlaceholder('Search messages...').fill('Alice')
    await page.waitForTimeout(400)
    await shot(page, '10j-message-browser-search-results.png')
  })

  test('10k message browser - status and search combined', async ({ page }) => {
    await page.goto('/messages')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)
    await page.waitForTimeout(600)
    await selectAntOption(page.getByTestId('queue-scope-selector'), queueName)
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 15000 })
    await selectAntOption(page.getByTestId('status-filter-select'), 'Pending')
    await page.getByPlaceholder('Search messages...').fill('ORD')
    await page.waitForTimeout(400)
    await shot(page, '10k-message-browser-combined-filters.png')
  })

  test('10l message browser - clear filters restores all messages', async ({ page }) => {
    await page.goto('/messages')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)
    await page.waitForTimeout(600)
    await selectAntOption(page.getByTestId('queue-scope-selector'), queueName)
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 15000 })
    // Apply filters then clear — captures the page after clearing with full count restored
    await selectAntOption(page.getByTestId('status-filter-select'), 'Pending')
    await page.getByPlaceholder('Search messages...').fill('Alice')
    await page.waitForTimeout(400)
    await page.getByTestId('clear-filters-btn').click()
    await page.waitForTimeout(400)
    await shot(page, '10l-message-browser-cleared-filters.png')
  })

  test('10m message browser - message details modal', async ({ page }) => {
    await page.goto('/messages')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)
    await page.waitForTimeout(600)
    await selectAntOption(page.getByTestId('queue-scope-selector'), queueName)
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 15000 })
    await page.waitForTimeout(400)
    await page.locator('.ant-table-tbody tr.ant-table-row').first()
      .locator('[data-testid="view-message-btn"]').click()
    await expect(page.locator('.ant-modal')).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(600)
    await page.screenshot({ path: path.join(DIR, '10m-message-browser-message-detail.png') })
    await page.keyboard.press('Escape')
  })

  test('10n message browser - message detail modal payload section', async ({ page }) => {
    await page.goto('/messages')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)
    await page.waitForTimeout(600)
    await selectAntOption(page.getByTestId('queue-scope-selector'), queueName)
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 15000 })
    await page.locator('.ant-table-tbody tr.ant-table-row').first()
      .locator('[data-testid="view-message-btn"]').click()
    await expect(page.locator('.ant-modal')).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(400)
    // Capture just the Payload card inside the modal
    const payloadCard = page.locator('.ant-modal-body .ant-card').filter({
      has: page.locator('.ant-card-head-title', { hasText: 'Payload' })
    })
    await payloadCard.screenshot({ path: path.join(DIR, '10n-message-browser-payload-card.png') })
    await page.keyboard.press('Escape')
  })

  test('10o message browser - filters drawer empty', async ({ page }) => {
    await page.goto('/messages')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await page.getByRole('button', { name: /filters/i }).click()
    await expect(page.locator('.ant-drawer-open')).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(DIR, '10o-message-browser-filters-drawer.png') })
    await page.keyboard.press('Escape')
  })

  test('10p message browser - filters drawer with all fields filled', async ({ page }) => {
    await page.goto('/messages')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await page.getByRole('button', { name: /filters/i }).click()
    await expect(page.locator('.ant-drawer-open')).toBeVisible({ timeout: 5000 })
    const drawer = page.locator('.ant-drawer-open')
    await drawer.getByPlaceholder('Message Type').fill('OrderMessage')
    await selectAntOption(drawer.getByTestId('drawer-status-filter-select'), 'Pending')
    await drawer.getByPlaceholder(/search in message payload/i).fill('orderId')
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(DIR, '10p-message-browser-filters-drawer-filled.png') })
    await page.keyboard.press('Escape')
  })

  test('10q message browser - filters drawer time range picker', async ({ page }) => {
    await page.goto('/messages')
    await page.locator('[data-testid="app-sidebar"]').waitFor({ state: 'visible' })
    await page.getByRole('button', { name: /filters/i }).click()
    await expect(page.locator('.ant-drawer-open')).toBeVisible({ timeout: 5000 })
    // Click the RangePicker start input to open the calendar panel
    await page.locator('.ant-drawer-open .ant-picker-range').locator('input').first().click()
    await expect(page.locator('.ant-picker-dropdown').filter({ hasNot: page.locator('.ant-picker-dropdown-hidden') })).toBeVisible({ timeout: 5000 })
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(DIR, '10q-message-browser-time-range-picker.png') })
    await page.keyboard.press('Escape')
    await page.keyboard.press('Escape')
  })

  // ── 7. Event Visualization ────────────────────────────────────────────────

  test('11 causation tree - empty state', async ({ page }) => {
    await page.goto('/causation-tree')
    await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Select Event Store' })).toBeVisible()
    await shot(page, '11-causation-tree-empty.png')
  })

  test('11b causation tree - store selected', async ({ page }) => {
    await page.goto('/causation-tree')
    await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Select Event Store' })).toBeVisible()
    await selectAntOption(page.getByTestId('causation-setup-select'), SETUP_ID)
    await selectAntOption(page.getByTestId('causation-eventstore-select'), eventStoreName)
    await expect(page.locator('.ant-card-head-title').filter({ hasText: /causation tree/i })).toBeVisible({ timeout: 10000 })
    await shot(page, '11b-causation-tree-store-selected.png')
  })

  test('12 causation tree - traced', async ({ page }) => {
    await page.goto('/causation-tree')
    await selectAntOption(page.getByTestId('causation-setup-select'), SETUP_ID)
    await selectAntOption(page.getByTestId('causation-eventstore-select'), eventStoreName)
    await expect(page.locator('.ant-card-head-title').filter({ hasText: /causation tree/i })).toBeVisible({ timeout: 10000 })
    await page.getByPlaceholder(/enter correlation id/i).fill(correlationId)
    await page.getByRole('button', { name: /trace/i }).click()
    // Wait for an actual visible tree node (aria-hidden helper nodes exist in the DOM
    // but are not visible — use ant-tree-node-content-wrapper to target real nodes)
    await expect(page.locator('.ant-tree-node-content-wrapper').first()).toBeVisible({ timeout: 20000 })
    await page.waitForTimeout(1000)
    await shot(page, '12-causation-tree-traced.png')
  })

  test('12b aggregate stream - with data', async ({ page }) => {
    await page.goto('/aggregate-stream')
    await selectAntOption(page.getByTestId('aggregate-setup-select'), SETUP_ID)
    await selectAntOption(page.getByTestId('aggregate-eventstore-select'), eventStoreName)
    await expect(page.locator('.ant-card-head-title').filter({ hasText: /aggregate stream/i })).toBeVisible({ timeout: 10000 })
    await page.getByRole('button', { name: /load aggregates/i }).click()
    const aggRow = page.locator('tr').filter({ hasText: aggregateId }).first()
    await expect(aggRow).toBeVisible({ timeout: 15000 })
    await aggRow.getByText('View Stream').click()
    await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 15000 })
    await page.waitForTimeout(800)
    await shot(page, '12b-aggregate-stream.png')
  })
})
