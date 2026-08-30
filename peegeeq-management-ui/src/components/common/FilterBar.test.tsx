import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import FilterBar from './FilterBar'

const FilterHarness = () => {
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState<string | string[] | undefined>(undefined)

  return (
    <>
      <FilterBar
        searchPlaceholder="Find a queue"
        searchValue={search}
        onSearch={setSearch}
        filters={[{
          label: 'Status',
          value: status,
          options: [
            { label: 'Active', value: 'active' },
            { label: 'Paused', value: 'paused' },
          ],
          onChange: setStatus,
        }]}
        onClear={() => {
          setSearch('')
          setStatus(undefined)
        }}
        extra={<span>2 queues</span>}
        style={{ border: '1px solid blue' }}
      />
      <output data-testid="filter-state">{search}|{status ?? ''}</output>
    </>
  )
}

describe('FilterBar', () => {
  it('updates search text and clears active criteria', async () => {
    const user = userEvent.setup()
    render(<FilterHarness />)

    const search = screen.getByPlaceholderText('Find a queue')
    await user.type(search, 'orders')

    expect(screen.getByTestId('filter-state').textContent).toBe('orders|')
    expect(screen.getByText('2 queues')).toBeTruthy()

    await user.click(screen.getByRole('button', { name: /Clear Filters/ }))

    expect(screen.getByTestId('filter-state').textContent).toBe('|')
    expect((search as HTMLInputElement).value).toBe('')
  })

  it('renders selectable filter options and reports the selected value', async () => {
    const user = userEvent.setup()
    render(<FilterHarness />)

    fireEvent.mouseDown(screen.getByRole('combobox'))
    await user.click(await screen.findByText('Active'))

    expect(screen.getByTestId('filter-state').textContent).toBe('|active')
  })

  it('omits optional controls when callbacks are not supplied', () => {
    render(<FilterBar />)

    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.queryByRole('button')).toBeNull()
  })
})
