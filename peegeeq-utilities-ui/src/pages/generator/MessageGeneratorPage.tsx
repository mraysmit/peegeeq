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
import RateControls from './RateControls'
import ProfilePhasesEditor from './ProfilePhasesEditor'
import ProfileResultsPanel from './ProfileResultsPanel'
import RampControls from './RampControls'
import ExerciserControls from './ExerciserControls'
import {
  blankTemplate,
  EXERCISER_DEFAULTS,
  makeDefaultPhase,
  RAMP_DEFAULTS,
  RATE_DEFAULTS,
  TRACE_DEFAULTS,
} from './generatorDefaults'
import ManifestPanel from './ManifestPanel'
import type { ManifestRun } from './ManifestPanel'
import TraceControls from './TraceControls'
import TraceSeedPanel from './TraceSeedPanel'
import type { TraceRun } from './TraceSeedPanel'
import CompareTargets from './CompareTargets'
import CompareResultsPanel from './CompareResultsPanel'
import { createProfileRunner } from '../../engine/profileRunner'
import type { ProfileHandle } from '../../engine/profileRunner'
import { createComparisonRunner } from '../../engine/comparisonRunner'
import type { CompareHandle } from '../../engine/comparisonRunner'
import { requestedFor, targetMismatchReason } from '../../engine/comparePlan'
import type {
  CompareReport,
  CompareSettings,
  CompareSideName,
  CompareSideProgress,
} from '../../types/compare'
import { buildRampPhases, rampHaltReason, sustainedRate } from '../../engine/rampPlan'
import type { ProfilePhase, ProfilePhaseResult } from '../../types/profile'
import type { RampSettings } from '../../types/ramp'
import type { ExerciserSettings } from '../../types/exerciser'
import type { TraceSettings } from '../../types/trace'
import TemplateEditor from './TemplateEditor'
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

  // Only BUILT modes are offered (G.3c, G.1a, G.5, G.6, G.2c) — a disabled
  // control would promise behaviour the app does not have.
  const [mode, setMode] = useState<
    'flat' | 'profile' | 'ramp' | 'exerciser' | 'trace' | 'compare'
  >('flat')
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

  // Trace-seed mode (G.6). The emitted-ids report is DERIVED after the run
  // from these captured inputs — settings, the run's rate/batch size, and the
  // summary's run id / attempted count. Nothing per-message is stored.
  const [traceSettings, setTraceSettings] = useState<TraceSettings>(TRACE_DEFAULTS)
  const [traceRun, setTraceRun] = useState<TraceRun | null>(null)

  // Compare mode (G.2). A comparison runs TWO engines at once, which the
  // generatorStore cannot represent — it holds one RunState — so the page
  // tracks its live state here and the store is left untouched. That is why
  // `comparing` exists rather than reading `status` for this mode.
  const [compareSettings, setCompareSettings] = useState<CompareSettings>({
    native: null,
    outbox: null,
  })
  const [compareProgress, setCompareProgress] = useState<
    Record<CompareSideName, CompareSideProgress | null>
  >({ native: null, outbox: null })
  const [compareReport, setCompareReport] = useState<CompareReport | null>(null)
  const [comparing, setComparing] = useState(false)
  const compareHandleRef = useRef<CompareHandle | null>(null)

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
  // A comparison never writes the store, so its live state must be folded in
  // here or Zone B/C and the mode selector would stay editable mid-comparison.
  const running = status === 'running' || comparing
  // What Zone D sees. In Compare mode the store is idle by design, so the
  // buttons would both be armed without this.
  const actionStatus = mode === 'compare' ? (comparing ? 'running' : 'idle') : status

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

  // Same for a live comparison: two engines publishing with nothing showing
  // them is worse than one, and only this page holds its state.
  useEffect(() => {
    return () => compareHandleRef.current?.stop()
  }, [])

  // Reference-stable: CompareTargets reports through an effect, so an inline
  // callback would re-run that effect on every render.
  const handleCompareTargetsChange = useCallback((settings: CompareSettings) => {
    setCompareSettings(settings)
  }, [])

  /**
   * Start a comparison (G.2c): two engines at once, driven by the comparison
   * runner. It does NOT go through runStarter — that refuses a second
   * concurrent run by design — so the page owns this run's live state.
   */
  function handleStartCompare() {
    const base: RunConfig = {
      // Placeholders: the runner overwrites both per side. They are only here
      // because RunConfig requires a target, and the shared load is what this
      // base actually carries.
      setupId: compareSettings.native?.setupId ?? '',
      queueName: compareSettings.native?.queueName ?? '',
      ...rateSettings,
      template: workingTemplate,
      previewIndex,
    }
    setCompareReport(null)
    setCompareProgress({ native: null, outbox: null })
    compareHandleRef.current = createComparisonRunner().start(base, compareSettings, {
      onSideProgress: (progress) =>
        setCompareProgress((prev) => ({ ...prev, [progress.side]: progress })),
      onCompareComplete: (report) => {
        compareHandleRef.current = null
        setComparing(false)
        setCompareProgress({ native: null, outbox: null })
        setCompareReport(report)
        message.success('Comparison complete.')
      },
      onCompareAborted: (reason) => {
        compareHandleRef.current = null
        setComparing(false)
        setCompareProgress({ native: null, outbox: null })
        // Never silent: no report will exist, and the reason says why.
        message.error(reason)
      },
    })
    // A synchronous refusal already reported itself through onCompareAborted;
    // marking the page as comparing would leave Stop armed over nothing.
    if (compareHandleRef.current === null) return
    setComparing(true)
  }

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
    // Compare is TWO concurrent runs, not a sequence and not one flat run —
    // it has its own runner and its own targets.
    if (mode === 'compare') {
      handleStartCompare()
      return
    }
    if (!target) return
    const isExerciser = mode === 'exerciser'
    const isTrace = mode === 'trace'
    // Captured here, not read in the terminal callback: the manifest and the
    // emitted-ids report must describe the settings and value lists THIS run
    // used, whatever the controls or lists look like when it settles.
    const ordering = exerciserSettings
    const trace = traceSettings
    const runValueLists = useValueListStore.getState().snapshot()
    const config: RunConfig = {
      setupId: target.setupId,
      queueName: target.queueName,
      ...rateSettings,
      template: workingTemplate,
      previewIndex,
      ...(isExerciser ? { ordering } : {}),
      ...(isTrace ? { trace } : {}),
    }
    if (isExerciser) setManifestRun(null)
    if (isTrace) setTraceRun(null)
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
        if (isTrace) {
          setTraceRun({
            settings: trace,
            runId: summary.runId,
            attempted: summary.totalAttempted ?? 0,
            errors: summary.totalErrors,
            rate: config.rate,
            maxBatchSize: config.maxBatchSize,
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
    // A comparison stop must reach BOTH engines. stopActiveRun below reaches
    // only the single store-backed run, which a comparison never uses.
    if (compareHandleRef.current !== null) {
      compareHandleRef.current.stop()
      return
    }
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
      ...(mode === 'trace' ? { trace: traceSettings } : {}),
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
              { label: 'Compare', value: 'compare' },
              { label: 'Delay / Prio / FIFO', value: 'exerciser' },
              { label: 'Trace seed', value: 'trace' },
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

      <Card title={mode === 'compare' ? 'Targets' : 'Target'} size="small">
        <div data-testid="zone-a">
          {mode === 'compare' ? (
            // §19.2 puts BOTH targets in Zone A, so Compare replaces the single
            // selector rather than adding one beside it.
            <CompareTargets onChange={handleCompareTargetsChange} disabled={running} />
          ) : (
            <TargetSelector
              key={targetSeed}
              initialTarget={initialTarget}
              onTargetSelected={handleTargetSelected}
              onTargetCleared={handleTargetCleared}
            />
          )}
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
                : mode === 'trace'
                  ? 'Correlation strategy'
                  : mode === 'compare'
                    ? 'Shared load'
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
        {mode === 'trace' && (
          // §19.6 Zone B is the correlation strategy PLUS rate/duration — a
          // trace-seed run is one flat run whose messages carry minted ids.
          <>
            <TraceControls value={traceSettings} onChange={setTraceSettings} disabled={running} />
            <div style={{ marginTop: 16 }}>
              <RateControls value={rateSettings} onChange={setRateSettings} disabled={running} />
            </div>
          </>
        )}
        {(mode === 'flat' || mode === 'compare') && (
          // §19.2 Zone B is the SHARED load — both sides get the same rate,
          // duration and guards, which is what makes the two comparable.
          <RateControls value={rateSettings} onChange={setRateSettings} disabled={running} />
        )}
      </Card>

      <Card title="Template" size="small">
        <TemplateEditor value={workingTemplate} onChange={setWorkingTemplate} disabled={running} />
      </Card>

      <Card title="Actions" size="small">
        <GeneratorActions
          template={workingTemplate}
          status={actionStatus}
          // Compare has its own two targets in Zone A, so the single-target
          // gate does not apply; its own blocked reason covers an invalid pair.
          targetSelected={mode === 'compare' ? true : target !== null}
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
                : mode === 'compare'
                  ? targetMismatchReason(compareSettings)
                  : exerciserBlockedReason()
          }
          scheduleBlockedReason={
            mode === 'profile'
              ? 'Scheduling stores a single rate and duration, so it cannot carry a multi-phase profile. Schedule a flat-rate run instead.'
              : mode === 'ramp'
                ? 'Scheduling stores a single rate and duration, so it cannot carry a ramp. Schedule a flat-rate run instead.'
                : mode === 'exerciser'
                  ? 'The schedule surfaces do not show ordering strategies, so a scheduled exerciser run would read as a plain flat run. Schedule a flat-rate run instead.'
                  : mode === 'trace'
                    ? 'The schedule surfaces do not show correlation strategies, so a scheduled trace-seed run would read as a plain flat run. Schedule a flat-rate run instead.'
                    : mode === 'compare'
                      ? 'Scheduling stores a single target, rate and duration, so it cannot carry a two-queue comparison. Schedule a flat-rate run instead.'
                      : undefined
          }
        />
      </Card>

      {/* A comparison never writes the generatorStore, so ProgressPanel — which
          reads it — would show a stale idle run beside live results. Compare
          gets its own panel instead. */}
      {mode === 'compare' ? (
        <Card title="Comparison results" size="small">
          <CompareResultsPanel
            progress={compareProgress}
            report={compareReport}
            requested={requestedFor(rateSettings)}
          />
        </Card>
      ) : (
        <Card
          title={mode === 'profile' ? 'Progress & results — active phase' : 'Progress & results'}
          size="small"
        >
          <ProgressPanel />
        </Card>
      )}

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

      {mode === 'trace' && (
        <Card title="Emitted ids" size="small">
          <TraceSeedPanel run={traceRun} />
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
