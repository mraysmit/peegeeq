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

    test('should show empty-state alert when no setups are connected', async ({ page, generatorPage }) => {
      await generatorPage.goto()
      await expect(generatorPage.getEmptyStateAlert()).toBeVisible({ timeout: 15000 })
      await expect(generatorPage.getConnectSetupButton()).toBeVisible()
    })

    test('should show the Connect setup button that opens the connect page', async ({ page, generatorPage }) => {
      await generatorPage.goto()
      await expect(generatorPage.getConnectSetupButton()).toBeVisible({ timeout: 15000 })

      await generatorPage.getConnectSetupButton().click()
      await page.waitForURL('**/setups/connect')
      await expect(page.getByTestId('connect-setup-page')).toBeVisible()

      // Back — do not connect a setup in this spec (connect-setup.spec.ts covers connecting)
      await page.getByTestId('back-button').click()
    })

  })

  test.describe('Template Manager Page', () => {

    test('should display the Template Manager heading', async ({ page }) => {
      await page.goto('/generator/templates')
      await page.waitForLoadState('load')
      await expect(page.getByRole('heading', { name: /template manager/i })).toBeVisible()
    })

    test('should render the template table with New Template and Import actions', async ({ page }) => {
      await page.goto('/generator/templates')
      await page.waitForLoadState('load')
      await expect(page.getByTestId('template-manager-page')).toBeVisible()
      await expect(page.getByTestId('template-table')).toBeVisible()
      await expect(page.getByRole('button', { name: /New Template/i })).toBeVisible()
      await expect(page.getByRole('button', { name: /Import/i })).toBeVisible()
    })

    test('New Template opens the generator editor with a blank working copy', async ({ page }) => {
      await page.goto('/generator/templates')
      await page.waitForLoadState('load')
      await page.getByRole('button', { name: /New Template/i }).click()
      await page.waitForURL('**/generator')
      await expect(page.getByTestId('template-editor')).toBeVisible()
      await expect(page.getByLabel(/^Name$/i)).toHaveValue('Untitled')
    })

    test('a template saved in the generator appears in the manager and reopens from it', async ({ page }) => {
      // Real localStorage round-trip: save in the editor, list in the manager,
      // reopen via the Name link.
      await page.goto('/generator')
      await page.waitForLoadState('load')
      await page.getByLabel(/^Name$/i).fill('E2E managed template')
      await page.getByLabel(/Message type/i).fill('e2e.managed')
      await page.getByRole('button', { name: /^Save$/ }).click()

      await page.goto('/generator/templates')
      await expect(page.getByRole('link', { name: 'E2E managed template' })).toBeVisible()
      await page.getByRole('link', { name: 'E2E managed template' }).click()
      await page.waitForURL('**/generator')
      await expect(page.getByLabel(/^Name$/i)).toHaveValue('E2E managed template')
      await expect(page.getByLabel(/Message type/i)).toHaveValue('e2e.managed')
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

    test('should render the list table with New List and Import actions', async ({ page }) => {
      await page.goto('/generator/value-lists')
      await page.waitForLoadState('load')
      await expect(page.getByTestId('value-list-manager-page')).toBeVisible()
      await expect(page.getByTestId('value-list-table')).toBeVisible()
      await expect(page.getByRole('button', { name: /New List/i })).toBeVisible()
      await expect(page.getByRole('button', { name: /Import JSON file/i })).toBeVisible()
    })

    test('creates a list and resolves it in a generator preview (localStorage round-trip)', async ({ page }) => {
      // Real end-to-end within the browser: create the list here, then the
      // generator preview must resolve {{list:e2e_names}} from it.
      await page.goto('/generator/value-lists')
      await page.getByRole('button', { name: /New List/i }).click()
      await page.getByLabel(/List name/i).fill('e2e_names')
      await page.getByLabel(/Values \(one per line\)/i).fill('OnlyValue')
      await page.getByRole('button', { name: /^Save$/ }).click()
      await expect(page.getByTestId('list-count-e2e_names')).toContainText('1')

      await page.goto('/generator')
      await page.getByLabel(/Payload/i).fill('{"name":"{{list:e2e_names}}"}')
      await page.getByRole('button', { name: /^Preview$/ }).click()
      await expect(page.getByTestId('preview-modal')).toContainText('"name": "OnlyValue"')
      await page.locator('.ant-modal-footer').getByRole('button', { name: /^Close$/ }).click()

      // Clean up the list so other specs see no leftovers.
      await page.goto('/generator/value-lists')
      await page.getByTestId('list-delete-e2e_names').click()
      await page.locator('.ant-modal-confirm').getByRole('button', { name: /^Delete$/ }).click()
      await expect(page.getByTestId('list-count-e2e_names')).not.toBeVisible()
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
