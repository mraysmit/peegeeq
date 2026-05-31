/**
 * Tests for CreateQueuePage component (§6.5 of the feature design).
 *
 * Covers: rendering, route-param setupId, form validation, implementation-type
 * selection, the Advanced settings panel, the success flow (createQueue called
 * with the parsed config + navigation back to the setup detail page), error
 * display, and cancel/back navigation.
 *
 * Network calls are intercepted via vi.mock on queueService.
 * Navigation and the :setupId route param are provided via MemoryRouter + Routes.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { ConfigProvider } from 'antd'
import CreateQueuePage from '../../pages/CreateQueuePage'
import { DEFAULT_QUEUE_CONFIG } from '../../types/queue'

// ── Mocks ─────────────────────────────────────────────────────────────────────

vi.mock('../../services/queueService', () => ({
  createQueue: vi.fn(),
}))

const mockNavigate = vi.fn()
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => mockNavigate }
})

import { createQueue } from '../../services/queueService'
const mockedCreateQueue = vi.mocked(createQueue)

// ── Helpers ───────────────────────────────────────────────────────────────────

const SETUP_ID = 'my-setup'

function renderPage() {
  return render(
    <MemoryRouter initialEntries={[`/setups/${SETUP_ID}/queues/new`]}>
      <ConfigProvider>
        <Routes>
          <Route path="/setups/:setupId/queues/new" element={<CreateQueuePage />} />
        </Routes>
      </ConfigProvider>
    </MemoryRouter>
  )
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('CreateQueuePage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  // ── Visibility ───────────────────────────────────────────────────────────────

  it('shows a "Create Queue" heading that includes the setup id from the route', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: /Create Queue in my-setup/i })).toBeTruthy()
  })

  it('renders the Queue name and Implementation type fields', () => {
    renderPage()
    expect(screen.getByLabelText(/Queue name/i)).toBeTruthy()
    expect(screen.getByLabelText(/Implementation type/i)).toBeTruthy()
  })

  it('Create queue button is present', () => {
    renderPage()
    expect(screen.getByTestId('create-queue-button')).toBeTruthy()
  })

  // ── Navigation ───────────────────────────────────────────────────────────────

  it('Cancel button navigates back to the setup detail page', async () => {
    renderPage()
    await userEvent.click(screen.getByRole('button', { name: /Cancel/i }))
    expect(mockNavigate).toHaveBeenCalledWith(`/setups/${SETUP_ID}`)
  })

  it('Back button navigates back to the setup detail page', async () => {
    renderPage()
    await userEvent.click(screen.getByTestId('back-button'))
    expect(mockNavigate).toHaveBeenCalledWith(`/setups/${SETUP_ID}`)
  })

  // ── Validation ───────────────────────────────────────────────────────────────

  it('shows a validation error and does not call createQueue when name is empty', async () => {
    renderPage()
    await userEvent.click(screen.getByTestId('create-queue-button'))
    await waitFor(() => {
      expect(screen.getByText(/Please enter a queue name/i)).toBeTruthy()
    })
    expect(mockedCreateQueue).not.toHaveBeenCalled()
  })

  // ── Success flow ─────────────────────────────────────────────────────────────

  it('defaults the implementation type to native', () => {
    renderPage()
    expect(screen.getByText('native')).toBeTruthy()
  })

  it('creates a native queue with default advanced config and navigates back on success', async () => {
    mockedCreateQueue.mockResolvedValueOnce(undefined)
    renderPage()

    await userEvent.type(screen.getByLabelText(/Queue name/i), 'orders')
    await userEvent.click(screen.getByTestId('create-queue-button'))

    await waitFor(() => {
      expect(mockedCreateQueue).toHaveBeenCalledWith(SETUP_ID, {
        queueName: 'orders',
        implementationType: 'native',
        maxRetries: DEFAULT_QUEUE_CONFIG.maxRetries,
        visibilityTimeoutSeconds: DEFAULT_QUEUE_CONFIG.visibilityTimeoutSeconds,
        batchSize: DEFAULT_QUEUE_CONFIG.batchSize,
        deadLetterEnabled: DEFAULT_QUEUE_CONFIG.deadLetterEnabled,
        fifoEnabled: DEFAULT_QUEUE_CONFIG.fifoEnabled,
      })
      expect(mockNavigate).toHaveBeenCalledWith(`/setups/${SETUP_ID}`)
    })
  })

  it('creates an outbox queue when the implementation type is changed', async () => {
    mockedCreateQueue.mockResolvedValueOnce(undefined)
    renderPage()

    await userEvent.type(screen.getByLabelText(/Queue name/i), 'events')
    // Open the Select and pick "outbox".
    await userEvent.click(screen.getByLabelText(/Implementation type/i))
    const options = await screen.findAllByText('outbox')
    await userEvent.click(options[options.length - 1])
    await userEvent.click(screen.getByTestId('create-queue-button'))

    await waitFor(() => {
      expect(mockedCreateQueue).toHaveBeenCalledWith(
        SETUP_ID,
        expect.objectContaining({ queueName: 'events', implementationType: 'outbox' })
      )
    })
  })

  // ── Error display ────────────────────────────────────────────────────────────

  it('shows an inline error alert on createQueue failure and stays on the page', async () => {
    mockedCreateQueue.mockRejectedValueOnce({
      response: { data: { error: 'Queue already exists' } },
    })
    renderPage()

    await userEvent.type(screen.getByLabelText(/Queue name/i), 'orders')
    await userEvent.click(screen.getByTestId('create-queue-button'))

    await waitFor(() => {
      expect(screen.getByTestId('create-queue-error')).toBeTruthy()
      expect(screen.getByText(/Queue already exists/i)).toBeTruthy()
    })
    expect(mockNavigate).not.toHaveBeenCalled()
  })
})
