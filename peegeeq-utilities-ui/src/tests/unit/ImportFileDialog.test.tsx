/**
 * ImportFileDialog — the shared in-app file-choosing dialog (2026-07-23).
 *
 * The dialog owns file CHOICE only: it must show the format hint, forward the
 * chosen File to onFile untouched, allow re-choosing the same file, and close
 * via Cancel without ever calling onFile. Real DOM + userEvent, no mocks.
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import ImportFileDialog from '../../components/ImportFileDialog'

function renderDialog(overrides: Partial<Parameters<typeof ImportFileDialog>[0]> = {}) {
  const onFile = vi.fn()
  const onClose = vi.fn()
  render(
    <ImportFileDialog
      open={true}
      title="Import value list"
      hint="A JSON array of strings."
      inputTestId="value-list-import-input"
      onFile={onFile}
      onClose={onClose}
      {...overrides}
    />
  )
  return { onFile, onClose }
}

describe('ImportFileDialog', () => {
  it('shows the title, the format hint, and the drop zone when open', () => {
    renderDialog()
    expect(screen.getByText('Import value list')).toBeTruthy()
    expect(screen.getByText('A JSON array of strings.')).toBeTruthy()
    expect(screen.getByText(/Drop the .json file here/)).toBeTruthy()
  })

  it('renders nothing when closed', () => {
    renderDialog({ open: false })
    expect(screen.queryByTestId('import-file-dialog')).toBeNull()
  })

  it('leaves nothing in the DOM AFTER being closed — not even its Cancel button', () => {
    // The case above only covers a dialog that was never opened. antd keeps a
    // closed Modal mounted and merely hides it, so once opened this dialog's
    // Cancel and file input survived the close. Every import flow then opens a
    // collision prompt that also has a "Cancel", and the duplicate name made
    // that prompt unaddressable by role+name. Pinned here because the leak is
    // invisible from the open state.
    const { rerender } = render(
      <ImportFileDialog
        open={true}
        title="Import value list"
        hint="A JSON array of strings."
        inputTestId="value-list-import-input"
        onFile={vi.fn()}
        onClose={vi.fn()}
      />
    )
    expect(screen.getByRole('button', { name: /^Cancel$/ })).toBeTruthy()

    rerender(
      <ImportFileDialog
        open={false}
        title="Import value list"
        hint="A JSON array of strings."
        inputTestId="value-list-import-input"
        onFile={vi.fn()}
        onClose={vi.fn()}
      />
    )

    expect(screen.queryByRole('button', { name: /^Cancel$/ })).toBeNull()
    expect(screen.queryByTestId('value-list-import-input')).toBeNull()
    expect(screen.queryByTestId('import-file-dialog')).toBeNull()
  })

  it('forwards the chosen file to onFile, untouched', async () => {
    const { onFile, onClose } = renderDialog()
    const file = new File(['["Zoe"]'], 'first_names.json', { type: 'application/json' })

    await userEvent.upload(screen.getByTestId('value-list-import-input'), file)

    expect(onFile).toHaveBeenCalledTimes(1)
    expect(onFile.mock.calls[0][0]).toBe(file)
    expect(onClose).not.toHaveBeenCalled() // closing is the parent's decision
  })

  it('allows choosing the same file twice (input value is reset after each choice)', async () => {
    const { onFile } = renderDialog()
    const file = new File(['["Zoe"]'], 'first_names.json', { type: 'application/json' })
    const input = screen.getByTestId('value-list-import-input')

    await userEvent.upload(input, file)
    await userEvent.upload(input, file)

    expect(onFile).toHaveBeenCalledTimes(2)
  })

  it('Cancel closes without ever calling onFile', async () => {
    const { onFile, onClose } = renderDialog()

    await userEvent.click(screen.getByRole('button', { name: /^Cancel$/ }))

    expect(onClose).toHaveBeenCalledTimes(1)
    expect(onFile).not.toHaveBeenCalled()
  })

  it('carries the caller-supplied input testid — pages keep their pre-dialog ids', () => {
    renderDialog({ inputTestId: 'template-import-input' })
    expect(screen.getByTestId('template-import-input')).toBeTruthy()
  })
})
