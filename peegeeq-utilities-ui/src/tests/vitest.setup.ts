/**
 * Test setup configuration for PeeGeeQ Utilities UI (Vitest only)
 *
 * Configures the testing environment with necessary polyfills and global test
 * utilities for Vitest unit tests.
 *
 * Note: Playwright E2E tests use their own setup via playwright.config.ts.
 *
 * IMPORTANT: This project has a strict NO MOCKS policy. All tests must use real implementations.
 */

import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

afterEach(() => {
  cleanup()
})

// Polyfill window.getComputedStyle — jsdom does not implement it and emits an error to
// virtualConsole (stderr) before throwing. rc-util/rc-table call it on every render.
// Replace entirely so jsdom's not-implemented path is never reached.
Object.defineProperty(window, 'getComputedStyle', {
  writable: true,
  value: (_elt: Element, _pseudoElt?: string | null): CSSStyleDeclaration =>
    new Proxy({} as CSSStyleDeclaration, {
      get: (_target, prop) => {
        if (prop === 'getPropertyValue') return () => ''
        if (prop === 'setProperty') return () => {}
        return ''
      },
    }),
})

// Web Locks API polyfill (browser API polyfill for jsdom) — exclusive mode,
// FIFO grants, `ifAvailable`, pending-request abort via `signal`, and query().
// `steal` and shared mode are not implemented (nothing in the app uses them).
// All runtimes in one test process share this manager — exactly what real
// same-origin tabs share. `globalThis.__resetWebLocks()` clears leaked state
// between tests (a held lock whose test failed before releasing it).
interface PolyfillLockRequest {
  name: string
  callback: (lock: { name: string; mode: 'exclusive' } | null) => unknown
  resolve: (value: unknown) => void
  reject: (reason: unknown) => void
}
const heldLocks = new Map<string, PolyfillLockRequest>()
const waitQueues = new Map<string, PolyfillLockRequest[]>()

function grantLock(request: PolyfillLockRequest): void {
  heldLocks.set(request.name, request)
  Promise.resolve()
    .then(() => request.callback({ name: request.name, mode: 'exclusive' }))
    .then(request.resolve, request.reject)
    .finally(() => {
      heldLocks.delete(request.name)
      const next = waitQueues.get(request.name)?.shift()
      if (next) grantLock(next)
    })
}

Object.defineProperty(navigator, 'locks', {
  writable: true,
  value: {
    request(
      name: string,
      optionsOrCallback: unknown,
      maybeCallback?: PolyfillLockRequest['callback']
    ): Promise<unknown> {
      const callback = (maybeCallback ?? optionsOrCallback) as PolyfillLockRequest['callback']
      const options = (maybeCallback ? optionsOrCallback : {}) as {
        signal?: AbortSignal
        ifAvailable?: boolean
      }
      return new Promise((resolve, reject) => {
        const request: PolyfillLockRequest = { name, callback, resolve, reject }
        if (options.signal?.aborted) {
          reject(new DOMException('The request was aborted.', 'AbortError'))
          return
        }
        if (!heldLocks.has(name)) {
          grantLock(request)
          return
        }
        if (options.ifAvailable) {
          Promise.resolve()
            .then(() => callback(null))
            .then(resolve, reject)
          return
        }
        const queue = waitQueues.get(name) ?? []
        queue.push(request)
        waitQueues.set(name, queue)
        options.signal?.addEventListener('abort', () => {
          const waiting = waitQueues.get(name) ?? []
          const index = waiting.indexOf(request)
          if (index >= 0) {
            waiting.splice(index, 1)
            reject(new DOMException('The request was aborted.', 'AbortError'))
          }
        })
      })
    },
    async query(): Promise<{
      held: Array<{ name: string; mode: string; clientId: string }>
      pending: Array<{ name: string; mode: string; clientId: string }>
    }> {
      return {
        held: [...heldLocks.keys()].map((name) => ({ name, mode: 'exclusive', clientId: 'vitest' })),
        pending: [...waitQueues.entries()].flatMap(([name, queue]) =>
          queue.map(() => ({ name, mode: 'exclusive', clientId: 'vitest' }))
        ),
      }
    },
  },
})
;(globalThis as Record<string, unknown> & typeof globalThis).__resetWebLocks = () => {
  heldLocks.clear()
  waitQueues.clear()
}

// Mock window.matchMedia (browser API polyfill for jsdom)
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
})
