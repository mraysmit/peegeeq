import { useState } from 'react'
import { BrowserRouter as Router, Routes, Route, Link, useLocation } from 'react-router-dom'
import { Layout, Menu, Typography, Badge } from 'antd'
import {
  DashboardOutlined,
  InboxOutlined,
  TeamOutlined,
  DatabaseOutlined,
  SearchOutlined
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

const { Header, Content, Sider } = Layout
const { Title } = Typography

// Navigation component that uses the current location
function Navigation() {
  const location = useLocation()
  const [collapsed, setCollapsed] = useState(false)

  const menuItems = [
    {
      key: '/',
      icon: <DashboardOutlined />,
      label: <Link to="/">Overview</Link>,
    },
    {
      key: '/queues',
      icon: <InboxOutlined />,
      label: <Link to="/queues">Queues</Link>,
    },
    {
      key: '/consumer-groups',
      icon: <TeamOutlined />,
      label: <Link to="/consumer-groups">Consumer Groups</Link>,
    },
    {
      key: '/event-stores',
      icon: <DatabaseOutlined />,
      label: <Link to="/event-stores">Event Stores</Link>,
    },
    {
      key: '/messages',
      icon: <SearchOutlined />,
      label: <Link to="/messages">Message Browser</Link>,
    },
  ]

  return (
    <Sider
      collapsible
      collapsed={collapsed}
      onCollapse={setCollapsed}
      width={240}
      theme="dark"
    >
      <div style={{
        padding: '16px',
        color: 'white',
        fontSize: '18px',
        fontWeight: 'bold',
        borderBottom: '1px solid #002140'
      }}>
        <DatabaseOutlined style={{ marginRight: '8px' }} />
        {!collapsed && 'PeeGeeQ'}
      </div>
      <Menu
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
      <Layout style={{ minHeight: '100vh' }}>
        <Navigation />

        <Layout>
          <Header style={{
            background: '#fff',
            padding: '0 24px',
            boxShadow: '0 2px 8px rgba(0,0,0,0.06)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between'
          }}>
            <Title level={3} style={{ margin: 0, color: '#262626' }}>
              PeeGeeQ Management Console
            </Title>
            <Badge status="success" text="Connected" />
          </Header>

          <Content style={{ margin: '24px', background: '#f0f2f5' }}>
            <Routes>
              <Route path="/" element={<Overview />} />
              {/* Phase 1: Enhanced Queue Management */}
              <Route path="/queues" element={<QueuesEnhanced />} />
              <Route path="/queues/:setupId/:queueName" element={<QueueDetailsEnhanced />} />
              {/* Legacy routes for backwards compatibility */}
              <Route path="/queues-old" element={<Queues />} />
              <Route path="/queues-old/:queueName" element={<QueueDetails />} />
              <Route path="/consumer-groups" element={<ConsumerGroups />} />
              <Route path="/event-stores" element={<EventStores />} />
              <Route path="/messages" element={<MessageBrowser />} />
            </Routes>
          </Content>
        </Layout>
      </Layout>
    </Router>
  )
}

export default App
