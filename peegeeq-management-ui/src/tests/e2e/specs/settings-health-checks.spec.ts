import { test, expect } from '@playwright/test'

/**
 * Settings Page – Connection Health Checks Tests
 *
 * Covers every button and toggle in the "Connection Health Checks" card and
 * the Disconnect button in the "Backend Connection" card.
 *
 * The existing settings.spec.ts fully covers the Backend Configuration card
 * (save, reset, validation, Test & Connect).  This spec fills the remaining gaps:
 *
 *   - Health Checks card structure (3 sub-cards visible)
 *   - Ping REST  → result Alert appears with a message (success or failure)
 *   - Ping WebSocket  → result Alert appears
 *   - Ping SSE  → result Alert appears
 *   - Auto-ping REST toggle  → interval InputNumber appears; timer fires
 *   - Auto-ping WebSocket toggle  → interval InputNumber appears
 *   - Auto-ping SSE toggle  → interval InputNumber appears
 *   - Auto-ping interval value can be changed
 *   - Disconnect button  → status badge changes to Disconnected; config restored
 *
 * The ping result assertions intentionally accept both success and error
 * outcomes — the test proves the result is surfaced to the user, not that
 * any particular backend endpoint is reachable.
 *
 * IMPORTANT: the `1-settings` project runs first and the config it leaves
 * behind is inherited by all later projects.  Every test that mutates the
 * stored config (Disconnect) restores it before finishing.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Settings – Connection Health Checks', () => {

    // Restore default config after every test so no state leaks to later specs.
    test.afterEach(async ({ page }) => {
        await page.goto('/settings')
        await page.waitForLoadState('load')
        await page.evaluate(() => localStorage.removeItem('peegeeq_backend_config'))
    })

    // ── helper ────────────────────────────────────────────────────────────────

    /**
     * Return the small inner health-check card that has the given title.
     *
     * Uses `:scope > .ant-card-head` so only the card's OWN direct header is
     * matched, not the headers of nested child cards.  Without this scope, the
     * outer "Connection Health Checks" card would also match (it contains the
     * inner cards as descendants), causing multi-element strict-mode failures.
     */
    function healthCard(page: Parameters<Parameters<typeof test>[1]>[0], title: string) {
        return page.locator('.ant-card').filter({
            has: page.locator(':scope > .ant-card-head .ant-card-head-title', { hasText: title }),
        })
    }

    // ── 1. Page structure ─────────────────────────────────────────────────────

    test('01 Connection Health Checks card shows REST API, WebSocket and SSE sub-cards', async ({ page }) => {
        await page.goto('/settings')
        await page.waitForLoadState('load')

        await expect(page.getByText('Connection Health Checks')).toBeVisible()

        await expect(healthCard(page, 'REST API')).toBeVisible()
        await expect(healthCard(page, 'WebSocket')).toBeVisible()
        await expect(healthCard(page, 'Server-Sent Events')).toBeVisible()

        // Each sub-card has a "Ping Now" button and an "Auto-ping" label
        for (const title of ['REST API', 'WebSocket', 'Server-Sent Events']) {
            const card = healthCard(page, title)
            await expect(card.getByRole('button', { name: /ping now/i })).toBeVisible()
            await expect(card.getByText('Auto-ping')).toBeVisible()
        }
    })

    test('02 each health card shows the endpoint it pings', async ({ page }) => {
        await page.goto('/settings')
        await page.waitForLoadState('load')

        // Use toContainText — the rendered text is "Endpoint: /api/v1/health"
        await expect(healthCard(page, 'REST API')).toContainText('/api/v1/health')
        await expect(healthCard(page, 'WebSocket')).toContainText('/ws/health')
        await expect(healthCard(page, 'Server-Sent Events')).toContainText('/api/v1/sse/health')
    })

    // ── 2. Manual pings ───────────────────────────────────────────────────────

    test('03 Ping REST returns a result and shows it in the card', async ({ page }) => {
        await page.goto('/settings')
        await page.waitForLoadState('load')

        await page.getByTestId('ping-rest-btn').click()

        // Result Alert must appear regardless of success or failure
        const result = page.getByTestId('ping-rest-result')
        await expect(result).toBeVisible({ timeout: 10000 })

        // The Alert contains a human-readable message
        const text = await result.textContent()
        expect(text).toBeTruthy()
        expect(text!.length).toBeGreaterThan(0)
    })

    test('04 Ping REST succeeds against the live backend', async ({ page }) => {
        await page.goto('/settings')
        await page.waitForLoadState('load')

        await page.getByTestId('ping-rest-btn').click()

        // REST ping to the running backend should succeed
        const result = page.getByTestId('ping-rest-result')
        await expect(result).toBeVisible({ timeout: 10000 })
        await expect(result).toHaveClass(/ant-alert-success/)

        // A green check-circle icon appears in the card header
        const card = healthCard(page, 'REST API')
        await expect(card.locator('.anticon-check-circle')).toBeVisible()
    })

    test('05 Ping WebSocket returns a result and shows it in the card', async ({ page }) => {
        await page.goto('/settings')
        await page.waitForLoadState('load')

        await page.getByTestId('ping-ws-btn').click()

        const result = page.getByTestId('ping-ws-result')
        await expect(result).toBeVisible({ timeout: 10000 })

        const text = await result.textContent()
        expect(text).toBeTruthy()
    })

    test('06 Ping SSE returns a result and shows it in the card', async ({ page }) => {
        await page.goto('/settings')
        await page.waitForLoadState('load')

        await page.getByTestId('ping-sse-btn').click()

        const result = page.getByTestId('ping-sse-result')
        await expect(result).toBeVisible({ timeout: 10000 })

        const text = await result.textContent()
        expect(text).toBeTruthy()
    })

    test('07 result shows a timestamp after pinging', async ({ page }) => {
        await page.goto('/settings')
        await page.waitForLoadState('load')

        await page.getByTestId('ping-rest-btn').click()
        await expect(page.getByTestId('ping-rest-result')).toBeVisible({ timeout: 10000 })

        // "Last ping: HH:MM:SS" is rendered inside the result alert
        await expect(page.getByTestId('ping-rest-result').getByText(/last ping:/i)).toBeVisible()
    })

    // ── 3. Auto-ping toggles ──────────────────────────────────────────────────

    test('08 enabling REST auto-ping shows the interval InputNumber', async ({ page }) => {
        await page.goto('/settings')
        await page.waitForLoadState('load')

        const card = healthCard(page, 'REST API')

        // The interval InputNumber is hidden until auto-ping is enabled
        await expect(card.locator('.ant-input-number')).not.toBeVisible()

        await card.locator('.ant-switch').click()
        await expect(card.locator('.ant-switch')).toHaveClass(/ant-switch-checked/)

        // Interval InputNumber with "sec" suffix now appears
        await expect(card.locator('.ant-input-number')).toBeVisible()
        await expect(card.getByText('sec')).toBeVisible()

        // Default interval is 10
        await expect(card.locator('.ant-input-number-input')).toHaveValue('10')

        // Turn auto-ping back off
        await card.locator('.ant-switch').click()
        await expect(card.locator('.ant-input-number')).not.toBeVisible()
    })

    test('09 enabling WebSocket auto-ping shows the interval InputNumber', async ({ page }) => {
        await page.goto('/settings')
        await page.waitForLoadState('load')

        const card = healthCard(page, 'WebSocket')
        await card.locator('.ant-switch').click()
        await expect(card.locator('.ant-input-number')).toBeVisible()
        await expect(card.locator('.ant-input-number-input')).toHaveValue('10')

        await card.locator('.ant-switch').click()
        await expect(card.locator('.ant-input-number')).not.toBeVisible()
    })

    test('10 enabling SSE auto-ping shows the interval InputNumber', async ({ page }) => {
        await page.goto('/settings')
        await page.waitForLoadState('load')

        const card = healthCard(page, 'Server-Sent Events')
        await card.locator('.ant-switch').click()
        await expect(card.locator('.ant-input-number')).toBeVisible()
        await expect(card.locator('.ant-input-number-input')).toHaveValue('10')

        await card.locator('.ant-switch').click()
        await expect(card.locator('.ant-input-number')).not.toBeVisible()
    })

    test('11 auto-ping interval value can be changed', async ({ page }) => {
        await page.goto('/settings')
        await page.waitForLoadState('load')

        const card = healthCard(page, 'REST API')
        await card.locator('.ant-switch').click()

        const intervalInput = card.locator('.ant-input-number-input')
        await intervalInput.clear()
        await intervalInput.fill('30')
        await intervalInput.press('Tab')

        await expect(intervalInput).toHaveValue('30')

        // Turn off auto-ping
        await card.locator('.ant-switch').click()
    })

    test('12 REST auto-ping fires automatically at the configured interval', async ({ page }) => {
        test.setTimeout(15000)

        await page.goto('/settings')
        await page.waitForLoadState('load')

        const card = healthCard(page, 'REST API')

        // Enable auto-ping and set a 1-second interval
        await card.locator('.ant-switch').click()
        const intervalInput = card.locator('.ant-input-number-input')
        await intervalInput.clear()
        await intervalInput.fill('1')
        await intervalInput.press('Tab')

        // The ping result should appear automatically within 3 seconds
        await expect(page.getByTestId('ping-rest-result')).toBeVisible({ timeout: 5000 })

        // Turn off auto-ping to stop the timer before navigating away
        await card.locator('.ant-switch').click()
    })

    // ── 4. Disconnect button ──────────────────────────────────────────────────

    test('13 Disconnect button changes connection status to Disconnected', async ({ page }) => {
        await page.goto('/settings')
        await page.waitForLoadState('load')

        // Verify currently connected
        await expect(page.getByText('Connected')).toBeVisible({ timeout: 10000 })
        await expect(page.getByTestId('disconnect-btn')).toBeEnabled()

        await page.getByTestId('disconnect-btn').click()

        // Status badge changes to Disconnected (URL set to localhost:9999)
        await expect(page.getByText('Disconnected')).toBeVisible({ timeout: 10000 })
        await expect(page.getByTestId('disconnect-btn')).toBeDisabled()

        // Restore: reset to defaults (http://127.0.0.1:8088) so later tests work
        await page.getByTestId('reset-settings-btn').click()
        await expect(page.getByText('Connected')).toBeVisible({ timeout: 10000 })
    })

    test('14 Disconnect button is disabled when already disconnected', async ({ page }) => {
        await page.goto('/settings')
        await page.waitForLoadState('load')

        await page.getByTestId('disconnect-btn').click()
        await expect(page.getByText('Disconnected')).toBeVisible({ timeout: 10000 })
        await expect(page.getByTestId('disconnect-btn')).toBeDisabled()

        // Restore
        await page.getByTestId('reset-settings-btn').click()
        await expect(page.getByText('Connected')).toBeVisible({ timeout: 10000 })
    })
})
