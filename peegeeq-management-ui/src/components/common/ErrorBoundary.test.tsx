import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import ErrorBoundary from './ErrorBoundary'

describe('ErrorBoundary', () => {
  it('renders its children while they are healthy', () => {
    render(
      <ErrorBoundary>
        <p>Healthy content</p>
      </ErrorBoundary>,
    )

    expect(screen.getByText('Healthy content')).toBeTruthy()
  })

  it('renders a custom fallback when a child fails', () => {
    const ExplodingChild = (): never => {
      throw new Error('component exploded')
    }

    render(
      <ErrorBoundary fallback={<p>Custom recovery view</p>}>
        <ExplodingChild />
      </ErrorBoundary>,
    )

    expect(screen.getByText('Custom recovery view')).toBeTruthy()
  })

  it('shows error details and can retry the child tree', async () => {
    let shouldThrow = true
    const RecoverableChild = () => {
      if (shouldThrow) throw new Error('temporary render failure')
      return <p>Recovered content</p>
    }

    render(
      <ErrorBoundary>
        <RecoverableChild />
      </ErrorBoundary>,
    )

    expect(screen.getByText('Something went wrong')).toBeTruthy()
    expect(screen.getByText('Error Details')).toBeTruthy()
    expect(screen.getByText(/Error: temporary render failure/)).toBeTruthy()

    shouldThrow = false
    await userEvent.click(screen.getByRole('button', { name: 'Try Again' }))

    expect(screen.getByText('Recovered content')).toBeTruthy()
  })
})
