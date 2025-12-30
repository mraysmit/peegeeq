/**
 * Test setup configuration for PeeGeeQ Management UI (Vitest only)
 * 
 * This file configures the testing environment with necessary polyfills
 * and global test utilities for Vitest unit tests.
 * 
 * Note: Playwright E2E tests use their own setup in global-setup-testcontainers.ts
 * 
 * IMPORTANT: This project has a strict NO MOCKS policy. All tests must use real implementations.
 */

import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

// Cleanup after each test case (e.g. clearing jsdom)
afterEach(() => {
  cleanup()
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
    dispatchEvent: () => {},
  }),
})

// Mock ResizeObserver (browser API polyfill for jsdom)
global.ResizeObserver = class ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

// Mock IntersectionObserver (browser API polyfill for jsdom)
global.IntersectionObserver = class IntersectionObserver {
  constructor() {}
  observe() {}
  unobserve() {}
  disconnect() {}
}

