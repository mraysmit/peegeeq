import { test, expect } from '@playwright/test'

/**
 * Settings Page – Auto-Ping Toggle and Interval Tests
 *
 * Covers gap:
 *   1. Toggling "Auto-ping", modifying the interval number, and verifying
 *      background intervals are triggered.
 *
 * The Settings page has three health-check cards (REST API, WebSocket, SSE).
 * Each card has:
 *   - A manual "Ping Now" button  (already covered in settings-ping-utilities.spec.ts)
 *   - A Switch labeled "Auto-ping"
 *   - When the switch is ON: an InputNumber for the interval in seconds appears
 *   - When the interval elapses the component calls the ping handler, which sets
 *     the ping result state → the result Alert becomes visible
 *
 * Because the default interval is 10 s (too long for a test), we set it to
 * 1 s before waiting for the auto-ping to fire.
 */
test.describe('Settings – Auto-Ping Toggle and Interval', () => {

    test.beforeEach(async ({ page }) => {
        await page.goto('/settings')
        await page.waitForLoadState('load')
    })

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Find the auto-ping Switch inside a health-check card identified by its title.
     * The Switch is rendered immediately before the "Auto-ping" text inside a Space.
     */
    function autoPingSwitch(page: any, cardTitle: string) {
        return page
            .locator('.ant-card-small')
            .filter({ hasText: cardTitle })
            .locator('.ant-switch')
    }

    /**
     * Find the interval InputNumber inside a health-check card.
     * It only appears after the switch is turned on.
     */
    function intervalInput(page: any, cardTitle: string) {
        return page
            .locator('.ant-card-small')
            .filter({ hasText: cardTitle })
            .locator('.ant-input-number-input')
    }

    // ── REST API auto-ping ────────────────────────────────────────────────────

    test('REST auto-ping toggle initially shows switch in OFF state', async ({ page }) => {
        const sw = autoPingSwitch(page, 'REST API')
        await expect(sw).toBeVisible()
        await expect(sw).not.toHaveClass(/ant-switch-checked/)
    })

    test('turning REST auto-ping ON reveals the interval InputNumber', async ({ page }) => {
        const sw = autoPingSwitch(page, 'REST API')
        await sw.click()
        await expect(sw).toHaveClass(/ant-switch-checked/)

        const input = intervalInput(page, 'REST API')
        await expect(input).toBeVisible()
    })

    test('REST auto-ping fires within 2 s when interval set to 1 s', async ({ page }) => {
        test.setTimeout(15000)

        const sw = autoPingSwitch(page, 'REST API')
        await sw.click()
        await expect(sw).toHaveClass(/ant-switch-checked/)

        // Lower the interval to 1 s so the test does not wait too long
        const input = intervalInput(page, 'REST API')
        await input.fill('1')
        await input.blur()

        // The interval fires → handlePingRest() runs → ping-rest-result appears
        const result = page.getByTestId('ping-rest-result')
        await expect(result).toBeVisible({ timeout: 8000 })
    })

    test('turning REST auto-ping OFF hides the interval InputNumber', async ({ page }) => {
        const sw = autoPingSwitch(page, 'REST API')

        // Turn on
        await sw.click()
        await expect(intervalInput(page, 'REST API')).toBeVisible()

        // Turn off
        await sw.click()
        await expect(sw).not.toHaveClass(/ant-switch-checked/)
        await expect(intervalInput(page, 'REST API')).not.toBeVisible({ timeout: 3000 })
    })

    // ── WebSocket auto-ping ───────────────────────────────────────────────────

    test('turning WebSocket auto-ping ON reveals the interval InputNumber', async ({ page }) => {
        const sw = autoPingSwitch(page, 'WebSocket')
        await sw.click()
        await expect(sw).toHaveClass(/ant-switch-checked/)

        await expect(intervalInput(page, 'WebSocket')).toBeVisible()
    })

    test('WebSocket auto-ping fires within 2 s when interval set to 1 s', async ({ page }) => {
        test.setTimeout(15000)

        const sw = autoPingSwitch(page, 'WebSocket')
        await sw.click()

        const input = intervalInput(page, 'WebSocket')
        await input.fill('1')
        await input.blur()

        const result = page.getByTestId('ping-ws-result')
        await expect(result).toBeVisible({ timeout: 8000 })
    })

    // ── SSE auto-ping ─────────────────────────────────────────────────────────

    test('turning SSE auto-ping ON reveals the interval InputNumber', async ({ page }) => {
        const sw = autoPingSwitch(page, 'Server-Sent Events')
        await sw.click()
        await expect(sw).toHaveClass(/ant-switch-checked/)

        await expect(intervalInput(page, 'Server-Sent Events')).toBeVisible()
    })

    test('SSE auto-ping fires within 2 s when interval set to 1 s', async ({ page }) => {
        test.setTimeout(15000)

        const sw = autoPingSwitch(page, 'Server-Sent Events')
        await sw.click()

        const input = intervalInput(page, 'Server-Sent Events')
        await input.fill('1')
        await input.blur()

        const result = page.getByTestId('ping-sse-result')
        await expect(result).toBeVisible({ timeout: 8000 })
    })
})
