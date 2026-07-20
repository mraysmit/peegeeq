/**
 * Tests for SetupsPage — the per-row details-failure path.
 *
 * A setup whose getSetupDetails call fails must render honestly: status
 * UNAVAILABLE and no fabricated counts — never a healthy-looking
 * "ACTIVE / 0 queues" row (no-error-swallowing rule).
 *
 * Network calls (getSetups, getSetupDetails, detachSetup) are intercepted via
 * vi.mock at the service boundary — the same pattern as TargetSelector.test.tsx.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import { ConfigProvider } from 'antd'
import { MemoryRouter } from 'react-router-dom'
import SetupsPage from '../../pages/SetupsPage'
import type { SetupDetails } from '../../types/setup'

vi.mock('../../services/setupService', () => ({
  getSetups: vi.fn(),
  getSetupDetails: vi.fn(),
  detachSetup: vi.fn(),
}))

const mockNavigate = vi.fn()
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => mockNavigate }
})

import { getSetups, getSetupDetails } from '../../services/setupService'
const mockedGetSetups = vi.mocked(getSetups)
const mockedGetSetupDetails = vi.mocked(getSetupDetails)

function renderPage() {
  return render(
    <MemoryRouter>
      <ConfigProvider>
        <SetupsPage />
      </ConfigProvider>
    </MemoryRouter>
  )
}

const HEALTHY_DETAILS: SetupDetails = {
  setupId: 'good-setup',
  status: 'active',
  queueFactories: ['orders', 'events'],
  eventStores: ['audit'],
} as unknown as SetupDetails

describe('SetupsPage — per-row details failure', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders a healthy setup row from its details', async () => {
    mockedGetSetups.mockResolvedValueOnce(['good-setup'])
    mockedGetSetupDetails.mockResolvedValueOnce(HEALTHY_DETAILS)

    renderPage()

    await waitFor(() => expect(screen.getByText('good-setup')).toBeTruthy())
    const row = screen.getByText('good-setup').closest('tr') as HTMLElement
    expect(within(row).getByText('ACTIVE')).toBeTruthy()
    expect(within(row).getByText('2')).toBeTruthy() // queues
    expect(within(row).getByText('1')).toBeTruthy() // event stores
  })

  it('renders a details-failure row as UNAVAILABLE with no fabricated counts', async () => {
    mockedGetSetups.mockResolvedValueOnce(['good-setup', 'broken-setup'])
    mockedGetSetupDetails.mockImplementation((id: string) =>
      id === 'good-setup'
        ? Promise.resolve(HEALTHY_DETAILS)
        : Promise.reject(new Error('details fetch failed'))
    )

    renderPage()

    await waitFor(() => expect(screen.getByText('broken-setup')).toBeTruthy())
    const brokenRow = screen.getByText('broken-setup').closest('tr') as HTMLElement
    const goodRow = screen.getByText('good-setup').closest('tr') as HTMLElement

    // The failed row must NOT masquerade as a healthy empty setup.
    expect(within(brokenRow).getByText('UNAVAILABLE')).toBeTruthy()
    expect(within(brokenRow).queryByText('ACTIVE')).toBeNull()
    // Its counts are unknown, not zero: two em-dash cells (queues + event stores).
    expect(within(brokenRow).getAllByText('—').length).toBe(2)
    expect(within(brokenRow).queryByText('0')).toBeNull()
    // The healthy row is unaffected.
    expect(within(goodRow).getByText('ACTIVE')).toBeTruthy()
  })
})
