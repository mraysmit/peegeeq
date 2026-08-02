/**
 * Tests for profileRunner.ts (design §19.3 — Phase G.3a).
 *
 * Contract under test (written FIRST, before the runner):
 * - phases run STRICTLY in order, one at a time: phase N+1 starts only after
 *   phase N has reached a terminal state
 * - each phase runs the shared base config with that phase's rate/duration
 * - an IDLE phase (rate 0) publishes NOTHING: no run is started, the runner
 *   just waits out its duration. The engine cannot express idle — it floors the
 *   quota at 1 message per tick and RunConfig requires rate ≥ 1 — so an idle
 *   phase driven through a run would silently publish traffic.
 * - per-phase results carry the server-acknowledged sent count, error count and
 *   terminal status; the REQUESTED total is derived from the phase, not stored
 * - stop() mid-profile stops the active phase and starts no further phases
 * - a phase ending in error ABORTS the profile: the remaining phases would
 *   report an achieved shape that never happened
 * - an empty profile is refused rather than "completing" vacuously
 *
 * No module mocking: the runner takes its run-starting and timer functions as
 * injected dependencies, and these tests pass real fakes.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createProfileRunner } from '../../engine/profileRunner'
import type { ProfilePhase } from '../../types/profile'
import type { RunHooks, RunHandle } from '../../engine/runStarter'
import type { MessageTemplate, RunConfig, RunSummary } from '../../types/generator'

function makeTemplate(): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: 't1',
    name: 'T',
    messageType: 'order.created',
    payloadSchema: '{"id":"{{messageId}}"}',
    headers: {},
    priority: 5,
    delaySeconds: 0,
    createdAt: now,
    updatedAt: now,
  }
}

/** The base config: everything except rate/durationSecs, which each phase sets. */
function makeBase(): RunConfig {
  return {
    setupId: 's1',
    queueName: 'orders',
    rate: 1,
    durationSecs: 1,
    maxBatchSize: 10,
    warnThreshold: 0,
    maxConsecErrors: 3,
    template: makeTemplate(),
    previewIndex: 1,
  }
}

function makePhase(overrides: Partial<ProfilePhase> = {}): ProfilePhase {
  return { id: crypto.randomUUID(), label: 'steady', rate: 10, durationSecs: 5, ...overrides }
}

function makeSummary(overrides: Partial<RunSummary> = {}): RunSummary {
  return {
    totalSent: 50,
    targetTotal: 50,
    avgRate: 10,
    durationMs: 5000,
    totalErrors: 0,
    finalStatus: 'completed',
    runId: 'r1',
    errors: [],
    ...overrides,
  }
}

/**
 * A fake run starter: records every config it was asked to run and hands back
 * control so the test decides when — and how — each phase terminates.
 */
function fakeStarter() {
  const configs: RunConfig[] = []
  const hooksSeen: RunHooks[] = []
  let stopped = 0
  const startRun = (config: RunConfig, hooks: RunHooks): RunHandle | null => {
    configs.push(config)
    hooksSeen.push(hooks)
    return { stop: () => { stopped++ } }
  }
  return {
    startRun,
    configs,
    stopCount: () => stopped,
    /** Settle the most recently started phase run. */
    finish(status: 'completed' | 'stopped' | 'error', summary = makeSummary(), reason?: string) {
      const hooks = hooksSeen[hooksSeen.length - 1]
      hooks.onTerminal?.({ ...summary, finalStatus: status }, status, reason)
    },
  }
}

describe('profileRunner', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('runs phases strictly in order, starting the next only after the previous terminates', () => {
    const starter = fakeStarter()
    const runner = createProfileRunner({ startRun: starter.startRun })
    const phases = [
      makePhase({ label: 'burst', rate: 500, durationSecs: 10 }),
      makePhase({ label: 'steady', rate: 100, durationSecs: 60 }),
    ]

    runner.start(makeBase(), phases, {})

    // Only the first phase has started.
    expect(starter.configs).toHaveLength(1)
    expect(starter.configs[0].rate).toBe(500)
    expect(starter.configs[0].durationSecs).toBe(10)

    starter.finish('completed')

    expect(starter.configs).toHaveLength(2)
    expect(starter.configs[1].rate).toBe(100)
    expect(starter.configs[1].durationSecs).toBe(60)
  })

  it('runs each phase with the shared base target, template and guards', () => {
    const starter = fakeStarter()
    const runner = createProfileRunner({ startRun: starter.startRun })
    const base = makeBase()

    runner.start(base, [makePhase({ rate: 42, durationSecs: 7 })], {})

    const config = starter.configs[0]
    expect(config.setupId).toBe('s1')
    expect(config.queueName).toBe('orders')
    expect(config.maxConsecErrors).toBe(3)
    expect(config.template).toEqual(base.template)
    // Only rate and duration come from the phase.
    expect(config.rate).toBe(42)
    expect(config.durationSecs).toBe(7)
  })

  it('an IDLE phase (rate 0) starts no run at all and waits out its duration', async () => {
    const starter = fakeStarter()
    const runner = createProfileRunner({ startRun: starter.startRun })
    const phases = [
      makePhase({ label: 'idle', rate: 0, durationSecs: 15 }),
      makePhase({ label: 'after', rate: 10, durationSecs: 5 }),
    ]

    runner.start(makeBase(), phases, {})

    // Nothing published during idle — the engine would floor a 0 rate to 1 msg/tick.
    expect(starter.configs).toHaveLength(0)

    await vi.advanceTimersByTimeAsync(14_999)
    expect(starter.configs).toHaveLength(0)

    await vi.advanceTimersByTimeAsync(1)
    expect(starter.configs).toHaveLength(1)
    expect(starter.configs[0].rate).toBe(10)
  })

  it('records per-phase results with acknowledged sends, errors and status', () => {
    const starter = fakeStarter()
    const runner = createProfileRunner({ startRun: starter.startRun })
    const onProfileComplete = vi.fn()
    const phase = makePhase({ rate: 10, durationSecs: 5 })

    runner.start(makeBase(), [phase], { onProfileComplete })
    starter.finish('completed', makeSummary({ totalSent: 47, totalErrors: 2 }))

    expect(onProfileComplete).toHaveBeenCalledTimes(1)
    const results = onProfileComplete.mock.calls[0][0]
    expect(results).toHaveLength(1)
    expect(results[0].phaseId).toBe(phase.id)
    expect(results[0].sent).toBe(47)
    expect(results[0].errors).toBe(2)
    expect(results[0].status).toBe('completed')
    // The requested total is DERIVED from the phase, never stored on the result.
    expect(results[0]).not.toHaveProperty('requested')
  })

  it('reports an idle phase as a completed result that sent nothing', async () => {
    const starter = fakeStarter()
    const runner = createProfileRunner({ startRun: starter.startRun })
    const onProfileComplete = vi.fn()

    runner.start(makeBase(), [makePhase({ label: 'idle', rate: 0, durationSecs: 3 })], {
      onProfileComplete,
    })
    await vi.advanceTimersByTimeAsync(3000)

    const results = onProfileComplete.mock.calls[0][0]
    expect(results[0].sent).toBe(0)
    expect(results[0].errors).toBe(0)
    expect(results[0].status).toBe('completed')
  })

  it('completes the profile only after the LAST phase terminates', () => {
    const starter = fakeStarter()
    const runner = createProfileRunner({ startRun: starter.startRun })
    const onProfileComplete = vi.fn()

    runner.start(makeBase(), [makePhase(), makePhase()], { onProfileComplete })
    starter.finish('completed')
    expect(onProfileComplete).not.toHaveBeenCalled()

    starter.finish('completed')
    expect(onProfileComplete).toHaveBeenCalledTimes(1)
    expect(onProfileComplete.mock.calls[0][0]).toHaveLength(2)
  })

  it('stop() stops the active phase and starts no further phases', () => {
    const starter = fakeStarter()
    const runner = createProfileRunner({ startRun: starter.startRun })
    const onProfileStopped = vi.fn()

    const handle = runner.start(makeBase(), [makePhase(), makePhase()], { onProfileStopped })
    handle!.stop()

    expect(starter.stopCount()).toBe(1)
    // The engine settles the stopped run through its terminal callback.
    starter.finish('stopped', makeSummary({ totalSent: 12 }))

    expect(starter.configs).toHaveLength(1) // the second phase never started
    expect(onProfileStopped).toHaveBeenCalledTimes(1)
    expect(onProfileStopped.mock.calls[0][0][0].sent).toBe(12)
  })

  it('stop() during an IDLE phase cancels the wait and settles as stopped', async () => {
    const starter = fakeStarter()
    const runner = createProfileRunner({ startRun: starter.startRun })
    const onProfileStopped = vi.fn()

    const handle = runner.start(
      makeBase(),
      [makePhase({ rate: 0, durationSecs: 30 }), makePhase()],
      { onProfileStopped }
    )
    handle!.stop()

    await vi.advanceTimersByTimeAsync(60_000)

    expect(starter.configs).toHaveLength(0) // idle never published, next phase never ran
    expect(onProfileStopped).toHaveBeenCalledTimes(1)
  })

  it('a phase ending in ERROR aborts the profile and reports the reason', () => {
    const starter = fakeStarter()
    const runner = createProfileRunner({ startRun: starter.startRun })
    const onProfileError = vi.fn()

    runner.start(makeBase(), [makePhase({ label: 'burst' }), makePhase()], { onProfileError })
    starter.finish('error', makeSummary({ totalSent: 3, totalErrors: 3 }), 'Auto-stopped: 3 consecutive errors')

    expect(starter.configs).toHaveLength(1) // the remaining phase must not run
    expect(onProfileError).toHaveBeenCalledTimes(1)
    const [results, reason] = onProfileError.mock.calls[0]
    expect(results[0].status).toBe('error')
    expect(reason).toContain('burst')
    expect(reason).toContain('Auto-stopped')
  })

  it('refuses an empty profile instead of completing vacuously', () => {
    const starter = fakeStarter()
    const runner = createProfileRunner({ startRun: starter.startRun })
    const onProfileComplete = vi.fn()

    const handle = runner.start(makeBase(), [], { onProfileComplete })

    expect(handle).toBeNull()
    expect(onProfileComplete).not.toHaveBeenCalled()
    expect(starter.configs).toHaveLength(0)
  })

  it('refuses to start when the run starter reports a run is already active', () => {
    const runner = createProfileRunner({ startRun: () => null })
    const onProfileError = vi.fn()

    const handle = runner.start(makeBase(), [makePhase()], { onProfileError })

    expect(handle).toBeNull()
    expect(onProfileError).toHaveBeenCalledTimes(1)
    expect(onProfileError.mock.calls[0][1]).toMatch(/already active|could not start/i)
  })

  // ── Early halt (G.1a): the hook a ramp needs ──────────────────────────────

  it('halts the sequence when shouldHaltAfterPhase returns a reason', () => {
    const starter = fakeStarter()
    const runner = createProfileRunner({ startRun: starter.startRun })
    const onProfileHalted = vi.fn()
    const onProfileComplete = vi.fn()

    runner.start(makeBase(), [makePhase(), makePhase(), makePhase()], {
      shouldHaltAfterPhase: (result) => (result.errors > 0 ? 'error rate exceeded' : null),
      onProfileHalted,
      onProfileComplete,
    })

    starter.finish('completed', makeSummary({ totalErrors: 0 }))
    expect(starter.configs).toHaveLength(2) // no halt yet — the sequence continued

    starter.finish('completed', makeSummary({ totalErrors: 5 }))

    expect(starter.configs).toHaveLength(2) // the third phase never started
    expect(onProfileHalted).toHaveBeenCalledTimes(1)
    expect(onProfileHalted.mock.calls[0][1]).toBe('error rate exceeded')
    // Halting early is a NORMAL outcome, not a completion and not an error.
    expect(onProfileComplete).not.toHaveBeenCalled()
  })

  it('passes every result so far to shouldHaltAfterPhase, not just the last', () => {
    const starter = fakeStarter()
    const runner = createProfileRunner({ startRun: starter.startRun })
    const seen: number[] = []

    runner.start(makeBase(), [makePhase(), makePhase()], {
      shouldHaltAfterPhase: (_result, results) => {
        seen.push(results.length)
        return null
      },
    })
    starter.finish('completed')
    starter.finish('completed')

    // A plateau check needs the previous steps, so the accumulated list must
    // include the phase that just settled.
    expect(seen).toEqual([1, 2])
  })

  it('completes normally when shouldHaltAfterPhase never returns a reason', () => {
    const starter = fakeStarter()
    const runner = createProfileRunner({ startRun: starter.startRun })
    const onProfileComplete = vi.fn()
    const onProfileHalted = vi.fn()

    runner.start(makeBase(), [makePhase(), makePhase()], {
      shouldHaltAfterPhase: () => null,
      onProfileComplete,
      onProfileHalted,
    })
    starter.finish('completed')
    starter.finish('completed')

    expect(onProfileComplete).toHaveBeenCalledTimes(1)
    expect(onProfileHalted).not.toHaveBeenCalled()
  })

  it('reports each phase start so the UI can show which phase is live', () => {
    const starter = fakeStarter()
    const runner = createProfileRunner({ startRun: starter.startRun })
    const onPhaseStart = vi.fn()
    const phases = [makePhase({ label: 'burst' }), makePhase({ label: 'steady' })]

    runner.start(makeBase(), phases, { onPhaseStart })
    expect(onPhaseStart).toHaveBeenCalledWith(0, phases[0])

    starter.finish('completed')
    expect(onPhaseStart).toHaveBeenCalledWith(1, phases[1])
  })
})
