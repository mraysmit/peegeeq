import { Layout, Button, Badge, Dropdown, Typography } from 'antd'
import {
  BellOutlined,
  UserOutlined,
  SettingOutlined,
  LogoutOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import { useLocation } from 'react-router-dom'
import ConnectionStatus from '../common/ConnectionStatus'

const { Header: AntHeader } = Layout
const { Text } = Typography

const pageTitle: Record<string, string> = {
  '/': 'Overview',
  '/overview': 'Overview',
  '/queues': 'Queues',
  '/consumer-groups': 'Consumer Groups',
  '/event-stores': 'Event Stores',
  '/message-browser': 'Message Browser',
  '/schema-registry': 'Schema Registry',
  '/developer-portal': 'Developer Portal',
  '/queue-designer': 'Queue Designer',
  '/monitoring': 'Monitoring',
  '/settings': 'Settings',
}

const userMenuItems = [
  {
    key: 'profile',
    icon: <UserOutlined />,
    label: 'Profile',
  },
  {
    key: 'settings',
    icon: <SettingOutlined />,
    label: 'Settings',
  },
  {
    type: 'divider' as const,
  },
  {
    key: 'logout',
    icon: <LogoutOutlined />,
    label: 'Logout',
  },
]

const Header: React.FC = () => {
  const location = useLocation()
  const currentTitle = pageTitle[location.pathname] || 'PeeGeeQ Management'

  const handleRefresh = () => {
    window.location.reload()
  }

  const handleUserMenuClick = ({ key }: { key: string }) => {
    switch (key) {
      case 'profile':
        // console.log('Profile clicked')
        break
      case 'settings':
        // console.log('Settings clicked')
        break
      case 'logout':
        // console.log('Logout clicked')
        break
    }
  }

  return (
    <AntHeader>
      <div className="header-content">
        <h1 className="header-title">{currentTitle}</h1>
        
        <div className="header-actions">
          <ConnectionStatus />
          
          <Button
            type="text"
            icon={<ReloadOutlined />}
            onClick={handleRefresh}
            title="Refresh"
          />
          
          <Badge count={3} size="small">
            <Button
              type="text"
              icon={<BellOutlined />}
              title="Notifications"
            />
          </Badge>
          
          <Dropdown
            menu={{
              items: userMenuItems,
              onClick: handleUserMenuClick,
            }}
            placement="bottomRight"
            arrow
          >
            <Button type="text" icon={<UserOutlined />}>
              <Text>Admin</Text>
            </Button>
          </Dropdown>
        </div>
      </div>
    </AntHeader>
  )
}

export default Header
