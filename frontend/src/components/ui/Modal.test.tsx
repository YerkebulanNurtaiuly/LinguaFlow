import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { Button } from './Button'
import { ConfirmDialog } from './ConfirmDialog'
import { Modal } from './Modal'

function ModalHarness({ onClose }: { onClose?: () => void }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button onClick={() => setOpen(true)}>Open</Button>
      <Modal
        open={open}
        onClose={() => {
          setOpen(false)
          onClose?.()
        }}
        title="Edit word"
        description="Changes are saved."
        footer={<Button onClick={() => setOpen(false)}>Save</Button>}
      >
        <input aria-label="Translation" />
      </Modal>
    </>
  )
}

describe('Modal', () => {
  it('is a labelled dialog that takes focus and returns it on close', async () => {
    render(<ModalHarness />)
    const opener = screen.getByRole('button', { name: 'Open' })
    await userEvent.click(opener)

    const dialog = screen.getByRole('dialog', { name: 'Edit word' })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveAccessibleDescription('Changes are saved.')
    expect(dialog).toContainElement(document.activeElement as HTMLElement)

    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(opener).toHaveFocus()
  })

  it('traps Tab inside the dialog', async () => {
    render(<ModalHarness />)
    await userEvent.click(screen.getByRole('button', { name: 'Open' }))
    const dialog = screen.getByRole('dialog')

    for (let i = 0; i < 6; i++) {
      await userEvent.tab()
      expect(dialog).toContainElement(document.activeElement as HTMLElement)
    }
    await userEvent.tab({ shift: true })
    expect(dialog).toContainElement(document.activeElement as HTMLElement)
  })

  it('closes on backdrop click and via the close button', async () => {
    const onClose = vi.fn()
    render(<ModalHarness onClose={onClose} />)

    await userEvent.click(screen.getByRole('button', { name: 'Open' }))
    await userEvent.click(screen.getByTestId('modal-backdrop'))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Open' }))
    await userEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(onClose).toHaveBeenCalledTimes(2)
  })

  it('locks page scroll while open', async () => {
    render(<ModalHarness />)
    await userEvent.click(screen.getByRole('button', { name: 'Open' }))
    expect(document.body.style.overflow).toBe('hidden')
    await userEvent.keyboard('{Escape}')
    expect(document.body.style.overflow).toBe('')
  })
})

describe('ConfirmDialog', () => {
  it('starts on Cancel so Enter does not confirm a destructive action', async () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    render(
      <ConfirmDialog
        open
        onCancel={onCancel}
        onConfirm={onConfirm}
        title="Delete this word?"
        confirmLabel="Delete"
        destructive
      />,
    )
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus()
    await userEvent.keyboard('{Enter}')
    expect(onCancel).toHaveBeenCalledOnce()
    expect(onConfirm).not.toHaveBeenCalled()
  })

  it('shows progress until an async confirm finishes', async () => {
    let finish: () => void = () => {}
    const onConfirm = vi.fn(
      () => new Promise<void>((resolve) => (finish = resolve)),
    )
    render(
      <ConfirmDialog
        open
        onCancel={vi.fn()}
        onConfirm={onConfirm}
        title="Remove German?"
        confirmLabel="Remove"
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: 'Remove' }))
    expect(screen.getByRole('button', { name: 'Remove' })).toHaveAttribute(
      'aria-busy',
      'true',
    )
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled()

    finish()
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'Remove' }),
      ).not.toHaveAttribute('aria-busy'),
    )
  })
})
