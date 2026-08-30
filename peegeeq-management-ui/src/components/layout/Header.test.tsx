import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import Header from './Header'
import { useManagementStore } from '../../stores/managementStore'

class BrowserWebSocketFake {
  onopen: (() => void) | null = null
  onerror: ((event: Event) => void) | null = null
  onclose: (() => void) | null = null

  constructor(readonly url: string) {
    queueMicrotask(() => this.onopen?.())
  }

  close(): void {
    this.onclose?.()
  }
}

class BrowserEventSourceFake {
  onmessage: ((event: MessageEvent<string>) => void) | null = null
  onerror: ((event: Event) => void) | null = null

  constructor(readonly url: string) {
    queueMicrotask(() => this.onmessage?.(new MessageEvent('message', { data: '{"status":"UP"}' })))
  }

  close(): void {}
}

const LocationProbe = () => {
  const location = useLocation()
  return <output data-testid="current-route">{location.pathname}</output>
}

const originalFetch = globalThis.fetch
const originalWebSocket = globalThis.WebSocket
const originalEventSource = globalThis.EventSource

describe('Header', () => {
  beforeEach(() => {
    localStorage.clear()
    useManagementStore.setState({ notifications: [], unreadCount: 0 })
    globalThis.fetch = async () => new Response('{"status":"UP"}', {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
    globalThis.WebSocket = BrowserWebSocketFake as unknown as typeof WebSocket
    globalThis.EventSource = BrowserEventSourceFake as unknown as typeof EventSource
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
    globalThis.WebSocket = originalWebSocket
    if (originalEventSource) globalThis.EventSource = originalEventSource
    else delete (globalThis as { EventSource?: typeof EventSource }).EventSource
  })

  it.each([
    ['/', 'Overview'],
    ['/message-browser', 'Message Browser'],
    ['/queues/setup-a/orders', 'Queue Details'],
    ['/unrecognised', 'PeeGeeQ Management'],
  ])('resolves the page title for %s', async (path, title) => {
    render(
      <MemoryRouter initialEntries={[path]}>
        <Header />
      </MemoryRouter>,
    )

    expect(screen.getByTestId('page-title').textContent).toBe(title)
    await waitFor(() => {
      expect(screen.getByTestId('connection-status').textContent).toContain('Online')
    })
  })

  it('marks notifications read when opening the drawer and clears them', async () => {
    const user = userEvent.setup()
    useManagementStore.getState().addNotification({
      resource: 'orders',
      action: 'queue created',
      description: 'Orders queue was created',
    })

    render(
      <MemoryRouter>
        <Header />
      </MemoryRouter>,
    )

    expect(useManagementStore.getState().unreadCount).toBe(1)
    await user.click(screen.getByTestId('notifications-btn'))

    expect(await screen.findByText('Orders queue was created')).toBeTruthy()
    expect(useManagementStore.getState().unreadCount).toBe(0)

    await user.click(screen.getByRole('button', { name: /Clear/ }))

    expect(await screen.findByText('No notifications')).toBeTruthy()
    expect(useManagementStore.getState().notifications).toEqual([])
  })

  it('navigates to settings from the user menu', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/overview']}>
        <Header />
        <LocationProbe />
      </MemoryRouter>,
    )

    await user.click(screen.getByTestId('user-menu-btn'))
    await user.click(await screen.findByText('Settings'))

    expect(screen.getByTestId('current-route').textContent).toBe('/settings')
  })
})
