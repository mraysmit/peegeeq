/**
 * Tests for the SCH.6 App wiring: the Scheduled Runs navigation entry, the
 * /generator/schedules route, and the scheduler lifecycle bound to App mount.
 *
 * The scheduler's start is observable without inspecting internals: the
 * executor tab holds the Web Locks lock (navigator.locks.query) while started
 * and releases it on stop.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import App from '../../App'

// App mounts Overview, whose store fires a REAL axios fetch; with no backend
// in jsdom that produces noisy ECONNREFUSED stderr on every run. This file
// asserts App WIRING only (navigation + scheduler lifecycle), so the network
// boundary is stubbed — the sanctioned axios boundary, as in
// publishService.test.ts. Overview's real error surface has its own tests.
vi.mock('axios', async (importOriginal) => {
  const actual = await importOriginal<typeof import('axios')>()
  return {
    default: {
      ...actual.default,
      get: vi.fn().mockRejectedValue(new Error('no backend in unit tests')),
      post: vi.fn().mockRejectedValue(new Error('no backend in unit tests')),
      isAxiosError: actual.default.isAxiosError,
    },
  }
})
import { SCHEDULER_LOCK_NAME } from '../../engine/schedulerRuntime'
import { useScheduleStore } from '../../stores/scheduleStore'

async function schedulerLockHeld(): Promise<boolean> {
  const state = await navigator.locks.query()
  return (state.held ?? []).some((lock) => lock.name === SCHEDULER_LOCK_NAME)
}

describe('App wiring for Scheduled Runs (SCH.6)', () => {
  beforeEach(() => {
    localStorage.clear()
    ;(globalThis as { __resetWebLocks?: () => void }).__resetWebLocks?.()
    window.history.pushState({}, '', '/')
    useScheduleStore.setState({ schedules: [], history: [], templates: [] })
    // The stubbed fetch rejection is EXPECTED here; the store's caught-error
    // log would otherwise be stderr noise in every run.
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the Scheduled Runs entry in the sidebar and navigates to the screen', async () => {
    render(<App />)
    const link = screen.getByTestId('nav-generator-schedules')
    expect(link.textContent).toContain('Scheduled Runs')

    await userEvent.click(link)
    await waitFor(() => {
      expect(screen.getByTestId('scheduled-runs-page')).toBeTruthy()
    })
  })

  it('starts the scheduler on App mount and stops it on unmount', async () => {
    const { unmount } = render(<App />)
    await waitFor(async () => {
      expect(await schedulerLockHeld()).toBe(true)
    })

    unmount()
    await waitFor(async () => {
      expect(await schedulerLockHeld()).toBe(false)
    })
  })
})
