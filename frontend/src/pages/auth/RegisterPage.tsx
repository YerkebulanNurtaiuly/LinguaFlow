import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useLocation, useNavigate } from 'react-router'
import { hasErrorCode } from '@/api/errors'
import { register as registerAccount } from '@/auth/authApi'
import { useAuth } from '@/auth/authContext'
import { type AuthPageState, readAuthPageState } from '@/auth/redirect'
import { Alert, Button, Field, Input, useToast } from '@/components/ui'
import { applyServerFieldErrors } from '@/lib/formErrors'
import {
  authErrorMessage,
  PASSWORD_MIN_LENGTH,
  type RegisterValues,
  registerSchema,
} from './authForms'

/** Error type that adds a "Log in instead" link under the email field */
const EMAIL_TAKEN = 'emailTaken'

/**
 * /register. Creates the account, then logs straight in (the backend's register call
 * doesn't); GuestRoute then moves the user on. If that login fails, the user lands on
 * /login with the email filled in.
 */
export function RegisterPage() {
  const { login } = useAuth()
  const { toast } = useToast()
  const navigate = useNavigate()
  const location = useLocation()
  const { from } = readAuthPageState(location.state)
  const [formError, setFormError] = useState<string>()

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<RegisterValues>({
    resolver: zodResolver(registerSchema),
    defaultValues: { displayName: '', email: '', password: '' },
  })

  const onSubmit = handleSubmit(async (values) => {
    setFormError(undefined)
    try {
      await registerAccount(values)
    } catch (error) {
      if (hasErrorCode(error, 'EMAIL_TAKEN')) {
        setError(
          'email',
          {
            type: EMAIL_TAKEN,
            message: 'An account with this email already exists.',
          },
          { shouldFocus: true },
        )
      } else if (
        !applyServerFieldErrors(error, setError, [
          'displayName',
          'email',
          'password',
        ])
      ) {
        setFormError(authErrorMessage(error))
      }
      return
    }

    toast({
      title: `Welcome, ${values.displayName}!`,
      description: `We've sent a verification link to ${values.email}.`,
      variant: 'success',
    })
    try {
      await login({ email: values.email, password: values.password })
    } catch {
      const state: AuthPageState = { from, registeredEmail: values.email }
      void navigate('/login', { replace: true, state })
    }
  })

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight text-fg">
          Create account
        </h1>
        <p className="mt-1 text-sm text-fg-muted">
          Start learning with your own vocabulary and grammar.
        </p>
      </div>

      {formError && <Alert variant="danger">{formError}</Alert>}

      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <Field label="Your name" error={errors.displayName?.message}>
          <Input autoComplete="name" autoFocus {...register('displayName')} />
        </Field>
        <Field
          label="Email"
          error={
            errors.email?.message && (
              <>
                {errors.email.message}
                {errors.email.type === EMAIL_TAKEN && (
                  <>
                    {' '}
                    <Link to="/login" state={{ from }} className="underline">
                      Log in instead
                    </Link>
                  </>
                )}
              </>
            )
          }
        >
          <Input type="email" autoComplete="email" {...register('email')} />
        </Field>
        <Field
          label="Password"
          hint={`At least ${PASSWORD_MIN_LENGTH} characters. A phrase of a few words works well.`}
          error={errors.password?.message}
        >
          <Input
            type="password"
            autoComplete="new-password"
            {...register('password')}
          />
        </Field>
        <Button type="submit" loading={isSubmitting} className="w-full">
          Create account
        </Button>
      </form>

      <p className="text-center text-sm text-fg-muted">
        Already have an account?{' '}
        <Link
          to="/login"
          state={{ from }}
          className="font-medium text-primary hover:underline"
        >
          Log in
        </Link>
      </p>
      <p className="text-center text-xs text-fg-subtle">
        <Link to="/privacy" className="hover:underline">
          How we handle your data
        </Link>
      </p>
    </div>
  )
}
