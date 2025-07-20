/**
 * Test setup configuration for PeeGeeQ Management UI
 * 
 * This file configures the testing environment with necessary polyfills,
 * mocks, and global test utilities.
 */

import { expect, afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'
import * as matchers from '@testing-library/jest-dom/matchers'

// Extend Vitest's expect with jest-dom matchers
expect.extend(matchers)

// Cleanup after each test case (e.g. clearing jsdom)
afterEach(() => {
  cleanup()
})

// Mock window.matchMedia
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

// Mock ResizeObserver
global.ResizeObserver = class ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

// Mock IntersectionObserver
global.IntersectionObserver = class IntersectionObserver {
  constructor() {}
  observe() {}
  unobserve() {}
  disconnect() {}
}

// Mock fetch for tests that don't explicitly mock it
global.fetch = global.fetch || (() => 
  Promise.resolve({
    ok: true,
    status: 200,
    json: () => Promise.resolve({}),
    text: () => Promise.resolve(''),
    headers: new Headers(),
  } as Response)
)

// Console warnings/errors configuration
const originalError = console.error
const originalWarn = console.warn

beforeEach(() => {
  // Suppress specific warnings during tests
  console.error = (...args: any[]) => {
    if (
      typeof args[0] === 'string' &&
      (args[0].includes('Warning: ReactDOM.render is deprecated') ||
       args[0].includes('Warning: componentWillReceiveProps'))
    ) {
      return
    }
    originalError.call(console, ...args)
  }

  console.warn = (...args: any[]) => {
    if (
      typeof args[0] === 'string' &&
      args[0].includes('deprecated')
    ) {
      return
    }
    originalWarn.call(console, ...args)
  }
})

afterEach(() => {
  console.error = originalError
  console.warn = originalWarn
})

// Global test utilities
declare global {
  namespace Vi {
    interface JestAssertion<T = any>
      extends jest.Matchers<void, T>,
        TestingLibraryMatchers<T, void> {}
  }
}

// Export test utilities
export const testUtils = {
  // Helper to wait for async operations
  waitFor: (ms: number) => new Promise(resolve => setTimeout(resolve, ms)),
  
  // Helper to create mock API responses
  createMockResponse: (data: any, status = 200) => ({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(data),
    text: () => Promise.resolve(JSON.stringify(data)),
    headers: new Headers({
      'content-type': 'application/json'
    })
  } as Response),

  // Helper to mock fetch with specific responses
  mockFetch: (responses: Record<string, any>) => {
    global.fetch = vi.fn().mockImplementation((url: string) => {
      const response = responses[url] || responses['default']
      if (!response) {
        return Promise.resolve({
          ok: false,
          status: 404,
          json: () => Promise.resolve({ error: 'Not found' })
        } as Response)
      }
      return Promise.resolve(testUtils.createMockResponse(response))
    })
  }
}
