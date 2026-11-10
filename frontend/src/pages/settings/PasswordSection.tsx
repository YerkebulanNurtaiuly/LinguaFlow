import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { describeError } from '@/api/errors'
import { changePassword } from '@/account/accountApi'
import { Alert, Button, Field, Input, useToast } from '@/components/ui'
import { applyServerFieldErrors } from '@/lib/formErrors'
import { PASSWORD_MIN_LENGTH } from '@/pages/auth/authForms'
import { type PasswordValues, passwordSchema } from './settingsForms'
import { SettingsSection } from './SettingsSection'

const EMPTY: PasswordValues = {
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
}

export function PasswordSection({ email }: { email: string }) {
  const { toast } = useToast()
  const [formError, setFormError] = useState<string>()
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<PasswordValues>({
    resolver: zodResolver(passwordSchema),
    defaultValues: EMPTY,
  })

  const onSubmit = handleSubmit(async ({ currentPassword, newPassword }) => {
    setFormError(undefined)
    try {
      await changePassword({ currentPassword, newPassword })
      reset(EMPTY)
      toast({
        title: 'Password changed',
        description: "You've been logged out on all other devices.",
        variant: 'success',
      })
    } catch (error) {
      if (
        !applyServerFieldErrors(
          error,
          setError,
          ['currentPassword', 'newPassword'],
          { currentPassword: 'Current password', newPassword: 'New password' },
        )
      ) {
        setFormError(describeError(error))
      }
    }
  })

  return (
    <SettingsSection
      title="Password"
      description="Changing it logs you out everywhere else."
    >
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        {formError && <Alert variant="danger">{formError}</Alert>}
        {/* Lets password managers link the new password to this account */}
        <input
          type="text"
          autoComplete="username"
          value={email}
          hidden
          readOnly
          tabIndex={-1}
        />
        <Field label="Current password" error={errors.currentPassword?.message}>
          <Input
            type="password"
            autoComplete="current-password"
            {...register('currentPassword')}
          />
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field
            label="New password"
            hint={`At least ${PASSWORD_MIN_LENGTH} characters.`}
            error={errors.newPassword?.message}
          >
            <Input
              type="password"
              autoComplete="new-password"
              {...register('newPassword')}
            />
          </Field>
          <Field
            label="Repeat new password"
            error={errors.confirmPassword?.message}
          >
            <Input
              type="password"
              autoComplete="new-password"
              {...register('confirmPassword')}
            />
          </Field>
        </div>
        <div className="flex justify-end">
          <Button type="submit" loading={isSubmitting}>
            Change password
          </Button>
        </div>
      </form>
    </SettingsSection>
  )
}
