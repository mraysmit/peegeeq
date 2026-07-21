import { test, expect } from '../page-objects'

/**
 * Navigation Tests
 *
 * Verifies that the application shell renders and all sidebar
 * navigation links route to their correct pages.
 *
 * These tests are pure UI / routing tests — they do not require
 * the backend to be running.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Navigation', () => {

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

  test.describe('App Shell', () => {

    test('should render the application layout', async ({ page, basePage }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      expect(await basePage.isLayoutVisible()).toBe(true)
    })

    test('should show the PeeGeeQ Utilities brand name in the sidebar', async ({ page, basePage }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      await expect(basePage.getLogo()).toContainText('PeeGeeQ Utilities')
    })

    test('should show all expected nav links in the sidebar', async ({ page }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      await expect(page.getByTestId('nav-overview')).toBeVisible()
      await expect(page.getByTestId('nav-setups')).toBeVisible()
      await expect(page.getByTestId('nav-generator')).toBeVisible()
      await expect(page.getByTestId('nav-generator-templates')).toBeVisible()
      await expect(page.getByTestId('nav-generator-value-lists')).toBeVisible()
    })

  })

  test.describe('Routes', () => {

    test('should navigate to Overview page', async ({ page, basePage }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      await basePage.navigateTo('overview')

      expect(page.url()).toMatch(/\/$|\/overview/)
      await expect(page.getByRole('heading', { name: /system overview/i })).toBeVisible()
    })

    test('should navigate to Message Generator page', async ({ page, basePage }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      await basePage.navigateTo('generator')

      expect(page.url()).toContain('/generator')
      await expect(page.getByRole('heading', { name: /queue message generator/i })).toBeVisible()
    })

    test('should navigate to Setups page', async ({ page, basePage }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      await basePage.navigateTo('setups')

      expect(page.url()).toContain('/setups')
      await expect(page.getByRole('heading', { name: /^Setups$/i })).toBeVisible()
    })

    test('should navigate to Templates page', async ({ page, basePage }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      await basePage.navigateTo('generator-templates')

      expect(page.url()).toContain('/generator/templates')
      await expect(page.getByRole('heading', { name: /template manager/i })).toBeVisible()
    })

    test('should navigate to Value Lists page', async ({ page, basePage }) => {
      await page.goto('/')
      await page.waitForLoadState('load')

      await basePage.navigateTo('generator-value-lists')

      expect(page.url()).toContain('/generator/value-lists')
      await expect(page.getByRole('heading', { name: /value list manager/i })).toBeVisible()
    })

    test('should highlight the active nav item for the current route', async ({ page }) => {
      await page.goto('/generator')
      await page.waitForLoadState('load')

      // Flat menu (2026-07-21): every page is a plain top-level item, so the
      // active route's li carries ant-menu-item-selected.
      const activeItem = page.locator('.ant-menu-item-selected')
      await expect(activeItem).toContainText('Message Generator')
    })

  })

})
