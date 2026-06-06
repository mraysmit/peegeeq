import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Causation Tree and Aggregate Stream pages - setup selector tests.
 *
 * Each page has its own independent inline setup + event-store selectors.
 * Prerequisite: database-setup.spec.ts runs first and creates SETUP_ID.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Causation Tree Page - Setup Selector', () => {

    test.beforeEach(async ({ page }) => {
        page.on('console', msg => {
            if (msg.type() === 'error') console.error('❌ Browser console error:', msg.text())
        })
        page.on('pageerror', error => {
            console.error('❌ Page error:', error.message)
        })
    })

    test('should display setup selector on the Causation Tree page', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-causation-tree').click()
        await page.waitForLoadState('networkidle')

        await expect(page.getByTestId('causation-setup-select')).toBeVisible()
        await expect(page.getByTestId('causation-eventstore-select')).toBeVisible()
    })

    test('should allow selecting a setup on the Causation Tree page', async ({ page }) => {
        await page.goto('/causation-tree')
        await page.waitForLoadState('networkidle')

        const setupSelect = page.getByTestId('causation-setup-select')
        await selectAntOption(setupSelect, SETUP_ID)
        await expect(setupSelect.locator('.ant-select-selection-item')).toContainText(SETUP_ID)
    })
})

test.describe('Aggregate Stream Page - Setup Selector', () => {

    test.beforeEach(async ({ page }) => {
        page.on('console', msg => {
            if (msg.type() === 'error') console.error('❌ Browser console error:', msg.text())
        })
        page.on('pageerror', error => {
            console.error('❌ Page error:', error.message)
        })
    })

    test('should display setup selector on the Aggregate Stream page', async ({ page }) => {
        await page.goto('/')
        await page.getByTestId('nav-aggregate-stream').click()
        await page.waitForLoadState('networkidle')

        await expect(page.getByTestId('aggregate-setup-select')).toBeVisible()
        await expect(page.getByTestId('aggregate-eventstore-select')).toBeVisible()
    })

    test('should allow selecting a setup on the Aggregate Stream page', async ({ page }) => {
        await page.goto('/aggregate-stream')
        await page.waitForLoadState('networkidle')

        const setupSelect = page.getByTestId('aggregate-setup-select')
        await selectAntOption(setupSelect, SETUP_ID)
        await expect(setupSelect.locator('.ant-select-selection-item')).toContainText(SETUP_ID)
    })
})
