/**
 * Message Generator page (feature §6.1) — assembles Zones A–E.
 *
 * State ownership (B.5, locked design 2026-07-18):
 * - The PAGE owns the working template, rate settings, preview index, and the
 *   selected target (Zone A callback).
 * - The generatorStore owns run state and the run summary; startRun() generates the
 *   runId. The page reads it and passes {runId, correlationId} into
 *   engine.start — the engine generates none of the ids.
 * - The engine is constructed fresh on Start and discarded on terminal state.
 *   Terminal callbacks write transitionTo + setSummary; onTick writes
 *   tickUpdate. Stop delegates to engine.stop(), whose onStop callback updates
 *   the store — the page never double-writes the terminal transition.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { Card, Space, Typography } from 'antd'
import TargetSelector from '../../components/TargetSelector'
import RateControls, { RATE_DEFAULTS } from './RateControls'
import TemplateEditor, { blankTemplate } from './TemplateEditor'
import GeneratorActions from './GeneratorActions'
import ScheduleRunModal from './ScheduleRunModal'
import ProgressPanel from './ProgressPanel'
import { startGeneratorRun } from '../../engine/runStarter'
import type { RunHandle } from '../../engine/runStarter'
import { useGeneratorStore } from '../../stores/generatorStore'
import { useTemplateStore } from '../../stores/templateStore'
import { useValueListStore } from '../../stores/valueListStore'
import { useScheduleStore } from '../../stores/scheduleStore'
import type { MessageTemplate, RateSettings, RunConfig } from '../../types/generator'

const { Title } = Typography

interface Target {
  setupId: string
  queueName: string
}

export default function MessageGeneratorPage() {
  const [target, setTarget] = useState<Target | null>(null)
  const [rateSettings, setRateSettings] = useState<RateSettings>(RATE_DEFAULTS)
  // Template Manager handoff: a template selected in the templateStore becomes
  // the initial working copy. The selection is consumed (cleared below) so a
  // later plain visit to /generator starts blank.
  const [workingTemplate, setWorkingTemplate] = useState<MessageTemplate>(() => {
    const selected = useTemplateStore.getState().selected
    return selected ? { ...selected, headers: { ...selected.headers } } : blankTemplate()
  })
  const [previewIndex, setPreviewIndex] = useState(1)
  const [scheduleModalOpen, setScheduleModalOpen] = useState(false)
  const runHandleRef = useRef<RunHandle | null>(null)

  useEffect(() => {
    useTemplateStore.getState().select(null)
    // Load value lists on mount: Preview and the engine both snapshot the
    // valueListStore, and on a fresh page load it starts empty — without this,
    // every {{list:...}} token resolved to "" with a false missing-list warning.
    useValueListStore.getState().loadFromStorage()
  }, [])

  const status = useGeneratorStore((s) => s.runState.status)
  const running = status === 'running'

  // Stable identity + reference-preserving update: TargetSelector's
  // notify-effect depends on this callback, so an inline version re-fires the
  // effect after every setTarget → infinite re-render loop.
  const handleTargetSelected = useCallback((setupId: string, queueName: string) => {
    setTarget((prev) =>
      prev && prev.setupId === setupId && prev.queueName === queueName
        ? prev
        : { setupId, queueName }
    )
  }, [])

  // Stop a still-running run when the page unmounts (navigation away). The
  // engine's onStop settles the store, so the run reports STOPPED, not limbo.
  useEffect(() => {
    return () => runHandleRef.current?.stop()
  }, [])

  function handleStart() {
    if (!target) return
    const config: RunConfig = {
      setupId: target.setupId,
      queueName: target.queueName,
      ...rateSettings,
      template: workingTemplate,
      previewIndex,
    }
    // Shared wiring (runStarter): store-generated run id, callbacks, terminal
    // settling — identical for the Start button and the scheduler.
    runHandleRef.current = startGeneratorRun(config, {
      onTerminal: (summary, status, reason) => {
        runHandleRef.current = null
        // Manual runs join the run history like scheduled firings.
        useScheduleStore
          .getState()
          .recordManualRun(config, status as 'completed' | 'stopped' | 'error', summary, reason)
      },
    })
  }

  function handleStop() {
    runHandleRef.current?.stop()
  }

  /** The config the schedule modal freezes — assembled identically to Start. */
  function assembledConfig(): RunConfig | null {
    if (!target) return null
    return {
      setupId: target.setupId,
      queueName: target.queueName,
      ...rateSettings,
      template: workingTemplate,
      previewIndex,
    }
  }

  const scheduleConfig = scheduleModalOpen ? assembledConfig() : null

  return (
    <Space direction="vertical" style={{ width: '100%' }} data-testid="generator-page">
      <Title level={3}>Queue Message Generator</Title>

      <Card title="Target" size="small">
        <div data-testid="zone-a">
          <TargetSelector onTargetSelected={handleTargetSelected} />
        </div>
      </Card>

      <Card title="Rate, duration & guards" size="small">
        <RateControls value={rateSettings} onChange={setRateSettings} disabled={running} />
      </Card>

      <Card title="Template" size="small">
        <TemplateEditor value={workingTemplate} onChange={setWorkingTemplate} disabled={running} />
      </Card>

      <Card title="Actions" size="small">
        <GeneratorActions
          template={workingTemplate}
          status={status}
          targetSelected={target !== null}
          previewIndex={previewIndex}
          onPreviewIndexChange={setPreviewIndex}
          onStart={handleStart}
          onStop={handleStop}
          onSchedule={() => setScheduleModalOpen(true)}
        />
      </Card>

      <Card title="Progress & results" size="small">
        <ProgressPanel />
      </Card>

      {scheduleConfig && (
        <ScheduleRunModal
          open={scheduleModalOpen}
          config={scheduleConfig}
          onClose={() => setScheduleModalOpen(false)}
        />
      )}
    </Space>
  )
}
