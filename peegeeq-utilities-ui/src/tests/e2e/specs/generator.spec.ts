import { test, expect } from '../page-objects'
import * as fs from 'fs'

/**
 * Generator / Templates / Value Lists / Tools Page Tests
 *
 * Message Generator tests verify the TargetSelector component renders
 * correctly when no setups exist (the typical state in a fresh test
 * environment). The full wizard + normal-state flow is covered in
 * quick-setup.spec.ts which runs after this spec.
 *
 * The Template Manager, Value List Manager and Tools pages are all built; this
 * spec checks their navigation and landing state. (The stale "still stubs"
 * note here predated Phases C, D and G.4.)
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

    test('should display the Queue Message Generator heading', async ({ generatorPage }) => {
      await generatorPage.goto()
      await expect(generatorPage.getHeading()).toBeVisible()
    })

    test('should highlight Message Generator as the active nav item', async ({ page }) => {
      await page.goto('/generator')
      await page.waitForLoadState('load')

      // Flat menu (2026-07-21): every page is a plain top-level item.
      const activeItem = page.locator('.ant-menu-item-selected')
      await expect(activeItem).toContainText('Message Generator')
    })

    test('should render zone-a containing the TargetSelector', async ({ page }) => {
      await page.goto('/generator')
      await page.waitForLoadState('load')

      await expect(page.getByTestId('zone-a')).toBeVisible()
    })

    test('should show empty-state alert when no setups are connected', async ({ generatorPage }) => {
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

    test('Export produces a real, complete download (revokeObjectURL timing)', async ({ page }) => {
      // Every export helper in this app does:
      //     anchor.click(); URL.revokeObjectURL(url)
      // revoking SYNCHRONOUSLY on the line after the click. Whether the browser
      // has already taken ownership of the blob by then is not something that
      // can be settled by reading the code — revoking too early is a documented
      // way to truncate or abort a download. This test settles it by observing
      // a real download in real Chromium: the event must fire, the download must
      // not report a failure, and the SAVED BYTES must match what was exported.
      //
      // Asserting only that the download event fired would not be enough — the
      // event can fire for a transfer that then fails. download.path() resolves
      // only after the download completes, and failure() exposes the abort.
      await page.goto('/generator/value-lists')
      await page.getByRole('button', { name: /New List/i }).click()
      await page.getByLabel(/List name/i).fill('e2e_download')
      await page.getByLabel(/Values \(one per line\)/i).fill('Alpha\nBeta\nGamma')
      await page.getByRole('button', { name: /^Save$/ }).click()
      await expect(page.getByTestId('list-count-e2e_download')).toContainText('3')

      const downloadPromise = page.waitForEvent('download')
      await page.getByTestId('list-export-e2e_download').click()
      const download = await downloadPromise

      expect(download.suggestedFilename()).toBe('e2e_download.json')
      expect(await download.failure(), 'the download must not be aborted').toBeNull()

      const savedPath = await download.path()
      expect(savedPath, 'a completed download must have a path on disk').toBeTruthy()
      const contents = fs.readFileSync(savedPath!, 'utf8')
      expect(JSON.parse(contents)).toEqual(['Alpha', 'Beta', 'Gamma'])

      // Clean up so other specs see no leftovers (mirrors the round-trip test).
      await page.getByTestId('list-delete-e2e_download').click()
      await page.locator('.ant-modal-confirm').getByRole('button', { name: /^Delete$/ }).click()
      await expect(page.getByTestId('list-count-e2e_download')).not.toBeVisible()
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

    // The route rendered a second copy of Overview until G.4; it is now the
    // generation-tool suite launcher, whose first panel is the scenario manager.
    test('should render the Generation Tools page with the scenarios panel', async ({ page }) => {
      await page.goto('/tools')
      await page.waitForLoadState('load')
      await expect(page.getByRole('heading', { name: /generation tools/i })).toBeVisible()
      await expect(page.getByTestId('tools-page')).toBeVisible()
      // No scenarios have been saved in this project's fixtures.
      await expect(page.getByTestId('scenarios-empty')).toBeVisible()
    })

  })

})
