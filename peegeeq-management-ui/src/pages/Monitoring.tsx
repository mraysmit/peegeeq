import React from 'react'
import { Card, Empty, Button } from 'antd'
import { BarChartOutlined } from '@ant-design/icons'

const Monitoring: React.FC = () => {
  return (
    <div className="fade-in">
      <Card title="Real-time Monitoring" extra={<Button type="primary">View Dashboards</Button>}>
        <Empty
          image={<BarChartOutlined style={{ fontSize: 64, color: '#d9d9d9' }} />}
          description="Real-time Monitoring dashboards coming soon"
        />
      </Card>
    </div>
  )
}

export default Monitoring
