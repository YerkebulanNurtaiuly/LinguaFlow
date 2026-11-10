import { z } from 'zod'
import type {
  LoginRequest,
  RegistrationRequest,
  UserProfile,
} from '@/api/types'
import { api, tokenResponseSchema } from '@/lib/api'
import { tokenStore } from './tokenStore'

export const currentUserSchema = z.object({
  id: z.string(),
  email: z.string(),
  displayName: z
    .string()
    .nullish()
    .transform((name) => name ?? undefined),
  role: z.enum(['USER', 'ADMIN']),
  emailVerified: z.boolean(),
  interfaceLanguage: z.string(),
  timezone: z.string(),
  createdAt: z.string(),
}) satisfies z.ZodType<UserProfile>

export type CurrentUser = z.infer<typeof currentUserSchema>

/** Creates an account. Doesn't log in; call `login` next. */
export async function register(request: RegistrationRequest): Promise<void> {
  await api.post('/auth/register', request, { skipAuthRefresh: true })
}

/** Logs in and starts the session; the refresh cookie is set by the response. */
export async function login(credentials: LoginRequest): Promise<void> {
  const { data } = await api.post<unknown>('/auth/login', credentials, {
    skipAuthRefresh: true,
  })
  tokenStore.set(tokenResponseSchema.parse(data).accessToken)
}

/** Revokes the refresh session on the server. Always ends the local session, even offline. */
export async function logout(): Promise<void> {
  try {
    await api.post('/auth/logout', undefined, { skipAuthRefresh: true })
  } finally {
    tokenStore.set(null)
  }
}

export async function fetchCurrentUser(): Promise<CurrentUser> {
  const { data } = await api.get<unknown>('/users/me')
  return currentUserSchema.parse(data)
}
