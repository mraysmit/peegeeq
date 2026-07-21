/**
 * Tests for TargetSelector component.
 *
 * Covers: loading, empty state (no setups), no-queues state, populated dropdowns,
 * Create Setup navigation, error display (setups and queues), and the
 * onTargetSelected callback.
 *
 * Network calls (getSetups, listQueueDetails) are intercepted via vi.mock.
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
}))

vi.mock('../../services/queueService', () => ({
  listQueueDetails: vi.fn(),
}))

const mockNavigate = vi.fn()
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => mockNavigate }
})

import { getSetups } from '../../services/setupService'
import { listQueueDetails } from '../../services/queueService'
const mockedGetSetups = vi.mocked(getSetups)
const mockedListQueueDetails = vi.mocked(listQueueDetails)

function renderSelector(
  onTargetSelected = vi.fn<(setupId: string, queueName: string) => void>(),
  onTargetCleared = vi.fn<() => void>()
) {
  return render(
    <MemoryRouter>
      <ConfigProvider>
        <TargetSelector onTargetSelected={onTargetSelected} onTargetCleared={onTargetCleared} />
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

  it('shows empty-state alert and Connect setup button when no setups exist', async () => {
    mockedGetSetups.mockResolvedValueOnce([])
    renderSelector()
    await waitFor(() => {
      expect(screen.getByText(/No PeeGeeQ setup connected/i)).toBeTruthy()
    })
    expect(screen.getByRole('button', { name: /Connect setup/i })).toBeTruthy()
  })

  it('does not show setup/queue dropdowns when no setups exist', async () => {
    mockedGetSetups.mockResolvedValueOnce([])
    renderSelector()
    await waitFor(() => screen.getByText(/No PeeGeeQ setup connected/i))
    expect(screen.queryByRole('combobox')).toBeNull()
  })

  // ── No-queues state ───────────────────────────────────────────────────────

  it('shows no-queues alert with Setups page link when setup exists but has no queues', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockResolvedValueOnce([])
    renderSelector()
    await waitFor(() => {
      expect(screen.getByText(/No queues found for this setup/i)).toBeTruthy()
    })
    expect(screen.getByRole('link', { name: /Setups page/i })).toBeTruthy()
  })

  it('keeps the SETUP dropdown visible in the no-queues state so the user can switch setups', async () => {
    // A queue-less first setup must not strand the user: with two setups where
    // the auto-selected first has no queues, the setup dropdown is the only way
    // to reach the second setup's queues.
    mockedGetSetups.mockResolvedValueOnce(['setup-a', 'setup-b'])
    mockedListQueueDetails.mockResolvedValueOnce([])
    renderSelector()
    await waitFor(() => screen.getByText(/No queues found for this setup/i))
    // Setup dropdown present, queue dropdown absent.
    expect(screen.getAllByRole('combobox')).toHaveLength(1)
  })

  // ── Populated state ───────────────────────────────────────────────────────

  it('shows setup and queue dropdowns when setups and queues exist', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockResolvedValueOnce([{ name: 'orders', implementationType: 'native' }])
    renderSelector()
    await waitFor(() => {
      expect(screen.getAllByRole('combobox').length).toBe(2)
    })
  })

  it('auto-selects first setup and loads its queue details', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a', 'setup-b'])
    mockedListQueueDetails.mockResolvedValueOnce([{ name: 'orders', implementationType: 'native' }])
    renderSelector()
    await waitFor(() => {
      expect(mockedListQueueDetails).toHaveBeenCalledWith('setup-a')
    })
  })

  it('shows the queue implementation type as plain text in the selected queue label', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockResolvedValueOnce([{ name: 'orders', implementationType: 'native' }])
    renderSelector()
    await waitFor(() => {
      expect(screen.getByText('orders (native)')).toBeTruthy()
    })
  })

  it('shows the bare queue name when the implementation type is unknown', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockResolvedValueOnce([{ name: 'orders', implementationType: null }])
    renderSelector()
    await waitFor(() => {
      expect(screen.getByText('orders')).toBeTruthy()
    })
  })

  it('shows "Manage queues →" link in normal state', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockResolvedValueOnce([{ name: 'orders', implementationType: 'native' }])
    renderSelector()
    await waitFor(() => {
      expect(screen.getByText(/Manage queues/i)).toBeTruthy()
    })
  })

  it('does not show a "+ New queue" button', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockResolvedValueOnce([{ name: 'orders', implementationType: 'native' }])
    renderSelector()
    await waitFor(() => screen.getAllByRole('combobox'))
    expect(screen.queryByText(/\+ New queue/i)).toBeNull()
  })

  // ── Error states ──────────────────────────────────────────────────────────

  it('shows error alert when getSetups fails', async () => {
    mockedGetSetups.mockRejectedValueOnce(new Error('network error'))
    renderSelector()
    await waitFor(() => {
      expect(screen.getByText(/Failed to load setups/i)).toBeTruthy()
    })
  })

  it('shows the underlying CAUSE in the setups load error, not only a generic line', async () => {
    mockedGetSetups.mockRejectedValueOnce(new Error('connect ECONNREFUSED 127.0.0.1:8080'))
    renderSelector()
    await waitFor(() => {
      expect(screen.getByText(/ECONNREFUSED/)).toBeTruthy()
    })
  })

  it('shows the underlying CAUSE in the queue load error', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockRejectedValueOnce(new Error('HTTP 503 from backend'))
    renderSelector()
    await waitFor(() => screen.getByTestId('queue-load-error'))
    expect(screen.getByText(/HTTP 503 from backend/)).toBeTruthy()
  })

  it('shows a queue-load error alert (not the no-queues state) when listQueueDetails fails', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockRejectedValueOnce(new Error('queues unavailable'))
    renderSelector()
    await waitFor(() => {
      expect(screen.getByTestId('queue-load-error')).toBeTruthy()
    })
    expect(screen.getByText(/Failed to load queues for this setup/i)).toBeTruthy()
    expect(screen.queryByText(/No queues found for this setup/i)).toBeNull()
    // The setup dropdown stays available — a failing setup must not strand the user.
    expect(screen.getAllByRole('combobox')).toHaveLength(1)
  })

  it('retries queue loading from the queue-load error alert', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails
      .mockRejectedValueOnce(new Error('queues unavailable'))
      .mockResolvedValueOnce([{ name: 'orders', implementationType: 'native' }])
    renderSelector()
    await waitFor(() => screen.getByTestId('queue-load-error'))

    await userEvent.click(screen.getByRole('button', { name: /Retry/i }))

    await waitFor(() => {
      expect(screen.getAllByRole('combobox').length).toBe(2)
    })
    expect(mockedListQueueDetails).toHaveBeenCalledTimes(2)
  })

  // ── Connect setup navigation ──────────────────────────────────────────────

  it('navigates to /setups/connect when Connect setup button is clicked', async () => {
    mockedGetSetups.mockResolvedValueOnce([])
    renderSelector()
    await waitFor(() => screen.getByRole('button', { name: /Connect setup/i }))
    await userEvent.click(screen.getByRole('button', { name: /Connect setup/i }))
    expect(mockNavigate).toHaveBeenCalledWith('/setups/connect')
  })

  // ── onTargetSelected callback ─────────────────────────────────────────────

  it('calls onTargetSelected with the queue NAME (not the display label)', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockResolvedValueOnce([{ name: 'orders', implementationType: 'native' }])
    const onTargetSelected = vi.fn()
    renderSelector(onTargetSelected)

    await waitFor(() => {
      expect(onTargetSelected).toHaveBeenCalledWith('setup-a', 'orders')
    })
  })

  // ── Resilience ───────────────────────────────────────────────────────────────

  it('no-queues alert link href points to the selected setup', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockResolvedValueOnce([])
    renderSelector()
    await waitFor(() => screen.getByRole('link', { name: /Setups page/i }))
    const link = screen.getByRole('link', { name: /Setups page/i })
    expect(link.getAttribute('href')).toBe('/setups/setup-a')
  })

  it('does not call onTargetSelected when the selected setup has no queues', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockResolvedValueOnce([])
    const onTargetSelected = vi.fn()
    renderSelector(onTargetSelected)
    await waitFor(() => screen.getByText(/No queues found for this setup/i))
    expect(onTargetSelected).not.toHaveBeenCalled()
  })

  it('does not call onTargetSelected when queue loading fails', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockRejectedValueOnce(new Error('queues unavailable'))
    const onTargetSelected = vi.fn()
    renderSelector(onTargetSelected)
    await waitFor(() => screen.getByTestId('queue-load-error'))
    expect(onTargetSelected).not.toHaveBeenCalled()
  })

  // ── Stale-target clearing ─────────────────────────────────────────────────
  // Without a clear signal the parent keeps the LAST valid pair: switching to a
  // setup whose queues fail to load (or which has none) leaves Start enabled
  // and publishing to the previously shown setup.

  it('clears the target when switching to a setup whose queue load fails', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a', 'setup-b'])
    mockedListQueueDetails.mockImplementation((setupId: string) =>
      setupId === 'setup-a'
        ? Promise.resolve([{ name: 'orders', implementationType: 'native' }])
        : Promise.reject(new Error('queues unavailable'))
    )
    const onTargetSelected = vi.fn()
    const onTargetCleared = vi.fn()
    renderSelector(onTargetSelected, onTargetCleared)

    await waitFor(() => expect(onTargetSelected).toHaveBeenCalledWith('setup-a', 'orders'))
    onTargetCleared.mockClear()

    // The setup dropdown is the first combobox (the queue one is second/absent).
    await userEvent.click(screen.getAllByRole('combobox')[0])
    await userEvent.click(await screen.findByTitle('setup-b'))

    await waitFor(() => screen.getByTestId('queue-load-error'))
    expect(onTargetCleared).toHaveBeenCalled()
    expect(onTargetSelected).not.toHaveBeenCalledWith('setup-b', expect.anything())
  })

  it('clears the target when switching to a setup that has no queues', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a', 'setup-b'])
    mockedListQueueDetails.mockImplementation((setupId: string) =>
      setupId === 'setup-a'
        ? Promise.resolve([{ name: 'orders', implementationType: 'native' }])
        : Promise.resolve([])
    )
    const onTargetSelected = vi.fn()
    const onTargetCleared = vi.fn()
    renderSelector(onTargetSelected, onTargetCleared)

    await waitFor(() => expect(onTargetSelected).toHaveBeenCalledWith('setup-a', 'orders'))
    onTargetCleared.mockClear()

    // The setup dropdown is the first combobox (the queue one is second/absent).
    await userEvent.click(screen.getAllByRole('combobox')[0])
    await userEvent.click(await screen.findByTitle('setup-b'))

    await waitFor(() => screen.getByText(/No queues found for this setup/i))
    expect(onTargetCleared).toHaveBeenCalled()
    expect(onTargetSelected).not.toHaveBeenCalledWith('setup-b', expect.anything())
  })
})
