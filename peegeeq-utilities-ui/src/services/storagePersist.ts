/**
 * Shared localStorage write path for the generator services.
 *
 * `localStorage.setItem` throws on quota exhaustion or disabled storage. The
 * services' save functions run inside zustand `set()` callbacks, so an
 * uncaught throw surfaces as an unhandled exception in whatever click handler
 * or scheduler callback triggered the save — with no user feedback. Every
 * write goes through here instead: a failure is reported (console + antd
 * message) and contained. In-memory state then runs ahead of storage until
 * the next successful write; the user has been told their data is not
 * persisting.
 */
import { message } from 'antd'

export function persistJson(key: string, value: unknown, what: string): void {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch (error) {
    console.error(`Failed to persist ${what} (${key}):`, error)
    message.error(
      `Saving ${what} to browser storage failed: ${error instanceof Error ? error.message : String(error)}`
    )
  }
}
