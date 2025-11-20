/**
 * Unit Tests for FilterBar Component
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import FilterBar from '../FilterBar';

describe('FilterBar', () => {
  it('renders search input when onSearch provided', () => {
    const onSearch = vi.fn();
    render(<FilterBar onSearch={onSearch} searchPlaceholder="Search queues..." />);
    
    expect(screen.getByPlaceholderText('Search queues...')).toBeInTheDocument();
  });

  it('calls onSearch when typing in search field', async () => {
    const user = userEvent.setup();
    const onSearch = vi.fn();
    render(<FilterBar onSearch={onSearch} />);
    
    const searchInput = screen.getByPlaceholderText('Search...');
    await user.type(searchInput, 'test');
    
    expect(onSearch).toHaveBeenCalled();
  });

  it('renders filter dropdowns', () => {
    const onChange = vi.fn();
    render(
      <FilterBar
        filters={[
          {
            label: 'Type',
            value: undefined,
            options: [
              { label: 'Option 1', value: 'opt1' },
              { label: 'Option 2', value: 'opt2' },
            ],
            onChange,
          },
        ]}
      />
    );
    
    // Ant Design Select renders with the placeholder
    expect(screen.getByText('Select Type')).toBeInTheDocument();
  });

  it('renders multiple filters', () => {
    render(
      <FilterBar
        filters={[
          {
            label: 'Type',
            value: undefined,
            options: [{ label: 'Native', value: 'NATIVE' }],
            onChange: vi.fn(),
          },
          {
            label: 'Status',
            value: undefined,
            options: [{ label: 'Active', value: 'ACTIVE' }],
            onChange: vi.fn(),
          },
        ]}
      />
    );
    
    expect(screen.getByText('Select Type')).toBeInTheDocument();
    expect(screen.getByText('Select Status')).toBeInTheDocument();
  });

  it('renders Clear Filters button when onClear provided', () => {
    const onClear = vi.fn();
    render(<FilterBar onClear={onClear} />);
    
    const clearButton = screen.getByText('Clear Filters');
    expect(clearButton).toBeInTheDocument();
  });

  it('calls onClear when Clear Filters button clicked', async () => {
    const user = userEvent.setup();
    const onClear = vi.fn();
    render(<FilterBar onClear={onClear} />);
    
    const clearButton = screen.getByText('Clear Filters');
    await user.click(clearButton);
    
    expect(onClear).toHaveBeenCalledTimes(1);
  });

  it('renders extra content in correct position', () => {
    render(
      <FilterBar
        extra={<button data-testid="extra-button">Extra Button</button>}
      />
    );
    
    expect(screen.getByTestId('extra-button')).toBeInTheDocument();
  });

  it('applies custom styles', () => {
    const { container } = render(
      <FilterBar style={{ backgroundColor: 'blue' }} />
    );
    
    const filterBar = container.firstChild as HTMLElement;
    expect(filterBar).toHaveStyle({ backgroundColor: 'rgb(0, 0, 255)' });
  });

  it('handles multiple mode for filters', () => {
    render(
      <FilterBar
        filters={[
          {
            label: 'Multi Select',
            value: ['opt1', 'opt2'],
            mode: 'multiple',
            options: [
              { label: 'Option 1', value: 'opt1' },
              { label: 'Option 2', value: 'opt2' },
            ],
            onChange: vi.fn(),
          },
        ]}
      />
    );
    
    // With multiple mode and values selected, check for the selected options
    expect(screen.getByText('Option 1')).toBeInTheDocument();
    expect(screen.getByText('Option 2')).toBeInTheDocument();
  });
});
