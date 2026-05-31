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
