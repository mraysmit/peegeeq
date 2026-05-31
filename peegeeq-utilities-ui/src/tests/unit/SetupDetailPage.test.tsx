/**
 * Tests for SetupDetailPage component.
 *
 * Covers: loading state, detail rendering (status, queue names, event-store names),
 * empty states, 404 handling, refresh, and delete navigation.
 *
 * Network calls are intercepted via vi.mock on setupService.
 * Routing params and navigation are mocked via react-router-dom.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { ConfigProvider } from 'antd'
import SetupDetailPage from '../../pages/SetupDetailPage'

// ── Mocks ─────────────────────────────────────────────────────────────────────

vi.mock('../../services/setupService', () => ({
  getSetupDetails: vi.fn(),
  deleteSetup: vi.fn(),
}))

const mockNavigate = vi.fn()
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return {
    ...actual,
    useNavigate: () => mockNavigate,
    useParams: () => ({ setupId: 'my-setup' }),
  }
})

import { getSetupDetails, deleteSetup } from '../../services/setupService'
const mockedGetSetupDetails = vi.mocked(getSetupDetails)
const mockedDeleteSetup = vi.mocked(deleteSetup)

// ── Helpers ───────────────────────────────────────────────────────────────────

function renderPage() {
  return render(
    <MemoryRouter>
      <ConfigProvider>
        <SetupDetailPage />
      </ConfigProvider>
    </MemoryRouter>
  )
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('SetupDetailPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('shows the setup ID heading', async () => {
    mockedGetSetupDetails.mockResolvedValueOnce({ queueFactories: [], eventStores: [], status: 'active' })
    renderPage()
    await waitFor(() => expect(screen.getByRole('heading', { name: /my-setup/i })).toBeTruthy())
  })

  it('renders queue names from getSetupDetails', async () => {
    mockedGetSetupDetails.mockResolvedValueOnce({
      queueFactories: ['orders', 'payments'],
      eventStores: [],
      status: 'active',
    })
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('orders')).toBeTruthy()
      expect(screen.getByText('payments')).toBeTruthy()
    })
  })

  it('renders event-store names from getSetupDetails', async () => {
    mockedGetSetupDetails.mockResolvedValueOnce({
      queueFactories: [],
      eventStores: ['audit-store'],
      status: 'active',
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('audit-store')).toBeTruthy())
  })

  it('shows empty state when the setup has no queues', async () => {
    mockedGetSetupDetails.mockResolvedValueOnce({ queueFactories: [], eventStores: [], status: 'active' })
    renderPage()
    await waitFor(() => expect(screen.getByText(/No queues in this setup/i)).toBeTruthy())
  })

  it('shows empty state when the setup has no event stores', async () => {
    mockedGetSetupDetails.mockResolvedValueOnce({ queueFactories: ['q1'], eventStores: [], status: 'active' })
    renderPage()
    await waitFor(() => expect(screen.getByText(/No event stores in this setup/i)).toBeTruthy())
  })

  it('shows the status tag', async () => {
    mockedGetSetupDetails.mockResolvedValueOnce({ queueFactories: [], eventStores: [], status: 'active' })
    renderPage()
    await waitFor(() => expect(screen.getByText('ACTIVE')).toBeTruthy())
  })

  it('shows a not-found error on 404', async () => {
    mockedGetSetupDetails.mockRejectedValueOnce({ response: { status: 404 } })
    renderPage()
    await waitFor(() => expect(screen.getByText(/not found/i)).toBeTruthy())
  })

  it('shows a generic error on non-404 failure', async () => {
    mockedGetSetupDetails.mockRejectedValueOnce(new Error('network error'))
    renderPage()
    await waitFor(() => expect(screen.getByText(/Failed to load setup/i)).toBeTruthy())
  })

  it('reloads details when Refresh is clicked', async () => {
    mockedGetSetupDetails
      .mockResolvedValueOnce({ queueFactories: [], eventStores: [], status: 'active' })
      .mockResolvedValueOnce({ queueFactories: [], eventStores: [], status: 'active' })
    renderPage()
    await waitFor(() => expect(screen.getByTestId('refresh-detail-button')).toBeTruthy())
    await userEvent.click(screen.getByTestId('refresh-detail-button'))
    await waitFor(() => expect(mockedGetSetupDetails).toHaveBeenCalledTimes(2))
  })

  it('navigates back to /setups when Back is clicked', async () => {
    mockedGetSetupDetails.mockResolvedValueOnce({ queueFactories: [], eventStores: [], status: 'active' })
    renderPage()
    await waitFor(() => expect(screen.getByTestId('back-button')).toBeTruthy())
    await userEvent.click(screen.getByTestId('back-button'))
    expect(mockNavigate).toHaveBeenCalledWith('/setups')
  })

  it('deletes the setup and navigates to /setups on confirm', async () => {
    mockedGetSetupDetails.mockResolvedValueOnce({ queueFactories: [], eventStores: [], status: 'active' })
    mockedDeleteSetup.mockResolvedValueOnce(undefined)
    renderPage()
    await waitFor(() => expect(screen.getByTestId('delete-detail-button')).toBeTruthy())
    await userEvent.click(screen.getByTestId('delete-detail-button'))

    const confirmBtn = await screen.findByRole('button', { name: /^Delete$/ })
    await userEvent.click(confirmBtn)

    await waitFor(() => expect(mockedDeleteSetup).toHaveBeenCalledWith('my-setup'))
    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/setups'))
  })
})
