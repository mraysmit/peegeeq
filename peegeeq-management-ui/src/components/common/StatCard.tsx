/**
 * StatCard Component - Display metric statistics
 */
import React from 'react';
import { Card, Statistic, Spin } from 'antd';
import { ArrowUpOutlined, ArrowDownOutlined } from '@ant-design/icons';

export interface StatCardProps {
  title: string;
  value: number | string;
  suffix?: string;
  prefix?: React.ReactNode;
  precision?: number;
  loading?: boolean;
  trend?: 'up' | 'down' | 'neutral';
  trendValue?: number;
  valueStyle?: React.CSSProperties;
  icon?: React.ReactNode;
  extra?: React.ReactNode;
}

export const StatCard: React.FC<StatCardProps> = ({
  title,
  value,
  suffix,
  prefix,
  precision = 0,
  loading = false,
  trend,
  trendValue,
  valueStyle,
  icon,
  extra,
}) => {
  const getTrendIcon = () => {
    if (!trend || trend === 'neutral') return null;
    return trend === 'up' ? <ArrowUpOutlined /> : <ArrowDownOutlined />;
  };

  const getTrendColor = () => {
    if (!trend || trend === 'neutral') return undefined;
    return trend === 'up' ? '#3f8600' : '#cf1322';
  };

  return (
    <Card
      variant="borderless"
      style={{
        boxShadow: '0 2px 8px rgba(0,0,0,0.06)',
      }}
    >
      {loading ? (
        <div style={{ textAlign: 'center', padding: '24px 0' }}>
          <Spin />
        </div>
      ) : (
        <>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
            <Statistic
              title={title}
              value={value}
              suffix={suffix}
              prefix={prefix}
              precision={precision}
              valueStyle={{ ...valueStyle, fontSize: '24px', fontWeight: 600 }}
            />
            {icon && (
              <div style={{
                fontSize: '32px',
                color: 'rgba(0, 0, 0, 0.25)',
              }}>
                {icon}
              </div>
            )}
          </div>
          {(trend || extra) && (
            <div style={{ marginTop: '8px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              {trend && trendValue !== undefined && (
                <span style={{ color: getTrendColor(), fontSize: '14px' }}>
                  {getTrendIcon()}
                  <span style={{ marginLeft: '4px' }}>
                    {trendValue}%
                  </span>
                </span>
              )}
              {extra && <span style={{ fontSize: '14px', color: 'rgba(0, 0, 0, 0.45)' }}>{extra}</span>}
            </div>
          )}
        </>
      )}
    </Card>
  );
};

export default StatCard;
