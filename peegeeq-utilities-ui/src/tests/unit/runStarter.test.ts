/**
 * Tests for runStarter.ts (Scheduled Runs design §9 — SCH.2).
 *
 * Contract under test (written FIRST, before the module):
 * - startGeneratorRun refuses (returns null) while a run is active
 * - it drives the store: setConfig + startRun (store generates the runId), engine
 *   identity equals the store's runId
 * - onTick flows acknowledged counts into the store
 * - terminal outcomes write transitionTo + setSummary and fire the onTerminal
 *   hook with (summary, status, reason?)
 * - the returned handle stops the run; after any terminal state a new run can
 *   start (the single-use engine is discarded)
 * - a run-time template failure ends in ERROR with the reason surfaced
 *
 * The publish network boundary is mocked exactly as in publicationEngine.test.ts
 * (the sanctioned boundary); everything else is the real stores and engine.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { startGeneratorRun, stopActiveRun } from '../../engine/runStarter'
import { useGeneratorStore } from '../../stores/generatorStore'
import { useValueListStore } from '../../stores/valueListStore'
import { publishBatch } from '../../services/publishService'
import type { MessageTemplate, RunConfig, RunSummary, RunStatus } from '../../types/generator'

vi.mock('../../services/publishService')
const mockedPublishBatch = vi.mocked(publishBatch)

function makeTemplate(payloadSchema = '{"id":"{{messageId}}"}'): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: 't1', name: 'T', messageType: 'x', payloadSchema, headers: {},
    priority: 5, delaySeconds: 0, createdAt: now, updatedAt: now,
  }
}

function makeConfig(overrides: Partial<RunConfig> = {}): RunConfig {
  return {
    setupId: 's1', queueName: 'orders', rate: 10, durationSecs: 2,
    maxBatchSize: 10, warnThreshold: 0, maxConsecErrors: 0,
    template: makeTemplate(), previewIndex: 1, ...overrides,
  }
}

describe('runStarter', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    useGeneratorStore.getState().resetRun()
    useGeneratorStore.setState({ config: null })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('refuses while a run is active', () => {
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const first = startGeneratorRun(makeConfig({ durationSecs: 60 }))
    expect(first).not.toBeNull()

    const second = startGeneratorRun(makeConfig())
    expect(second).toBeNull()

    first!.stop()
  })

  it('gets its run id from the store — the summary runId is the store runId', async () => {
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const terminal = vi.fn()
    startGeneratorRun(makeConfig({ durationSecs: 1 }), { onTerminal: terminal })

    const storeRunId = useGeneratorStore.getState().runState.runId
    expect(storeRunId).not.toBeNull()

    await vi.advanceTimersByTimeAsync(1000)
    const summary = terminal.mock.calls[0][0] as RunSummary
    expect(summary.runId).toBe(storeRunId)
  })

  it('flows acknowledged counts into the store via onTick', async () => {
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    startGeneratorRun(makeConfig({ durationSecs: 60 }))
    await vi.advanceTimersByTimeAsync(0)
    expect(useGeneratorStore.getState().runState.sent).toBe(10)
  })

  it('completion writes the store terminal state and fires onTerminal', async () => {
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const terminal = vi.fn()
    startGeneratorRun(makeConfig({ durationSecs: 1 }), { onTerminal: terminal })
    await vi.advanceTimersByTimeAsync(1000)

    const state = useGeneratorStore.getState()
    expect(state.runState.status).toBe('completed')
    expect(state.summary!.finalStatus).toBe('completed')
    expect(terminal).toHaveBeenCalledOnce()
    const [summary, status] = terminal.mock.calls[0] as [RunSummary, RunStatus]
    expect(status).toBe('completed')
    expect(summary.totalSent).toBe(10)
  })

  it('the handle stops the run: STOPPED in store and hook', async () => {
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const terminal = vi.fn()
    const handle = startGeneratorRun(makeConfig({ durationSecs: 60 }), { onTerminal: terminal })
    await vi.advanceTimersByTimeAsync(0)

    handle!.stop()
    await vi.advanceTimersByTimeAsync(0)

    expect(useGeneratorStore.getState().runState.status).toBe('stopped')
    expect(terminal.mock.calls[0][1]).toBe('stopped')
  })

  it('a run-time template failure ends in ERROR with the reason surfaced', async () => {
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const terminal = vi.fn()
    startGeneratorRun(
      makeConfig({ template: makeTemplate('{"broken": }'), durationSecs: 60 }),
      { onTerminal: terminal }
    )
    await vi.advanceTimersByTimeAsync(0)

    const state = useGeneratorStore.getState()
    expect(state.runState.status).toBe('error')
    expect(state.runState.autoStopReason).toMatch(/template/i)
    const [, status, reason] = terminal.mock.calls[0] as [RunSummary, RunStatus, string]
    expect(status).toBe('error')
    expect(reason).toMatch(/template/i)
  })

  it('a synchronous engine-start failure settles the store to ERROR — never stuck RUNNING', async () => {
    // engine.start snapshots the value lists synchronously; a throw there used
    // to leave the store RUNNING with no engine and no handle: Start blocked
    // and every scheduled firing skipped until a page reload.
    const originalSnapshot = useValueListStore.getState().snapshot
    useValueListStore.setState({
      snapshot: () => {
        throw new Error('storage exploded')
      },
    })
    const terminal = vi.fn()

    const handle = startGeneratorRun(makeConfig(), { onTerminal: terminal })

    expect(handle).toBeNull()
    expect(useGeneratorStore.getState().runState.status).toBe('error')
    expect(terminal).toHaveBeenCalledOnce()
    const [, status, reason] = terminal.mock.calls[0] as [RunSummary, RunStatus, string]
    expect(status).toBe('error')
    expect(reason).toMatch(/failed to start/i)

    // Recovery: with the fault gone, a new run starts normally.
    useValueListStore.setState({ snapshot: originalSnapshot })
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const second = startGeneratorRun(makeConfig({ durationSecs: 60 }))
    expect(second).not.toBeNull()
    second!.stop()
  })

  it('stopActiveRun stops the active run regardless of which surface started it', async () => {
    // The run is started as the scheduler starts one: the returned handle is
    // NOT kept anywhere a page could reach. Stop must still work — the Stop
    // button was a no-op for scheduler/"Run now" runs when it relied on a
    // page-local handle.
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const terminal = vi.fn()
    startGeneratorRun(makeConfig({ durationSecs: 60 }), { onTerminal: terminal })
    await vi.advanceTimersByTimeAsync(0)

    stopActiveRun()
    await vi.advanceTimersByTimeAsync(0)

    expect(useGeneratorStore.getState().runState.status).toBe('stopped')
    expect(terminal.mock.calls[0][1]).toBe('stopped')
  })

  it('stopActiveRun is a no-op when idle and after a terminal state', async () => {
    expect(() => stopActiveRun()).not.toThrow() // idle: nothing to stop

    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    startGeneratorRun(makeConfig({ durationSecs: 1 }))
    await vi.advanceTimersByTimeAsync(1000)
    expect(useGeneratorStore.getState().runState.status).toBe('completed')

    stopActiveRun() // the handle was cleared at terminal — must not disturb the result
    await vi.advanceTimersByTimeAsync(0)
    expect(useGeneratorStore.getState().runState.status).toBe('completed')
  })

  it('a new run can start after a terminal state', async () => {
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    startGeneratorRun(makeConfig({ durationSecs: 1 }))
    await vi.advanceTimersByTimeAsync(1000)
    expect(useGeneratorStore.getState().runState.status).toBe('completed')

    const second = startGeneratorRun(makeConfig({ durationSecs: 1 }))
    expect(second).not.toBeNull()
    expect(useGeneratorStore.getState().runState.status).toBe('running')
    second!.stop()
  })
})
