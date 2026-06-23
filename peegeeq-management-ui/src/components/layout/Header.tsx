import { Layout, Button, Badge, Dropdown, Typography, Drawer, List, Empty } from 'antd'
import {
  BellOutlined,
  UserOutlined,
  SettingOutlined,
  LogoutOutlined,
  ReloadOutlined,
  ClearOutlined,
} from '@ant-design/icons'
import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import ConnectionStatus from '../common/ConnectionStatus'
import { useManagementStore } from '../../stores/managementStore'

const { Header: AntHeader } = Layout
const { Text } = Typography

const pageTitle: Record<string, string> = {
  '/': 'Overview',
  '/overview': 'Overview',
  '/queues': 'Queues',
  '/consumer-groups': 'Consumer Groups',
  '/event-stores': 'Event Stores',
  '/events': 'Events',
  '/causation-tree': 'Causation Tree',
  '/aggregate-stream': 'Aggregate Stream',
  '/messages': 'Message Browser',
  '/message-browser': 'Message Browser',
  '/notifications': 'Notifications',
  '/database-setups': 'Database Setups',
  '/schema-registry': 'Schema Registry',
  '/developer-portal': 'Developer Portal',
  '/queue-designer': 'Queue Designer',
  '/monitoring': 'Monitoring',
  '/settings': 'Settings',
}

/**
 * Resolve the header title for a route. Exact matches come from `pageTitle`; the dynamic
 * Queue Details route (`/queues/:setupId/:queueName`) is matched by pattern. The `/queues`
 * list page is exact-matched above, so it stays "Queues".
 */
const resolvePageTitle = (pathname: string): string => {
  const exact = pageTitle[pathname]
  if (exact) return exact
  if (/^\/queues\/[^/]+\/[^/]+\/?$/.test(pathname)) return 'Queue Details'
  return 'PeeGeeQ Management'
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
  const currentTitle = resolvePageTitle(location.pathname)
  const { notifications, unreadCount, markAllNotificationsRead, clearNotifications } = useManagementStore()
  const [notifOpen, setNotifOpen] = useState(false)

  const openNotifications = () => {
    setNotifOpen(true)
    markAllNotificationsRead()
  }

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

          <Badge count={unreadCount} size="small" overflowCount={99}>
            <Button
              data-testid="notifications-btn"
              type="text"
              icon={<BellOutlined />}
              title="Notifications"
              onClick={openNotifications}
            />
          </Badge>

          <Drawer
            title="Notifications"
            placement="right"
            width={360}
            open={notifOpen}
            onClose={() => setNotifOpen(false)}
            extra={
              <Button
                size="small"
                icon={<ClearOutlined />}
                onClick={clearNotifications}
                disabled={notifications.length === 0}
              >
                Clear
              </Button>
            }
          >
            {notifications.length === 0
              ? <Empty description="No notifications" />
              : (
                <List
                  size="small"
                  dataSource={notifications}
                  renderItem={(n) => (
                    <List.Item>
                      <List.Item.Meta
                        title={`${n.action} — ${n.resource}`}
                        description={new Date(n.timestamp).toLocaleString()}
                      />
                    </List.Item>
                  )}
                />
              )}
          </Drawer>

          <Dropdown
            menu={{
              items: userMenuItems,
              onClick: handleUserMenuClick,
            }}
            placement="bottomRight"
            arrow
          >
            <Button data-testid="user-menu-btn" type="text" icon={<UserOutlined />}>
              <Text style={{ color: '#bfbfbf' }}>Admin</Text>
            </Button>
          </Dropdown>
        </div>
      </div>
    </AntHeader>
  )
}

export default Header
