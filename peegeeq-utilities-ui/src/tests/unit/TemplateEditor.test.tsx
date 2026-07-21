/**
 * Tests for TemplateEditor (Zone C — feature §6.1, working-copy contract 2026-07-18).
 *
 * Contract under test (written FIRST, before the component):
 * - fields render from the working copy (name, message type, priority, delay, group, payload)
 * - the template dropdown lists the real templateStore's templates; selecting one replaces
 *   the working copy; a dirty working copy asks for confirmation first
 * - New replaces the working copy with a blank template (confirm when dirty)
 * - Save persists the working copy through the real store (add for a new id, update for an
 *   existing one) — Save is pure persistence, never required before a run
 * - payload validation on blur uses resolve-then-parse (§8): bare tokens are legal; a
 *   genuinely invalid template shows the parse error inline
 * - headers add/edit/remove, hard cap 20 pairs
 * - the placeholder reference panel lists every §5.3 token
 * - disabled disables every control
 *
 * No service mocks: the real templateStore + localStorage back the dropdown and Save.
 * The only stub is a URL.createObjectURL polyfill (jsdom does not implement it) for Export.
 */
import { useState } from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import TemplateEditor, { blankTemplate } from '../../pages/generator/TemplateEditor'
import { useTemplateStore } from '../../stores/templateStore'
import { loadAll } from '../../services/templateService'
import type { MessageTemplate } from '../../types/generator'

function makeTemplate(overrides: Partial<MessageTemplate> = {}): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: 'tpl-1',
    name: 'Order created',
    messageType: 'order.created',
    payloadSchema: '{"id":"{{messageId}}"}',
    headers: { source: 'generator' },
    priority: 5,
    delaySeconds: 0,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  }
}

function Harness({
  initial,
  onChangeSpy,
  disabled,
}: {
  initial: MessageTemplate
  onChangeSpy: (t: MessageTemplate) => void
  disabled?: boolean
}) {
  const [value, setValue] = useState(initial)
  return (
    <ConfigProvider>
      <TemplateEditor
        value={value}
        onChange={(next) => {
          setValue(next)
          onChangeSpy(next)
        }}
        disabled={disabled}
      />
    </ConfigProvider>
  )
}

function renderEditor(
  initial: MessageTemplate = makeTemplate(),
  onChangeSpy = vi.fn<(t: MessageTemplate) => void>(),
  disabled = false
) {
  const utils = render(<Harness initial={initial} onChangeSpy={onChangeSpy} disabled={disabled} />)
  return { ...utils, onChangeSpy }
}

async function openTemplateDropdown() {
  await userEvent.click(screen.getByLabelText(/^Template$/i))
}

describe('TemplateEditor', () => {
  beforeEach(() => {
    localStorage.clear()
    useTemplateStore.setState({ templates: [], selected: null })
    document.querySelectorAll('.ant-modal-root, .ant-select-dropdown').forEach((n) => n.remove())
  })

  // ── Rendering from the working copy ───────────────────────────────────────

  it('renders every field from the working copy', () => {
    renderEditor()
    expect((screen.getByLabelText(/^Name$/i) as HTMLInputElement).value).toBe('Order created')
    expect((screen.getByLabelText(/Message type/i) as HTMLInputElement).value).toBe('order.created')
    expect((screen.getByLabelText(/^Priority$/i) as HTMLInputElement).value).toBe('5')
    expect((screen.getByLabelText(/Delay \(seconds\)/i) as HTMLInputElement).value).toBe('0')
    expect((screen.getByLabelText(/Payload/i) as HTMLTextAreaElement).value).toBe(
      '{"id":"{{messageId}}"}'
    )
  })

  it('reports field edits through onChange', async () => {
    const { onChangeSpy } = renderEditor()
    const messageType = screen.getByLabelText(/Message type/i)
    await userEvent.clear(messageType)
    await userEvent.type(messageType, 'order.updated')
    await waitFor(() => {
      expect(onChangeSpy.mock.calls.at(-1)![0].messageType).toBe('order.updated')
    })
  })

  // ── Template dropdown + working-copy replacement ──────────────────────────

  it('lists stored templates and selecting one replaces the working copy', async () => {
    const stored = makeTemplate({ id: 'tpl-2', name: 'Payment made', messageType: 'payment.made' })
    useTemplateStore.getState().add(stored)
    // Working copy identical to a stored template → not dirty → no confirm.
    useTemplateStore.getState().add(makeTemplate())
    const { onChangeSpy } = renderEditor(makeTemplate())

    await openTemplateDropdown()
    await userEvent.click(await screen.findByTitle('Payment made'))

    await waitFor(() => {
      expect(onChangeSpy.mock.calls.at(-1)![0].id).toBe('tpl-2')
    })
    expect((screen.getByLabelText(/Message type/i) as HTMLInputElement).value).toBe('payment.made')
  })

  it('asks for confirmation before discarding a dirty working copy on select', async () => {
    useTemplateStore.getState().add(makeTemplate())
    useTemplateStore.getState().add(makeTemplate({ id: 'tpl-2', name: 'Payment made' }))
    // Working copy differs from its stored counterpart → dirty.
    const { onChangeSpy } = renderEditor(makeTemplate({ messageType: 'order.EDITED' }))

    await openTemplateDropdown()
    await userEvent.click(await screen.findByTitle('Payment made'))

    // antd renders the confirm title twice (aria node + visible span) — match all.
    expect((await screen.findAllByText('Unsaved changes')).length).toBeGreaterThan(0)

    // Cancel keeps the working copy.
    await userEvent.click(screen.getByRole('button', { name: /cancel/i }))
    expect(onChangeSpy).not.toHaveBeenCalled()

    // Confirm switches (a fresh confirm; click the LAST OK in case the first
    // modal is still animating out in jsdom).
    await openTemplateDropdown()
    await userEvent.click(await screen.findByTitle('Payment made'))
    const okButtons = await screen.findAllByRole('button', { name: /^OK$/i })
    await userEvent.click(okButtons[okButtons.length - 1])
    await waitFor(() => {
      expect(onChangeSpy.mock.calls.at(-1)![0].id).toBe('tpl-2')
    })
  })

  // ── New ───────────────────────────────────────────────────────────────────

  it('New replaces the working copy with a blank template', async () => {
    useTemplateStore.getState().add(makeTemplate())
    const { onChangeSpy } = renderEditor(makeTemplate())

    await userEvent.click(screen.getByRole('button', { name: /^New$/i }))

    await waitFor(() => {
      const next = onChangeSpy.mock.calls.at(-1)![0]
      expect(next.id).not.toBe('tpl-1')
      expect(next.name).toBe('Untitled')
      expect(next.priority).toBe(5)
    })
  })

  // ── Save ──────────────────────────────────────────────────────────────────

  it('Save adds a new template to the store and persists it to localStorage', async () => {
    const fresh = blankTemplate()
    renderEditor({ ...fresh, name: 'Brand new', messageType: 'x.y' })

    await userEvent.click(screen.getByRole('button', { name: /^Save$/i }))

    await waitFor(() => {
      expect(useTemplateStore.getState().templates.map((t) => t.name)).toContain('Brand new')
    })
    expect(loadAll().map((t) => t.name)).toContain('Brand new')
  })

  it('Save updates an existing template in place', async () => {
    useTemplateStore.getState().add(makeTemplate())
    renderEditor(makeTemplate({ messageType: 'order.v2' }))

    await userEvent.click(screen.getByRole('button', { name: /^Save$/i }))

    await waitFor(() => {
      const stored = useTemplateStore.getState().templates.find((t) => t.id === 'tpl-1')!
      expect(stored.messageType).toBe('order.v2')
    })
    expect(useTemplateStore.getState().templates).toHaveLength(1)
  })

  // ── Payload validation (resolve-then-parse, §8) ───────────────────────────

  it('shows the parse error inline when the resolved payload is not valid JSON', async () => {
    renderEditor()
    const payload = screen.getByLabelText(/Payload/i)
    await userEvent.clear(payload)
    await userEvent.type(payload, '{{"broken": }')
    await userEvent.tab()
    await waitFor(() => {
      expect(screen.getByTestId('payload-error')).toBeTruthy()
    })
  })

  it('accepts bare numeric tokens — validation resolves before parsing', async () => {
    renderEditor(makeTemplate({ payloadSchema: '{"amount": {{random:500}}}' }))
    const payload = screen.getByLabelText(/Payload/i)
    await userEvent.click(payload)
    await userEvent.tab()
    expect(screen.queryByTestId('payload-error')).toBeNull()
  })

  it('clears the inline error once the payload is fixed', async () => {
    renderEditor()
    const payload = screen.getByLabelText(/Payload/i)
    await userEvent.clear(payload)
    await userEvent.type(payload, 'not json')
    await userEvent.tab()
    await waitFor(() => expect(screen.getByTestId('payload-error')).toBeTruthy())

    await userEvent.clear(payload)
    await userEvent.type(payload, '{{"ok": 1}')
    await userEvent.tab()
    await waitFor(() => expect(screen.queryByTestId('payload-error')).toBeNull())
  })

  // ── Headers ───────────────────────────────────────────────────────────────

  it('adds, edits, and removes header pairs through onChange', async () => {
    const { onChangeSpy } = renderEditor(makeTemplate({ headers: {} }))

    await userEvent.click(screen.getByRole('button', { name: /Add header/i }))
    await userEvent.type(screen.getByTestId('header-key-0'), 'traceId')
    await userEvent.type(screen.getByTestId('header-value-0'), '{{{{uuid}}')
    await waitFor(() => {
      expect(onChangeSpy.mock.calls.at(-1)![0].headers).toEqual({ traceId: '{{uuid}}' })
    })

    await userEvent.click(screen.getByTestId('header-remove-0'))
    await waitFor(() => {
      expect(onChangeSpy.mock.calls.at(-1)![0].headers).toEqual({})
    })
  })

  it('warns visibly when two header rows share a key — last-wins must not be silent', async () => {
    // toRecord collapses duplicate keys to the last row's value; both rows stay
    // visible in the editor, so without a warning one value is silently lost.
    renderEditor() // starts with the 'source' header
    await userEvent.click(screen.getByRole('button', { name: /Add header/i }))
    await userEvent.type(screen.getByTestId('header-key-1'), 'source')

    const warning = await screen.findByTestId('duplicate-header-warning')
    expect(warning.textContent).toContain('source')

    // Renaming the row resolves the collision and clears the warning.
    await userEvent.type(screen.getByTestId('header-key-1'), '2') // now "source2"
    await waitFor(() => {
      expect(screen.queryByTestId('duplicate-header-warning')).toBeNull()
    })
  })

  it('caps headers at 20 pairs', async () => {
    const headers = Object.fromEntries(Array.from({ length: 20 }, (_, i) => [`k${i}`, `v${i}`]))
    renderEditor(makeTemplate({ headers }))
    const addButton = screen.getByRole('button', { name: /Add header/i })
    expect((addButton as HTMLButtonElement).disabled).toBe(true)
  })

  // ── Save feedback and pristine-blank dirtiness ───────────────────────────

  it('Save reports success', async () => {
    renderEditor()
    await userEvent.click(screen.getByRole('button', { name: /^Save$/i }))
    await waitFor(() => {
      expect(screen.getByText(/Template "Order created" saved/i)).toBeTruthy()
    })
  })

  it('New on a PRISTINE blank template does not ask to discard', async () => {
    // isDirty treated any unstored template as dirty, so pressing New twice on
    // an untouched blank raised a spurious "Unsaved changes" confirm.
    renderEditor(blankTemplate())
    await userEvent.click(screen.getByRole('button', { name: /^New$/i }))
    expect(document.querySelector('.ant-modal-confirm')).toBeNull()
  })

  // ── Placeholder reference ────────────────────────────────────────────────

  it('lists every §5.3 placeholder token in the reference panel', async () => {
    renderEditor()
    await userEvent.click(screen.getByText(/Placeholder reference/i))
    for (const token of [
      '{{messageId}}',
      '{{sequenceId}}',
      '{{uuid}}',
      '{{timestamp}}',
      '{{unixMs}}',
      '{{index}}',
      '{{random:N}}',
      '{{randomAlpha:N}}',
      '{{list:name}}',
      '{{correlationId}}',
      '{{runId}}',
    ]) {
      expect(await screen.findByText(token)).toBeTruthy()
    }
  })

  // ── Export ────────────────────────────────────────────────────────────────

  it('Export downloads the working copy as JSON', async () => {
    const createObjectURL = vi.fn(() => 'blob:fake')
    const revokeObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL, revokeObjectURL })

    renderEditor()
    await userEvent.click(screen.getByRole('button', { name: /^Export$/i }))

    expect(createObjectURL).toHaveBeenCalledOnce()
    const blob = createObjectURL.mock.calls[0][0] as unknown as Blob
    // jsdom's Blob has no .text(); read through FileReader instead.
    const text = await new Promise<string>((resolve) => {
      const reader = new FileReader()
      reader.onload = () => resolve(reader.result as string)
      reader.readAsText(blob)
    })
    expect(JSON.parse(text).id).toBe('tpl-1')
  })

  // ── Disabled ──────────────────────────────────────────────────────────────

  it('disables every control when disabled is set', () => {
    renderEditor(makeTemplate(), vi.fn(), true)
    expect((screen.getByLabelText(/^Name$/i) as HTMLInputElement).disabled).toBe(true)
    expect((screen.getByLabelText(/Payload/i) as HTMLTextAreaElement).disabled).toBe(true)
    for (const name of [/^New$/i, /^Save$/i, /^Export$/i, /Add header/i]) {
      expect((screen.getByRole('button', { name }) as HTMLButtonElement).disabled).toBe(true)
    }
  })
})
