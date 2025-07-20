import React from 'react'
import { Card, Empty, Button } from 'antd'
import { BookOutlined } from '@ant-design/icons'

const DeveloperPortal: React.FC = () => {
  return (
    <div className="fade-in">
      <Card title="Developer Portal" extra={<Button type="primary">View API Docs</Button>}>
        <Empty
          image={<BookOutlined style={{ fontSize: 64, color: '#d9d9d9' }} />}
          description="Developer Portal interface coming soon"
        />
      </Card>
    </div>
  )
}

export default DeveloperPortal
