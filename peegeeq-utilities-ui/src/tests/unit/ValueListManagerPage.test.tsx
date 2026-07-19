/**
 * Tests for ValueListManagerPage (Phase D — feature §6.3, TD §8).
 *
 * Contract under test (written FIRST, before the page):
 * - table lists stored value lists: name, values preview, count, actions
 * - Edit opens the panel (one value per line); the count badge updates live;
 *   blank lines are ignored and whitespace trimmed on save
 * - rename renames the stored key (old gone, new present, values kept);
 *   renaming onto an existing name is rejected with a visible error
 * - New List creates via the panel; empty names are rejected
 * - Cancel discards edits
 * - Export downloads the row's value array
 * - Delete confirms; when a saved template references the list (payload OR
 *   header values — §5.3), the confirmation names the referencing template(s)
 * - Import: new name → added; name collision → Overwrite / Merge / Cancel
 *   (overwrite replaces, merge de-duplicates, cancel does nothing); invalid
 *   files surface named errors; numeric coercion surfaces a warning
 *
 * No mocks: real valueListStore + templateStore + localStorage + FileReader.
 * URL.createObjectURL is polyfilled for Export (jsdom).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import ValueListManagerPage from '../../pages/value-lists/ValueListManagerPage'
import { useValueListStore } from '../../stores/valueListStore'
import { useTemplateStore } from '../../stores/templateStore'
import { loadAll } from '../../services/valueListService'
import type { MessageTemplate, ValueList } from '../../types/generator'

function makeList(name: string, values: string[]): ValueList {
  const now = new Date().toISOString()
  return { name, values, createdAt: now, updatedAt: now }
}

function makeTemplate(overrides: Partial<MessageTemplate> = {}): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: 't1', name: 'Order created', messageType: 'x', payloadSchema: '{}',
    headers: {}, priority: 5, delaySeconds: 0, createdAt: now, updatedAt: now,
    ...overrides,
  }
}

function renderPage() {
  return render(
    <ConfigProvider>
      <ValueListManagerPage />
    </ConfigProvider>
  )
}

function importFile(content: string, name = 'first_names.json'): File {
  return new File([content], name, { type: 'application/json' })
}

describe('ValueListManagerPage', () => {
  beforeEach(() => {
    localStorage.clear()
    useValueListStore.setState({ lists: [], selected: null })
    useTemplateStore.setState({ templates: [], selected: null })
    document.querySelectorAll('.ant-modal-root, .ant-popover').forEach((n) => n.remove())
  })

  // ── Table ─────────────────────────────────────────────────────────────────

  it('lists stored value lists with name, preview, and count', () => {
    useValueListStore.getState().add(makeList('countries', ['GB', 'US', 'DE', 'FR']))
    renderPage()
    expect(screen.getByText('countries')).toBeTruthy()
    expect(screen.getByTestId('list-preview-countries').textContent).toContain('GB')
    expect(screen.getByTestId('list-count-countries').textContent).toContain('4')
  })

  // ── Edit panel ────────────────────────────────────────────────────────────

  it('Edit opens the panel with one value per line and a live count', async () => {
    useValueListStore.getState().add(makeList('names', ['Mark', 'Dave']))
    renderPage()
    await userEvent.click(screen.getByTestId('list-edit-names'))

    const values = screen.getByLabelText(/Values \(one per line\)/i) as HTMLTextAreaElement
    expect(values.value).toBe('Mark\nDave')
    expect(screen.getByTestId('value-count').textContent).toContain('2')

    await userEvent.type(values, '\nJanet\n\n  ')
    // Live count ignores blank lines.
    await waitFor(() => {
      expect(screen.getByTestId('value-count').textContent).toContain('3')
    })
  })

  it('Save persists trimmed values with blank lines dropped', async () => {
    useValueListStore.getState().add(makeList('names', ['Mark']))
    renderPage()
    await userEvent.click(screen.getByTestId('list-edit-names'))
    const values = screen.getByLabelText(/Values \(one per line\)/i)
    await userEvent.clear(values)
    await userEvent.type(values, '  Mark  \n\nDave\n   ')
    await userEvent.click(screen.getByRole('button', { name: /^Save$/i }))

    await waitFor(() => {
      expect(useValueListStore.getState().lists.find((l) => l.name === 'names')!.values).toEqual([
        'Mark',
        'Dave',
      ])
    })
    expect(loadAll()['names']).toEqual(['Mark', 'Dave'])
  })

  it('rename renames the stored key and keeps the values', async () => {
    useValueListStore.getState().add(makeList('old_name', ['a', 'b']))
    renderPage()
    await userEvent.click(screen.getByTestId('list-edit-old_name'))
    const name = screen.getByLabelText(/List name/i)
    await userEvent.clear(name)
    await userEvent.type(name, 'new_name')
    await userEvent.click(screen.getByRole('button', { name: /^Save$/i }))

    await waitFor(() => {
      const stored = loadAll()
      expect(stored['new_name']).toEqual(['a', 'b'])
      expect(stored['old_name']).toBeUndefined()
    })
  })

  it('rejects a rename onto an existing list name', async () => {
    useValueListStore.getState().add(makeList('one', ['a']))
    useValueListStore.getState().add(makeList('two', ['b']))
    renderPage()
    await userEvent.click(screen.getByTestId('list-edit-one'))
    const name = screen.getByLabelText(/List name/i)
    await userEvent.clear(name)
    await userEvent.type(name, 'two')
    await userEvent.click(screen.getByRole('button', { name: /^Save$/i }))

    await waitFor(() => {
      expect(screen.getByTestId('panel-error').textContent).toMatch(/already exists/i)
    })
    expect(loadAll()['one']).toEqual(['a'])
    expect(loadAll()['two']).toEqual(['b'])
  })

  it('Cancel discards edits', async () => {
    useValueListStore.getState().add(makeList('names', ['Mark']))
    renderPage()
    await userEvent.click(screen.getByTestId('list-edit-names'))
    const values = screen.getByLabelText(/Values \(one per line\)/i)
    await userEvent.clear(values)
    await userEvent.type(values, 'Changed')
    await userEvent.click(screen.getByRole('button', { name: /^Cancel$/i }))

    expect(useValueListStore.getState().lists.find((l) => l.name === 'names')!.values).toEqual(['Mark'])
    expect(screen.queryByLabelText(/Values \(one per line\)/i)).toBeNull()
  })

  // ── New List ──────────────────────────────────────────────────────────────

  it('New List creates a list through the panel; an empty name is rejected', async () => {
    renderPage()
    await userEvent.click(screen.getByRole('button', { name: /New List/i }))

    // Empty name rejected.
    await userEvent.click(screen.getByRole('button', { name: /^Save$/i }))
    await waitFor(() => {
      expect(screen.getByTestId('panel-error').textContent).toMatch(/name/i)
    })

    await userEvent.type(screen.getByLabelText(/List name/i), 'fresh')
    await userEvent.type(screen.getByLabelText(/Values \(one per line\)/i), 'x\ny')
    await userEvent.click(screen.getByRole('button', { name: /^Save$/i }))

    await waitFor(() => {
      expect(loadAll()['fresh']).toEqual(['x', 'y'])
    })
  })

  // ── Export ────────────────────────────────────────────────────────────────

  it('Export downloads the value array', async () => {
    const createObjectURL = vi.fn(() => 'blob:fake')
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() })
    useValueListStore.getState().add(makeList('countries', ['GB', 'US']))
    renderPage()

    await userEvent.click(screen.getByTestId('list-export-countries'))
    expect(createObjectURL).toHaveBeenCalledOnce()
  })

  // ── Delete ────────────────────────────────────────────────────────────────

  it('Delete removes the list after confirmation', async () => {
    useValueListStore.getState().add(makeList('gone', ['a']))
    renderPage()
    await userEvent.click(screen.getByTestId('list-delete-gone'))
    const deleteButtons = await screen.findAllByRole('button', { name: /^Delete$/i })
    await userEvent.click(deleteButtons[deleteButtons.length - 1])

    await waitFor(() => {
      expect(useValueListStore.getState().lists).toHaveLength(0)
    })
    expect(loadAll()['gone']).toBeUndefined()
  })

  it('Delete warns with the referencing template names (payload and header references)', async () => {
    useValueListStore.getState().add(makeList('names', ['Mark']))
    useTemplateStore.getState().add(
      makeTemplate({ id: 'tp', name: 'Payload user', payloadSchema: '{"n":"{{list:names}}"}' })
    )
    useTemplateStore.getState().add(
      makeTemplate({ id: 'th', name: 'Header user', headers: { who: '{{list:names}}' } })
    )
    renderPage()
    await userEvent.click(screen.getByTestId('list-delete-names'))

    const warning = await screen.findByTestId('delete-references-warning')
    expect(warning.textContent).toContain('Payload user')
    expect(warning.textContent).toContain('Header user')
  })

  // ── Import ────────────────────────────────────────────────────────────────

  it('Import adds a new list named after the file', async () => {
    renderPage()
    await userEvent.upload(
      screen.getByTestId('value-list-import-input'),
      importFile('["Mark","Dave"]', 'first_names.json')
    )
    await waitFor(() => {
      expect(loadAll()['first_names']).toEqual(['Mark', 'Dave'])
    })
  })

  it('Import collision offers Overwrite / Merge / Cancel with correct semantics', async () => {
    useValueListStore.getState().add(makeList('first_names', ['Mark', 'Janet']))
    renderPage()

    // Merge de-duplicates.
    await userEvent.upload(
      screen.getByTestId('value-list-import-input'),
      importFile('["Mark","Dave"]', 'first_names.json')
    )
    await screen.findByTestId('import-collision-modal')
    await userEvent.click(screen.getByRole('button', { name: /^Merge$/i }))
    await waitFor(() => {
      expect(loadAll()['first_names']).toEqual(['Mark', 'Janet', 'Dave'])
    })

    // Overwrite replaces.
    await userEvent.upload(
      screen.getByTestId('value-list-import-input'),
      importFile('["Solo"]', 'first_names.json')
    )
    await screen.findByTestId('import-collision-modal')
    await userEvent.click(screen.getByRole('button', { name: /^Overwrite$/i }))
    await waitFor(() => {
      expect(loadAll()['first_names']).toEqual(['Solo'])
    })

    // Cancel changes nothing.
    await userEvent.upload(
      screen.getByTestId('value-list-import-input'),
      importFile('["Ignored"]', 'first_names.json')
    )
    await screen.findByTestId('import-collision-modal')
    await userEvent.click(screen.getByRole('button', { name: /^Cancel$/i }))
    await waitFor(() => {
      expect(loadAll()['first_names']).toEqual(['Solo'])
    })
  })

  it('Import surfaces named errors for invalid files and a warning for coerced numbers', async () => {
    renderPage()
    await userEvent.upload(screen.getByTestId('value-list-import-input'), importFile('not json'))
    await waitFor(() => {
      expect(screen.getByText(/not valid JSON/i)).toBeTruthy()
    })

    await userEvent.upload(
      screen.getByTestId('value-list-import-input'),
      importFile('[1,2,"three"]', 'nums.json')
    )
    await waitFor(() => {
      expect(screen.getByText(/coerced 2 numeric/i)).toBeTruthy()
    })
    expect(loadAll()['nums']).toEqual(['1', '2', 'three'])
  })
})
