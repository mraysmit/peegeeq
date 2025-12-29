import { useState } from 'react'
import { BrowserRouter as Router, Routes, Route, Link, useLocation } from 'react-router-dom'
import { Layout, Menu } from 'antd'
import {
  DashboardOutlined,
  InboxOutlined,
  TeamOutlined,
  DatabaseOutlined,
  SearchOutlined,
  SettingOutlined
} from '@ant-design/icons'

// Import page components
import Overview from './pages/Overview'
import Queues from './pages/Queues'
import QueuesEnhanced from './pages/QueuesEnhanced'
import QueueDetails from './pages/QueueDetails'
import QueueDetailsEnhanced from './pages/QueueDetailsEnhanced'
import ConsumerGroups from './pages/ConsumerGroups'
import EventStores from './pages/EventStores'
import MessageBrowser from './pages/MessageBrowser'
import DatabaseSetups from './pages/DatabaseSetups'
import Settings from './pages/Settings'

// Import layout components
import Header from './components/layout/Header'
import ErrorBoundary from './components/common/ErrorBoundary'

const { Content, Sider } = Layout

// Navigation component that uses the current location
function Navigation() {
  const location = useLocation()
  const [collapsed, setCollapsed] = useState(false)

  const menuItems = [
    {
      key: '/',
      icon: <DashboardOutlined />,
      label: <Link to="/" data-testid="nav-overview">Overview</Link>,
    },
    {
      key: '/database-setups',
      icon: <SettingOutlined />,
      label: <Link to="/database-setups" data-testid="nav-database-setups">Database Setups</Link>,
    },
    {
      key: '/queues',
      icon: <InboxOutlined />,
      label: <Link to="/queues" data-testid="nav-queues">Queues</Link>,
    },
    {
      key: '/consumer-groups',
      icon: <TeamOutlined />,
      label: <Link to="/consumer-groups" data-testid="nav-consumer-groups">Consumer Groups</Link>,
    },
    {
      key: '/event-stores',
      icon: <DatabaseOutlined />,
      label: <Link to="/event-stores" data-testid="nav-event-stores">Event Stores</Link>,
    },
    {
      key: '/messages',
      icon: <SearchOutlined />,
      label: <Link to="/messages" data-testid="nav-messages">Message Browser</Link>,
    },
  ]

  return (
    <Sider
      data-testid="app-sidebar"
      collapsible
      collapsed={collapsed}
      onCollapse={setCollapsed}
      width={240}
      theme="dark"
    >
      <div
        data-testid="app-logo"
        style={{
          padding: '16px',
          color: 'white',
          fontSize: '18px',
          fontWeight: 'bold',
          borderBottom: '1px solid #002140'
        }}
      >
        <DatabaseOutlined style={{ marginRight: '8px' }} />
        {!collapsed && 'PeeGeeQ'}
      </div>
      <Menu
        data-testid="app-nav-menu"
        theme="dark"
        mode="inline"
        selectedKeys={[location.pathname]}
        items={menuItems}
        style={{ borderRight: 0 }}
      />
    </Sider>
  )
}

// Main App component
function App() {
  return (
    <Router>
      <Layout data-testid="app-layout" style={{ minHeight: '100vh' }}>
        <Navigation />

        <Layout>
          <Header />

          <Content data-testid="app-content" style={{ margin: '24px', background: '#f0f2f5' }}>
            <ErrorBoundary>
              <Routes>
                <Route path="/" element={<Overview />} />
                <Route path="/database-setups" element={<DatabaseSetups />} />
                {/* Phase 1: Enhanced Queue Management */}
                <Route path="/queues" element={<QueuesEnhanced />} />
                <Route path="/queues/:setupId/:queueName" element={<QueueDetailsEnhanced />} />
                {/* Legacy routes for backwards compatibility */}
                <Route path="/queues-old" element={<Queues />} />
                <Route path="/queues-old/:queueName" element={<QueueDetails />} />
                <Route path="/consumer-groups" element={<ConsumerGroups />} />
                <Route path="/event-stores" element={<EventStores />} />
                <Route path="/messages" element={<MessageBrowser />} />
                <Route path="/settings" element={<Settings />} />
              </Routes>
            </ErrorBoundary>
          </Content>
        </Layout>
      </Layout>
    </Router>
  )
}

export default App
