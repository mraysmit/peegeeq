/**
 * Tests for the SCH.6 App wiring: the Scheduled Runs navigation entry, the
 * /generator/schedules route, and the scheduler lifecycle bound to App mount.
 *
 * The scheduler's start is observable without inspecting internals: the
 * lease-holding tab writes `peegeeq_scheduler_lease` on start and removes its
 * own lease on stop.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import App from '../../App'
import { useScheduleStore } from '../../stores/scheduleStore'

describe('App wiring for Scheduled Runs (SCH.6)', () => {
  beforeEach(() => {
    localStorage.clear()
    window.history.pushState({}, '', '/')
    useScheduleStore.setState({ schedules: [], history: [], templates: [] })
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
    await waitFor(() => {
      expect(localStorage.getItem('peegeeq_scheduler_lease')).not.toBeNull()
    })

    unmount()
    expect(localStorage.getItem('peegeeq_scheduler_lease')).toBeNull()
  })
})
