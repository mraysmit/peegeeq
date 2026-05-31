import { test, expect } from '../page-objects'

/**
 * Generator / Templates / Value Lists / Tools Page Tests
 *
 * Message Generator tests verify the TargetSelector component renders
 * correctly when no setups exist (the typical state in a fresh test
 * environment). The full wizard + normal-state flow is covered in
 * quick-setup.spec.ts which runs after this spec.
 *
 * Template Manager / Value List Manager / Tools pages are still stubs —
 * their "Coming soon" text is verified here.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Generator Pages', () => {

  test.beforeEach(async ({ page }) => {
    page.on('console', msg => {
      if (msg.type() === 'error') {
        console.error('Browser console error:', msg.text())
      }
    })
    page.on('pageerror', error => {
      console.error('Page error:', error.message)
    })
  })

  test.describe('Message Generator Page', () => {

    test('should display the Queue Message Generator heading', async ({ page, generatorPage }) => {
      await generatorPage.goto()
      await expect(generatorPage.getHeading()).toBeVisible()
    })

    test('should highlight Message Generator as the active nav item', async ({ page }) => {
      await page.goto('/generator')
      await page.waitForLoadState('load')

      const activeItem = page.locator('.ant-menu-item-selected')
      await expect(activeItem).toContainText('Message Generator')
    })

    test('should render zone-a containing the TargetSelector', async ({ page }) => {
      await page.goto('/generator')
      await page.waitForLoadState('load')

      await expect(page.getByTestId('zone-a')).toBeVisible()
    })

    test('should show empty-state alert when no setups are configured', async ({ page, generatorPage }) => {
      await generatorPage.goto()
      await expect(generatorPage.getEmptyStateAlert()).toBeVisible({ timeout: 15000 })
      await expect(generatorPage.getCreateSetupButton()).toBeVisible()
    })

    test('should show the Create Setup button that opens the create-setup page', async ({ page, generatorPage }) => {
      await generatorPage.goto()
      await expect(generatorPage.getCreateSetupButton()).toBeVisible({ timeout: 15000 })

      await generatorPage.getCreateSetupButton().click()
      await page.waitForURL('**/generator/setup/new')
      await expect(generatorPage.getCreateSetupPageHeading()).toBeVisible()

      // Cancel — do not create a setup in this spec (quick-setup.spec.ts handles that)
      await generatorPage.getWizardCancelButton().click()
      await page.waitForURL('**/generator')
    })

  })

  test.describe('Template Manager Page', () => {

    test('should display the Template Manager heading', async ({ page }) => {
      await page.goto('/generator/templates')
      await page.waitForLoadState('load')
      await expect(page.getByRole('heading', { name: /template manager/i })).toBeVisible()
    })

    test('should display "Coming soon" placeholder text', async ({ page }) => {
      await page.goto('/generator/templates')
      await page.waitForLoadState('load')
      await expect(page.getByText(/coming soon/i)).toBeVisible()
    })

    test('should highlight Templates as the active nav item', async ({ page }) => {
      await page.goto('/generator/templates')
      await page.waitForLoadState('load')
      const activeItem = page.locator('.ant-menu-item-selected')
      await expect(activeItem).toContainText('Templates')
    })

  })

  test.describe('Value List Manager Page', () => {

    test('should display the Value List Manager heading', async ({ page }) => {
      await page.goto('/generator/value-lists')
      await page.waitForLoadState('load')
      await expect(page.getByRole('heading', { name: /value list manager/i })).toBeVisible()
    })

    test('should display "Coming soon" placeholder text', async ({ page }) => {
      await page.goto('/generator/value-lists')
      await page.waitForLoadState('load')
      await expect(page.getByText(/coming soon/i)).toBeVisible()
    })

    test('should highlight Value Lists as the active nav item', async ({ page }) => {
      await page.goto('/generator/value-lists')
      await page.waitForLoadState('load')
      const activeItem = page.locator('.ant-menu-item-selected')
      await expect(activeItem).toContainText('Value Lists')
    })

  })

  test.describe('Tools Page', () => {

    test('should navigate to the Tools page via the sidebar', async ({ page, basePage }) => {
      await page.goto('/')
      await page.waitForLoadState('load')
      await basePage.navigateTo('tools')
      expect(page.url()).toContain('/tools')
    })

    test('should render the System Overview heading on the Tools page', async ({ page }) => {
      await page.goto('/tools')
      await page.waitForLoadState('load')
      await expect(page.getByRole('heading', { name: /system overview/i })).toBeVisible()
    })

  })

})
