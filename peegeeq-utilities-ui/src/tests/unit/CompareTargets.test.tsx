/**
 * Tests for CompareTargets (Zone A — Compare mode, design §19.2 — G.2b).
 *
 * Contract under test (written before the component):
 * - two labelled rows, native and outbox, each with its own setup and queue
 * - each row auto-selects the first queue OF ITS OWN TYPE, so the two sides do
 *   not both land on the same queue and immediately refuse to run
 * - a setup with no queue of a row's type selects nothing for that row and says
 *   so — picking a wrong-type queue would silently build the mismatch the
 *   comparison exists to avoid
 * - the reported target carries the implementation type, which is what lets
 *   comparePlan validate the two roles at all
 * - a queue whose type the backend did not report is selectable but named in a
 *   non-blocking warning
 * - load failures surface with their cause and a Retry, and clear the affected
 *   side rather than leaving a stale target armed (the TargetSelector contract)
 * - the two rows are independent: changing one side's setup does not disturb
 *   the other's selection
 *
 * Only the two service functions are mocked — the network boundary, exactly as
 * TargetSelector.test.tsx does. No business logic is mocked.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import { MemoryRouter } from 'react-router-dom'
import CompareTargets from '../../pages/generator/CompareTargets'
import type { CompareSettings } from '../../types/compare'

vi.mock('../../services/setupService', () => ({
  getSetups: vi.fn(),
}))

vi.mock('../../services/queueService', () => ({
  listQueueDetails: vi.fn(),
}))

import { getSetups } from '../../services/setupService'
import { listQueueDetails } from '../../services/queueService'
const mockedGetSetups = vi.mocked(getSetups)
const mockedListQueueDetails = vi.mocked(listQueueDetails)

const MIXED_QUEUES = [
  { name: 'orders', implementationType: 'native' as const },
  { name: 'events', implementationType: 'outbox' as const },
]

function renderTargets(onChange = vi.fn<(settings: CompareSettings) => void>()) {
  render(
    <MemoryRouter>
      <ConfigProvider>
        <CompareTargets onChange={onChange} />
      </ConfigProvider>
    </MemoryRouter>
  )
  return onChange
}

/** The most recent settings the component reported. */
function lastSettings(onChange: ReturnType<typeof vi.fn>): CompareSettings {
  const calls = onChange.mock.calls
  return calls[calls.length - 1][0] as CompareSettings
}

describe('CompareTargets', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders a native row and an outbox row', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockResolvedValue(MIXED_QUEUES)
    renderTargets()

    await waitFor(() => expect(screen.getByTestId('compare-row-native')).toBeTruthy())
    expect(screen.getByTestId('compare-row-outbox')).toBeTruthy()
  })

  it('auto-selects the first queue OF EACH ROW\'S OWN TYPE, not the same queue twice', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockResolvedValue(MIXED_QUEUES)
    const onChange = renderTargets()

    await waitFor(() => {
      const settings = lastSettings(onChange)
      expect(settings.native?.queueName).toBe('orders')
      expect(settings.outbox?.queueName).toBe('events')
    })
  })

  it('reports each target WITH its implementation type', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockResolvedValue(MIXED_QUEUES)
    const onChange = renderTargets()

    await waitFor(() => {
      const settings = lastSettings(onChange)
      // Without the type, comparePlan cannot tell a valid pair from two
      // native queues wearing different labels.
      expect(settings.native).toEqual({
        setupId: 'setup-a',
        queueName: 'orders',
        implementationType: 'native',
      })
      expect(settings.outbox?.implementationType).toBe('outbox')
    })
  })

  it('selects NOTHING for a row whose setup has no queue of that type, and says so', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockResolvedValue([
      { name: 'orders', implementationType: 'native' },
      { name: 'orders2', implementationType: 'native' },
    ])
    const onChange = renderTargets()

    await waitFor(() => expect(screen.getByTestId('compare-no-match-outbox')).toBeTruthy())
    // The advisory renders as soon as the queues load, which is BEFORE the
    // auto-select effect has reported — so the settings must be waited for in
    // their own right, not read off the back of the alert appearing.
    await waitFor(() => {
      // Falling back to a native queue here would build the exact mismatch the
      // comparison exists to avoid, and it would look deliberate.
      expect(lastSettings(onChange).native?.queueName).toBe('orders')
    })
    expect(lastSettings(onChange).outbox).toBeNull()
  })

  it('offers a queue whose type the backend did not report, and warns about it', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockResolvedValue([
      { name: 'orders', implementationType: null },
      { name: 'events', implementationType: 'outbox' },
    ])
    renderTargets()

    await waitFor(() => expect(screen.getByTestId('compare-unverified-warning')).toBeTruthy())
    expect(screen.getByTestId('compare-unverified-warning').textContent).toContain('orders')
  })

  it('does not warn when both types were reported', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockResolvedValue(MIXED_QUEUES)
    renderTargets()

    await waitFor(() => expect(screen.getByTestId('compare-row-native')).toBeTruthy())
    expect(screen.queryByTestId('compare-unverified-warning')).toBeNull()
  })

  it('shows the underlying CAUSE when the setups load fails, with a Retry', async () => {
    mockedGetSetups.mockRejectedValueOnce(new Error('connect ECONNREFUSED 127.0.0.1:8088'))
    renderTargets()

    await waitFor(() => expect(screen.getByTestId('compare-load-error')).toBeTruthy())
    expect(screen.getByText(/ECONNREFUSED/)).toBeTruthy()
    expect(screen.getByRole('button', { name: /Retry/i })).toBeTruthy()
  })

  it('retries the setups load from the error alert', async () => {
    mockedGetSetups
      .mockRejectedValueOnce(new Error('setups unavailable'))
      .mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockResolvedValue(MIXED_QUEUES)
    renderTargets()
    await waitFor(() => screen.getByTestId('compare-load-error'))

    await userEvent.click(screen.getByRole('button', { name: /Retry/i }))

    await waitFor(() => expect(screen.getByTestId('compare-row-native')).toBeTruthy())
    expect(mockedGetSetups).toHaveBeenCalledTimes(2)
  })

  it('surfaces a queue-load failure on the first load with its cause', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockRejectedValue(new Error('HTTP 503 from backend'))
    const onChange = renderTargets()

    await waitFor(() => expect(screen.getByTestId('compare-queue-error')).toBeTruthy())
    expect(screen.getByText(/HTTP 503 from backend/)).toBeTruthy()
    expect(lastSettings(onChange)).toEqual({ native: null, outbox: null })
  })

  it('clears the row whose setup failed to load, and LEAVES the healthy row alone', async () => {
    // The failure must land AFTER a successful load, or nothing was ever
    // cached and "clears the stale selection" is not exercised at all. It must
    // also hit only one setup, or "leaves the healthy row alone" is untested.
    mockedGetSetups.mockResolvedValueOnce(['setup-a', 'setup-b'])
    mockedListQueueDetails.mockImplementation((setupId: string) =>
      setupId === 'setup-a'
        ? Promise.resolve(MIXED_QUEUES)
        : Promise.reject(new Error('HTTP 503 from backend'))
    )
    const onChange = renderTargets()
    await waitFor(() => expect(lastSettings(onChange).native?.queueName).toBe('orders'))

    // Point only the NATIVE row at the failing setup.
    const nativeRow = screen.getByTestId('compare-row-native')
    await userEvent.click(within(nativeRow).getAllByRole('combobox')[0])
    await userEvent.click(await screen.findByTitle('setup-b'))

    await waitFor(() => expect(screen.getByTestId('compare-queue-error')).toBeTruthy())
    await waitFor(() => {
      const settings = lastSettings(onChange)
      // A stale target left armed would publish load at a queue the UI can no
      // longer justify showing.
      expect(settings.native).toBeNull()
      // setup-a answered perfectly well; failing fast across both rows would
      // discard a selection that is still valid.
      expect(settings.outbox).toEqual({
        setupId: 'setup-a',
        queueName: 'events',
        implementationType: 'outbox',
      })
    })
  })

  it('drops a setup\'s CACHED queues when a later load for it fails', async () => {
    // The stale case that matters: a setup that answered once and fails on a
    // subsequent load. Keeping its cached queues would leave a row armed at a
    // queue the backend can no longer confirm exists.
    let setupACalls = 0
    mockedGetSetups.mockResolvedValueOnce(['setup-a', 'setup-b'])
    mockedListQueueDetails.mockImplementation((setupId: string) => {
      if (setupId === 'setup-b') return Promise.resolve(MIXED_QUEUES)
      setupACalls++
      return setupACalls === 1
        ? Promise.resolve(MIXED_QUEUES)
        : Promise.reject(new Error('setup-a went away'))
    })
    const onChange = renderTargets()
    await waitFor(() => expect(lastSettings(onChange).outbox?.queueName).toBe('events'))

    // Move the native row to setup-b. That reloads BOTH needed setups, and
    // setup-a now fails — while the outbox row still points at it.
    const nativeRow = screen.getByTestId('compare-row-native')
    await userEvent.click(within(nativeRow).getAllByRole('combobox')[0])
    await userEvent.click(await screen.findByTitle('setup-b'))

    await waitFor(() => {
      const settings = lastSettings(onChange)
      expect(settings.outbox).toBeNull()
      expect(settings.native?.setupId).toBe('setup-b')
    })
    expect(screen.getByText(/setup-a went away/)).toBeTruthy()
  })

  it('reports an empty pair when no setups exist, and points at Connect setup', async () => {
    mockedGetSetups.mockResolvedValueOnce([])
    const onChange = renderTargets()

    await waitFor(() => expect(screen.getByText(/No PeeGeeQ setup connected/i)).toBeTruthy())
    expect(lastSettings(onChange)).toEqual({ native: null, outbox: null })
  })

  it('lets a row point at a DIFFERENT setup from the other row', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a', 'setup-b'])
    mockedListQueueDetails.mockImplementation((setupId: string) =>
      setupId === 'setup-a'
        ? Promise.resolve(MIXED_QUEUES)
        : Promise.resolve([{ name: 'obx', implementationType: 'outbox' as const }])
    )
    const onChange = renderTargets()
    await waitFor(() => expect(lastSettings(onChange).outbox?.queueName).toBe('events'))

    // Change only the outbox row's setup. The dropdown opens from the combobox
    // itself, not the wrapper div (the TargetSelector.test.tsx approach).
    const outboxRow = screen.getByTestId('compare-row-outbox')
    await userEvent.click(within(outboxRow).getAllByRole('combobox')[0])
    await userEvent.click(await screen.findByTitle('setup-b'))

    await waitFor(() => {
      const settings = lastSettings(onChange)
      expect(settings.outbox).toEqual({
        setupId: 'setup-b',
        queueName: 'obx',
        implementationType: 'outbox',
      })
      // The native row is untouched — the rows are independent.
      expect(settings.native?.setupId).toBe('setup-a')
      expect(settings.native?.queueName).toBe('orders')
    })
  })

  it('loads queue details once per distinct setup, not once per row', async () => {
    mockedGetSetups.mockResolvedValueOnce(['setup-a'])
    mockedListQueueDetails.mockResolvedValue(MIXED_QUEUES)
    renderTargets()

    await waitFor(() => expect(screen.getByTestId('compare-row-native')).toBeTruthy())
    // Both rows start on setup-a; fetching its queues twice is a duplicate
    // round trip for one answer.
    expect(mockedListQueueDetails).toHaveBeenCalledTimes(1)
    expect(mockedListQueueDetails).toHaveBeenCalledWith('setup-a')
  })
})
