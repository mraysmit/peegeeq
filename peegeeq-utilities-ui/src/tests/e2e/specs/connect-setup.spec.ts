import { test, expect } from '../page-objects'
import * as fs from 'fs'

/**
 * Connect-to-existing-setup E2E — real backend, no mocks (mirrors peegeeq-management-ui's
 * database-setup.spec.ts style: drive the real UI, provision/verify via the real REST API).
 *
 * utilities-ui is connect-only: it never provisions. So this spec provisions a setup out-of-band
 * via the admin create endpoint, then DETACHES it (non-destructive DELETE — the database + registry
 * persist, the in-memory binding is dropped), so the UI can CONNECT to it fresh. It then verifies
 * the reconnected setup against the real GET /api/v1/setups.
 */

const API_BASE_URL = 'http://127.0.0.1:8088'
const SCHEMA = 'public'

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

test.describe('Connect to existing setup', () => {
  const stamp = Date.now()
  const CONNECT_SETUP_ID = `e2e-connect-${stamp}`
  const CONNECT_DB_NAME = `e2e_connect_db_${stamp}`
  let db: DbConnectionInfo

  test.beforeAll(async ({ request }) => {
    db = readDbConfig()
    // Clean any leftover binding from a prior run (non-destructive; ignore result).
    await request.delete(`${API_BASE_URL}/api/v1/setups/${CONNECT_SETUP_ID}`)

    // 1. Provision the setup out-of-band (admin create) so a real DB + schema + registry exist.
    const create = await request.post(`${API_BASE_URL}/api/v1/database-setup/create`, {
      data: {
        setupId: CONNECT_SETUP_ID,
        databaseConfig: {
          host: db.host,
          port: db.port,
          databaseName: CONNECT_DB_NAME,
          username: db.username,
          password: db.password,
          schema: SCHEMA,
          templateDatabase: 'template0',
          encoding: 'UTF8',
        },
        queues: [{ queueName: 'connectq', maxRetries: 3, visibilityTimeout: 30 }],
        eventStores: [],
      },
      timeout: 120000,
    })
    if (!create.ok()) {
      throw new Error(`Provision (create) failed: ${create.status()} ${await create.text()}`)
    }

    // 2. Detach (non-destructive): drop the in-memory binding so the backend no longer holds it
    //    active — the database + self-describing registry persist, so the UI can connect fresh.
    const detach = await request.delete(`${API_BASE_URL}/api/v1/setups/${CONNECT_SETUP_ID}`)
    expect(detach.ok()).toBeTruthy()

    // Confirm it is gone from the active list before the UI connects.
    await expect.poll(async () => {
      const resp = await request.get(`${API_BASE_URL}/api/v1/setups`)
      const body = await resp.json()
      return (body.setupIds ?? []).includes(CONNECT_SETUP_ID)
    }, { timeout: 15000 }).toBe(false)
  })

  test.afterAll(async ({ request }) => {
    await request.delete(`${API_BASE_URL}/api/v1/setups/${CONNECT_SETUP_ID}`)
  })

  test.beforeEach(async ({ page }) => {
    page.on('pageerror', error => console.error('Page error:', error.message))
  })

  test('connects to a pre-provisioned setup through the UI and it becomes active', async ({ page }) => {
    test.setTimeout(120000)

    await page.goto('/setups')
    await page.getByTestId('connect-setup-button').click()
    await page.waitForURL('**/setups/connect')
    await expect(page.getByTestId('connect-setup-page')).toBeVisible()

    // Fill the connect form with the REAL coordinates of the pre-provisioned setup.
    await page.getByLabel('Setup ID').fill(CONNECT_SETUP_ID)
    await page.getByLabel('Database name').fill(CONNECT_DB_NAME)
    await page.getByLabel('Database password').fill(db.password)

    // Expand connection details to set the real testcontainers host/port.
    await page.getByText('Connection details').click()
    await page.getByLabel(/^Host$/).fill(db.host)
    await page.getByLabel(/^Port$/).fill(String(db.port))

    await page.getByTestId('connect-button').click()

    // Redirects to /setups and the connected setup appears in the table.
    await page.waitForURL('**/setups', { timeout: 60000 })
    await expect(page.locator(`tr:has-text("${CONNECT_SETUP_ID}")`)).toBeVisible({ timeout: 30000 })

    // Verify against the REAL backend that the setup is active again.
    await expect.poll(async () => {
      const resp = await page.request.get(`${API_BASE_URL}/api/v1/setups`)
      const body = await resp.json()
      return (body.setupIds ?? []).includes(CONNECT_SETUP_ID)
    }, { timeout: 15000 }).toBe(true)
  })

  test('connecting against an absent schema shows an error and stays on the form', async ({ page }) => {
    // The component surfaces the backend error via an inline alert; the console error listener
    // would otherwise pollute output.
    await page.removeAllListeners('console')

    await page.goto('/setups/connect')
    await expect(page.getByTestId('connect-setup-page')).toBeVisible()

    await page.getByLabel('Setup ID').fill('does-not-exist')
    await page.getByLabel('Database name').fill(CONNECT_DB_NAME)
    await page.getByLabel('Database password').fill(db.password)

    await page.getByText('Connection details').click()
    await page.getByLabel(/^Host$/).fill(db.host)
    await page.getByLabel(/^Port$/).fill(String(db.port))
    // A schema with no PeeGeeQ registry — connect must refuse, non-destructively.
    await page.getByLabel(/^Schema$/).fill('no_peegeeq_schema')

    await page.getByTestId('connect-button').click()

    // Inline error alert appears and we stay on the connect form (no navigation to /setups).
    await expect(page.locator('.ant-alert-error')).toBeVisible({ timeout: 30000 })
    await expect(page.getByTestId('connect-setup-page')).toBeVisible()
  })

  test('detaches a connected setup through the UI and it leaves the active list', async ({ page }) => {
    // Serial: the first test left CONNECT_SETUP_ID connected. Detach it through the UI
    // (non-destructive Popconfirm) and verify against the real GET /api/v1/setups.
    await page.goto('/setups')

    const row = page.locator(`tr:has-text("${CONNECT_SETUP_ID}")`)
    await expect(row).toBeVisible({ timeout: 30000 })

    await page.getByTestId(`detach-setup-${CONNECT_SETUP_ID}`).click()

    // Detach is non-destructive — the Popconfirm OK is a plain primary button.
    const confirmBtn = page.locator('.ant-popconfirm .ant-btn-primary')
    await expect(confirmBtn).toBeVisible({ timeout: 5000 })
    await confirmBtn.click()

    // Success toast and the row disappears.
    await expect(page.locator('.ant-message-success')).toBeVisible({ timeout: 15000 })
    await expect(row).not.toBeVisible({ timeout: 15000 })

    // Verify against the REAL backend: no longer in the active list.
    await expect.poll(async () => {
      const resp = await page.request.get(`${API_BASE_URL}/api/v1/setups`)
      const body = await resp.json()
      return (body.setupIds ?? []).includes(CONNECT_SETUP_ID)
    }, { timeout: 15000 }).toBe(false)
  })
})
