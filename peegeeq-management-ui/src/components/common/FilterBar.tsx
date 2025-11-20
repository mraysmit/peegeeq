/**
 * FilterBar Component - Search and filter controls
 */
import React from 'react';
import { Input, Select, Space, Button } from 'antd';
import { SearchOutlined, ClearOutlined } from '@ant-design/icons';

const { Search } = Input;
const { Option } = Select;

export interface FilterOption {
  label: string;
  value: string;
}

export interface FilterBarProps {
  searchPlaceholder?: string;
  searchValue?: string;
  onSearch?: (value: string) => void;
  filters?: {
    label: string;
    value: string | string[] | undefined;
    options: FilterOption[];
    mode?: 'multiple' | 'tags';
    onChange: (value: string | string[]) => void;
    placeholder?: string;
  }[];
  onClear?: () => void;
  extra?: React.ReactNode;
  style?: React.CSSProperties;
}

export const FilterBar: React.FC<FilterBarProps> = ({
  searchPlaceholder = 'Search...',
  searchValue,
  onSearch,
  filters,
  onClear,
  extra,
  style,
}) => {
  return (
    <div
      style={{
        padding: '16px',
        background: '#fff',
        borderRadius: '4px',
        marginBottom: '16px',
        boxShadow: '0 2px 8px rgba(0,0,0,0.06)',
        ...style,
      }}
    >
      <Space size="middle" wrap style={{ width: '100%' }}>
        {onSearch && (
          <Search
            placeholder={searchPlaceholder}
            value={searchValue}
            onChange={(e) => onSearch(e.target.value)}
            onSearch={onSearch}
            prefix={<SearchOutlined />}
            allowClear
            style={{ width: 300 }}
          />
        )}

        {filters?.map((filter, index) => (
          <Select
            key={index}
            mode={filter.mode}
            placeholder={filter.placeholder || `Select ${filter.label}`}
            value={filter.value}
            onChange={filter.onChange}
            allowClear
            style={{ minWidth: 150 }}
          >
            {filter.options.map((option) => (
              <Option key={option.value} value={option.value}>
                {option.label}
              </Option>
            ))}
          </Select>
        ))}

        {onClear && (
          <Button
            icon={<ClearOutlined />}
            onClick={onClear}
          >
            Clear Filters
          </Button>
        )}

        {extra && <div style={{ marginLeft: 'auto' }}>{extra}</div>}
      </Space>
    </div>
  );
};

export default FilterBar;
