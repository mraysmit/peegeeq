/**
 * In-app file-choosing dialog for the three JSON import sites (value lists,
 * message templates, schedules).
 *
 * Decision 2026-07-23 (user): imports must present an in-app dialog — showing
 * WHAT the file must contain — instead of popping the native picker straight
 * from a hidden input. The hidden-input pattern was also untestable beyond
 * setInputFiles and impossible to screenshot, which is why the import step was
 * absent from the documentation gallery.
 *
 * The dialog owns only file CHOICE. Parsing, validation, collision handling,
 * and outcome messages stay with each page's existing handleImport — this
 * component forwards the chosen File and nothing else. The file input keeps
 * the page's pre-dialog data-testid so unit tests and e2e specs target the
 * same id; it is stretched invisibly over the drop zone, so clicking anywhere
 * in the zone opens the browser's file picker and dropping a file onto the
 * zone hits the input's native drag-and-drop handling.
 */
import type { ReactNode } from 'react'
import { Button, Modal, Typography } from 'antd'

const { Text } = Typography

export interface ImportFileDialogProps {
  open: boolean
  title: string
  /** What the file must contain — the user is never left guessing the format. */
  hint: ReactNode
  /** data-testid for the real file input (preserved from the pre-dialog wiring). */
  inputTestId: string
  accept?: string
  /** Receives the chosen file; the parent closes the dialog and runs its import. */
  onFile: (file: File) => void
  onClose: () => void
}

export default function ImportFileDialog({
  open,
  title,
  hint,
  inputTestId,
  accept = '.json,application/json',
  onFile,
  onClose,
}: ImportFileDialogProps) {
  return (
    <Modal
      title={title}
      open={open}
      onCancel={onClose}
      // Unmount the body and footer on close (2026-07-29). Without this, antd
      // keeps a closed Modal mounted and merely hides it, so this dialog's
      // "Cancel" and its file input stayed in the DOM after the parent closed
      // it — and every import flow opens a SECOND modal (the collision prompt)
      // that also has a "Cancel". Two identically-named buttons made the
      // collision prompt unaddressable by name. It also stops a chosen File
      // being retained by a hidden input after the dialog is done with it.
      // `destroyOnHidden` is the antd 5.25+ name; `destroyOnClose` is deprecated.
      destroyOnHidden
      footer={[
        <Button key="cancel" onClick={onClose}>
          Cancel
        </Button>,
      ]}
    >
      <div data-testid="import-file-dialog">
        <div style={{ marginBottom: 12 }}>
          <Text>{hint}</Text>
        </div>
        <div
          style={{
            position: 'relative',
            border: '1px dashed #d9d9d9',
            borderRadius: 8,
            padding: '32px 16px',
            textAlign: 'center',
          }}
        >
          <Text type="secondary">Drop the .json file here, or click to choose a file</Text>
          <input
            type="file"
            accept={accept}
            data-testid={inputTestId}
            aria-label="Import file"
            style={{
              position: 'absolute',
              inset: 0,
              width: '100%',
              height: '100%',
              opacity: 0,
              cursor: 'pointer',
            }}
            onChange={(e) => {
              const file = e.target.files?.[0]
              if (file) onFile(file)
              e.target.value = '' // allow re-importing the same file
            }}
          />
        </div>
      </div>
    </Modal>
  )
}
