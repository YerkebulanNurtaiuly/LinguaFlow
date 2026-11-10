import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useLocation } from 'react-router'
import { hasErrorCode } from '@/api/errors'
import { useAuth } from '@/auth/authContext'
import { readAuthPageState } from '@/auth/redirect'
import { Alert, Button, Field, Input } from '@/components/ui'
import { applyServerFieldErrors } from '@/lib/formErrors'
import { authErrorMessage, type LoginValues, loginSchema } from './authForms'

/** /login. On success GuestRoute moves the user on to `state.from` or the dashboard. */
export function LoginPage() {
  const { login } = useAuth()
  const location = useLocation()
  const { from, registeredEmail } = readAuthPageState(location.state)
  const [formError, setFormError] = useState<string>()

  const {
    register,
    handleSubmit,
    setError,
    resetField,
    setFocus,
    formState: { errors, isSubmitting },
  } = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: registeredEmail ?? '', password: '' },
  })

  const onSubmit = handleSubmit(async (values) => {
    setFormError(undefined)
    try {
      await login(values)
    } catch (error) {
      if (applyServerFieldErrors(error, setError, ['email', 'password'])) {
        return
      }
      setFormError(authErrorMessage(error))
      if (hasErrorCode(error, 'INVALID_CREDENTIALS')) {
        resetField('password')
        setFocus('password')
      }
    }
  })

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight text-fg">Log in</h1>
        <p className="mt-1 text-sm text-fg-muted">
          Welcome back. Pick up where you left off.
        </p>
      </div>

      {registeredEmail && !formError && (
        <Alert variant="success" title="Account created">
          Log in to continue.
        </Alert>
      )}
      {formError && <Alert variant="danger">{formError}</Alert>}

      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <Field label="Email" error={errors.email?.message}>
          <Input
            type="email"
            autoComplete="email"
            autoFocus={!registeredEmail}
            {...register('email')}
          />
        </Field>
        <Field label="Password" error={errors.password?.message}>
          <Input
            type="password"
            autoComplete="current-password"
            autoFocus={Boolean(registeredEmail)}
            {...register('password')}
          />
        </Field>
        <Button type="submit" loading={isSubmitting} className="w-full">
          Log in
        </Button>
      </form>

      <p className="text-center text-sm text-fg-muted">
        New to LinguaFlow?{' '}
        <Link
          to="/register"
          state={{ from }}
          className="font-medium text-primary hover:underline"
        >
          Create an account
        </Link>
      </p>
    </div>
  )
}
