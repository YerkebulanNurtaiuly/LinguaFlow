import type {
  ChangePasswordRequest,
  DeleteAccountRequest,
  UpdateProfileRequest,
} from '@/api/types'
import { type CurrentUser, currentUserSchema } from '@/auth/authApi'
import { tokenStore } from '@/auth/tokenStore'
import { api, tokenResponseSchema } from '@/lib/api'

/** Partial update of the profile; absent fields stay unchanged. */
export async function updateProfile(
  request: UpdateProfileRequest,
): Promise<CurrentUser> {
  const { data } = await api.patch<unknown>('/users/me', request)
  return currentUserSchema.parse(data)
}

/**
 * Changes the password. The backend logs out every other device and returns new
 * tokens (and a new refresh cookie) so this one stays logged in.
 */
export async function changePassword(
  request: ChangePasswordRequest,
): Promise<void> {
  const { data } = await api.put<unknown>('/users/me/password', request)
  tokenStore.set(tokenResponseSchema.parse(data).accessToken)
}

/** Deletes the account and all its data, then ends the local session. */
export async function deleteAccount(
  request: DeleteAccountRequest,
): Promise<void> {
  await api.delete('/users/me', { data: request })
  tokenStore.set(null)
}
