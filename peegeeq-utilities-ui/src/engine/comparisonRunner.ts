/**
 * Native-vs-Outbox comparison runner (design §19.2 — Phase G.2a).
 *
 * Fires identical load at one native and one outbox queue AT THE SAME TIME and
 * collects each side's outcome next to the other's, with the backend telemetry
 * sampled either side of the run.
 *
 * **Why this does not go through `runStarter`/`generatorStore`.** Both are
 * single-run singletons: `startGeneratorRun` returns null while a run is
 * active, and `generatorStore` holds ONE `RunState`. That is correct for every
 * other mode — flat, exerciser and trace are one run, and profile/ramp are a
 * SEQUENCE of runs, one live at a time. A comparison is the first mode with
 * two runs live at once, and the store cannot represent it: writing both sides
 * into one `RunState` would report a merged run that neither side performed.
 * So this runner drives two {@link PublicationEngine} instances directly, with
 * caller-supplied identities (the §7/B.0 contract the engine already has), and
 * owns its own per-side state. Nothing here touches the store.
 *
 * **Telemetry is sampled twice, not streamed.** `pg_stat_*` counters and the
 * `/stats` percentile histograms are cumulative, so a run is described by the
 * baseline-to-final delta (telemetry §4A "Deltas"). Two samples are the whole
 * requirement; §4A also says these queries are heavier than a counter read and
 * should not be polled at 1 Hz. The baseline is taken BEFORE either side
 * publishes — taken afterwards it would understate every delta.
 *
 * **Telemetry never gates the run.** A failed read is recorded as its reason
 * (a {@link TelemetryCapture}) and the publishing continues. The reverse — a
 * telemetry failure aborting a run that is already putting load on a database
 * — would be worse, and a read that silently returned zeroes would report an
 * idle database for a run that hammered it.
 */
import { message } from 'antd'
import { createPublicationEngine } from './publicationEngine'
import type { PublicationEngine } from './publicationEngine'
import { checkCompareTargets, distinctSetupIds } from './comparePlan'
import { captureDbTelemetry, captureQueueStats } from '../services/telemetryService'
import type { RunConfig, RunSummary } from '../types/generator'
import type {
  CompareReport,
  CompareSettings,
  CompareSideName,
  CompareSideProgress,
  CompareSideResult,
  CompareTarget,
  DbTelemetrySnapshot,
  QueueStatsSnapshot,
  TelemetryCapture,
} from '../types/compare'

/** The two sides, in the order they are started and reported. */
const SIDES: readonly CompareSideName[] = ['native', 'outbox']

export interface CompareHooks {
  /** Fires on every engine tick, per side, while both are live. */
  onSideProgress?(progress: CompareSideProgress): void
  /** Fires as each side reaches a terminal state, before the other may have. */
  onSideSettled?(result: CompareSideResult): void
  /** Fires once, after BOTH sides settled and the final telemetry was sampled. */
  onCompareComplete?(report: CompareReport): void
  /**
   * Fires when no report will exist, with the reason: an invalid target pair,
   * or a stop that landed before either side started publishing. Not an error
   * channel — it is the "there is nothing to report, and here is why" signal.
   */
  onCompareAborted?(reason: string): void
}

export interface CompareHandle {
  /** Stop both sides. Each settles through its own engine callback. */
  stop(): void
}

export interface ComparisonRunner {
  start(base: RunConfig, settings: CompareSettings, hooks: CompareHooks): CompareHandle | null
}

export interface ComparisonRunnerDeps {
  createEngine?: () => PublicationEngine
  captureStats?: (
    setupId: string,
    queueName: string
  ) => Promise<TelemetryCapture<QueueStatsSnapshot>>
  captureDb?: (setupId: string) => Promise<TelemetryCapture<DbTelemetrySnapshot>>
  newRunId?: () => string
}

/** One baseline-or-final telemetry sample across both sides and every setup. */
interface TelemetrySample {
  stats: Record<CompareSideName, TelemetryCapture<QueueStatsSnapshot>>
  db: Record<string, TelemetryCapture<DbTelemetrySnapshot>>
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

/**
 * The summary for a side whose engine refused to start.
 *
 * Mirrors runStarter's synthetic failure summary: the side produced no run, so
 * every count is genuinely zero — this is not a fabricated measurement, it is
 * the record of a run that never began, and its `finalStatus` says so.
 */
function failedToStartSummary(config: RunConfig, runId: string): RunSummary {
  return {
    totalSent: 0,
    totalAttempted: 0,
    targetTotal: config.rate * config.durationSecs,
    avgRate: 0,
    durationMs: 0,
    totalErrors: 0,
    finalStatus: 'error',
    runId,
    errors: [],
  }
}

export function createComparisonRunner(deps: ComparisonRunnerDeps = {}): ComparisonRunner {
  const createEngine = deps.createEngine ?? createPublicationEngine
  const captureStats = deps.captureStats ?? captureQueueStats
  const captureDb = deps.captureDb ?? captureDbTelemetry
  const newRunId = deps.newRunId ?? (() => crypto.randomUUID())

  return {
    start(base, settings, hooks) {
      const check = checkCompareTargets(settings)
      if (!check.ok) {
        hooks.onCompareAborted?.(check.reason)
        return null
      }
      const targets: Record<CompareSideName, CompareTarget> = {
        native: check.native,
        outbox: check.outbox,
      }
      const setupIds = distinctSetupIds(settings)

      const engines: PublicationEngine[] = []
      let nativeResult: CompareSideResult | null = null
      let outboxResult: CompareSideResult | null = null
      let baseline: TelemetrySample | null = null
      let stopRequested = false
      let settled = false

      /**
       * One telemetry sample. Every dep call is shielded so this can never
       * reject: an injected reader that throws instead of returning a capture
       * must not take down a comparison, and its failure is still stated.
       */
      async function sample(): Promise<TelemetrySample> {
        const [nativeStats, outboxStats, dbCaptures] = await Promise.all([
          safeStats(targets.native),
          safeStats(targets.outbox),
          Promise.all(setupIds.map((setupId) => safeDb(setupId))),
        ])
        const db: Record<string, TelemetryCapture<DbTelemetrySnapshot>> = {}
        setupIds.forEach((setupId, index) => {
          db[setupId] = dbCaptures[index]
        })
        return { stats: { native: nativeStats, outbox: outboxStats }, db }
      }

      async function safeStats(
        target: CompareTarget
      ): Promise<TelemetryCapture<QueueStatsSnapshot>> {
        try {
          return await captureStats(target.setupId, target.queueName)
        } catch (error) {
          return {
            ok: false,
            error: `Queue stats for ${target.setupId}/${target.queueName} could not be read: ${messageOf(error)}`,
          }
        }
      }

      async function safeDb(setupId: string): Promise<TelemetryCapture<DbTelemetrySnapshot>> {
        try {
          return await captureDb(setupId)
        } catch (error) {
          return {
            ok: false,
            error: `Database telemetry for ${setupId} could not be read: ${messageOf(error)}`,
          }
        }
      }

      function abort(reason: string): void {
        if (settled) return
        settled = true
        hooks.onCompareAborted?.(reason)
      }

      function settleSide(side: CompareSideName, result: CompareSideResult): void {
        // An engine settles once, but a stop racing a completion could deliver
        // two terminal callbacks; the first outcome is the real one.
        if (side === 'native') {
          if (nativeResult !== null) return
          nativeResult = result
        } else {
          if (outboxResult !== null) return
          outboxResult = result
        }
        hooks.onSideSettled?.(result)
        finishWhenBothSettled()
      }

      function finishWhenBothSettled(): void {
        const native = nativeResult
        const outbox = outboxResult
        if (settled || native === null || outbox === null) return
        settled = true
        sample()
          .then((final) => {
            const report: CompareReport = {
              native,
              outbox,
              telemetry: {
                stats: {
                  native: { baseline: baselineStats('native'), final: final.stats.native },
                  outbox: { baseline: baselineStats('outbox'), final: final.stats.outbox },
                },
                db: pairDb(final),
              },
            }
            hooks.onCompareComplete?.(report)
          })
          .catch((error: unknown) => {
            // Reached only when a hook or an injected reader throws outside the
            // shields above. The comparison DID run; losing its outcome
            // silently is the failure this reports.
            console.error('Comparison reporting failed:', error)
            message.error(`Comparison finished but reporting it failed: ${messageOf(error)}`)
          })
      }

      /**
       * The baseline capture for a side.
       *
       * `baseline` is assigned in the same continuation that then starts the
       * engines, so by the time any side can settle it is set. It is still
       * typed nullable up to that assignment, and this states the gap in words
       * rather than asserting it away with `!` — an unexplained non-null
       * assertion is how a real null reaches a report as a silent zero.
       */
      function baselineStats(side: CompareSideName): TelemetryCapture<QueueStatsSnapshot> {
        return (
          baseline?.stats[side] ?? {
            ok: false,
            error: 'Baseline queue stats were not sampled before the run started.',
          }
        )
      }

      function pairDb(final: TelemetrySample) {
        const db: CompareReport['telemetry']['db'] = {}
        for (const setupId of setupIds) {
          db[setupId] = {
            baseline: baseline?.db[setupId] ?? {
              ok: false,
              error: `Baseline database telemetry for ${setupId} was not sampled before the run started.`,
            },
            final: final.db[setupId],
          }
        }
        return db
      }

      function startSide(side: CompareSideName): void {
        const target = targets[side]
        const config: RunConfig = {
          ...base,
          setupId: target.setupId,
          queueName: target.queueName,
        }
        const runId = newRunId()
        const engine = createEngine()
        engines.push(engine)
        try {
          engine.start(
            config,
            { runId, correlationId: newRunId() },
            {
              onTick: (sent, errors, _consecErrors, elapsedMs) =>
                hooks.onSideProgress?.({ side, sent, errors: errors.length, elapsedMs }),
              onComplete: (summary) => settleSide(side, { side, target, summary }),
              onStop: (summary) => settleSide(side, { side, target, summary }),
              onError: (summary, reason) =>
                settleSide(side, { side, target, summary, errorReason: reason }),
            }
          )
        } catch (error) {
          // A synchronous start failure (the value-list snapshot throwing, for
          // one) must settle THIS side rather than leaving the comparison
          // waiting forever on a run that never began. The other side is left
          // alone: it is already publishing real load.
          settleSide(side, {
            side,
            target,
            summary: failedToStartSummary(config, runId),
            errorReason: `Run failed to start: ${messageOf(error)}`,
          })
        }
      }

      sample()
        .then((captured) => {
          if (stopRequested) {
            abort('Comparison stopped before it started publishing.')
            return
          }
          baseline = captured
          for (const side of SIDES) startSide(side)
        })
        .catch((error: unknown) => {
          console.error('Comparison baseline sampling failed:', error)
          abort(`Comparison could not start: ${messageOf(error)}`)
        })

      return {
        stop() {
          if (settled || stopRequested) return
          stopRequested = true
          for (const engine of engines) engine.stop()
        },
      }
    },
  }
}
