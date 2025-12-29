import { Layout, Button, Badge, Dropdown, Typography } from 'antd'
import {
  BellOutlined,
  UserOutlined,
  SettingOutlined,
  LogoutOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import { useLocation, useNavigate } from 'react-router-dom'
import ConnectionStatus from '../common/ConnectionStatus'

const { Header: AntHeader } = Layout
const { Text } = Typography

const pageTitle: Record<string, string> = {
  '/': 'Overview',
  '/overview': 'Overview',
  '/queues': 'Queues',
  '/consumer-groups': 'Consumer Groups',
  '/event-stores': 'Event Stores',
  '/messages': 'Message Browser',
  '/message-browser': 'Message Browser',
  '/database-setups': 'Database Setups',
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
  const navigate = useNavigate()
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
        navigate('/settings')
        break
      case 'logout':
        // console.log('Logout clicked')
        break
    }
  }

  return (
    <AntHeader data-testid="app-header">
      <div className="header-content">
        <h1 className="header-title" data-testid="page-title">{currentTitle}</h1>

        <div className="header-actions">
          <ConnectionStatus />

          <Button
            data-testid="refresh-btn"
            type="text"
            icon={<ReloadOutlined />}
            onClick={handleRefresh}
            title="Refresh"
          />

          <Badge count={0} size="small">
            <Button
              data-testid="notifications-btn"
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
            <Button data-testid="user-menu-btn" type="text" icon={<UserOutlined />}>
              <Text style={{ color: '#262626' }}>Admin</Text>
            </Button>
          </Dropdown>
        </div>
      </div>
    </AntHeader>
  )
}

export default Header
