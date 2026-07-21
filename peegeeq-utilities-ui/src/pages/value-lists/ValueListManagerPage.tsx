/**
 * Value List Manager page (Phase D — feature §6.3, TD §8).
 *
 * Manages the named lists behind {{list:name}} tokens, composed from the real
 * valueListStore (localStorage-backed) and valueListService (import/export).
 *
 * Rename is remove-old + add-new (the store keys by name); renaming onto an
 * existing name is rejected. Delete warns with the names of saved templates
 * that reference the list — in payloads OR header values (§5.3). Import
 * collisions offer Overwrite / Merge / Cancel; merge de-duplicates (store
 * semantics). Values are one per line; blank lines ignored, whitespace trimmed.
 */
import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Card, Input, Modal, Space, Table, Tag, Typography, message } from 'antd'
import { DeleteOutlined, EditOutlined, ExportOutlined, ImportOutlined, PlusOutlined } from '@ant-design/icons'
import { useValueListStore } from '../../stores/valueListStore'
import { useTemplateStore } from '../../stores/templateStore'
import { exportList, extractListNames, importFromFile } from '../../services/valueListService'
import type { MessageTemplate, ValueList } from '../../types/generator'

const { Title } = Typography

interface PanelState {
  /** null = creating a new list; otherwise the name being edited. */
  originalName: string | null
  name: string
  valuesText: string
  error: string | null
}

interface PendingImport {
  name: string
  values: string[]
}

function parseValues(text: string): string[] {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')
}

/** Names of saved templates referencing `listName` in payload or header values. */
function referencingTemplates(templates: MessageTemplate[], listName: string): string[] {
  return templates
    .filter((t) => {
      const surface = [t.payloadSchema, ...Object.values(t.headers)].join('\n')
      return extractListNames(surface).includes(listName)
    })
    .map((t) => t.name)
}

export default function ValueListManagerPage() {
  const lists = useValueListStore((s) => s.lists)
  const loadLists = useValueListStore((s) => s.loadFromStorage)
  const loadTemplates = useTemplateStore((s) => s.loadFromStorage)
  const [panel, setPanel] = useState<PanelState | null>(null)
  const [pendingImport, setPendingImport] = useState<PendingImport | null>(null)
  const fileInputRef = useRef<HTMLInputElement | null>(null)

  useEffect(() => {
    loadLists()
    loadTemplates()
  }, [loadLists, loadTemplates])

  function openEdit(list: ValueList) {
    setPanel({
      originalName: list.name,
      name: list.name,
      valuesText: list.values.join('\n'),
      error: null,
    })
  }

  function savePanel() {
    if (!panel) return
    const name = panel.name.trim()
    const values = parseValues(panel.valuesText)
    if (name === '') {
      setPanel({ ...panel, error: 'A list name is required.' })
      return
    }
    const store = useValueListStore.getState()
    const collides = store.lists.some((l) => l.name === name)
    const now = new Date().toISOString()

    if (panel.originalName === null) {
      if (collides) {
        setPanel({ ...panel, error: `A list named "${name}" already exists.` })
        return
      }
      store.add({ name, values, createdAt: now, updatedAt: now })
    } else if (name === panel.originalName) {
      const existing = store.lists.find((l) => l.name === name)
      if (!existing) {
        // Deleted behind the open panel (e.g. in another tab): writing
        // {...undefined, values} would store a nameless malformed entry.
        setPanel({ ...panel, error: `The list "${name}" no longer exists — it may have been deleted elsewhere.` })
        return
      }
      store.update({ ...existing, values })
    } else {
      if (collides) {
        setPanel({ ...panel, error: `A list named "${name}" already exists.` })
        return
      }
      // Rename: the store keys by name — remove the old key, add the new one.
      const original = store.lists.find((l) => l.name === panel.originalName)
      store.remove(panel.originalName)
      store.add({
        name,
        values,
        createdAt: original?.createdAt ?? now,
        updatedAt: now,
      })
    }
    setPanel(null)
  }

  function confirmDelete(list: ValueList) {
    const references = referencingTemplates(useTemplateStore.getState().templates, list.name)
    Modal.confirm({
      title: `Delete list "${list.name}"?`,
      okText: 'Delete',
      okType: 'danger',
      content:
        references.length > 0 ? (
          <div data-testid="delete-references-warning">
            <Alert
              type="warning"
              showIcon
              message={`Referenced by ${references.length} template(s): ${references.join(', ')}. Their {{list:${list.name}}} tokens will resolve to "" after deletion.`}
            />
          </div>
        ) : (
          'This cannot be undone.'
        ),
      onOk: () => {
        useValueListStore.getState().remove(list.name)
        if (panel?.originalName === list.name) setPanel(null)
      },
    })
  }

  async function handleImport(file: File) {
    const { values, defaultName, errors } = await importFromFile(file)
    for (const error of errors) {
      // Coercion is a non-fatal warning (values still present); the rest are errors.
      if (values.length > 0) {
        message.warning(error)
      } else {
        message.error(`Import rejected — ${error}`)
      }
    }
    if (values.length === 0) return
    const collides = useValueListStore.getState().lists.some((l) => l.name === defaultName)
    if (collides) {
      setPendingImport({ name: defaultName, values })
    } else {
      const result = useValueListStore.getState().importList(defaultName, values, 'overwrite')
      message.success(`Imported list "${result.name}" (${result.total} values)`)
    }
  }

  function resolveCollision(mode: 'overwrite' | 'merge' | 'cancel') {
    if (!pendingImport) return
    if (mode !== 'cancel') {
      const result = useValueListStore.getState().importList(pendingImport.name, pendingImport.values, mode)
      message.success(
        mode === 'merge'
          ? `Merged into "${result.name}": ${result.added} new value(s), ${result.total} total`
          : `Overwrote "${result.name}" (${result.total} values)`
      )
    }
    setPendingImport(null)
  }

  const liveCount = panel ? parseValues(panel.valuesText).length : 0

  const columns = [
    { title: 'Name', dataIndex: 'name', key: 'name' },
    {
      title: 'Values (preview)',
      key: 'preview',
      render: (record: ValueList) => (
        <span data-testid={`list-preview-${record.name}`}>
          {record.values.slice(0, 4).join(', ')}
          {record.values.length > 4 ? ', …' : ''}
        </span>
      ),
    },
    {
      title: 'Count',
      key: 'count',
      render: (record: ValueList) => (
        <Tag color="blue" data-testid={`list-count-${record.name}`}>
          {record.values.length}
        </Tag>
      ),
    },
    {
      title: 'Actions',
      key: 'actions',
      render: (record: ValueList) => (
        <Space>
          <Button
            type="text"
            size="small"
            icon={<EditOutlined />}
            data-testid={`list-edit-${record.name}`}
            onClick={() => openEdit(record)}
          />
          <Button
            type="text"
            size="small"
            icon={<ExportOutlined />}
            data-testid={`list-export-${record.name}`}
            onClick={() => exportList(record.name, record.values)}
          />
          <Button
            type="text"
            size="small"
            danger
            icon={<DeleteOutlined />}
            data-testid={`list-delete-${record.name}`}
            onClick={() => confirmDelete(record)}
          />
        </Space>
      ),
    },
  ]

  return (
    <div data-testid="value-list-manager-page">
      <Title level={3}>Value List Manager</Title>
      <Space style={{ marginBottom: 12 }}>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => setPanel({ originalName: null, name: '', valuesText: '', error: null })}
        >
          New List
        </Button>
        <Button icon={<ImportOutlined />} onClick={() => fileInputRef.current?.click()}>
          Import JSON file
        </Button>
        <input
          ref={fileInputRef}
          type="file"
          accept=".json,application/json"
          style={{ display: 'none' }}
          data-testid="value-list-import-input"
          onChange={(e) => {
            const file = e.target.files?.[0]
            if (file) {
              // Never fire-and-forget: anything escaping handleImport surfaces.
              handleImport(file).catch((error: unknown) =>
                message.error(`Import failed: ${error instanceof Error ? error.message : String(error)}`)
              )
            }
            e.target.value = '' // allow re-importing the same file
          }}
        />
      </Space>

      <Table
        rowKey="name"
        columns={columns}
        dataSource={lists}
        pagination={{ pageSize: 10, showTotal: (total) => `${total} list${total !== 1 ? 's' : ''}` }}
        data-testid="value-list-table"
      />

      {panel && (
        <Card
          title={panel.originalName === null ? 'New list' : `Edit: ${panel.originalName}`}
          size="small"
          style={{ maxWidth: 560, marginTop: 12 }}
          data-testid="value-list-panel"
        >
          <Space direction="vertical" style={{ width: '100%' }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <label htmlFor="value-list-name">List name</label>
              <Input
                id="value-list-name"
                value={panel.name}
                onChange={(e) => setPanel({ ...panel, name: e.target.value, error: null })}
                style={{ width: 280 }}
              />
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <label htmlFor="value-list-values">Values (one per line)</label>
              <Input.TextArea
                id="value-list-values"
                value={panel.valuesText}
                onChange={(e) => setPanel({ ...panel, valuesText: e.target.value })}
                rows={10}
                style={{ fontFamily: 'monospace' }}
              />
            </div>
            <Tag color="blue" data-testid="value-count">
              {liveCount} value{liveCount !== 1 ? 's' : ''}
            </Tag>
            {panel.error && (
              <div data-testid="panel-error">
                <Alert type="error" showIcon message={panel.error} />
              </div>
            )}
            <Space>
              <Button type="primary" onClick={savePanel}>
                Save
              </Button>
              <Button onClick={() => setPanel(null)}>Cancel</Button>
            </Space>
          </Space>
        </Card>
      )}

      <Modal
        title={`List "${pendingImport?.name}" already exists`}
        open={pendingImport !== null}
        onCancel={() => resolveCollision('cancel')}
        footer={[
          <Button key="cancel" onClick={() => resolveCollision('cancel')}>
            Cancel
          </Button>,
          <Button key="merge" onClick={() => resolveCollision('merge')}>
            Merge
          </Button>,
          <Button key="overwrite" danger type="primary" onClick={() => resolveCollision('overwrite')}>
            Overwrite
          </Button>,
        ]}
      >
        <div data-testid="import-collision-modal">
          Overwrite replaces the existing {pendingImport === null ? 0 : useValueListStore.getState().lists.find((l) => l.name === pendingImport.name)?.values.length ?? 0}{' '}
          value(s); Merge appends the new values, skipping duplicates.
        </div>
      </Modal>
    </div>
  )
}
