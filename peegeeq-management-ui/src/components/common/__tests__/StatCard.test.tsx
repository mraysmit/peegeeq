/**
 * Unit Tests for StatCard Component
 */
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { InboxOutlined } from '@ant-design/icons';
import StatCard from '../StatCard';

describe('StatCard', () => {
  it('renders basic stat card with title and value', () => {
    render(<StatCard title="Test Stat" value={42} />);
    
    expect(screen.getByText('Test Stat')).toBeInTheDocument();
    expect(screen.getByText('42')).toBeInTheDocument();
  });

  it('displays suffix when provided', () => {
    render(<StatCard title="Rate" value={10.5} suffix="msg/s" precision={1} />);
    
    // Ant Design Statistic splits decimal values into int and decimal parts
    expect(screen.getByText('10')).toBeInTheDocument();
    expect(screen.getByText('.5')).toBeInTheDocument();
    expect(screen.getByText('msg/s')).toBeInTheDocument();
  });

  it('renders icon when provided', () => {
    const { container } = render(
      <StatCard title="Messages" value={100} icon={<InboxOutlined data-testid="icon" />} />
    );
    
    expect(screen.getByTestId('icon')).toBeInTheDocument();
  });

  it('shows loading state', () => {
    const { container } = render(<StatCard title="Loading" value={0} loading={true} />);
    
    // Loading shows spinner not text
    expect(container.querySelector('.ant-spin')).toBeInTheDocument();
  });

  it('displays trend indicator with value', () => {
    render(
      <StatCard 
        title="Growth" 
        value={100} 
        trend="up" 
        trendValue={15} 
      />
    );
    
    expect(screen.getByText('15%')).toBeInTheDocument();
  });

  it('applies custom value styles', () => {
    const { container } = render(
      <StatCard 
        title="Custom" 
        value={50} 
        valueStyle={{ color: 'red' }} 
      />
    );
    
    const valueElement = container.querySelector('.ant-statistic-content-value');
    // Browsers convert named colors to rgb format
    expect(valueElement).toHaveStyle({ color: 'rgb(255, 0, 0)' });
  });

  it('displays extra content when provided', () => {
    render(
      <StatCard 
        title="Test" 
        value={100} 
        extra={<span>Extra Info</span>} 
      />
    );
    
    expect(screen.getByText('Extra Info')).toBeInTheDocument();
  });

  it('formats large numbers correctly', () => {
    render(<StatCard title="Big Number" value={1234567} precision={0} />);
    
    // Ant Design Statistic formats large numbers
    expect(screen.getByText('1,234,567')).toBeInTheDocument();
  });
});
