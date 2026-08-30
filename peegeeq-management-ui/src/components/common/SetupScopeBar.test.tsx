import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { message } from 'antd'
import SetupScopeBar from './SetupScopeBar'
import { useManagementStore } from '../../stores/managementStore'
import { resetBackendConfig, saveBackendConfig } from '../../services/configService'
import { HttpTestServer } from '../../tests/fixtures/httpTestServer'

const writeJson = (response: import('node:http').ServerResponse, value: unknown, status = 200): void => {
  response.statusCode = status
  response.setHeader('Content-Type', 'application/json')
  response.end(JSON.stringify(value))
}

describe('SetupScopeBar', () => {
  let server: HttpTestServer

  beforeEach(async () => {
    localStorage.clear()
    useManagementStore.setState({ selectedSetupId: null, selectedQueueName: null })
    server = new HttpTestServer()
    const baseUrl = await server.start()
    saveBackendConfig({ apiUrl: baseUrl })
  })

  afterEach(async () => {
    message.destroy()
    resetBackendConfig()
    await server.stop()
  })

  it('loads setups and automatically selects the sole setup', async () => {
    server.setResponder((_request, response) => writeJson(response, { setupIds: ['alpha'] }))

    render(<SetupScopeBar extra={<span>Scoped content</span>} />)

    await waitFor(() => {
      expect(useManagementStore.getState().selectedSetupId).toBe('alpha')
    })
    expect(screen.getByTestId('scope-bar')).toBeTruthy()
    expect(screen.getByText('Scoped content')).toBeTruthy()
    expect(server.requests[0].url).toBe('/api/v1/setups')
  })

  it('loads array-form queue factories and automatically selects the sole queue', async () => {
    useManagementStore.setState({ selectedSetupId: 'alpha', selectedQueueName: null })
    server.setResponder((request, response) => {
      if (request.url === '/api/v1/setups/alpha') {
        writeJson(response, { queueFactories: ['orders'] })
        return
      }
      writeJson(response, { setupIds: ['alpha', 'beta'] })
    })

    render(<SetupScopeBar mode="setup+queue" />)

    await waitFor(() => {
      expect(useManagementStore.getState().selectedQueueName).toBe('orders')
    })
    expect(screen.getByTestId('queue-scope-selector')).toBeTruthy()
    expect(server.requests.map(request => request.url)).toContain('/api/v1/setups/alpha')
  })

  it('accepts object-form queue factories returned by older backends', async () => {
    useManagementStore.setState({ selectedSetupId: 'alpha', selectedQueueName: null })
    server.setResponder((request, response) => {
      if (request.url === '/api/v1/setups/alpha') {
        writeJson(response, { queueFactories: { orders: {}, billing: {} } })
        return
      }
      writeJson(response, { setupIds: ['alpha'] })
    })

    render(<SetupScopeBar mode="setup+queue" />)

    await waitFor(() => {
      expect(server.requests.map(request => request.url)).toContain('/api/v1/setups/alpha')
    })
    expect(useManagementStore.getState().selectedQueueName).toBeNull()
  })

  it('surfaces setup loading failures to the user', async () => {
    server.setResponder((_request, response) => writeJson(response, { message: 'unavailable' }, 503))

    render(<SetupScopeBar />)

    expect(await screen.findByText('Failed to load setups')).toBeTruthy()
    expect(useManagementStore.getState().selectedSetupId).toBeNull()
  })
})
