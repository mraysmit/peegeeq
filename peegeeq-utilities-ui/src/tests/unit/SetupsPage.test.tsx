/**
 * Tests for SetupsPage component.
 *
 * Covers: rendering, loading state, empty state, setup list display,
 * Create Setup navigation, delete flow, and detail modal.
 *
 * Network calls are intercepted via vi.mock on setupService.
 * Navigation is verified via a mocked useNavigate.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { ConfigProvider } from 'antd'
import SetupsPage from '../../pages/SetupsPage'

// ── Mocks ─────────────────────────────────────────────────────────────────────

vi.mock('../../services/setupService', () => ({
  getSetups: vi.fn(),
  getSetupDetails: vi.fn(),
  deleteSetup: vi.fn(),
}))

const mockNavigate = vi.fn()
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => mockNavigate }
})

import { getSetups, getSetupDetails, deleteSetup } from '../../services/setupService'
const mockedGetSetups = vi.mocked(getSetups)
const mockedGetSetupDetails = vi.mocked(getSetupDetails)
const mockedDeleteSetup = vi.mocked(deleteSetup)

// ── Helpers ───────────────────────────────────────────────────────────────────

function renderPage() {
  return render(
    <MemoryRouter>
      <ConfigProvider>
        <SetupsPage />
      </ConfigProvider>
    </MemoryRouter>
  )
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('SetupsPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  // ── Heading / structure ──────────────────────────────────────────────────────

  it('shows "Setups" page heading', async () => {
    mockedGetSetups.mockResolvedValueOnce([])
    renderPage()
    expect(screen.getByRole('heading', { name: /^Setups$/i })).toBeTruthy()
  })

  it('renders the "Database Setups" card title', async () => {
    mockedGetSetups.mockResolvedValueOnce([])
    renderPage()
    await waitFor(() => expect(screen.getByText('Database Setups')).toBeTruthy())
  })

  // ── Empty state ──────────────────────────────────────────────────────────────

  it('shows "No setups found" alert when there are no setups', async () => {
    mockedGetSetups.mockResolvedValueOnce([])
    renderPage()
    await waitFor(() => expect(screen.getByText(/No setups found/i)).toBeTruthy())
  })

  // ── Create Setup navigation ──────────────────────────────────────────────────

  it('Create Setup button navigates to /generator/setup/new', async () => {
    mockedGetSetups.mockResolvedValueOnce([])
    renderPage()
    await waitFor(() => expect(screen.getByTestId('create-setup-button')).toBeTruthy())
    await userEvent.click(screen.getByTestId('create-setup-button'))
    expect(mockNavigate).toHaveBeenCalledWith('/generator/setup/new')
  })

  // ── Setup list ───────────────────────────────────────────────────────────────

  it('displays setup rows in the table when setups exist', async () => {
    mockedGetSetups.mockResolvedValueOnce(['prod-setup', 'staging-setup'])
    mockedGetSetupDetails
      .mockResolvedValueOnce({ queueFactories: ['q1', 'q2'], eventStores: [], status: 'active' })
      .mockResolvedValueOnce({ queueFactories: [], eventStores: ['es1'], status: 'active' })

    renderPage()
    await waitFor(() => {
      expect(screen.getByText('prod-setup')).toBeTruthy()
      expect(screen.getByText('staging-setup')).toBeTruthy()
    })
  })

  it('does NOT show "No setups found" alert when setups are present', async () => {
    mockedGetSetups.mockResolvedValueOnce(['my-setup'])
    mockedGetSetupDetails.mockResolvedValueOnce({ queueFactories: [], eventStores: [], status: 'active' })

    renderPage()
    await waitFor(() => expect(screen.getByText('my-setup')).toBeTruthy())
    expect(screen.queryByText(/No setups found/i)).toBeNull()
  })

  it('shows queues and event stores counts from getSetupDetails', async () => {
    mockedGetSetups.mockResolvedValueOnce(['demo'])
    mockedGetSetupDetails.mockResolvedValueOnce({
      queueFactories: ['q1', 'q2', 'q3'],
      eventStores: ['es1'],
      status: 'active',
    })

    renderPage()
    await waitFor(() => expect(screen.getByText('demo')).toBeTruthy())
    // Ant Design Table renders cell content — queues=3, eventStores=1 appear as Tag text.
    // Use getAllByText because pagination also renders page numbers as separate elements.
    expect(screen.getAllByText('3').length).toBeGreaterThan(0)
    expect(screen.getAllByText('1').length).toBeGreaterThan(0)
  })

  // ── Detail navigation ────────────────────────────────────────────────────────

  it('navigates to the setup detail page when Details button is clicked', async () => {
    mockedGetSetups.mockResolvedValueOnce(['alpha'])
    mockedGetSetupDetails.mockResolvedValueOnce({ queueFactories: ['q1'], eventStores: [], status: 'active' })

    renderPage()
    await waitFor(() => expect(screen.getByTestId('view-details-alpha')).toBeTruthy())
    await userEvent.click(screen.getByTestId('view-details-alpha'))

    expect(mockNavigate).toHaveBeenCalledWith('/setups/alpha')
  })

  // ── Refresh ──────────────────────────────────────────────────────────────────

  it('reloads setups when the Refresh button is clicked', async () => {
    mockedGetSetups
      .mockResolvedValueOnce(['alpha'])
      .mockResolvedValueOnce(['alpha'])
    mockedGetSetupDetails
      .mockResolvedValueOnce({ queueFactories: [], eventStores: [], status: 'active' })
      .mockResolvedValueOnce({ queueFactories: [], eventStores: [], status: 'active' })

    renderPage()
    await waitFor(() => expect(screen.getByTestId('refresh-setups-button')).toBeTruthy())
    await userEvent.click(screen.getByTestId('refresh-setups-button'))

    await waitFor(() => expect(mockedGetSetups).toHaveBeenCalledTimes(2))
  })

  // ── Delete flow ──────────────────────────────────────────────────────────────

  it('calls deleteSetup and reloads when delete is confirmed', async () => {
    mockedGetSetups
      .mockResolvedValueOnce(['to-delete'])
      .mockResolvedValueOnce([]) // after reload
    mockedGetSetupDetails.mockResolvedValueOnce({ queueFactories: [], eventStores: [], status: 'active' })
    mockedDeleteSetup.mockResolvedValueOnce(undefined)

    renderPage()
    await waitFor(() => expect(screen.getByTestId('delete-to-delete')).toBeTruthy())
    await userEvent.click(screen.getByTestId('delete-to-delete'))

    // Popconfirm shows "Delete" confirmation button
    const confirmBtn = await screen.findByRole('button', { name: /^Delete$/ })
    await userEvent.click(confirmBtn)

    await waitFor(() => expect(mockedDeleteSetup).toHaveBeenCalledWith('to-delete'))
    await waitFor(() => expect(mockedGetSetups).toHaveBeenCalledTimes(2))
  })

  // ── Load error ───────────────────────────────────────────────────────────────

  it('shows error alert when getSetups fails', async () => {
    mockedGetSetups.mockRejectedValueOnce(new Error('network error'))
    renderPage()
    await waitFor(() => expect(screen.getByText(/Failed to load setups/i)).toBeTruthy())
  })
})
