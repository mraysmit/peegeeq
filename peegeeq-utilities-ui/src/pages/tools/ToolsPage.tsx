/**
 * Generation tools page — route `/tools` (design §19.0, §19.4 — Phase G.4).
 *
 * The route previously rendered a second copy of Overview. It is now the
 * launcher for the generation tool suite; its first panel is the scenario
 * manager. The remaining modes (Ramp, Profile, Delay/Priority/FIFO, Trace seed)
 * join this page as they are built.
 *
 * Load selects the scenario in the scenarioStore and navigates to /generator,
 * where MessageGeneratorPage consumes the selection — the same handoff the
 * Template Manager uses. Import validates per entry (Zod, via
 * scenarioService.importFromFile); duplicate IDs are rejected with a NAMED
 * warning, never overwritten.
 */
import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Button, Card, Empty, Popconfirm, Space, Table, Tag, Typography, message } from 'antd'
import { DeleteOutlined, ExportOutlined, ImportOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import ImportFileDialog from '../../components/ImportFileDialog'
import { useScenarioStore } from '../../stores/scenarioStore'
import { exportScenario, importFromFile } from '../../services/scenarioService'
import type { RunConfig } from '../../types/generator'
import type { Scenario } from '../../types/scenario'

dayjs.extend(relativeTime)

const { Title, Text } = Typography

/** Derived at render time from the stored config — never a stored field. */
function describeRun(config: RunConfig): string {
  return `${config.rate} msg/s × ${config.durationSecs} s = ${config.rate * config.durationSecs} · "${config.template.name}"`
}

/**
 * How a scenario replays, derived from its own shape (G.3d).
 *
 * A profile is described by its phases, NOT by the base config's flat rate:
 * that rate is never used when the profile runs, so showing it would describe a
 * run that never happens.
 */
function describeScenario(scenario: Scenario): string {
  if (scenario.mode === 'profile') {
    const phases = scenario.phases ?? []
    const messages = phases.reduce((sum, p) => sum + p.rate * p.durationSecs, 0)
    const seconds = phases.reduce((sum, p) => sum + p.durationSecs, 0)
    return `${phases.length} phase${phases.length === 1 ? '' : 's'} · ${seconds} s · ${messages} messages`
  }
  return describeRun(scenario.config)
}

export default function ToolsPage() {
  const navigate = useNavigate()
  const scenarios = useScenarioStore((s) => s.scenarios)
  const loadFromStorage = useScenarioStore((s) => s.loadFromStorage)
  const [importOpen, setImportOpen] = useState(false)

  useEffect(() => {
    loadFromStorage()
  }, [loadFromStorage])

  function openInGenerator(id: string) {
    useScenarioStore.getState().select(id)
    navigate('/generator')
  }

  async function handleImport(file: File) {
    const { scenarios: valid, errors } = await importFromFile(file)
    for (const error of errors) {
      message.error(`Import rejected — ${error}`)
    }
    if (valid.length > 0) {
      const { added, skipped } = useScenarioStore.getState().importScenarios(valid)
      if (skipped.length > 0) {
        message.warning(
          `Skipped ${skipped.length} scenario(s) with existing IDs (no overwrite): ${skipped.join(', ')}`
        )
      }
      if (added > 0) {
        message.success(`Imported ${added} scenario(s)`)
      }
    }
  }

  const columns = [
    {
      title: 'Name',
      key: 'name',
      render: (record: Scenario) => (
        <Link to="/generator" onClick={() => useScenarioStore.getState().select(record.id)}>
          {record.name}
        </Link>
      ),
    },
    {
      title: 'Target',
      key: 'target',
      render: (record: Scenario) => (
        <span data-testid={`scenario-target-${record.id}`}>
          {record.config.setupId} / {record.config.queueName}
        </span>
      ),
    },
    {
      title: 'Mode',
      key: 'mode',
      render: (record: Scenario) => (
        <Tag
          data-testid={`scenario-mode-${record.id}`}
          color={record.mode === 'profile' ? 'geekblue' : 'default'}
        >
          {record.mode === 'profile' ? 'Profile' : 'Flat rate'}
        </Tag>
      ),
    },
    {
      title: 'Run',
      key: 'run',
      render: (record: Scenario) => (
        <span data-testid={`scenario-run-${record.id}`}>{describeScenario(record)}</span>
      ),
    },
    {
      title: 'Updated',
      key: 'updated',
      render: (record: Scenario) => (
        <span data-testid={`scenario-updated-${record.id}`}>{dayjs(record.updatedAt).fromNow()}</span>
      ),
    },
    {
      title: 'Actions',
      key: 'actions',
      render: (record: Scenario) => (
        <Space>
          <Button
            size="small"
            data-testid={`scenario-load-${record.id}`}
            onClick={() => openInGenerator(record.id)}
          >
            Load
          </Button>
          <Button
            type="text"
            size="small"
            icon={<ExportOutlined />}
            data-testid={`scenario-export-${record.id}`}
            onClick={() => exportScenario(record)}
          />
          <Popconfirm
            title={`Delete scenario "${record.name}"?`}
            description="This cannot be undone."
            okText="Delete"
            okType="danger"
            cancelText="Cancel"
            onConfirm={() => useScenarioStore.getState().remove(record.id)}
          >
            <Button
              type="text"
              size="small"
              danger
              icon={<DeleteOutlined />}
              data-testid={`scenario-delete-${record.id}`}
            />
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div data-testid="tools-page">
      <Title level={3}>Generation Tools</Title>

      <Card
        title="Scenarios"
        size="small"
        extra={
          <Button icon={<ImportOutlined />} onClick={() => setImportOpen(true)}>
            Import
          </Button>
        }
      >
        <div style={{ marginBottom: 12 }}>
          <Text type="secondary">
            A scenario is a saved run configuration — target, rate, duration, guards and
            template. Save one from the{' '}
            <Link to="/generator">Message Generator</Link> with "Save as…", then load it here
            to replay it.
          </Text>
        </div>

        {scenarios.length === 0 ? (
          <Empty
            data-testid="scenarios-empty"
            description='No saved scenarios yet — save one from the Message Generator with "Save as…".'
          />
        ) : (
          <Table
            rowKey="id"
            columns={columns}
            dataSource={scenarios}
            pagination={{
              pageSize: 10,
              showTotal: (total) => `${total} scenario${total !== 1 ? 's' : ''}`,
            }}
            data-testid="scenario-table"
          />
        )}
      </Card>

      <ImportFileDialog
        open={importOpen}
        title="Import scenarios"
        hint={
          <>
            A scenario export (<code>.json</code>): one scenario object or an array of
            scenarios. Entries are validated individually; entries with existing IDs are
            skipped, never overwritten.
          </>
        }
        inputTestId="scenario-import-input"
        onFile={(file) => {
          setImportOpen(false)
          // Never fire-and-forget: anything escaping handleImport surfaces.
          handleImport(file).catch((error: unknown) =>
            message.error(`Import failed: ${error instanceof Error ? error.message : String(error)}`)
          )
        }}
        onClose={() => setImportOpen(false)}
      />
    </div>
  )
}
