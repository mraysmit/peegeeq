/** Presentational coverage for the G.1b ramp attribution panel. No mocks. */
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ConfigProvider } from 'antd'
import RampAttributionPanel from '../../pages/generator/RampAttributionPanel'
import type { RampTelemetryReport } from '../../types/rampTelemetry'

function renderPanel(props: React.ComponentProps<typeof RampAttributionPanel>) {
  return render(<ConfigProvider><RampAttributionPanel {...props} /></ConfigProvider>)
}

describe('RampAttributionPanel', () => {
  it('states that telemetry has not started before a ramp', () => {
    renderPanel({
      live: null,
      report: null,
      phases: [{ id: 'p1', label: 'Step 1', rate: 5, durationSecs: 2 }],
    })
    expect(screen.getByTestId('ramp-attribution-empty').textContent).toMatch(/start with the ramp/i)
  })

  it('shows live pushed sample counts without presenting absent metrics as zero', () => {
    renderPanel({
      live: { queueSampleCount: 2, systemSampleCount: 1, streamErrors: [] },
      report: null,
      phases: [{ id: 'p1', label: 'Step 1', rate: 5, durationSecs: 2 }],
    })
    expect(screen.getByTestId('ramp-attribution-live').textContent).toContain('2 queue')
    expect(screen.getByTestId('ramp-live-event-loop').textContent).toContain('—')
  })

  it('surfaces database and stream telemetry failures in the finished report', () => {
    const report: RampTelemetryReport = {
      target: { setupId: 's1', queueName: 'orders' },
      queueSamples: [],
      systemSamples: [],
      database: {
        baseline: { ok: false, error: 'Database telemetry for s1 could not be read: 503' },
        final: { ok: false, error: 'Database telemetry for s1 could not be read: 503' },
      },
      streamErrors: ['Queue stats stream for s1/orders failed: connection lost'],
    }
    renderPanel({
      live: null,
      report,
      phases: [{ id: 'p1', label: 'Step 1', rate: 5, durationSecs: 2 }],
    })
    expect(screen.getByTestId('ramp-telemetry-errors').textContent).toContain('503')
    expect(screen.getByTestId('ramp-telemetry-errors').textContent).toContain('connection lost')
    expect(screen.getByTestId('ramp-attribution-scope').textContent).toMatch(/evidence, not proof/i)
  })
})
