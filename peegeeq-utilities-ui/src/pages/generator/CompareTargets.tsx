/**
 * Zone A — Compare mode targets (design §19.2 — G.2b).
 *
 * §19.2 puts BOTH targets in Zone A ("Native queue: … / Outbox queue: …"), so
 * Compare replaces the single {@link TargetSelector} rather than adding a
 * control beside it. Zone B stays the ordinary shared RateControls.
 *
 * **Why this is not two `TargetSelector`s.** That component reports only
 * `(setupId, queueName)` — never the implementation type — and `comparePlan`
 * cannot validate the two roles without it. It also auto-selects the FIRST
 * queue, so two instances would both land on the same queue and the comparison
 * would refuse to start before the user had touched anything. This component
 * reuses the two SERVICES instead, and each row auto-selects the first queue of
 * its own type.
 *
 * A row whose setup holds no queue of its type selects NOTHING and says so.
 * Falling back to a wrong-type queue would build the exact native-vs-outbox
 * mismatch the comparison exists to avoid, and it would look deliberate.
 *
 * Loading and error behaviour mirrors TargetSelector's recorded contract: the
 * cause is shown, a Retry is offered, and a failure CLEARS the affected
 * selection rather than leaving a stale target armed — a target the UI no
 * longer shows must never stay armed for publishing.
 */
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, Button, Select, Space, Spin, Typography } from 'antd'
import { Link, useNavigate } from 'react-router-dom'
import { getSetups } from '../../services/setupService'
import { listQueueDetails } from '../../services/queueService'
import type { QueueSummary } from '../../types/queue'
import type { CompareSettings, CompareSideName, CompareTarget } from '../../types/compare'

const { Text } = Typography

const SIDES: readonly CompareSideName[] = ['native', 'outbox']

const SIDE_LABEL: Record<CompareSideName, string> = {
  native: 'Native queue',
  outbox: 'Outbox queue',
}

export interface CompareTargetsProps {
  /**
   * Fires with the current pair whenever either side changes. Either side is
   * null when it has no valid selection.
   *
   * Callers must pass a REFERENCE-STABLE callback (`useCallback`): this fires
   * from an effect, so an inline function re-runs that effect on every render.
   * MessageGeneratorPage carries the same note on its Zone A handlers.
   */
  onChange: (settings: CompareSettings) => void
  disabled?: boolean
}

export default function CompareTargets({ onChange, disabled = false }: CompareTargetsProps) {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [setups, setSetups] = useState<string[]>([])

  // Queues are cached per setup, so two rows on one setup cost one round trip.
  const [queuesBySetup, setQueuesBySetup] = useState<Record<string, QueueSummary[]>>({})
  /** Per setup, why its queue load failed. Keyed so one bad setup clears one row. */
  const [queueErrors, setQueueErrors] = useState<Record<string, string>>({})

  const [selectedSetup, setSelectedSetup] = useState<Record<CompareSideName, string | null>>({
    native: null,
    outbox: null,
  })
  const [selectedQueue, setSelectedQueue] = useState<Record<CompareSideName, string | null>>({
    native: null,
    outbox: null,
  })

  const loadSetups = useCallback(async () => {
    setLoading(true)
    setLoadError(null)
    try {
      const ids = await getSetups()
      setSetups(ids)
      if (ids.length === 0) {
        setSelectedSetup({ native: null, outbox: null })
        return
      }
      setSelectedSetup({ native: ids[0], outbox: ids[0] })
    } catch (error) {
      // The cause is carried, not replaced by a generic line — an unreachable
      // backend must not read as "no setups exist".
      console.error('Failed to load setups:', error)
      setLoadError(
        `Failed to load setups: ${error instanceof Error ? error.message : String(error)}. Check your connection.`
      )
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadSetups()
  }, [loadSetups])

  /** Every setup either row currently points at, once each. */
  const neededSetups = useMemo(() => {
    const ids: string[] = []
    for (const side of SIDES) {
      const setupId = selectedSetup[side]
      if (setupId !== null && !ids.includes(setupId)) ids.push(setupId)
    }
    return ids
  }, [selectedSetup])

  /**
   * Load queues for each setup a row points at, settling them INDEPENDENTLY.
   *
   * `Promise.all` would fail fast: one unreachable setup would blank both rows,
   * including the one whose setup answered perfectly well. The rows are
   * independent everywhere else and must be here too.
   *
   * A setup that fails is dropped from the cache and recorded with its cause,
   * which clears exactly the rows pointing at it — a selection the UI can no
   * longer justify must not survive as an armed publish target.
   */
  const loadQueues = useCallback(async (setupIds: string[]) => {
    const settled = await Promise.allSettled(setupIds.map((id) => listQueueDetails(id)))
    const loaded: Record<string, QueueSummary[]> = {}
    const failures: Record<string, string> = {}
    settled.forEach((result, index) => {
      const setupId = setupIds[index]
      if (result.status === 'fulfilled') {
        loaded[setupId] = result.value
        return
      }
      const cause = result.reason instanceof Error ? result.reason.message : String(result.reason)
      console.error(`Failed to load queues for ${setupId}:`, result.reason)
      failures[setupId] = cause
    })
    setQueuesBySetup(loaded)
    setQueueErrors(failures)
  }, [])

  useEffect(() => {
    if (neededSetups.length > 0) loadQueues(neededSetups)
  }, [neededSetups, loadQueues])

  /**
   * Auto-select each row's queue: the first of its OWN type; failing that the
   * first whose type is UNKNOWN; never one of the other type.
   *
   * The unknown fallback is load-bearing, not leniency. `listQueueDetails`
   * reports a null type when the backend sends the names-only payload, and
   * with an exact-match-only rule NEITHER row could select anything there —
   * Compare mode would be dead against that backend. `comparePlan` makes the
   * same three-way distinction: unknown is not a mismatch, and it is named in
   * the unverified warning below rather than passed off as confirmed.
   *
   * Runs whenever the available queues change. A row whose current queue is
   * still present is left alone, so this never fights a manual choice.
   */
  useEffect(() => {
    setSelectedQueue((previous) => {
      const next = { ...previous }
      let changed = false
      for (const side of SIDES) {
        const setupId = selectedSetup[side]
        const queues = setupId === null ? undefined : queuesBySetup[setupId]
        if (queues === undefined) {
          if (next[side] !== null) {
            next[side] = null
            changed = true
          }
          continue
        }
        if (next[side] !== null && queues.some((q) => q.name === next[side])) continue
        const match =
          queues.find((q) => q.implementationType === side) ??
          queues.find((q) => q.implementationType === null)
        const chosen = match ? match.name : null
        if (next[side] !== chosen) {
          next[side] = chosen
          changed = true
        }
      }
      return changed ? next : previous
    })
  }, [queuesBySetup, selectedSetup])

  /** The resolved target for a row, or null when it has no valid selection. */
  const targetFor = useCallback(
    (side: CompareSideName): CompareTarget | null => {
      const setupId = selectedSetup[side]
      const queueName = selectedQueue[side]
      if (setupId === null || queueName === null) return null
      const queue = queuesBySetup[setupId]?.find((q) => q.name === queueName)
      if (queue === undefined) return null
      return { setupId, queueName, implementationType: queue.implementationType }
    },
    [selectedSetup, selectedQueue, queuesBySetup]
  )

  // Memoised so the reported objects keep their identity between renders.
  // Rebuilding them on every render would change the onChange effect's
  // dependencies on every pass, and a parent that stores the result in state
  // would re-render forever. The component's own tests cannot see this — their
  // onChange is a spy that triggers no render.
  const native = useMemo(() => targetFor('native'), [targetFor])
  const outbox = useMemo(() => targetFor('outbox'), [targetFor])

  useEffect(() => {
    onChange({ native, outbox })
  }, [native, outbox, onChange])

  if (loading) {
    return (
      <Spin spinning={true}>
        <div data-testid="compare-loading" style={{ padding: 24 }} />
      </Spin>
    )
  }

  if (loadError) {
    return (
      <Alert
        type="error"
        showIcon
        data-testid="compare-load-error"
        message={loadError}
        action={
          <Button size="small" onClick={loadSetups}>
            Retry
          </Button>
        }
      />
    )
  }

  if (setups.length === 0) {
    return (
      <Alert
        type="info"
        showIcon
        message="No PeeGeeQ setup connected"
        description="A comparison needs one native and one outbox queue. Connect to an existing setup first."
        action={
          <Button type="primary" size="small" onClick={() => navigate('/setups/connect')}>
            Connect setup
          </Button>
        }
      />
    )
  }

  /** Queues whose type the backend did not report — selectable, but unverified. */
  const unverified = [native, outbox]
    .filter((target): target is CompareTarget => target !== null)
    .filter((target) => target.implementationType === null)
    .map((target) => target.queueName)

  function row(side: CompareSideName) {
    const setupId = selectedSetup[side]
    const queues = setupId === null ? [] : (queuesBySetup[setupId] ?? [])
    const hasMatch = queues.some((q) => q.implementationType === side)
    return (
      <div key={side} data-testid={`compare-row-${side}`}>
        <Space wrap align="center">
          <Text strong style={{ minWidth: 110, display: 'inline-block' }}>
            {SIDE_LABEL[side]}
          </Text>
          <label htmlFor={`compare-${side}-setup`}>Setup</label>
          <Select
            id={`compare-${side}-setup`}
            aria-label={`${SIDE_LABEL[side]} setup`}
            value={setupId ?? undefined}
            onChange={(next) => {
              setSelectedSetup((prev) => ({ ...prev, [side]: next }))
              // Cleared, not carried over: the new setup's queues decide.
              setSelectedQueue((prev) => ({ ...prev, [side]: null }))
            }}
            options={setups.map((s) => ({ value: s, label: s }))}
            disabled={disabled}
            style={{ minWidth: 160 }}
          />
          <label htmlFor={`compare-${side}-queue`}>Queue</label>
          <Select
            id={`compare-${side}-queue`}
            aria-label={`${SIDE_LABEL[side]} queue`}
            value={selectedQueue[side] ?? undefined}
            onChange={(next) => setSelectedQueue((prev) => ({ ...prev, [side]: next }))}
            options={queues.map((q) => ({
              value: q.name,
              label: q.implementationType ? `${q.name} (${q.implementationType})` : q.name,
            }))}
            disabled={disabled}
            style={{ minWidth: 200 }}
          />
        </Space>
        {!hasMatch && queues.length > 0 && (
          <div style={{ marginTop: 8 }}>
            <Alert
              type="warning"
              showIcon
              data-testid={`compare-no-match-${side}`}
              message={`No ${side} queue in ${setupId}`}
              description={
                <Text>
                  This comparison needs a {side} queue. Choose another setup, or add one on the{' '}
                  <Link to={setupId ? `/setups/${setupId}` : '/setups'}>Setups page</Link>.
                </Text>
              }
            />
          </div>
        )}
      </div>
    )
  }

  return (
    <Space direction="vertical" style={{ width: '100%' }} data-testid="compare-targets">
      {Object.entries(queueErrors).map(([setupId, cause]) => (
        <Alert
          key={setupId}
          type="error"
          showIcon
          data-testid="compare-queue-error"
          message={`Failed to load queues for ${setupId}: ${cause}. Check your connection.`}
          action={
            <Button size="small" onClick={() => loadQueues(neededSetups)}>
              Retry
            </Button>
          }
        />
      ))}
      {SIDES.map((side) => row(side))}
      {unverified.length > 0 && (
        <Alert
          type="warning"
          showIcon
          data-testid="compare-unverified-warning"
          message={`The backend did not report an implementation type for: ${unverified.join(', ')}`}
          description="The comparison will run, but it cannot confirm the two sides are actually native and outbox."
        />
      )}
    </Space>
  )
}
