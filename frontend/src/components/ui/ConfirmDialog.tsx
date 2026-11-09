import { useRef, useState, type ReactNode } from 'react'
import { Button } from './Button'
import { Modal } from './Modal'

export interface ConfirmDialogProps {
  open: boolean
  onCancel: () => void
  /** May return a promise; the confirm button shows a spinner until it settles */
  onConfirm: () => void | Promise<void>
  title: ReactNode
  description?: ReactNode
  confirmLabel?: string
  cancelLabel?: string
  /** Destructive actions get a red confirm button */
  destructive?: boolean
}

/**
 * Asks before an action. Focus starts on Cancel, so pressing Enter right away never
 * confirms a destructive action by accident.
 */
export function ConfirmDialog({
  open,
  onCancel,
  onConfirm,
  title,
  description,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  destructive = false,
}: ConfirmDialogProps) {
  const [busy, setBusy] = useState(false)
  const cancelButton = useRef<HTMLButtonElement>(null)

  const confirm = async () => {
    setBusy(true)
    try {
      await onConfirm()
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      open={open}
      onClose={busy ? () => {} : onCancel}
      title={title}
      description={description}
      size="sm"
      closeOnBackdrop={!busy}
      initialFocus={cancelButton}
      footer={
        <>
          <Button
            ref={cancelButton}
            variant="secondary"
            onClick={onCancel}
            disabled={busy}
          >
            {cancelLabel}
          </Button>
          <Button
            variant={destructive ? 'danger' : 'primary'}
            onClick={confirm}
            loading={busy}
          >
            {confirmLabel}
          </Button>
        </>
      }
    />
  )
}
