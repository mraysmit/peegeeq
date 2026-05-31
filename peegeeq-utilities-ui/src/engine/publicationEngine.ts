/**
 * Client-side publication engine (§7, §13 of the feature design).
 *
 * Drives a `setInterval` tick loop that generates and publishes batches of
 * messages from a template at a target rate. The engine holds no persistent
 * state and is created fresh for each run; it reports progress and terminal
 * outcomes through {@link EngineCallbacks}. The generatorStore owns all state.
 */
import type {
  RunConfig,
  RunSummary,
  RunStatus,
  PublishError,
} from '../types/generator'
import type { BatchMessageRequest, MessageRequest } from '../types/queue'
import { resolveTemplate } from './templateResolver'
import { publishBatch } from '../services/publishService'
import { useValueListStore } from '../stores/valueListStore'

export interface EngineCallbacks {
  onTick(sent: number, errors: PublishError[], consecErrors: number, elapsedMs: number): void
  onComplete(summary: RunSummary): void
  onStop(summary: RunSummary): void
  onError(summary: RunSummary, reason: string): void
}

export interface PublicationEngine {
  start(config: RunConfig, callbacks: EngineCallbacks): void
  stop(): void
}

/** Extract an HTTP status code from a thrown publish error, if present. */
function httpStatusOf(error: unknown): number | undefined {
  const response = (error as { response?: { status?: number } })?.response
  return typeof response?.status === 'number' ? response.status : undefined
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

/** Create a new, single-use publication engine. */
export function createPublicationEngine(): PublicationEngine {
  let timer: ReturnType<typeof setInterval> | null = null
  let inFlight = false
  let finished = false

  let config: RunConfig
  let callbacks: EngineCallbacks
  let valueLists: Record<string, string[]>

  let runId: string
  let correlationId: string
  let startedAt = 0
  let nextMessageId = 1
  let sent = 0
  let consecErrors = 0
  const errors: PublishError[] = []

  function buildSummary(finalStatus: RunStatus): RunSummary {
    const durationMs = Date.now() - startedAt
    return {
      totalSent: sent,
      targetTotal: config.rate * config.durationSecs,
      avgRate: durationMs > 0 ? sent / (durationMs / 1000) : 0,
      durationMs,
      totalErrors: errors.length,
      finalStatus,
      runId,
      errors: [...errors],
    }
  }

  function clear(): void {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  function buildBatch(batchSize: number): BatchMessageRequest {
    const { template } = config
    const messages: MessageRequest[] = []
    const now = new Date()
    for (let i = 0; i < batchSize; i++) {
      const messageId = nextMessageId++
      const payload = resolveTemplate(template.payloadSchema, {
        messageId,
        index: messageId - 1,
        runId,
        correlationId,
        now,
        valueLists,
      })
      messages.push({
        payload,
        headers: template.headers,
        messageType: template.messageType,
        correlationId,
        priority: template.priority,
        delaySeconds: template.delaySeconds,
        messageGroup: template.messageGroup,
      })
    }
    return { messages, maxBatchSize: config.maxBatchSize }
  }

  async function tick(): Promise<void> {
    if (finished || inFlight) return

    if (Date.now() - startedAt >= config.durationSecs * 1000) {
      finished = true
      clear()
      callbacks.onComplete(buildSummary('completed'))
      return
    }

    inFlight = true
    const batchSize = Math.min(config.maxBatchSize, Math.max(1, Math.floor(config.rate)))
    const batch = buildBatch(batchSize)
    const firstIndex = nextMessageId - batchSize - 1

    try {
      const result = await publishBatch(config.setupId, config.queueName, batch)
      sent += result.messagesSent
      consecErrors = 0
    } catch (error) {
      consecErrors++
      errors.push({
        messageIndex: firstIndex,
        httpStatus: httpStatusOf(error),
        message: messageOf(error),
        timestamp: new Date().toISOString(),
      })
      if (config.maxConsecErrors > 0 && consecErrors >= config.maxConsecErrors) {
        finished = true
        clear()
        inFlight = false
        const reason = `Auto-stopped: ${consecErrors} consecutive errors. Last: ${messageOf(error)}`
        callbacks.onError(buildSummary('error'), reason)
        return
      }
    } finally {
      inFlight = false
    }

    if (!finished) {
      callbacks.onTick(sent, [...errors], consecErrors, Date.now() - startedAt)
    }
  }

  return {
    start(runConfig, runCallbacks) {
      config = runConfig
      callbacks = runCallbacks
      valueLists = useValueListStore.getState().snapshot()
      runId = crypto.randomUUID()
      correlationId = crypto.randomUUID()
      startedAt = Date.now()
      nextMessageId = 1
      sent = 0
      consecErrors = 0
      errors.length = 0
      finished = false
      inFlight = false

      const batchSize = Math.min(config.maxBatchSize, Math.max(1, Math.floor(config.rate)))
      const tickMs = (batchSize / config.rate) * 1000
      timer = setInterval(() => void tick(), tickMs)
    },

    stop() {
      if (finished) return
      finished = true
      clear()
      callbacks.onStop(buildSummary('stopped'))
    },
  }
}
