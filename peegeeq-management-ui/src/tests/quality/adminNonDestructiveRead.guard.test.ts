import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

/**
 * UI-side guard (Phase 13.3 — destructive-read safeguards).
 *
 * The PeeGeeQ management UI is an admin/observability tool. It must never open a
 * *consuming* queue read — a stream that subscribes/acks and therefore removes the very
 * messages it displays, stealing them from real consumers. Non-destructive reads only:
 * `…/messages` (browse) and `…/messages/stream` (observe, backed by QueueBrowser.tail()).
 * A genuinely-consuming endpoint would be named `…/consume` and is never called here.
 *
 * This test statically scans every non-test source file under `src/` (comments masked,
 * string/template contents preserved because the forbidden URLs live inside strings) and
 * fails if any of the removed consuming clients or a consuming queue-stream URL reappears.
 * It is the front-end counterpart to the backend `AdminConsumingReadGuardTest`
 * (peegeeq-rest). See §7.13 of
 * `peegeeq-management-ui/docs/tasks/MANAGEMENT_UI_ENHANCEMENTS-14-Jun-2026.md`.
 */

const SRC_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../')

interface ForbiddenMarker {
    pattern: RegExp
    label: string
}

const FORBIDDEN: ForbiddenMarker[] = [
    {
        pattern: /\bcreateMessageStreamService\b/g,
        label: 'createMessageStreamService — consuming WS message-stream factory (removed in Phase 13.1)',
    },
    {
        pattern: /\buseMessageStream\b/g,
        label: 'useMessageStream — consuming WS message-stream hook (removed in Phase 13.1)',
    },
    {
        pattern: /\.streamMessages\s*\(/g,
        label: '.streamMessages( — consuming SSE client method (removed in Phase 13.1; use browse/tail)',
    },
    {
        pattern: /\/ws\/queues\//g,
        label: 'ws/queues — consuming WebSocket queue-stream path (admin UI must not consume)',
    },
    {
        // The consuming SSE URL shape `/queues/{setupId}/{queueName}/stream`. The
        // non-destructive path `/queues/{setupId}/{queueName}/messages/stream` does NOT match
        // (its second `${...}` is followed by `/messages`, not `/stream`).
        pattern: /queues\/\$\{[^}]+\}\/\$\{[^}]+\}\/stream/g,
        label: 'queue /stream SSE URL — consuming (the non-destructive path is /messages/stream)',
    },
]

/** Recursively collect `.ts`/`.tsx` source files under `src/`, excluding all test scaffolding. */
function collectSourceFiles(dir: string, acc: string[] = []): string[] {
    for (const entry of readdirSync(dir)) {
        const full = path.join(dir, entry)
        if (statSync(full).isDirectory()) {
            // Skip the `tests` tree (this guard, unit tests, and e2e specs all reference the
            // forbidden tokens intentionally) plus build/vendor dirs.
            if (entry === 'tests' || entry === 'node_modules' || entry === 'dist') continue
            collectSourceFiles(full, acc)
        } else if (/\.(ts|tsx)$/.test(entry) && !/\.(test|spec)\.(ts|tsx)$/.test(entry)) {
            acc.push(full)
        }
    }
    return acc
}

/**
 * Blanks the contents of `//` and block comments (preserving newlines) so a commented-out
 * reference is not flagged. String and template-literal contents are preserved — the URLs
 * we scan for live inside them. Strings are skipped first, so a `//` inside a URL (e.g.
 * `ws://`) is never mistaken for a comment start (which would blank real code → false negative).
 */
function maskComments(src: string): string {
    const out = src.split('')
    const n = src.length
    const blank = (from: number, to: number) => {
        for (let k = from; k < to && k < n; k++) {
            if (out[k] !== '\n' && out[k] !== '\r') out[k] = ' '
        }
    }
    let i = 0
    while (i < n) {
        const c = src[i]
        if (c === '/' && i + 1 < n && src[i + 1] === '/') {
            let j = i
            while (j < n && src[j] !== '\n') j++
            blank(i, j)
            i = j
            continue
        }
        if (c === '/' && i + 1 < n && src[i + 1] === '*') {
            let j = i + 2
            while (j + 1 < n && !(src[j] === '*' && src[j + 1] === '/')) j++
            const end = Math.min(j + 2, n)
            blank(i, end)
            i = end
            continue
        }
        if (c === '"' || c === "'" || c === '`') {
            const quote = c
            let j = i + 1
            while (j < n) {
                if (src[j] === '\\') { j += 2; continue }
                if (src[j] === quote) { j++; break }
                j++
            }
            i = j
            continue
        }
        i++
    }
    return out.join('')
}

describe('admin UI non-destructive read guard (Phase 13.3)', () => {
    it('never reintroduces a consuming queue read in src/', () => {
        const violations: string[] = []
        for (const file of collectSourceFiles(SRC_ROOT)) {
            const code = maskComments(readFileSync(file, 'utf8'))
            const rel = path.relative(SRC_ROOT, file).replace(/\\/g, '/')
            for (const { pattern, label } of FORBIDDEN) {
                pattern.lastIndex = 0
                let m: RegExpExecArray | null
                while ((m = pattern.exec(code)) !== null) {
                    const line = code.slice(0, m.index).split('\n').length
                    violations.push(`  src/${rel}:${line}  ${label}`)
                    if (m.index === pattern.lastIndex) pattern.lastIndex++
                }
            }
        }

        const message =
            'Admin UI must never open a consuming queue read (Phase 13.3).\n' +
            'Non-destructive reads only: /messages (browse) or /messages/stream (observe).\n' +
            'A consuming endpoint must be named /consume and is never called by the admin UI.\n' +
            'Violations:\n' + violations.join('\n')

        expect(violations, message).toEqual([])
    })

    it('actually detects the consuming forms it guards against (detector self-test)', () => {
        // One line per forbidden marker — each pattern must fire on at least one of them, so a
        // green scan means "no violations", never "the matcher is broken and always passes".
        const bad = [
            'const s = createMessageStreamService(setupId, queueName)',
            'const h = useMessageStream(setupId, queueName)',
            'peeGeeQClient.streamMessages(setupId, queueName, onMsg)',
            'new WebSocket(`${base}/ws/queues/${setupId}/${queueName}`)',
            'new EventSource(`${base}/queues/${setupId}/${queueName}/stream`)',
        ].join('\n')
        const matched = FORBIDDEN.filter(({ pattern }) => {
            pattern.lastIndex = 0
            return pattern.test(bad)
        })
        expect(matched.length).toBe(FORBIDDEN.length)

        // The non-destructive path and commented references must NOT be flagged.
        const safe = maskComments(
            '// createMessageStreamService in a comment\n' +
            'new EventSource(`${base}/queues/${setupId}/${queueName}/messages/stream`)'
        )
        const safeHits = FORBIDDEN.filter(({ pattern }) => {
            pattern.lastIndex = 0
            return pattern.test(safe)
        })
        expect(safeHits).toEqual([])
    })
})
