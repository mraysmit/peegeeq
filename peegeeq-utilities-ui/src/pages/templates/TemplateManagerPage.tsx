/**
 * Template Manager page (Phase C — feature §6.2, TD §8).
 *
 * A table over the templateStore's localStorage-backed templates. Name and the
 * Edit action open the template in the generator editor: select(id) in the
 * store, then navigate to /generator, where MessageGeneratorPage consumes the
 * selection as its working copy. Import validates per entry (Zod, via
 * templateService.importFromFile); duplicate IDs are rejected with a NAMED
 * warning — no silent overwrites.
 */
import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Button, Popconfirm, Space, Table, Tooltip, Typography, message } from 'antd'
import { CopyOutlined, DeleteOutlined, EditOutlined, ExportOutlined, ImportOutlined, PlusOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import ImportFileDialog from '../../components/ImportFileDialog'
import { useTemplateStore } from '../../stores/templateStore'
import { exportTemplate, importFromFile } from '../../services/templateService'
import type { MessageTemplate } from '../../types/generator'

dayjs.extend(relativeTime)

const { Title } = Typography

const DESCRIPTION_LIMIT = 80

export default function TemplateManagerPage() {
  const navigate = useNavigate()
  const templates = useTemplateStore((s) => s.templates)
  const loadFromStorage = useTemplateStore((s) => s.loadFromStorage)
  const [importOpen, setImportOpen] = useState(false)

  useEffect(() => {
    loadFromStorage()
  }, [loadFromStorage])

  function openInGenerator(id: string | null) {
    useTemplateStore.getState().select(id)
    navigate('/generator')
  }

  async function handleImport(file: File) {
    const { templates: valid, errors } = await importFromFile(file)
    for (const error of errors) {
      message.error(`Import rejected — ${error}`)
    }
    if (valid.length > 0) {
      const { added, skipped } = useTemplateStore.getState().importTemplates(valid)
      if (skipped.length > 0) {
        message.warning(
          `Skipped ${skipped.length} template(s) with existing IDs (no overwrite): ${skipped.join(', ')}`
        )
      }
      if (added > 0) {
        message.success(`Imported ${added} template(s)`)
      }
    }
  }

  const columns = [
    {
      title: 'Name',
      key: 'name',
      render: (record: MessageTemplate) => (
        <Link to="/generator" onClick={() => useTemplateStore.getState().select(record.id)}>
          {record.name}
        </Link>
      ),
    },
    {
      title: 'Message Type',
      dataIndex: 'messageType',
      key: 'messageType',
    },
    {
      title: 'Description',
      key: 'description',
      render: (record: MessageTemplate) => {
        const description = record.description ?? ''
        const truncated =
          description.length > DESCRIPTION_LIMIT
            ? `${description.slice(0, DESCRIPTION_LIMIT)}…`
            : description
        return (
          <Tooltip title={description}>
            <span data-testid={`template-description-${record.id}`}>{truncated}</span>
          </Tooltip>
        )
      },
    },
    {
      title: 'Updated',
      key: 'updated',
      render: (record: MessageTemplate) => (
        <span data-testid={`template-updated-${record.id}`}>{dayjs(record.updatedAt).fromNow()}</span>
      ),
    },
    {
      title: 'Actions',
      key: 'actions',
      render: (record: MessageTemplate) => (
        <Space>
          <Button
            type="text"
            size="small"
            icon={<EditOutlined />}
            data-testid={`template-edit-${record.id}`}
            onClick={() => openInGenerator(record.id)}
          />
          <Button
            type="text"
            size="small"
            icon={<CopyOutlined />}
            data-testid={`template-duplicate-${record.id}`}
            onClick={() => useTemplateStore.getState().duplicate(record.id)}
          />
          <Popconfirm
            title={`Delete template "${record.name}"?`}
            description="This cannot be undone."
            okText="Delete"
            okType="danger"
            cancelText="Cancel"
            onConfirm={() => useTemplateStore.getState().remove(record.id)}
          >
            <Button
              type="text"
              size="small"
              danger
              icon={<DeleteOutlined />}
              data-testid={`template-delete-${record.id}`}
            />
          </Popconfirm>
          <Button
            type="text"
            size="small"
            icon={<ExportOutlined />}
            data-testid={`template-export-${record.id}`}
            onClick={() => exportTemplate(record)}
          />
        </Space>
      ),
    },
  ]

  return (
    <div data-testid="template-manager-page">
      <Title level={3}>Template Manager</Title>
      <Space style={{ marginBottom: 12 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => openInGenerator(null)}>
          New Template
        </Button>
        <Button icon={<ImportOutlined />} onClick={() => setImportOpen(true)}>
          Import
        </Button>
      </Space>

      <ImportFileDialog
        open={importOpen}
        title="Import templates"
        hint={
          <>
            A template export (<code>.json</code>): one template object or an array of
            templates. Entries are validated individually; entries with existing IDs are
            skipped, never overwritten.
          </>
        }
        inputTestId="template-import-input"
        onFile={(file) => {
          setImportOpen(false)
          // Never fire-and-forget: anything escaping handleImport surfaces.
          handleImport(file).catch((error: unknown) =>
            message.error(`Import failed: ${error instanceof Error ? error.message : String(error)}`)
          )
        }}
        onClose={() => setImportOpen(false)}
      />
      <Table
        rowKey="id"
        columns={columns}
        dataSource={templates}
        pagination={{ pageSize: 10, showTotal: (total) => `${total} template${total !== 1 ? 's' : ''}` }}
        data-testid="template-table"
      />
    </div>
  )
}
