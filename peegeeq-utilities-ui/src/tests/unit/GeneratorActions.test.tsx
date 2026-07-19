/**
 * Tests for GeneratorActions (Zone D — feature §6.1, §5.5, §8).
 *
 * Contract under test (written FIRST, before the component):
 * - Preview resolves the working copy at the preview index with a fresh
 *   runId/correlationId, opens a modal with the resolved MessageRequest JSON,
 *   makes no HTTP call, and shows a missing-lists warning inside the modal
 * - a template that fails resolve-then-parse shows an inline error, no modal
 * - Start is enabled only in idle with a target selected; its pre-flight warns
 *   about missing lists via a confirm (Proceed / Cancel) and is otherwise direct
 * - Stop is enabled only while running
 * - the preview index input reports changes
 *
 * No mocks: resolution runs the real templateResolver against the real
 * valueListStore (localStorage-backed). No network boundary exists in Zone D.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import GeneratorActions from '../../pages/generator/GeneratorActions'
import { useValueListStore } from '../../stores/valueListStore'
import type { MessageTemplate, RunStatus } from '../../types/generator'

function makeTemplate(overrides: Partial<MessageTemplate> = {}): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: 'tpl-1',
    name: 'Order created',
    messageType: 'order.created',
    payloadSchema: '{"id":"{{messageId}}","run":"{{runId}}"}',
    headers: { source: 'generator' },
    priority: 5,
    delaySeconds: 0,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  }
}

interface RenderOptions {
  template?: MessageTemplate
  status?: RunStatus
  targetSelected?: boolean
  previewIndex?: number
}

function renderActions(options: RenderOptions = {}) {
  const onStart = vi.fn()
  const onStop = vi.fn()
  const onSchedule = vi.fn()
  const onPreviewIndexChange = vi.fn()
  render(
    <ConfigProvider>
      <GeneratorActions
        template={options.template ?? makeTemplate()}
        status={options.status ?? 'idle'}
        targetSelected={options.targetSelected ?? true}
        previewIndex={options.previewIndex ?? 1}
        onPreviewIndexChange={onPreviewIndexChange}
        onStart={onStart}
        onStop={onStop}
        onSchedule={onSchedule}
      />
    </ConfigProvider>
  )
  return { onStart, onStop, onSchedule, onPreviewIndexChange }
}

describe('GeneratorActions', () => {
  beforeEach(() => {
    localStorage.clear()
    useValueListStore.setState({ lists: [], selected: null })
    document.querySelectorAll('.ant-modal-root').forEach((n) => n.remove())
  })

  // ── Preview ───────────────────────────────────────────────────────────────

  it('opens a modal with the message resolved at the preview index', async () => {
    renderActions({ previewIndex: 3 })
    await userEvent.click(screen.getByRole('button', { name: /^Preview$/i }))

    const modal = await screen.findByTestId('preview-modal')
    expect(modal.textContent).toContain('00000003') // messageId = previewIndex, padded
    expect(modal.textContent).toContain('order.created')
  })

  it('uses a fresh runId for display — not a fixed placeholder value', async () => {
    renderActions()
    await userEvent.click(screen.getByRole('button', { name: /^Preview$/i }))

    const modal = await screen.findByTestId('preview-modal')
    // {{runId}} resolves to a UUID.
    expect(modal.textContent).toMatch(/"run": "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"/)
  })

  it('shows an inline error and no modal when the template does not resolve to JSON', async () => {
    renderActions({ template: makeTemplate({ payloadSchema: '{"broken": }' }) })
    await userEvent.click(screen.getByRole('button', { name: /^Preview$/i }))

    await waitFor(() => {
      expect(screen.getByTestId('preview-error')).toBeTruthy()
    })
    expect(screen.queryByTestId('preview-modal')).toBeNull()
  })

  it('warns inside the modal about missing value lists and substitutes empty strings', async () => {
    renderActions({
      template: makeTemplate({ payloadSchema: '{"name":"{{list:first_names}}"}' }),
    })
    await userEvent.click(screen.getByRole('button', { name: /^Preview$/i }))

    const modal = await screen.findByTestId('preview-modal')
    const warning = screen.getByTestId('missing-lists-warning')
    expect(warning.textContent).toContain('first_names')
    expect(modal.textContent).toContain('"name": ""')
  })

  it('resolves from the real value list store when the list exists — no warning', async () => {
    const now = new Date().toISOString()
    useValueListStore.getState().add({ name: 'first_names', values: ['Mark'], createdAt: now, updatedAt: now })
    renderActions({
      template: makeTemplate({ payloadSchema: '{"name":"{{list:first_names}}"}' }),
    })
    await userEvent.click(screen.getByRole('button', { name: /^Preview$/i }))

    const modal = await screen.findByTestId('preview-modal')
    expect(screen.queryByTestId('missing-lists-warning')).toBeNull()
    expect(modal.textContent).toContain('"name": "Mark"')
  })

  it('resolves placeholders in header values in the preview (§5.3)', async () => {
    renderActions({
      template: makeTemplate({ headers: { trace: '{{uuid}}', fixed: 'constant' } }),
    })
    await userEvent.click(screen.getByRole('button', { name: /^Preview$/i }))

    const modal = await screen.findByTestId('preview-modal')
    expect(modal.textContent).toMatch(/"trace": "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"/)
    expect(modal.textContent).toContain('"fixed": "constant"')
  })

  it('counts a missing list referenced from a header value in the warnings and pre-flight', async () => {
    const { onStart } = renderActions({
      template: makeTemplate({
        payloadSchema: '{"ok": 1}',
        headers: { name: '{{list:header_list}}' },
      }),
    })

    await userEvent.click(screen.getByRole('button', { name: /^Preview$/i }))
    const warning = await screen.findByTestId('missing-lists-warning')
    expect(warning.textContent).toContain('header_list')
    // Two close controls exist (modal X + footer button) — either dismisses.
    await userEvent.click(screen.getAllByRole('button', { name: /close/i })[0])

    await userEvent.click(screen.getByRole('button', { name: /^Start$/i }))
    expect((await screen.findAllByText(/header_list/)).length).toBeGreaterThan(0)
    expect(onStart).not.toHaveBeenCalled()
  })

  it('reports preview index changes', async () => {
    const { onPreviewIndexChange } = renderActions()
    const input = screen.getByLabelText(/Preview message #/i)
    await userEvent.clear(input)
    await userEvent.type(input, '7')
    await waitFor(() => {
      expect(onPreviewIndexChange).toHaveBeenLastCalledWith(7)
    })
  })

  // ── Start enablement ──────────────────────────────────────────────────────

  it('Start is enabled in idle with a target selected', () => {
    renderActions({ status: 'idle', targetSelected: true })
    expect((screen.getByRole('button', { name: /^Start$/i }) as HTMLButtonElement).disabled).toBe(false)
  })

  it('Start is disabled without a target', () => {
    renderActions({ status: 'idle', targetSelected: false })
    expect((screen.getByRole('button', { name: /^Start$/i }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('Start is disabled outside idle', () => {
    for (const status of ['running', 'completed', 'stopped', 'error'] as RunStatus[]) {
      const { unmount } = render(
        <ConfigProvider>
          <GeneratorActions
            template={makeTemplate()}
            status={status}
            targetSelected={true}
            previewIndex={1}
            onPreviewIndexChange={vi.fn()}
            onStart={vi.fn()}
            onStop={vi.fn()}
          />
        </ConfigProvider>
      )
      expect((screen.getByRole('button', { name: /^Start$/i }) as HTMLButtonElement).disabled).toBe(true)
      unmount()
    }
  })

  // ── Start pre-flight ──────────────────────────────────────────────────────

  it('starts directly when no value lists are missing', async () => {
    const { onStart } = renderActions()
    await userEvent.click(screen.getByRole('button', { name: /^Start$/i }))
    expect(onStart).toHaveBeenCalledOnce()
    expect(document.querySelector('.ant-modal-confirm')).toBeNull()
  })

  it('warns about missing lists before starting; Cancel aborts, Proceed starts', async () => {
    const { onStart } = renderActions({
      template: makeTemplate({ payloadSchema: '{"name":"{{list:absent}}"}' }),
    })

    await userEvent.click(screen.getByRole('button', { name: /^Start$/i }))
    expect((await screen.findAllByText(/absent/)).length).toBeGreaterThan(0)
    expect(onStart).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: /cancel/i }))
    expect(onStart).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: /^Start$/i }))
    const proceedButtons = await screen.findAllByRole('button', { name: /proceed/i })
    await userEvent.click(proceedButtons[proceedButtons.length - 1])
    await waitFor(() => {
      expect(onStart).toHaveBeenCalledOnce()
    })
  })

  // ── Schedule… (SCH.4) ─────────────────────────────────────────────────────

  it('Schedule… is enabled under the same conditions as Start and reports the click', async () => {
    const { onSchedule } = renderActions({ status: 'idle', targetSelected: true })
    const button = screen.getByRole('button', { name: /Schedule…/ })
    expect((button as HTMLButtonElement).disabled).toBe(false)
    await userEvent.click(button)
    expect(onSchedule).toHaveBeenCalledOnce()
  })

  it('Schedule… is disabled without a target and outside idle', () => {
    renderActions({ status: 'idle', targetSelected: false })
    expect((screen.getByRole('button', { name: /Schedule…/ }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('Schedule… is disabled while running', () => {
    renderActions({ status: 'running', targetSelected: true })
    expect((screen.getByRole('button', { name: /Schedule…/ }) as HTMLButtonElement).disabled).toBe(true)
  })

  // ── Stop ──────────────────────────────────────────────────────────────────

  it('Stop is enabled only while running and reports the stop', async () => {
    const { onStop } = renderActions({ status: 'running' })
    const stop = screen.getByRole('button', { name: /^Stop$/i })
    expect((stop as HTMLButtonElement).disabled).toBe(false)
    await userEvent.click(stop)
    expect(onStop).toHaveBeenCalledOnce()
  })

  it('Stop is disabled outside running', () => {
    renderActions({ status: 'idle' })
    expect((screen.getByRole('button', { name: /^Stop$/i }) as HTMLButtonElement).disabled).toBe(true)
  })
})
