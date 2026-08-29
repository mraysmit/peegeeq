import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

/**
 * Regression guard for the queue-list SSE readiness race (D23).
 *
 * The live-count E2E test must publish only after Playwright has observed the
 * setup-specific EventSource handshake. A fixed delay is neither proof that the
 * stream is ready nor portable to a loaded CI worker.
 */

const SPEC_PATH = path.resolve(
    process.cwd(),
    'src/tests/e2e/specs/queue-updates-sse.spec.ts'
)

function liveCountTestSource(): string {
    const source = readFileSync(SPEC_PATH, 'utf8')
    const start = source.indexOf(
        "test('06 publishing a message updates the queues-list count in real time (no manual refresh)'"
    )

    expect(start, 'Queue-updates live-count E2E test must exist').toBeGreaterThanOrEqual(0)
    return source.slice(start)
}

describe('queue-updates SSE readiness guard (D23)', () => {
    it('does not use a fixed delay as proof that the setup stream is ready', () => {
        expect(liveCountTestSource()).not.toContain('waitForTimeout(')
    })

    it('observes a successful event-stream response before publishing', () => {
        const testSource = liveCountTestSource()
        const readiness = testSource.indexOf('waitForResponse(')
        const publish = testSource.indexOf(
            'page.request.post(`/api/v1/queues/${SETUP_ID}/${queueName}/messages`'
        )

        expect(readiness, 'Test must observe the setup-specific SSE response').toBeGreaterThanOrEqual(0)
        expect(testSource).toContain("response.status() === 200")
        expect(testSource).toContain("text/event-stream")
        expect(publish, 'Test must publish a message').toBeGreaterThan(readiness)
    })
})
