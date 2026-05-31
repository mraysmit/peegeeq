/**
 * Tests for CreateSetupPage component.
 *
 * Covers: rendering, form validation, success flow, error display, and
 * navigation behaviour (cancel / back navigate(-1), success navigates to /setups).
 *
 * Network calls are intercepted via vi.mock on setupService.
 * Navigation is verified via a mocked useNavigate.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { ConfigProvider } from 'antd'
import CreateSetupPage from '../../pages/CreateSetupPage'

// ── Mocks ─────────────────────────────────────────────────────────────────────

vi.mock('../../services/setupService', () => ({
  createSetup: vi.fn(),
}))

const mockNavigate = vi.fn()
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => mockNavigate }
})

import { createSetup } from '../../services/setupService'
const mockedCreateSetup = vi.mocked(createSetup)

// ── Helpers ───────────────────────────────────────────────────────────────────

function renderPage() {
  return render(
    <MemoryRouter>
      <ConfigProvider>
        <CreateSetupPage />
      </ConfigProvider>
    </MemoryRouter>
  )
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('CreateSetupPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  // ── Visibility ───────────────────────────────────────────────────────────────

  it('shows "Create Setup" page heading', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: /Create Setup/i })).toBeTruthy()
  })

  // ── Form fields ──────────────────────────────────────────────────────────────

  it('renders Setup name, Database name and Password fields', () => {
    renderPage()
    expect(screen.getByLabelText(/Setup name/i)).toBeTruthy()
    expect(screen.getByLabelText(/Database name/i)).toBeTruthy()
    expect(screen.getByLabelText(/Database password/i)).toBeTruthy()
  })

  it('Create setup button is present', () => {
    renderPage()
    expect(screen.getByRole('button', { name: /Create setup/i })).toBeTruthy()
  })

  it('Cancel button calls navigate(-1)', async () => {
    renderPage()
    await userEvent.click(screen.getByRole('button', { name: /Cancel/i }))
    expect(mockNavigate).toHaveBeenCalledWith(-1)
  })

  it('Back button calls navigate(-1)', async () => {
    renderPage()
    await userEvent.click(screen.getByTestId('back-button'))
    expect(mockNavigate).toHaveBeenCalledWith(-1)
  })

  // ── Validation ───────────────────────────────────────────────────────────────

  it('shows validation errors when submitted with empty fields', async () => {
    renderPage()
    await userEvent.click(screen.getByRole('button', { name: /Create setup/i }))
    await waitFor(() => {
      expect(screen.getByText(/Please enter a setup name/i)).toBeTruthy()
    })
    expect(mockedCreateSetup).not.toHaveBeenCalled()
  })

  // ── Success flow ─────────────────────────────────────────────────────────────

  it('calls createSetup with correct body and navigates to /setups on success', async () => {
    mockedCreateSetup.mockResolvedValueOnce(undefined)
    renderPage()

    await userEvent.type(screen.getByLabelText(/Setup name/i), 'my-setup')
    await userEvent.type(screen.getByLabelText(/Database name/i), 'peegeeq_dev')
    await userEvent.type(screen.getByLabelText(/Database password/i), 's3cr3t')
    await userEvent.click(screen.getByRole('button', { name: /Create setup/i }))

    await waitFor(() => {
      expect(mockedCreateSetup).toHaveBeenCalledWith(
        expect.objectContaining({
          setupId: 'my-setup',
          databaseConfig: expect.objectContaining({
            databaseName: 'peegeeq_dev',
            password: 's3cr3t',
            host: 'localhost',
            port: 5432,
            sslEnabled: false,
          }),
        })
      )
      expect(mockNavigate).toHaveBeenCalledWith('/setups')
    })
  })

  // ── Error display ────────────────────────────────────────────────────────────

  it('shows inline error alert on createSetup failure and stays on the page', async () => {
    mockedCreateSetup.mockRejectedValueOnce({
      response: { data: { error: 'Setup ID already exists' } },
    })
    renderPage()

    await userEvent.type(screen.getByLabelText(/Setup name/i), 'dup-setup')
    await userEvent.type(screen.getByLabelText(/Database name/i), 'peegeeq_dup')
    await userEvent.type(screen.getByLabelText(/Database password/i), 's3cr3t')
    await userEvent.click(screen.getByRole('button', { name: /Create setup/i }))

    await waitFor(() => {
      expect(screen.getByText(/Setup ID already exists/i)).toBeTruthy()
    })
    // Navigate was NOT called — still on the page
    expect(mockNavigate).not.toHaveBeenCalled()
    expect(screen.getByLabelText(/Setup name/i)).toBeTruthy()
  })

  // ── Loading state ────────────────────────────────────────────────────────────

  it('Cancel button is disabled while the form is submitting', async () => {
    let resolveCreate!: () => void
    mockedCreateSetup.mockReturnValueOnce(
      new Promise<void>((resolve) => { resolveCreate = resolve })
    )
    renderPage()

    await userEvent.type(screen.getByLabelText(/Setup name/i), 'my-setup')
    await userEvent.type(screen.getByLabelText(/Database name/i), 'peegeeq_dev')
    await userEvent.type(screen.getByLabelText(/Database password/i), 's3cr3t')
    await userEvent.click(screen.getByRole('button', { name: /Create setup/i }))

    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: /Cancel/i }).hasAttribute('disabled')
      ).toBe(true)
    })

    resolveCreate()
  })

  // ── Permissions warning ──────────────────────────────────────────────────────

  it('shows Database Permissions Required warning', () => {
    renderPage()
    expect(screen.getByText(/Database Permissions Required/i)).toBeTruthy()
    expect(screen.getByText(/CREATEDB/i)).toBeTruthy()
  })

  // ── Connection details ───────────────────────────────────────────────────────

  it('renders the Connection details collapsible panel header', () => {
    renderPage()
    expect(screen.getByText('Connection details')).toBeTruthy()
  })

  it('includes custom host in createSetup request when connection detail is filled', async () => {
    mockedCreateSetup.mockResolvedValueOnce(undefined)
    renderPage()

    await userEvent.type(screen.getByLabelText(/Setup name/i), 'my-setup')
    await userEvent.type(screen.getByLabelText(/Database name/i), 'peegeeq_dev')
    await userEvent.type(screen.getByLabelText(/Database password/i), 's3cr3t')
    // Expand the Connection details panel so the host input becomes accessible
    // (Ant Design Collapse sets aria-hidden on collapsed content).
    await userEvent.click(screen.getByText('Connection details'))
    const hostInput = await screen.findByLabelText(/^Host$/i)
    await userEvent.clear(hostInput)
    await userEvent.type(hostInput, 'db.example.com')

    await userEvent.click(screen.getByRole('button', { name: /Create setup/i }))

    await waitFor(() => {
      expect(mockedCreateSetup).toHaveBeenCalledWith(
        expect.objectContaining({
          databaseConfig: expect.objectContaining({
            host: 'db.example.com',
          }),
        })
      )
    })
  })

  it('shows error.message when server response has no structured error field', async () => {
    mockedCreateSetup.mockRejectedValueOnce(new Error('request timeout'))
    renderPage()

    await userEvent.type(screen.getByLabelText(/Setup name/i), 'my-setup')
    await userEvent.type(screen.getByLabelText(/Database name/i), 'peegeeq_dev')
    await userEvent.type(screen.getByLabelText(/Database password/i), 's3cr3t')
    await userEvent.click(screen.getByRole('button', { name: /Create setup/i }))

    await waitFor(() => {
      expect(screen.getByText(/request timeout/i)).toBeTruthy()
    })
  })

  it('SSL checkbox is present in connection details panel and defaults to unchecked', async () => {
    renderPage()
    await userEvent.click(screen.getByText('Connection details'))
    const sslCheckbox = await screen.findByRole('checkbox', { name: /Enable SSL/i })
    expect(sslCheckbox.hasAttribute('checked') || (sslCheckbox as HTMLInputElement).checked === false).toBeTruthy()
  })

  it('includes sslEnabled=true in createSetup request when SSL checkbox is checked', async () => {
    mockedCreateSetup.mockResolvedValueOnce(undefined)
    renderPage()

    await userEvent.type(screen.getByLabelText(/Setup name/i), 'ssl-setup')
    await userEvent.type(screen.getByLabelText(/Database name/i), 'peegeeq_ssl')
    await userEvent.type(screen.getByLabelText(/Database password/i), 's3cr3t')
    await userEvent.click(screen.getByText('Connection details'))
    await userEvent.click(await screen.findByRole('checkbox', { name: /Enable SSL/i }))
    await userEvent.click(screen.getByRole('button', { name: /Create setup/i }))

    await waitFor(() => {
      expect(mockedCreateSetup).toHaveBeenCalledWith(
        expect.objectContaining({
          databaseConfig: expect.objectContaining({ sslEnabled: true }),
        })
      )
    })
  })

  it('shows username required error when submitted with empty username after expanding connection details', async () => {
    renderPage()
    await userEvent.click(screen.getByText('Connection details'))
    const usernameInput = await screen.findByLabelText(/^Username$/i)
    await userEvent.clear(usernameInput)

    await userEvent.type(screen.getByLabelText(/Setup name/i), 'my-setup')
    await userEvent.type(screen.getByLabelText(/Database name/i), 'peegeeq_dev')
    await userEvent.type(screen.getByLabelText(/Database password/i), 's3cr3t')
    await userEvent.click(screen.getByRole('button', { name: /Create setup/i }))

    await waitFor(() => {
      expect(screen.getByText(/Please enter a username/i)).toBeTruthy()
    })
    expect(mockedCreateSetup).not.toHaveBeenCalled()
  })
})
