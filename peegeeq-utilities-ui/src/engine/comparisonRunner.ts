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
 *
 * **The FINAL database sample is re-read until it accounts for the run (G.2e).**
 * PostgreSQL does not make a committed INSERT visible in `pg_stat_user_tables`
 * at commit — each backend accumulates its counters and flushes them on a rate
 * limit — and the two sides commit on DIFFERENT connections. A single read
 * after both sides settle can therefore report one side's inserts correctly and
 * the other's as zero, which is how "10 acknowledged, 0 rows inserted" reached
 * a screenshot. The fix observes the condition rather than guessing an
 * interval: re-read until each side's insert delta reaches the rows that side
 * acknowledged, bounded by a deadline, and record the read as unusable if it
 * never does. Agreement between two consecutive samples is NOT the condition —
 * two reads of a backend that has not flushed agree with each other.
 */
import { message } from 'antd'
import { createPublicationEngine } from './publicationEngine'
import type { PublicationEngine } from './publicationEngine'
import { CHURN_TABLE, checkCompareTargets, churnDeltaFor, churnReconciled, distinctSetupIds } from './comparePlan'
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
  TelemetryPair,
} from '../types/compare'

/** The two sides, in the order they are started and reported. */
const SIDES: readonly CompareSideName[] = ['native', 'outbox']

/**
 * How long to wait between re-reads of an unreconciled final database sample.
 * Matches the backend's own churn poll (`DatabaseTelemetryHandlerIntegrationTest`),
 * which faces the same statistics-flush lag.
 */
export const CHURN_SETTLE_INTERVAL_MS = 500

/**
 * How long to keep re-reading before reporting the churn as unavailable.
 *
 * Past this the run's figures are stated as unknown rather than as the last
 * reading, which would be a row count the database had not yet made visible.
 *
 * MEASURED, not guessed. A 10-message-per-side comparison against a
 * TestContainers PostgreSQL settled after 17 attempts spanning 9.0 s. The
 * backend's own churn poll uses 15 s for the same lag, which would have left a
 * 1.66x margin over a measurement taken on an idle machine with a trivial load
 * — too close for a figure whose failure mode is silently rendering as unknown.
 * 60 s is that measurement with room for a slower machine and a real load. The
 * cost of the larger bound is paid only when the counters never catch up, which
 * is the case where the answer is "unknown" regardless.
 */
export const CHURN_SETTLE_DEADLINE_MS = 60_000

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
  setTimer?: (fn: () => void, ms: number) => ReturnType<typeof setTimeout>
}

/** One baseline-or-final telemetry sample across both sides and every setup. */
interface TelemetrySample {
  stats: Record<CompareSideName, TelemetryCapture<QueueStatsSnapshot>>
  db: Record<string, TelemetryCapture<DbTelemetrySnapshot>>
}

/**
 * What one setup's final database sample must show before its churn figures
 * describe the run.
 *
 * Built only for setups whose BASELINE read succeeded and whose sides actually
 * acknowledged something: with no baseline there is no delta to reconcile, and
 * a run that sent nothing is owed no rows. Waiting in either case would burn
 * the whole deadline to reach the same answer.
 */
interface ChurnExpectation {
  setupId: string
  /** The setup's baseline capture, known to have succeeded. */
  baseline: TelemetryCapture<DbTelemetrySnapshot>
  sides: Array<{ table: string; acknowledged: number }>
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
  const setTimer = deps.setTimer ?? ((fn, ms) => setTimeout(fn, ms))

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

      /** A delay expressed as a promise, so the poll reads as a sequence. */
      function delay(ms: number): Promise<void> {
        return new Promise((resolve) => {
          setTimer(() => resolve(), ms)
        })
      }

      /**
       * What each setup's final sample must show, for the setups where that
       * question can be answered at all.
       */
      function churnExpectations(sides: CompareSideResult[]): ChurnExpectation[] {
        const bySetup = new Map<string, ChurnExpectation>()
        for (const result of sides) {
          if (result.summary.totalSent <= 0) continue
          const setupId = result.target.setupId
          const baselineCapture = baseline?.db[setupId]
          if (baselineCapture === undefined || !baselineCapture.ok) continue
          const expectation = bySetup.get(setupId) ?? {
            setupId,
            baseline: baselineCapture,
            sides: [],
          }
          expectation.sides.push({
            table: CHURN_TABLE[result.side],
            acknowledged: result.summary.totalSent,
          })
          bySetup.set(setupId, expectation)
        }
        return [...bySetup.values()]
      }

      /**
       * Whether re-reading could still change this setup's churn figures.
       *
       * A failed read can succeed next time. An insert delta short of the
       * acknowledged rows can catch up when the writing backend flushes. A
       * table the snapshot does not carry cannot appear by waiting — that is an
       * absent table, not a stale counter.
       */
      function worthReReading(expectation: ChurnExpectation, current: TelemetrySample): boolean {
        const capture = current.db[expectation.setupId]
        if (!capture.ok) return true
        const pair: TelemetryPair<DbTelemetrySnapshot> = {
          baseline: expectation.baseline,
          final: capture,
        }
        return expectation.sides.some(
          (side) =>
            churnDeltaFor(pair, side.table) !== null &&
            !churnReconciled(pair, side.table, side.acknowledged)
        )
      }

      /**
       * Record each unreconciled setup's final read as unusable, carrying why.
       *
       * This reuses the existing failure channel rather than adding a new one:
       * `churnDeltaFor` already yields null for a failed capture, so the panel
       * renders the churn as unknown and the reason lands in its telemetry
       * warning. Handing back the last reading instead would print a row count
       * the database had not yet made visible.
       */
      function markChurnUnusable(
        current: TelemetrySample,
        outstanding: ChurnExpectation[]
      ): TelemetrySample {
        const db = { ...current.db }
        for (const expectation of outstanding) {
          const capture = db[expectation.setupId]
          const cause = capture.ok
            ? `its counters never reached the rows this run acknowledged (${expectation.sides
                .map((side) => `${side.table} needs ${side.acknowledged}`)
                .join(', ')})`
            : capture.error
          db[expectation.setupId] = {
            ok: false,
            error: `Database telemetry for ${expectation.setupId} did not settle within ${
              CHURN_SETTLE_DEADLINE_MS / 1000
            }s, so the churn for this run cannot be stated: ${cause}`,
          }
        }
        return { ...current, db }
      }

      /**
       * Re-read the final database sample until it accounts for the run.
       *
       * Recursive rather than a loop so each attempt is a fresh continuation
       * with its own deadline check, and so the interval is a scheduled wait
       * rather than a blocking one.
       */
      async function settleChurn(
        current: TelemetrySample,
        expectations: ChurnExpectation[],
        deadline: number
      ): Promise<TelemetrySample> {
        const outstanding = expectations.filter((e) => worthReReading(e, current))
        if (outstanding.length === 0) return current
        if (Date.now() >= deadline) return markChurnUnusable(current, outstanding)

        await delay(CHURN_SETTLE_INTERVAL_MS)
        const captures = await Promise.all(outstanding.map((e) => safeDb(e.setupId)))
        const db = { ...current.db }
        outstanding.forEach((expectation, index) => {
          db[expectation.setupId] = captures[index]
        })
        // Only the db sample is re-read. Re-reading /stats would move the
        // latency sample delta after the run had already ended, crediting this
        // run with measurements taken after it finished.
        return settleChurn({ ...current, db }, expectations, deadline)
      }

      function finishWhenBothSettled(): void {
        const native = nativeResult
        const outbox = outboxResult
        if (settled || native === null || outbox === null) return
        settled = true
        sample()
          .then((captured) =>
            settleChurn(
              captured,
              churnExpectations([native, outbox]),
              Date.now() + CHURN_SETTLE_DEADLINE_MS
            )
          )
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
