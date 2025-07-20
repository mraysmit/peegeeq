import React from 'react'
import { Card, Empty, Button } from 'antd'
import { FileTextOutlined } from '@ant-design/icons'

const SchemaRegistry: React.FC = () => {
  return (
    <div className="fade-in">
      <Card title="Schema Registry" extra={<Button type="primary">Add Schema</Button>}>
        <Empty
          image={<FileTextOutlined style={{ fontSize: 64, color: '#d9d9d9' }} />}
          description="Schema Registry interface coming soon"
        />
      </Card>
    </div>
  )
}

export default SchemaRegistry
