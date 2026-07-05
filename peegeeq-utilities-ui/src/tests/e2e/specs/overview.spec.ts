import { test, expect } from '../page-objects'

/**
 * Overview Page Tests
 *
 * The Overview (/) page is a per-setup browser: a selectable list of setups at
 * the top and, for the selected setup, its queues (with message stats and
 * consumer groups) and event stores beneath. There are no global aggregates,
 * statistic cards, or charts.
 *
 * These tests run against the live e2e backend (no HTTP stubbing). They assert
 * the structural elements that are present regardless of how many setups exist,
 * so they are robust to the backend's data state at run time.
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

    test('should display the Setups card with Refresh and Create Setup actions', async ({ page, overviewPage }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Setups' })).toBeVisible()
      await expect(overviewPage.getRefreshButton()).toBeVisible()
      await expect(overviewPage.getCreateSetupButton()).toBeVisible()
    })

    test('should show either the setups list or the empty-state alert', async ({ page, overviewPage }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      // Exactly one of the two is present depending on whether any setup exists.
      const list = overviewPage.getSetupsList()
      const empty = overviewPage.getNoSetupsAlert()
      await expect(list.or(empty)).toBeVisible({ timeout: 15000 })
    })

  })

  test.describe('Interactions', () => {

    test('should respond to the Refresh button click without error', async ({ page, overviewPage }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      await overviewPage.getRefreshButton().click()

      await expect(overviewPage.getHeading()).toBeVisible()
    })

    test('Create Setup navigates to the create-setup form', async ({ page, overviewPage }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      await overviewPage.getCreateSetupButton().click()

      await expect(page.getByTestId('create-setup-page')).toBeVisible()
    })

  })

})
