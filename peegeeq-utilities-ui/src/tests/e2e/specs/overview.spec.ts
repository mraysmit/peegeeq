import { test, expect } from '../page-objects'

/**
 * Overview Page Tests
 *
 * Verifies that the Overview (/) page renders its key sections:
 * system status alert, metric cards, throughput charts, and the
 * queue table.
 *
 * The page makes API calls to the backend; if the backend is
 * offline the statistics show zeros but all structural elements
 * still render.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Overview', () => {

  test.beforeEach(async ({ page }) => {
    page.on('console', msg => {
      if (msg.type() === 'error') {
        console.error('❌ Browser console error:', msg.text())
      }
    })
    page.on('pageerror', error => {
      console.error('❌ Page error:', error.message)
    })
  })

  test.describe('Page Structure', () => {

    test('should display the System Overview heading', async ({ page, overviewPage }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      await expect(overviewPage.getHeading()).toBeVisible()
    })

    test('should display the system status alert', async ({ page, overviewPage }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      // The status alert contains the system-status-info span
      await expect(overviewPage.getSystemStatusInfo()).toBeVisible()
    })

    test('should display the Refresh button', async ({ page, overviewPage }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      await expect(overviewPage.getRefreshButton()).toBeVisible()
    })

    test('should display metric statistic cards', async ({ page }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      // Ant Design Statistic titles. Scope to .ant-statistic-title because the
      // system-status-info span also contains the substrings "queues" and
      // "consumer groups", which would otherwise collide under Playwright's
      // case-insensitive substring matching.
      await expect(page.locator('.ant-statistic-title').filter({ hasText: 'Setups' })).toBeVisible()
      await expect(page.locator('.ant-statistic-title').filter({ hasText: 'Total Queues' })).toBeVisible()
      await expect(page.locator('.ant-statistic-title').filter({ hasText: 'Consumer Groups' })).toBeVisible()
      await expect(page.locator('.ant-statistic-title').filter({ hasText: 'Messages/sec' })).toBeVisible()
    })

    test('should display the queue table', async ({ page, overviewPage }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      // Wait for any loading spinner to disappear
      await overviewPage.waitForSpinnerToHide()

      await expect(overviewPage.getQueueTable()).toBeVisible()
    })

    test('should display the queue table with correct column headers', async ({ page, overviewPage }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      await overviewPage.waitForSpinnerToHide()

      const table = overviewPage.getQueueTable()
      await expect(table).toBeVisible()

      // Column headers defined in Overview.tsx queueColumns
      await expect(table.getByText('Queue Name')).toBeVisible()
      await expect(table.getByText('Messages')).toBeVisible()
      await expect(table.getByText('Consumers')).toBeVisible()
      await expect(table.getByText('Status')).toBeVisible()
    })

  })

  test.describe('Interactions', () => {

    test('should respond to the Refresh button click without error', async ({ page, overviewPage }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      // Clicking Refresh triggers a data reload — the button enters loading state briefly.
      await overviewPage.getRefreshButton().click()

      // The page should still show the heading after refresh
      await expect(overviewPage.getHeading()).toBeVisible()
    })

  })

  test.describe('System Status Alert', () => {

    test('should show error alert when backend is unreachable', async ({ page, overviewPage }) => {
      // Block all management/overview API calls so fetchSystemData fails
      await page.route('**/management/overview', route => route.abort('failed'))

      await page.goto('/')
      await page.waitForLoadState('load')

      // Wait for the fetch to fail and the store to update
      await expect(overviewPage.getSystemStatusAlert()).toHaveClass(/ant-alert-error/, { timeout: 10000 })
      await expect(page.getByText('System Status: Backend unreachable')).toBeVisible()
    })

    test('should show success alert when backend responds', async ({ page, overviewPage }) => {
      // No route interception — backend is live during e2e tests
      await page.goto('/')
      await page.waitForLoadState('load')

      // Wait for the fetch to complete and store to update
      await expect(overviewPage.getSystemStatusAlert()).toHaveClass(/ant-alert-success/, { timeout: 15000 })
      await expect(page.getByText('System Status: All services operational')).toBeVisible()
    })

  })

  test.describe('Statistics', () => {

    test('should display the Messages/sec stat card', async ({ page }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      await expect(page.locator('.ant-statistic-title').filter({ hasText: 'Messages/sec' })).toBeVisible()
    })

    test('should display all four statistic cards together', async ({ page }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      await expect(page.locator('.ant-statistic-title').filter({ hasText: 'Setups' })).toBeVisible()
      await expect(page.locator('.ant-statistic-title').filter({ hasText: 'Total Queues' })).toBeVisible()
      await expect(page.locator('.ant-statistic-title').filter({ hasText: 'Consumer Groups' })).toBeVisible()
      await expect(page.locator('.ant-statistic-title').filter({ hasText: 'Messages/sec' })).toBeVisible()
    })

  })

  test.describe('Charts', () => {

    test('should display the Message Throughput chart card', async ({ page }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Message Throughput (live)' })).toBeVisible()
    })

    test('should display the Active Connections chart card', async ({ page }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Active Connections' })).toBeVisible()
    })

    test('should display the Queue Overview card title', async ({ page }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Queue Overview' })).toBeVisible()
    })

  })

})
