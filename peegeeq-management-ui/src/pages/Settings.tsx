import React from 'react'
import { Card, Empty, Button } from 'antd'
import { SettingOutlined } from '@ant-design/icons'

const Settings: React.FC = () => {
  return (
    <div className="fade-in">
      <Card title="System Settings" extra={<Button type="primary">Configure</Button>}>
        <Empty
          image={<SettingOutlined style={{ fontSize: 64, color: '#d9d9d9' }} />}
          description="System Settings interface coming soon"
        />
      </Card>
    </div>
  )
}

export default Settings
