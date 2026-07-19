/**
 * Tests for TemplateManagerPage (Phase C — feature §6.2, TD §8).
 *
 * Contract under test (written FIRST, before the page):
 * - table lists stored templates: Name, Message Type, truncated Description,
 *   relative Updated time
 * - Name link (and the Edit action) selects the template in the templateStore
 *   and navigates to /generator (real routing, no mocks)
 * - Duplicate adds a "(copy)"; Delete removes after confirmation; Export
 *   downloads the row's template
 * - Import (.json): valid new templates appended; duplicate IDs rejected with
 *   a NAMED warning (no silent overwrite); invalid JSON / schema-invalid
 *   entries surface named errors
 *
 * No mocks: real templateStore + localStorage + real templateService import
 * path (FileReader). URL.createObjectURL is polyfilled for Export (jsdom).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import TemplateManagerPage from '../../pages/templates/TemplateManagerPage'
import { useTemplateStore } from '../../stores/templateStore'
import { loadAll } from '../../services/templateService'
import type { MessageTemplate } from '../../types/generator'

function makeTemplate(overrides: Partial<MessageTemplate> = {}): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: 'tpl-1',
    name: 'Order created',
    description: 'Creates an order event',
    messageType: 'order.created',
    payloadSchema: '{"id":"{{messageId}}"}',
    headers: {},
    priority: 5,
    delaySeconds: 0,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  }
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/generator/templates']}>
      <ConfigProvider>
        <Routes>
          <Route path="/generator/templates" element={<TemplateManagerPage />} />
          <Route path="/generator" element={<div data-testid="generator-route" />} />
        </Routes>
      </ConfigProvider>
    </MemoryRouter>
  )
}

function importFile(content: string, name = 'import.json'): File {
  return new File([content], name, { type: 'application/json' })
}

describe('TemplateManagerPage', () => {
  beforeEach(() => {
    localStorage.clear()
    useTemplateStore.setState({ templates: [], selected: null })
    document.querySelectorAll('.ant-modal-root, .ant-popover').forEach((n) => n.remove())
  })

  it('lists stored templates with name, message type, and relative updated time', () => {
    useTemplateStore.getState().add(makeTemplate())
    renderPage()
    expect(screen.getByText('Order created')).toBeTruthy()
    expect(screen.getByText('order.created')).toBeTruthy()
    expect(screen.getByTestId('template-updated-tpl-1').textContent).toMatch(/ago|few seconds/i)
  })

  it('truncates long descriptions at 80 characters', () => {
    const longDescription = 'x'.repeat(120)
    useTemplateStore.getState().add(makeTemplate({ description: longDescription }))
    renderPage()
    const cell = screen.getByTestId('template-description-tpl-1')
    expect(cell.textContent!.length).toBeLessThanOrEqual(81) // 80 + ellipsis
    expect(cell.textContent).toContain('…')
  })

  it('Name link selects the template and navigates to the generator', async () => {
    useTemplateStore.getState().add(makeTemplate())
    renderPage()
    await userEvent.click(screen.getByRole('link', { name: 'Order created' }))
    await waitFor(() => {
      expect(screen.getByTestId('generator-route')).toBeTruthy()
    })
    expect(useTemplateStore.getState().selected?.id).toBe('tpl-1')
  })

  it('Edit action selects the template and navigates to the generator', async () => {
    useTemplateStore.getState().add(makeTemplate())
    renderPage()
    await userEvent.click(screen.getByTestId('template-edit-tpl-1'))
    await waitFor(() => {
      expect(screen.getByTestId('generator-route')).toBeTruthy()
    })
    expect(useTemplateStore.getState().selected?.id).toBe('tpl-1')
  })

  it('Duplicate adds a "(copy)" row', async () => {
    useTemplateStore.getState().add(makeTemplate())
    renderPage()
    await userEvent.click(screen.getByTestId('template-duplicate-tpl-1'))
    await waitFor(() => {
      expect(screen.getByText('Order created (copy)')).toBeTruthy()
    })
    expect(useTemplateStore.getState().templates).toHaveLength(2)
  })

  it('Delete removes the template after confirmation and persists', async () => {
    useTemplateStore.getState().add(makeTemplate())
    renderPage()
    await userEvent.click(screen.getByTestId('template-delete-tpl-1'))
    // The row icon and the Popconfirm OK are both named "Delete" — take the confirm
    // button (the one without the row testid).
    const deleteButtons = await screen.findAllByRole('button', { name: /^Delete$/i })
    const confirmButton = deleteButtons.find((b) => !b.hasAttribute('data-testid'))!
    await userEvent.click(confirmButton)
    await waitFor(() => {
      expect(useTemplateStore.getState().templates).toHaveLength(0)
    })
    expect(loadAll()).toHaveLength(0)
  })

  it('Export downloads the row template as JSON', async () => {
    const createObjectURL = vi.fn(() => 'blob:fake')
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() })
    useTemplateStore.getState().add(makeTemplate())
    renderPage()

    await userEvent.click(screen.getByTestId('template-export-tpl-1'))
    expect(createObjectURL).toHaveBeenCalledOnce()
  })

  it('New Template selects nothing and navigates to the generator', async () => {
    useTemplateStore.getState().add(makeTemplate())
    useTemplateStore.getState().select('tpl-1')
    renderPage()
    await userEvent.click(screen.getByRole('button', { name: /New Template/i }))
    await waitFor(() => {
      expect(screen.getByTestId('generator-route')).toBeTruthy()
    })
    // No pre-selected template: the generator opens with a blank working copy.
    expect(useTemplateStore.getState().selected).toBeNull()
  })

  it('Import appends valid new templates', async () => {
    renderPage()
    const incoming = [makeTemplate({ id: 'tpl-new', name: 'Imported' })]
    await userEvent.upload(screen.getByTestId('template-import-input'), importFile(JSON.stringify(incoming)))

    await waitFor(() => {
      expect(useTemplateStore.getState().templates.map((t) => t.id)).toContain('tpl-new')
    })
    expect(screen.getByText('Imported')).toBeTruthy()
    expect(loadAll().map((t) => t.id)).toContain('tpl-new')
  })

  it('Import rejects duplicate IDs with a named warning and no overwrite', async () => {
    useTemplateStore.getState().add(makeTemplate({ id: 'tpl-1', name: 'Original', messageType: 'v1' }))
    renderPage()
    const incoming = [makeTemplate({ id: 'tpl-1', name: 'Clobber attempt', messageType: 'v2' })]
    await userEvent.upload(screen.getByTestId('template-import-input'), importFile(JSON.stringify(incoming)))

    await waitFor(() => {
      // Named warning surfaces the skipped ID.
      expect(screen.getByText(/tpl-1/)).toBeTruthy()
    })
    const stored = useTemplateStore.getState().templates.find((t) => t.id === 'tpl-1')!
    expect(stored.messageType).toBe('v1') // untouched
    expect(useTemplateStore.getState().templates).toHaveLength(1)
  })

  it('Import surfaces named errors for schema-invalid entries and bad JSON', async () => {
    renderPage()
    await userEvent.upload(
      screen.getByTestId('template-import-input'),
      importFile(JSON.stringify([{ id: 'x', name: 'Broken entry' }]))
    )
    await waitFor(() => {
      expect(screen.getByText(/Broken entry/)).toBeTruthy()
    })

    await userEvent.upload(screen.getByTestId('template-import-input'), importFile('not json at all'))
    await waitFor(() => {
      expect(screen.getByText(/not valid JSON/i)).toBeTruthy()
    })
    expect(useTemplateStore.getState().templates).toHaveLength(0)
  })
})
