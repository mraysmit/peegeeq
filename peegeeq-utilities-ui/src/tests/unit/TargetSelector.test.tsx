/**
 * Tests for TargetSelector component.
 *
 * Covers: loading, empty state (no setups), no-queues state, populated dropdowns,
 * Create Setup navigation, error display, and onTargetSelected callback.
 *
 * Network calls (getSetups, getQueues) are intercepted via vi.mock.
 * useNavigate is mocked — navigation is verified by checking the mock was called.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import { MemoryRouter } from 'react-router-dom'
import TargetSelector from '../../components/TargetSelector'

vi.mock('../../services/setupService', () => ({
  getSetups: vi.fn(),
  getQueues: vi.fn(),
}))

const mockNavigate = vi.fn()
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => mockNavigate }
})

import { getSetups, getQueues } from '../../services/setupService'
const mockedGetSetups = vi.mocked(getSetups)
const mockedGetQueues = vi.mocked(getQueues)

function renderSelector(
  onTargetSelected = vi.fn<(setupId: string, queueName: string) => void>()
) {
  return render(
    <MemoryRouter>
      <ConfigProvider>
        <TargetSelector onTargetSelected={onTargetSelected} />
      </ConfigProvider>
    </MemoryRouter>
  )
}

describe('TargetSelector', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // ── Loading state ─────────────────────────────────────────────────────────

  it('shows a loading indicator while fetching setups', async () => {
    mockedGetSetups.mockReturnValueOnce(new Promise(() => {}))
    renderSelector()
    expect(screen.getByTestId('loading-state')).toBeTruthy()
  })

  // ── Empty state (no setups) ───────────────────────────────────────────────

  it('shows empty-state alert and Create Setup button when no setups exist', async () => {
    mockedGetSetups.mockResolvedValueOnce([])
    renderSelector()
    await waitFor(() => {
      expect(screen.getByText(/No PeeGeeQ setup found/i)).toBeTruthy()
    })
    expect(screen.getByRole('button', { name: /Create Setup/i })).toBeTruthy()
  })

  it('does not show setup/queue dropdowns when no setups exist', async () => {
    mockedGetSetups.mockResolvedValueOnce([])
    renderSelector()
    await waitFor(() => screen.getByText(/No PeeGeeQ setup found/i))
    expect(screen.queryByRole('combobox')).toBeNull()
  })

  // ── No-queues state ───────────────────────────────────────────────────────

  it('shows no-queues alert with Setups page link when setup exists but has no queues', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedGetQueues.mockResolvedValueOnce([])
    renderSelector()
    await waitFor(() => {
      expect(screen.getByText(/No queues found for this setup/i)).toBeTruthy()
    })
    expect(screen.getByRole('link', { name: /Setups page/i })).toBeTruthy()
  })

  it('does not show dropdowns in no-queues state', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedGetQueues.mockResolvedValueOnce([])
    renderSelector()
    await waitFor(() => screen.getByText(/No queues found for this setup/i))
    expect(screen.queryByRole('combobox')).toBeNull()
  })

  // ── Populated state ───────────────────────────────────────────────────────

  it('shows setup and queue dropdowns when setups and queues exist', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedGetQueues.mockResolvedValueOnce(['orders'])
    renderSelector()
    await waitFor(() => {
      expect(screen.getAllByRole('combobox').length).toBe(2)
    })
  })

  it('auto-selects first setup and loads its queues', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a', 'setup-b'])
    mockedGetQueues.mockResolvedValueOnce(['orders'])
    renderSelector()
    await waitFor(() => {
      expect(mockedGetQueues).toHaveBeenCalledWith('setup-a')
    })
  })

  it('shows "Manage queues →" link in normal state', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedGetQueues.mockResolvedValueOnce(['orders'])
    renderSelector()
    await waitFor(() => {
      expect(screen.getByText(/Manage queues/i)).toBeTruthy()
    })
  })

  it('does not show a "+ New queue" button', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedGetQueues.mockResolvedValueOnce(['orders'])
    renderSelector()
    await waitFor(() => screen.getAllByRole('combobox'))
    expect(screen.queryByText(/\+ New queue/i)).toBeNull()
  })

  // ── Error state ───────────────────────────────────────────────────────────

  it('shows error alert when getSetups fails', async () => {
    mockedGetSetups.mockRejectedValueOnce(new Error('network error'))
    renderSelector()
    await waitFor(() => {
      expect(screen.getByText(/Failed to load setups/i)).toBeTruthy()
    })
  })

  // ── Create Setup navigation ───────────────────────────────────────────────

  it('navigates to /generator/setup/new when Create Setup button is clicked', async () => {
    mockedGetSetups.mockResolvedValueOnce([])
    renderSelector()
    await waitFor(() => screen.getByRole('button', { name: /Create Setup/i }))
    await userEvent.click(screen.getByRole('button', { name: /Create Setup/i }))
    expect(mockNavigate).toHaveBeenCalledWith('/generator/setup/new')
  })

  // ── onTargetSelected callback ─────────────────────────────────────────────

  it('calls onTargetSelected when both setup and queue are selected', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedGetQueues.mockResolvedValueOnce(['orders'])
    const onTargetSelected = vi.fn()
    renderSelector(onTargetSelected)

    await waitFor(() => {
      expect(onTargetSelected).toHaveBeenCalledWith('setup-a', 'orders')
    })
  })

  // ── Resilience ───────────────────────────────────────────────────────────────

  it('shows no-queues state when getQueues rejects rather than showing an error banner', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedGetQueues.mockRejectedValueOnce(new Error('queues unavailable'))
    renderSelector()
    await waitFor(() => {
      expect(screen.getByText(/No queues found for this setup/i)).toBeTruthy()
    })
    expect(screen.queryByText(/queues unavailable/i)).toBeNull()
  })

  it('no-queues alert link href points to /setups', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedGetQueues.mockResolvedValueOnce([])
    renderSelector()
    await waitFor(() => screen.getByRole('link', { name: /Setups page/i }))
    const link = screen.getByRole('link', { name: /Setups page/i })
    expect(link.getAttribute('href')).toBe('/setups')
  })

  it('does not call onTargetSelected when the selected setup has no queues', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedGetQueues.mockResolvedValueOnce([])
    const onTargetSelected = vi.fn()
    renderSelector(onTargetSelected)
    await waitFor(() => screen.getByText(/No queues found for this setup/i))
    expect(onTargetSelected).not.toHaveBeenCalled()
  })
})
