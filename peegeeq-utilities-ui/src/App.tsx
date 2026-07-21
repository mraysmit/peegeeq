import { useEffect } from 'react'
import { BrowserRouter as Router, Routes, Route, Link, useLocation } from 'react-router-dom'
import { Layout, Menu } from 'antd'
import { ToolOutlined, HomeOutlined, ThunderboltOutlined, FieldTimeOutlined, FileTextOutlined, UnorderedListOutlined, DatabaseOutlined } from '@ant-design/icons'
import Overview from './pages/Overview'
import ConnectSetupPage from './pages/ConnectSetupPage'
import SetupsPage from './pages/SetupsPage'
import SetupDetailPage from './pages/SetupDetailPage'
import MessageGeneratorPage from './pages/generator/MessageGeneratorPage'
import TemplateManagerPage from './pages/templates/TemplateManagerPage'
import ValueListManagerPage from './pages/value-lists/ValueListManagerPage'
import ScheduledRunsPage from './pages/schedules/ScheduledRunsPage'
import { schedulerRuntime } from './engine/schedulerRuntime'

const { Content, Sider } = Layout

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
    // Flat menu, no nesting and no indent (2026-07-21, user decision): every
    // page is a plain top-level entry.
    {
      key: '/generator',
      icon: <ThunderboltOutlined />,
      label: <Link to="/generator" data-testid="nav-generator">Message Generator</Link>,
    },
    {
      key: '/generator/schedules',
      icon: <FieldTimeOutlined />,
      label: <Link to="/generator/schedules" data-testid="nav-generator-schedules">Scheduled Runs</Link>,
    },
    {
      key: '/generator/templates',
      icon: <FileTextOutlined />,
      label: <Link to="/generator/templates" data-testid="nav-generator-templates">Templates</Link>,
    },
    {
      key: '/generator/value-lists',
      icon: <UnorderedListOutlined />,
      label: <Link to="/generator/value-lists" data-testid="nav-generator-value-lists">Value Lists</Link>,
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
  // The scheduler runs app-wide: schedules fire regardless of which screen is
  // open, for as long as the app itself is open (design §7.1).
  useEffect(() => {
    schedulerRuntime.start()
    return () => schedulerRuntime.stop()
  }, [])

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
              <Route path="/setups/connect" element={<ConnectSetupPage />} />
              <Route path="/setups/:setupId" element={<SetupDetailPage />} />
              <Route path="/generator/schedules" element={<ScheduledRunsPage />} />
              <Route path="/generator/templates" element={<TemplateManagerPage />} />
              <Route path="/generator/value-lists" element={<ValueListManagerPage />} />
            </Routes>
          </Content>
        </Layout>
      </Layout>
    </Router>
  )
}
