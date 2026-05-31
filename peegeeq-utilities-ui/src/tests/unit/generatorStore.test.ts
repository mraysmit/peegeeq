/**
 * Tests for generatorStore.ts (§11 of the feature design).
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { useGeneratorStore } from '../../stores/generatorStore'
import type { RunConfig, MessageTemplate, PublishError } from '../../types/generator'

function makeTemplate(): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: 't1',
    name: 'T',
    messageType: 'x',
    payloadSchema: '{}',
    headers: {},
    priority: 5,
    delaySeconds: 0,
    createdAt: now,
    updatedAt: now,
  }
}

function makeConfig(overrides: Partial<RunConfig> = {}): RunConfig {
  return {
    setupId: 's1',
    queueName: 'orders',
    rate: 10,
    durationSecs: 5,
    maxBatchSize: 100,
    warnThreshold: 0,
    maxConsecErrors: 3,
    template: makeTemplate(),
    previewIndex: 1,
    ...overrides,
  }
}

function reset() {
  useGeneratorStore.getState().resetRun()
  useGeneratorStore.setState({ config: null })
}

describe('generatorStore', () => {
  beforeEach(reset)

  it('starts in idle with a zeroed run state', () => {
    const { runState } = useGeneratorStore.getState()
    expect(runState.status).toBe('idle')
    expect(runState.sent).toBe(0)
    expect(runState.runId).toBeNull()
  })

  it('setConfig stores the run configuration', () => {
    const cfg = makeConfig()
    useGeneratorStore.getState().setConfig(cfg)
    expect(useGeneratorStore.getState().config).toEqual(cfg)
  })

  it('startRun transitions to running and computes totalToSend = rate * durationSecs', () => {
    useGeneratorStore.getState().setConfig(makeConfig({ rate: 10, durationSecs: 5 }))
    useGeneratorStore.getState().startRun()

    const { runState } = useGeneratorStore.getState()
    expect(runState.status).toBe('running')
    expect(runState.totalToSend).toBe(50)
    expect(runState.runId).not.toBeNull()
    expect(runState.startedAt).not.toBeNull()
  })

  it('startRun is a no-op when no config is set', () => {
    useGeneratorStore.getState().startRun()
    expect(useGeneratorStore.getState().runState.status).toBe('idle')
  })

  it('tickUpdate refreshes counters and computes currentRate', () => {
    useGeneratorStore.getState().setConfig(makeConfig())
    useGeneratorStore.getState().startRun()

    const errors: PublishError[] = []
    useGeneratorStore.getState().tickUpdate(20, errors, 0, 2000)

    const { runState } = useGeneratorStore.getState()
    expect(runState.sent).toBe(20)
    expect(runState.elapsedMs).toBe(2000)
    expect(runState.consecErrors).toBe(0)
    expect(runState.currentRate).toBeCloseTo(10, 5)
  })

  it('transitionTo sets a terminal status and optional autoStopReason', () => {
    useGeneratorStore.getState().setConfig(makeConfig())
    useGeneratorStore.getState().startRun()

    useGeneratorStore.getState().transitionTo('error', 'Auto-stopped: 3 consecutive errors. Last: boom')

    const { runState } = useGeneratorStore.getState()
    expect(runState.status).toBe('error')
    expect(runState.autoStopReason).toContain('Auto-stopped')
  })

  it('stopRun transitions to stopped', () => {
    useGeneratorStore.getState().setConfig(makeConfig())
    useGeneratorStore.getState().startRun()
    useGeneratorStore.getState().stopRun()

    expect(useGeneratorStore.getState().runState.status).toBe('stopped')
  })

  it('resetRun returns the run state to idle', () => {
    useGeneratorStore.getState().setConfig(makeConfig())
    useGeneratorStore.getState().startRun()
    useGeneratorStore.getState().tickUpdate(5, [], 0, 1000)
    useGeneratorStore.getState().resetRun()

    const { runState } = useGeneratorStore.getState()
    expect(runState.status).toBe('idle')
    expect(runState.sent).toBe(0)
    expect(runState.runId).toBeNull()
  })
})
