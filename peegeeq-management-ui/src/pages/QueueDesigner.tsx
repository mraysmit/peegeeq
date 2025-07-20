import React from 'react'
import { Card, Empty, Button } from 'antd'
import { DesktopOutlined } from '@ant-design/icons'

const QueueDesigner: React.FC = () => {
  return (
    <div className="fade-in">
      <Card title="Visual Queue Designer" extra={<Button type="primary">New Design</Button>}>
        <Empty
          image={<DesktopOutlined style={{ fontSize: 64, color: '#d9d9d9' }} />}
          description="Visual Queue Designer interface coming soon"
        />
      </Card>
    </div>
  )
}

export default QueueDesigner
