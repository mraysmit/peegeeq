import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { message } from 'antd'
import EventsPage from './EventsPage'
import { useManagementStore } from '../stores/managementStore'
import { resetBackendConfig, saveBackendConfig } from '../services/configService'
import { HttpTestServer } from '../tests/fixtures/httpTestServer'

const writeJson = (response: import('node:http').ServerResponse, value: unknown): void => {
  response.setHeader('Content-Type', 'application/json')
  response.end(JSON.stringify(value))
}

describe('EventsPage', () => {
  let server: HttpTestServer

  beforeEach(async () => {
    localStorage.clear()
    useManagementStore.setState({ selectedSetupId: 'alpha', selectedQueueName: null })
    server = new HttpTestServer()
    const baseUrl = await server.start()
    saveBackendConfig({ apiUrl: baseUrl })
    server.setResponder((request, response) => {
      if (request.url === '/api/v1/setups') {
        writeJson(response, { setupIds: ['alpha'] })
        return
      }
      if (request.url === '/api/v1/management/event-stores') {
        writeJson(response, { eventStores: [{ setup: 'alpha', name: 'audit', events: 2 }] })
        return
      }
      if (request.url === '/api/v1/eventstores/alpha/audit/events?limit=1000') {
        writeJson(response, {
          events: [
            {
              eventId: 'event-1',
              eventType: 'OrderCreated',
              eventData: { orderId: 'order-1' },
              validTime: '2026-08-30T09:00:00Z',
              transactionTime: '2026-08-30T09:00:01Z',
              correlationId: 'workflow-42',
              aggregateId: 'order-1',
              aggregateType: 'Order',
              streamId: 'orders',
              version: 2,
            },
            {
              eventId: 'event-2',
              eventType: 'CustomerRegistered',
              eventData: { customerId: 'customer-1' },
              validTime: '2026-08-30T10:00:00Z',
              transactionTime: '2026-08-30T10:00:01Z',
              causationId: 'signup-7',
              aggregateId: 'customer-1',
              aggregateType: 'Customer',
              streamId: 'customers',
              version: 1,
            },
          ],
          totalCount: 2_500,
          hasMore: true,
        })
        return
      }
      response.statusCode = 404
      writeJson(response, { message: `Unexpected route: ${request.url}` })
    })
  })

  afterEach(async () => {
    message.destroy()
    resetBackendConfig()
    await server.stop()
  })

  it('loads scoped events, surfaces truncation, and applies combined client-side filters', async () => {
    const user = userEvent.setup()
    render(<EventsPage />)

    const eventStoreSelect = screen.getByTestId('query-eventstore-select')
    await waitFor(() => {
      expect(eventStoreSelect.className).not.toContain('ant-select-disabled')
      expect(server.requests.map(request => request.url)).toContain('/api/v1/management/event-stores')
    })

    fireEvent.mouseDown(within(eventStoreSelect).getByRole('combobox'))
    await user.click(await screen.findByText('audit (2 events)'))
    await user.click(screen.getByRole('button', { name: /Load Events/ }))

    expect(await screen.findByText('OrderCreated')).toBeTruthy()
    expect(screen.getByText('CustomerRegistered')).toBeTruthy()
    expect(screen.getByText('Events (2)')).toBeTruthy()
    expect(screen.getByTestId('events-truncated-alert').textContent).toContain('Showing first 2 of 2,500 events')

    await user.type(screen.getByPlaceholderText('Event Type'), 'order')
    await waitFor(() => expect(screen.getByText('Events (1)')).toBeTruthy())
    expect(screen.queryByText('CustomerRegistered')).toBeNull()

    await user.type(screen.getByPlaceholderText('Correlation/Causation ID'), 'workflow-42')
    expect(screen.getByText('Events (1)')).toBeTruthy()

    await user.clear(screen.getByPlaceholderText('Event Type'))
    await user.clear(screen.getByPlaceholderText('Correlation/Causation ID'))
    await user.type(screen.getByPlaceholderText('Aggregate Type'), 'customer')

    await waitFor(() => expect(screen.getByText('Events (1)')).toBeTruthy())
    expect(screen.getByText('CustomerRegistered')).toBeTruthy()
    expect(screen.queryByText('OrderCreated')).toBeNull()
  })
})
