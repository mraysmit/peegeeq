import { test, expect } from '../page-objects'

/**
 * Database Setups Page – Create Form Defaults and Port Range Validation
 *
 * Covers gaps:
 *   2. Port range validation: InputNumber has min=1 / max=65535; entering a value
 *      outside that range should be clamped to the boundary on blur.
 *   3. Form field default values (Host=localhost, Port=5432, Username=peegeeq)
 *      should be pre-filled when the modal opens; Schema has no default and is required.
 *
 * No backend calls needed — these are purely form-level assertions.
 */
test.describe('Database Setups – Form Defaults and Port Range', () => {

    test.beforeEach(async ({ page, databaseSetupsPage }) => {
        await page.goto('/')
        await databaseSetupsPage.goto()

        // Open the Create Setup modal
        await databaseSetupsPage.clickCreate()
        await expect(page.locator('.ant-modal')).toBeVisible()
    })

    test.afterEach(async ({ page }) => {
        const modal = page.locator('.ant-modal')
        if (await modal.isVisible()) {
            // Prefer clicking the modal's X button — more reliable than Escape
            // because AntD modal animations can exceed a short timeout
            const closeBtn = page.locator('.ant-modal-close')
            if (await closeBtn.isVisible({ timeout: 1000 }).catch(() => false)) {
                await closeBtn.click()
            } else {
                await page.keyboard.press('Escape')
            }
            await expect(modal).not.toBeVisible({ timeout: 10000 })
        }
    })

    // ── Default values ─────────────────────────────────────────────────────────

    test('Host field defaults to "localhost"', async ({ page }) => {
        const hostInput = page.locator('.ant-modal').getByLabel('Host')
        await expect(hostInput).toHaveValue('localhost')
    })

    test('Port field defaults to 5432', async ({ page }) => {
        // Ant Design InputNumber renders its input with the form item name as id suffix
        const portInput = page.locator('.ant-modal').locator('input[id$="port"]')
        await expect(portInput).toHaveValue('5432')
    })

    test('Username field defaults to "peegeeq"', async ({ page }) => {
        const usernameInput = page.locator('.ant-modal').getByLabel('Username')
        await expect(usernameInput).toHaveValue('peegeeq')
    })

    test('Schema field has no default', async ({ page }) => {
        const schemaInput = page.locator('.ant-modal').getByLabel('Schema')
        await expect(schemaInput).toHaveValue('')
    })

    // ── Port range clamping ────────────────────────────────────────────────────

    test('Port value above 65535 is clamped to 65535 on blur', async ({ page }) => {
        const portInput = page.locator('.ant-modal').locator('input[id$="port"]')

        await portInput.fill('99999')
        await portInput.blur()

        // Ant Design InputNumber enforces min/max by clamping on blur
        await expect(portInput).toHaveValue('65535', { timeout: 3000 })
    })

    test('Port value of 0 is clamped to 1 on blur', async ({ page }) => {
        const portInput = page.locator('.ant-modal').locator('input[id$="port"]')

        await portInput.fill('0')
        await portInput.blur()

        await expect(portInput).toHaveValue('1', { timeout: 3000 })
    })

    test('Port value within 1–65535 is accepted unchanged', async ({ page }) => {
        const portInput = page.locator('.ant-modal').locator('input[id$="port"]')

        await portInput.fill('5433')
        await portInput.blur()

        await expect(portInput).toHaveValue('5433', { timeout: 3000 })
    })
})
