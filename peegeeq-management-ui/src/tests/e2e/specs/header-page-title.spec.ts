import { test, expect } from '@playwright/test'
import { SETUP_ID } from '../test-constants'

/**
 * Header — Page Title Mapping (Phase 4a, §7.4a)
 *
 * The header `<h1 data-testid="page-title">` derives its text from the current route via
 * the `pageTitle` map in `Header.tsx`. Before this phase the map lacked `/events`,
 * `/causation-tree`, `/aggregate-stream`, and the dynamic `/queues/:setupId/:queueName`
 * route, so those pages fell back to the generic "PeeGeeQ Management". Each must now show
 * its specific title. The `/queues` list page must remain "Queues" (not "Queue Details").
 */
test.describe('Header — Page Title Mapping', () => {

    const cases: Array<{ path: string; title: string }> = [
        { path: '/events', title: 'Events' },
        { path: '/causation-tree', title: 'Causation Tree' },
        { path: '/aggregate-stream', title: 'Aggregate Stream' },
        { path: '/queues', title: 'Queues' }, // list page — must NOT become "Queue Details"
        { path: `/queues/${SETUP_ID}/title_probe_queue`, title: 'Queue Details' },
    ]

    for (const { path, title } of cases) {
        test(`header title at ${path} is "${title}"`, async ({ page }) => {
            await page.goto(path)
            await page.waitForLoadState('load')
            // Header lives in the persistent layout, so the title is route-derived and does
            // not depend on whether the page's data loads.
            await expect(page.getByTestId('page-title')).toHaveText(title, { timeout: 10000 })
        })
    }
})
