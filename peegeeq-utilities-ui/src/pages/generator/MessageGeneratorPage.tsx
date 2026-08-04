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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Alert, Card, Radio, Space, Typography, message } from 'antd'
import TargetSelector from '../../components/TargetSelector'
import ScenarioBar from '../../components/ScenarioBar'
import RateControls, { RATE_DEFAULTS } from './RateControls'
import ProfilePhasesEditor, { makeDefaultPhase } from './ProfilePhasesEditor'
import ProfileResultsPanel from './ProfileResultsPanel'
import RampControls, { RAMP_DEFAULTS } from './RampControls'
import ExerciserControls, { EXERCISER_DEFAULTS } from './ExerciserControls'
import ManifestPanel from './ManifestPanel'
import type { ManifestRun } from './ManifestPanel'
import { createProfileRunner } from '../../engine/profileRunner'
import type { ProfileHandle } from '../../engine/profileRunner'
import { buildRampPhases, rampHaltReason, sustainedRate } from '../../engine/rampPlan'
import type { ProfilePhase, ProfilePhaseResult } from '../../types/profile'
import type { RampSettings } from '../../types/ramp'
import type { ExerciserSettings } from '../../types/exerciser'
import TemplateEditor, { blankTemplate } from './TemplateEditor'
import GeneratorActions from './GeneratorActions'
import ScheduleRunModal from './ScheduleRunModal'
import ProgressPanel from './ProgressPanel'
import { startGeneratorRun, stopActiveRun } from '../../engine/runStarter'
import type { RunHandle } from '../../engine/runStarter'
import { useGeneratorStore } from '../../stores/generatorStore'
import { useTemplateStore } from '../../stores/templateStore'
import { useValueListStore } from '../../stores/valueListStore'
import { useScheduleStore } from '../../stores/scheduleStore'
import { useScenarioStore } from '../../stores/scenarioStore'
import type { MessageTemplate, RateSettings, RunConfig } from '../../types/generator'
import type { Scenario } from '../../types/scenario'

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
  // Seeds Zone A when a scenario is loaded (G.4). TargetSelector consumes its
  // initialTarget once per mount, so the key is bumped alongside it to remount
  // the selector — that is what makes a second Load in the same visit apply.
  const [initialTarget, setInitialTarget] = useState<{ setupId: string; queueName: string } | undefined>(
    undefined
  )
  const [targetSeed, setTargetSeed] = useState(0)
  const runHandleRef = useRef<RunHandle | null>(null)

  // Only BUILT modes are offered (G.3c, G.1a, G.5). Compare / Trace are not
  // built, and a disabled control for them would promise behaviour the app does
  // not have.
  const [mode, setMode] = useState<'flat' | 'profile' | 'ramp' | 'exerciser'>('flat')
  const [phases, setPhases] = useState<ProfilePhase[]>(() => [makeDefaultPhase()])
  const [phaseResults, setPhaseResults] = useState<ProfilePhaseResult[]>([])
  const [activePhaseIndex, setActivePhaseIndex] = useState<number | null>(null)
  const profileHandleRef = useRef<ProfileHandle | null>(null)

  // Ramp mode (G.1a). The STEPS are derived from the settings on every render —
  // storing them alongside would let the plan disagree with the controls.
  const [rampSettings, setRampSettings] = useState<RampSettings>(RAMP_DEFAULTS)
  const [rampHalt, setRampHalt] = useState<string | null>(null)
  const rampPhases = useMemo(() => buildRampPhases(rampSettings), [rampSettings])

  // Exerciser mode (G.5). The manifest is DERIVED after the run from these
  // captured inputs — settings, the value-list snapshot the run used, and the
  // summary's run id / attempted count. Nothing per-message is stored.
  const [exerciserSettings, setExerciserSettings] = useState<ExerciserSettings>(EXERCISER_DEFAULTS)
  const [manifestRun, setManifestRun] = useState<ManifestRun | null>(null)
  // Subscribed (not getState) so the per-key blocked reason follows list edits.
  const valueLists = useValueListStore((s) => s.lists)

  /** The steps the results panel shows: a ramp's steps ARE phases. */
  const sequencePhases = mode === 'ramp' ? rampPhases : phases

  /**
   * Apply a saved scenario to the page's working state (G.4). The page owns all
   * of it, so the scenario is unpacked here and nowhere else. The template is
   * copied (headers included) so editing the working copy never mutates the
   * stored scenario.
   */
  const applyScenario = useCallback((scenario: Scenario) => {
    const { config } = scenario
    setRateSettings({
      rate: config.rate,
      durationSecs: config.durationSecs,
      maxBatchSize: config.maxBatchSize,
      warnThreshold: config.warnThreshold,
      maxConsecErrors: config.maxConsecErrors,
    })
    setWorkingTemplate({ ...config.template, headers: { ...config.template.headers } })
    setPreviewIndex(config.previewIndex)
    setInitialTarget({ setupId: config.setupId, queueName: config.queueName })
    setTargetSeed((seed) => seed + 1)
    // A scenario replays in the mode it was captured in (G.3d). Phases are
    // copied, so editing the working shape never mutates the stored scenario.
    // Default to flat for the same reason the storage schema does: a scenario
    // written before modes existed has no mode, and setting it to undefined
    // would render NO Zone B at all.
    setMode(scenario.mode ?? 'flat')
    if (scenario.mode === 'profile') {
      setPhases((scenario.phases ?? []).map((p) => ({ ...p })))
      setPhaseResults([])
      setActivePhaseIndex(null)
    }
  }, [])

  useEffect(() => {
    // Tools page handoff: a scenario selected there becomes this page's working
    // configuration. Consumed (cleared) so a later plain visit starts clean.
    const selectedScenario = useScenarioStore.getState().selected
    if (selectedScenario) {
      applyScenario(selectedScenario)
      useScenarioStore.getState().select(null)
    }
    useTemplateStore.getState().select(null)
    // Load value lists on mount: Preview and the engine both snapshot the
    // valueListStore, and on a fresh page load it starts empty — without this,
    // every {{list:...}} token resolved to "" with a false missing-list warning.
    useValueListStore.getState().loadFromStorage()
  }, [applyScenario])

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

  // Clearing disables Start until the selector reports a valid pair again —
  // a queue-load failure on a newly selected setup must not leave Start armed
  // with the previous setup's target.
  const handleTargetCleared = useCallback(() => {
    setTarget((prev) => (prev === null ? prev : null))
  }, [])

  // Stop a still-running run when the page unmounts (navigation away). The
  // engine's onStop settles the store, so the run reports STOPPED, not limbo.
  useEffect(() => {
    return () => runHandleRef.current?.stop()
  }, [])

  // Stop a live profile on unmount too — otherwise navigating away leaves the
  // sequencer starting later phases with nothing showing them.
  useEffect(() => {
    return () => profileHandleRef.current?.stop()
  }, [])

  /**
   * Start a profile run (G.3c): the sequencer drives the same engine once per
   * phase. Each phase's rate/duration replaces the flat settings; everything
   * else (target, template, guards) is shared.
   */
  function handleStartProfile() {
    if (!target) return
    const base: RunConfig = {
      setupId: target.setupId,
      queueName: target.queueName,
      ...rateSettings,
      template: workingTemplate,
      previewIndex,
    }
    setPhaseResults([])
    setActivePhaseIndex(null)
    setRampHalt(null)
    // A ramp IS a profile whose steps are computed, plus a stop rule — the same
    // sequencer drives both.
    const isRamp = mode === 'ramp'
    const sequence = isRamp ? rampPhases : phases
    profileHandleRef.current = createProfileRunner().start(base, sequence, {
      shouldHaltAfterPhase: isRamp
        ? (result, results) => rampHaltReason(rampSettings, sequence, result, results)
        : undefined,
      onProfileHalted: (results, reason) => {
        profileHandleRef.current = null
        setActivePhaseIndex(null)
        const knee = sustainedRate(sequence, results)
        setRampHalt(
          knee === null
            ? `${reason}. No step sustained its requested rate.`
            : `${reason}. Max sustained rate: ${knee} msg/s.`
        )
      },
      onPhaseStart: (index) => setActivePhaseIndex(index),
      onPhaseComplete: (result) => setPhaseResults((prev) => [...prev, result]),
      onProfileComplete: (results) => {
        profileHandleRef.current = null
        setActivePhaseIndex(null)
        if (isRamp) {
          // A ramp that finished every step never found a knee — the target
          // kept up to the cap. Say that, rather than implying a limit.
          const knee = sustainedRate(sequence, results)
          setRampHalt(
            knee === null
              ? 'Ramp finished without sustaining any step — the target could not keep up even at the start rate.'
              : `Ramp reached the cap without a knee. Max sustained rate: ${knee} msg/s.`
          )
        }
        message.success(isRamp ? 'Ramp complete.' : 'Profile complete.')
      },
      onProfileStopped: () => {
        profileHandleRef.current = null
        setActivePhaseIndex(null)
        message.info(isRamp ? 'Ramp stopped.' : 'Profile stopped.')
      },
      onProfileError: (_results, reason) => {
        profileHandleRef.current = null
        setActivePhaseIndex(null)
        // Never silent: an aborted profile leaves later phases unrun, and the
        // reason names the phase that failed.
        message.error(reason)
      },
    })
    if (profileHandleRef.current === null) {
      message.error('Cannot start the profile — check it has at least one phase and no run is active.')
    }
  }

  function handleStart() {
    // Profile and Ramp are SEQUENCES of steps driven by the same runner —
    // testing for 'profile' alone silently ran a ramp as a single flat run.
    // The exerciser is NOT a sequence: it is one run whose messages carry
    // per-message ordering assignments, so it goes through the flat wiring
    // below with `ordering` on the config.
    if (mode === 'profile' || mode === 'ramp') {
      handleStartProfile()
      return
    }
    if (!target) return
    const isExerciser = mode === 'exerciser'
    // Captured here, not read in the terminal callback: the manifest must
    // describe the settings and value lists THIS run used, whatever the
    // controls or lists look like when it settles.
    const ordering = exerciserSettings
    const runValueLists = useValueListStore.getState().snapshot()
    const config: RunConfig = {
      setupId: target.setupId,
      queueName: target.queueName,
      ...rateSettings,
      template: workingTemplate,
      previewIndex,
      ...(isExerciser ? { ordering } : {}),
    }
    if (isExerciser) setManifestRun(null)
    // Shared wiring (runStarter): store-generated run id, callbacks, terminal
    // settling — identical for the Start button and the scheduler.
    runHandleRef.current = startGeneratorRun(config, {
      onTerminal: (summary, status, reason) => {
        runHandleRef.current = null
        if (isExerciser) {
          setManifestRun({
            settings: ordering,
            runId: summary.runId,
            attempted: summary.totalAttempted ?? 0,
            errors: summary.totalErrors,
            valueLists: runValueLists,
          })
        }
        // Manual runs join the run history like scheduled firings.
        useScheduleStore
          .getState()
          .recordManualRun(config, status as 'completed' | 'stopped' | 'error', summary, reason)
      },
    })
    if (runHandleRef.current === null) {
      // A scheduled run can start in the window between render (Start still
      // enabled) and this click. The refusal must not be silent.
      message.error('Cannot start — another run is active.')
    }
  }

  function handleStop() {
    // A profile stop must reach the SEQUENCER, not just the live phase: stopping
    // only the phase would let the next phase start immediately.
    if (profileHandleRef.current !== null) {
      profileHandleRef.current.stop()
      return
    }
    // Global stop: the RUNNING state shown here may belong to a scheduler or
    // "Run now" run — the page-local ref only reaches runs this page started.
    stopActiveRun()
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
      ...(mode === 'exerciser' ? { ordering: exerciserSettings } : {}),
    }
  }

  /**
   * Why Start must stay disabled in exerciser mode, or undefined. A per-key
   * group strategy with no usable list cannot assign groups — the engine would
   * refuse at the first tick (assignmentFor throws), so it is blocked here
   * with the reason instead.
   */
  function exerciserBlockedReason(): string | undefined {
    if (mode !== 'exerciser' || exerciserSettings.group.kind !== 'per-key') return undefined
    const { listName } = exerciserSettings.group
    if (listName === '') return 'Choose a value list for the per-key group strategy.'
    const list = valueLists.find((l) => l.name === listName)
    if (!list || list.values.length === 0) {
      return `Value list "${listName}" is missing or empty — the per-key group strategy cannot assign groups.`
    }
    return undefined
  }

  const scheduleConfig = scheduleModalOpen ? assembledConfig() : null

  return (
    <Space direction="vertical" style={{ width: '100%' }} data-testid="generator-page">
      <Title level={3}>Queue Message Generator</Title>

      <Card size="small">
        <Space wrap align="center" data-testid="generator-mode">
          <span>Mode</span>
          <Radio.Group
            value={mode}
            onChange={(e) => setMode(e.target.value)}
            disabled={running}
            optionType="button"
            options={[
              { label: 'Flat rate', value: 'flat' },
              { label: 'Profile', value: 'profile' },
              { label: 'Ramp', value: 'ramp' },
              { label: 'Delay / Prio / FIFO', value: 'exerciser' },
            ]}
          />
        </Space>
      </Card>

      <Card size="small">
        <ScenarioBar
          config={assembledConfig()}
          onLoad={applyScenario}
          disabled={running}
          mode={mode}
          phases={phases}
        />
      </Card>

      <Card title="Target" size="small">
        <div data-testid="zone-a">
          <TargetSelector
            key={targetSeed}
            initialTarget={initialTarget}
            onTargetSelected={handleTargetSelected}
            onTargetCleared={handleTargetCleared}
          />
        </div>
      </Card>

      <Card
        title={
          mode === 'profile'
            ? 'Traffic profile'
            : mode === 'ramp'
              ? 'Ramp to breaking point'
              : mode === 'exerciser'
                ? 'Ordering & scheduling'
                : 'Rate, duration & guards'
        }
        size="small"
      >
        {mode === 'profile' && (
          <ProfilePhasesEditor value={phases} onChange={setPhases} disabled={running} />
        )}
        {mode === 'ramp' && (
          <RampControls value={rampSettings} onChange={setRampSettings} disabled={running} />
        )}
        {mode === 'exerciser' && (
          // §19.5 Zone B is the ordering strategies PLUS rate/duration — an
          // exerciser is one flat run whose messages carry assignments.
          <>
            <ExerciserControls
              value={exerciserSettings}
              onChange={setExerciserSettings}
              disabled={running}
            />
            <div style={{ marginTop: 16 }}>
              <RateControls value={rateSettings} onChange={setRateSettings} disabled={running} />
            </div>
          </>
        )}
        {mode === 'flat' && (
          <RateControls value={rateSettings} onChange={setRateSettings} disabled={running} />
        )}
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
          startBlockedReason={
            mode === 'profile' && phases.length === 0
              ? 'Add at least one phase to run a profile.'
              : mode === 'ramp' && rampPhases.length === 0
                ? 'This ramp has no steps — the start rate is above the max rate.'
                : exerciserBlockedReason()
          }
          scheduleBlockedReason={
            mode === 'profile'
              ? 'Scheduling stores a single rate and duration, so it cannot carry a multi-phase profile. Schedule a flat-rate run instead.'
              : mode === 'ramp'
                ? 'Scheduling stores a single rate and duration, so it cannot carry a ramp. Schedule a flat-rate run instead.'
                : mode === 'exerciser'
                  ? 'The schedule surfaces do not show ordering strategies, so a scheduled exerciser run would read as a plain flat run. Schedule a flat-rate run instead.'
                  : undefined
          }
        />
      </Card>

      <Card
        title={mode === 'profile' ? 'Progress & results — active phase' : 'Progress & results'}
        size="small"
      >
        <ProgressPanel />
      </Card>

      {(mode === 'profile' || mode === 'ramp') && (
        <Card
          title={mode === 'ramp' ? 'Ramp — achieved vs requested per step' : 'Profile — achieved vs requested'}
          size="small"
        >
          {rampHalt && (
            <div style={{ marginBottom: 12 }}>
              <Alert type="info" showIcon data-testid="ramp-knee" message={rampHalt} />
            </div>
          )}
          <ProfileResultsPanel
            phases={sequencePhases}
            results={phaseResults}
            activeIndex={activePhaseIndex}
          />
        </Card>
      )}

      {mode === 'exerciser' && (
        <Card title="Sent manifest" size="small">
          <ManifestPanel run={manifestRun} />
        </Card>
      )}

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
