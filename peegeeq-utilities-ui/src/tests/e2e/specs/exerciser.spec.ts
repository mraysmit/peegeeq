import { test, expect } from '../page-objects'
import * as fs from 'fs'

/**
 * Delay / Priority / FIFO exerciser mode E2E — real backend, no mocks (G.5).
 *
 * Provisions its own setup + queue, then drives exerciser mode end to end:
 * mode switch → ordering controls + rate controls in Zone B → a REAL run
 * publishing per-message delay/priority/group assignments → the derived
 * manifest with its download. Also pins the refusals only a selected target
 * can prove (the unit tests cannot): Schedule is blocked, an exerciser run
 * cannot be saved as a scenario, and a per-key group strategy with no value
 * list blocks Start with its reason.
 *
 * The run is deliberately tiny (5 msg/s × 2 s = 10 messages) so the whole
 * flow is a few seconds.
 */

const API_BASE_URL = 'http://127.0.0.1:8088'
const SCHEMA = 'public'
const QUEUE_NAME = 'exerciserq'

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

test.describe('Exerciser mode', () => {
  const stamp = Date.now()
  const SETUP_ID = `e2e-exerciser-${stamp}`
  const DB_NAME = `e2e_exerciser_db_${stamp}`

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
        queues: [{ queueName: QUEUE_NAME, maxRetries: 3, visibilityTimeout: 30 }],
        eventStores: [],
      },
      timeout: 120000,
    })
    if (!create.ok()) {
      throw new Error(`Provision (create) failed: ${create.status()} ${await create.text()}`)
    }
  })

  test.afterAll(async ({ request }) => {
    await request.delete(`${API_BASE_URL}/api/v1/setups/${SETUP_ID}`)
  })

  async function openExerciserMode(page: import('@playwright/test').Page): Promise<void> {
    await page.goto('/generator')
    await expect(page.locator('#target-setup-select')).toBeVisible({ timeout: 15000 })
    await page.locator('.ant-select:has(#target-setup-select)').click()
    await page.locator('.ant-select-dropdown').getByTitle(SETUP_ID).click()
    await expect(page.locator('#target-queue-select')).toBeVisible({ timeout: 15000 })
    await page
      .getByTestId('generator-mode')
      .getByText('Delay / Prio / FIFO', { exact: true })
      .click()
    await expect(page.getByTestId('exerciser-controls')).toBeVisible()
  }

  test('shows the ordering controls with the plan preview, and refuses what an exerciser cannot do', async ({
    page,
  }) => {
    test.setTimeout(120000)
    page.on('pageerror', (error) => console.error('Page error:', error.message))

    await openExerciserMode(page)

    // Zone B carries the ordering strategies AND the rate controls (§19.5).
    await expect(page.getByTestId('rate-controls')).toBeVisible()
    // The preview and the run derive from the same assignmentFor: the default
    // round-robin groups appear before anything runs.
    await expect(page.getByTestId('exerciser-plan-preview')).toContainText('grp-0')
    await expect(page.getByTestId('manifest-empty')).toBeVisible()

    // Both refusals, asserted WITH a target selected (the unit tests cannot):
    // schedules and scenarios have no exerciser kind.
    await expect(page.getByRole('button', { name: /Schedule/ })).toBeDisabled()
    await expect(page.getByTestId('scenario-save-as')).toBeDisabled()
  })

  test('a per-key group strategy with no value list blocks Start with its reason', async ({
    page,
  }) => {
    test.setTimeout(120000)
    page.on('pageerror', (error) => console.error('Page error:', error.message))

    await openExerciserMode(page)

    await page.getByLabel(/Per-key from value list/i).click()

    await expect(page.getByTestId('exerciser-per-key-warning')).toBeVisible()
    await expect(page.getByRole('button', { name: /^Start$/ })).toBeDisabled()
  })

  test('runs for real and derives the manifest of what was sent', async ({ page }) => {
    test.setTimeout(120000)
    page.on('pageerror', (error) => console.error('Page error:', error.message))

    await openExerciserMode(page)

    // Deterministic strategies so the manifest rows are exact: fixed 0 s
    // delay, round-robin priority, round-robin over 3 groups. The delay fill
    // is SCOPED: the TemplateEditor's delaySeconds field carries the same
    // "Delay (seconds)" label (it is the field these strategies override).
    await page
      .getByTestId('exerciser-controls')
      .getByLabel(/^Delay \(seconds\)$/i)
      .fill('0')
    await page.getByLabel(/Round-robin 1–10/i).click()
    await page.getByLabel(/Number of groups/i).fill('3')

    // 5 msg/s × 2 s = 10 messages.
    await page.getByLabel(/Rate \(msg\/s\)/i).fill('5')
    await page.getByLabel(/Duration \(seconds\)/i).fill('2')
    await page.getByLabel(/Message type/i).fill('e2e.exerciser')
    await page.getByLabel(/Payload/i).fill('{"id":"{{messageId}}"}')

    await page.getByRole('button', { name: /^Start$/ }).click()

    // The manifest replaces the empty state once the run settles.
    await expect(page.getByTestId('manifest-panel')).toBeVisible({ timeout: 60000 })
    await expect(page.getByTestId('manifest-header')).toContainText('10 messages')

    // Rows carry the deterministic assignments: id 1 → grp-0/p1/d0s, id 2 → grp-1/p2.
    await expect(page.getByTestId(/^manifest-row-/)).toHaveCount(10)
    await expect(page.getByTestId('manifest-row-1')).toContainText('grp-0')
    await expect(page.getByTestId('manifest-row-1')).toContainText('p1')
    await expect(page.getByTestId('manifest-row-1')).toContainText('d0s')
    await expect(page.getByTestId('manifest-row-2')).toContainText('grp-1')
    await expect(page.getByTestId('manifest-row-2')).toContainText('p2')

    // A clean run carries no delivery caveat, and the downstream pointer is there.
    await expect(page.getByTestId('manifest-errors-caveat')).toHaveCount(0)
    await expect(page.getByTestId('manifest-verify-note')).toContainText(/Message Browser/i)

    // The download is a REAL browser download carrying the full manifest name.
    const downloadPromise = page.waitForEvent('download')
    await page.getByTestId('manifest-download').click()
    const download = await downloadPromise
    expect(download.suggestedFilename()).toMatch(/^manifest-.+\.json$/)
  })
})
