import { useState } from 'react'
import { BrowserRouter as Router, Routes, Route, Link, useLocation } from 'react-router-dom'
import { Layout, Menu, Typography, Space } from 'antd'
import { ToolOutlined, HomeOutlined, ThunderboltOutlined, FileTextOutlined, UnorderedListOutlined, DatabaseOutlined } from '@ant-design/icons'
import Overview from './pages/Overview'
import CreateSetupPage from './pages/CreateSetupPage'
import SetupsPage from './pages/SetupsPage'
import SetupDetailPage from './pages/SetupDetailPage'
import TargetSelector from './components/TargetSelector'

const { Title, Text } = Typography
const { Content, Sider } = Layout

function MessageGeneratorPage() {
  const [target, setTarget] = useState<{ setupId: string; queueName: string } | null>(null)

  return (
    <Space direction="vertical" style={{ width: '100%' }} data-testid="generator-page">
      <Title level={3}>Queue Message Generator</Title>
      <div data-testid="zone-a">
        <TargetSelector onTargetSelected={(setupId, queueName) => setTarget({ setupId, queueName })} />
      </div>
      {target && (
        <div data-testid="generator-workspace">
          <Text type="secondary">Zone B — Message composer — Phase 2</Text>
        </div>
      )}
    </Space>
  )
}
function TemplateManagerPage() {
  return <div><Title level={3}>Template Manager</Title><Text type="secondary">Coming soon — Phase 3</Text></div>
}
function ValueListManagerPage() {
  return <div><Title level={3}>Value List Manager</Title><Text type="secondary">Coming soon — Phase 3</Text></div>
}

function Navigation() {
  const location = useLocation()

  const menuItems = [
    {
      key: '/',
      icon: <HomeOutlined />,
      label: <Link to="/" data-testid="nav-overview">Overview</Link>,
    },
    {
      key: '/tools',
      icon: <ToolOutlined />,
      label: <Link to="/tools" data-testid="nav-tools">Tools</Link>,
    },
    {
      key: '/setups',
      icon: <DatabaseOutlined />,
      label: <Link to="/setups" data-testid="nav-setups">Setups</Link>,
    },
    {
      key: '/generator',
      icon: <ThunderboltOutlined />,
      label: <Link to="/generator" data-testid="nav-generator">Message Generator</Link>,
    },
    {
      key: '/generator/templates',
      icon: <FileTextOutlined />,
      label: <Link to="/generator/templates" data-testid="nav-generator-templates">Templates</Link>,
      style: { paddingLeft: 40 },
    },
    {
      key: '/generator/value-lists',
      icon: <UnorderedListOutlined />,
      label: <Link to="/generator/value-lists" data-testid="nav-generator-value-lists">Value Lists</Link>,
      style: { paddingLeft: 40 },
    },
  ]

  return (
    <Sider data-testid="app-sidebar" width={220} theme="light" style={{ borderRight: '1px solid #f0f0f0' }}>
      <div data-testid="app-logo" style={{ padding: '16px', fontWeight: 700, fontSize: 16, borderBottom: '1px solid #f0f0f0' }}>
        PeeGeeQ Utilities
      </div>
      <Menu
        mode="inline"
        selectedKeys={[location.pathname]}
        items={menuItems}
        style={{ borderRight: 0, paddingTop: 8 }}
      />
    </Sider>
  )
}

export default function App() {
  return (
    <Router>
      <Layout data-testid="app-layout" style={{ height: '100vh' }}>
        <Navigation />
        <Layout>
          <Content style={{ padding: 24, overflowY: 'auto' }}>
            <Routes>
              <Route path="/" element={<Overview />} />
              <Route path="/tools" element={<Overview />} />
              <Route path="/generator" element={<MessageGeneratorPage />} />
              <Route path="/setups" element={<SetupsPage />} />
              <Route path="/setups/:setupId" element={<SetupDetailPage />} />
              <Route path="/generator/setup/new" element={<CreateSetupPage />} />
              <Route path="/generator/templates" element={<TemplateManagerPage />} />
              <Route path="/generator/value-lists" element={<ValueListManagerPage />} />
            </Routes>
          </Content>
        </Layout>
      </Layout>
    </Router>
  )
}
