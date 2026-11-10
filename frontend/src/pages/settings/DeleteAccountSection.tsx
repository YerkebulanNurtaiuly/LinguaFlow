import { zodResolver } from '@hookform/resolvers/zod'
import { useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { getErrorMessage } from '@/api/errors'
import { deleteAccount } from '@/account/accountApi'
import { Alert, Button, Field, Input, Modal, useToast } from '@/components/ui'
import { applyServerFieldErrors } from '@/lib/formErrors'
import { type DeleteAccountValues, deleteAccountSchema } from './settingsForms'
import { SettingsSection } from './SettingsSection'

/** Danger zone: deletes the account after the password is re-entered. */
export function DeleteAccountSection() {
  const { toast } = useToast()
  const [open, setOpen] = useState(false)
  const [formError, setFormError] = useState<string>()
  const passwordInput = useRef<HTMLInputElement | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<DeleteAccountValues>({
    resolver: zodResolver(deleteAccountSchema),
    defaultValues: { password: '' },
  })
  const { ref: registerRef, ...passwordField } = register('password')

  const close = () => {
    setOpen(false)
    setFormError(undefined)
    reset()
  }

  const onSubmit = handleSubmit(async (values) => {
    setFormError(undefined)
    try {
      // Ends the session; the route guard then shows the login page
      await deleteAccount(values)
      toast({
        title: 'Your account has been deleted',
        description: 'All your data is gone. Thanks for learning with us.',
      })
    } catch (error) {
      if (
        !applyServerFieldErrors(error, setError, ['password'], {
          password: 'Password',
        })
      ) {
        setFormError(getErrorMessage(error))
      }
    }
  })

  return (
    <SettingsSection
      title="Delete account"
      tone="danger"
      description="Permanently delete your account and everything in it."
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-fg-muted">
          Your vocabulary, grammar topics, flashcards and progress for every
          language are deleted at once. This can't be undone.
        </p>
        <Button variant="danger" onClick={() => setOpen(true)}>
          Delete account
        </Button>
      </div>

      <Modal
        open={open}
        onClose={close}
        closeOnBackdrop={false}
        title="Delete your account?"
        description="Enter your password to confirm. Everything is deleted immediately."
        initialFocus={passwordInput}
        footer={
          <>
            <Button variant="secondary" onClick={close}>
              Cancel
            </Button>
            <Button
              variant="danger"
              type="submit"
              form="delete-account-form"
              loading={isSubmitting}
            >
              Delete my account
            </Button>
          </>
        }
      >
        <form
          id="delete-account-form"
          onSubmit={onSubmit}
          noValidate
          className="space-y-4"
        >
          {formError && <Alert variant="danger">{formError}</Alert>}
          <Field label="Password" error={errors.password?.message}>
            <Input
              type="password"
              autoComplete="current-password"
              {...passwordField}
              ref={(element) => {
                registerRef(element)
                passwordInput.current = element
              }}
            />
          </Field>
        </form>
      </Modal>
    </SettingsSection>
  )
}
