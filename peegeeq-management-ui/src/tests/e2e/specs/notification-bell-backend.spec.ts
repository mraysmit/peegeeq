import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'

/**
 * Phase 8 — backend management_event → notification bell (true end-to-end).
 *
 * Proves the genuine backend→UI path: a resource mutation triggered via the REST API (which does
 * NOT run the client-side addNotification — that only fires from the UI create flow) reaches the
 * bell solely because the backend publishes a `management_event` over /ws/monitoring, which the
 * Overview page's monitoring WebSocket listener turns into a notification.
 *
 * Depends on setup-prerequisite (SETUP_ID must exist).
 */
test.describe.configure({ mode: 'serial' })

const headerBadge = (page: Parameters<Parameters<typeof test>[1]>[0]) =>
    page.locator('.ant-badge:has([data-testid="notifications-btn"]) .ant-badge-count')

test.describe('Notification bell — backend management_event', () => {

    test('a backend (API-triggered) resource event increments the bell via the monitoring WebSocket', async ({ page }) => {
        test.setTimeout(60000)

        await page.goto('/')
        // The monitoring WebSocket must be connected for its management_event listener to be live.
        await expect(page.getByTestId('websocket-status')).toContainText('Connected', { timeout: 20000 })

        // Fresh load → empty in-memory feed → no badge.
        await expect(headerBadge(page)).toHaveCount(0)

        // Create a queue via the REST API directly. This bypasses the UI create flow, so the
        // client-side addNotification never runs — the bell can only increment via the backend
        // management_event delivered over the monitoring WebSocket.
        const queueName = `notif_be_${Date.now()}`
        const resp = await page.request.post('/api/v1/management/queues', {
            data: { setupId: SETUP_ID, name: queueName, type: 'native' },
        })
        if (!resp.ok()) {
            throw new Error(`Create queue failed: ${resp.status()} ${await resp.text()}`)
        }

        // Bell increments via the WS-delivered event.
        await expect(headerBadge(page)).toContainText('1', { timeout: 15000 })

        // The drawer lists the event with the backend-composed description (includes the queue name).
        await page.getByTestId('notifications-btn').click()
        const drawer = page.locator('.ant-drawer')
        await expect(drawer).toBeVisible()
        await expect(drawer.getByText(queueName)).toBeVisible()
    })
})
