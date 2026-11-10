import { z } from 'zod'
import {
  getErrorMessage,
  getRetryAfterSeconds,
  hasErrorCode,
  isNetworkError,
} from '@/api/errors'
import type { LoginRequest, RegistrationRequest } from '@/api/types'

const email = z
  .string()
  .trim()
  .min(1, 'Enter your email address')
  .max(320, 'Email address is too long')
  .pipe(z.email('Enter a valid email address'))

/** Mirrors the backend policy (auth.password.@ValidPassword), except the common-passwords list. */
export const PASSWORD_MIN_LENGTH = 10
const PASSWORD_MAX_BYTES = 72

export const newPasswordSchema = z
  .string()
  .min(PASSWORD_MIN_LENGTH, `Use at least ${PASSWORD_MIN_LENGTH} characters`)
  .refine((value) => value.trim().length > 0, 'Password cannot be only spaces')
  .refine(
    (value) => new TextEncoder().encode(value).length <= PASSWORD_MAX_BYTES,
    'Password is too long',
  )

export const loginSchema = z.object({
  email,
  password: z.string().min(1, 'Enter your password'),
}) satisfies z.ZodType<LoginRequest>

export const registerSchema = z.object({
  displayName: z
    .string()
    .trim()
    .min(1, 'Enter your name')
    .max(100, 'Name is too long'),
  email,
  password: newPasswordSchema,
}) satisfies z.ZodType<RegistrationRequest>

export type LoginValues = z.infer<typeof loginSchema>
export type RegisterValues = z.infer<typeof registerSchema>

function waitTime(seconds: number): string {
  if (seconds < 60) return seconds === 1 ? '1 second' : `${seconds} seconds`
  const minutes = Math.ceil(seconds / 60)
  return minutes === 1 ? '1 minute' : `${minutes} minutes`
}

/** Form-level message for a failed login or registration request. */
export function authErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return "Can't reach LinguaFlow right now. Check your connection and try again."
  }
  if (hasErrorCode(error, 'INVALID_CREDENTIALS')) {
    return 'Wrong email or password.'
  }
  if (hasErrorCode(error, 'ACCOUNT_DISABLED')) {
    return 'This account has been disabled.'
  }
  if (hasErrorCode(error, 'TOO_MANY_REQUESTS')) {
    const seconds = getRetryAfterSeconds(error)
    return seconds
      ? `Too many attempts. Try again in ${waitTime(seconds)}.`
      : 'Too many attempts. Try again later.'
  }
  return getErrorMessage(error)
}
