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
import { cleanup, waitFor } from '@testing-library/react'

afterEach(async () => {
  // Ant Design's static feedback APIs mount roots outside the tree tracked by
  // Testing Library. Only load and destroy the singleton when a test actually
  // opened a message; calling destroy() without an existing holder makes Ant
  // Design create one asynchronously while the test environment is tearing
  // down. Wait for notice removal so the next test cannot observe an animated
  // remnant and make assertions depend on suite speed.
  if (document.querySelector('.ant-message-notice')) {
    const { message } = await import('antd')
    message.destroy()
    await waitFor(() => {
      if (document.querySelector('.ant-message-notice')) {
        throw new Error('Ant Design message portal was not removed during test cleanup')
      }
    })
  }
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

// `<a download>` activation (browser API polyfill for jsdom).
//
// jsdom does not implement the `download` attribute. It sees only an anchor with
// an href, so a programmatic click runs the hyperlink activation behaviour and
// tries to NAVIGATE — which jsdom cannot do, so it writes
// "Not implemented: navigation (except hash changes)" to virtualConsole (stderr)
// from a queued task, after the assertion has already passed. Same failure shape
// as the getComputedStyle case above: real behaviour is missing, so jsdom falls
// through to its not-implemented path.
//
// A real browser dispatches the click and then SAVES the file; it does not
// navigate. This reproduces that: the click event is still dispatched, so
// listeners and `vi.spyOn(HTMLAnchorElement.prototype, 'click')` observe it
// exactly as before, but the default action is suppressed so jsdom's navigation
// path is never reached. Anchors without `download` are untouched and keep
// jsdom's native behaviour.
//
// Covers the six export/download helpers (templateService, valueListService,
// scheduleService, TemplateEditor, ProgressPanel, ScheduledRunsPage), so no test
// needs its own local anchor stub to keep the output clean.
function preventAnchorNavigation(event: Event): void {
  event.preventDefault()
}

const nativeAnchorClick = HTMLAnchorElement.prototype.click
HTMLAnchorElement.prototype.click = function click(this: HTMLAnchorElement): void {
  if (!this.hasAttribute('download')) {
    nativeAnchorClick.call(this)
    return
  }
  // `once` removes the listener after it fires; it is registered before dispatch
  // so it runs ahead of jsdom's post-dispatch activation check.
  this.addEventListener('click', preventAnchorNavigation, { once: true })
  this.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
}
