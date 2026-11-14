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

/** The file name from a `Content-Disposition: attachment; filename="…"` header. */
export function attachmentFilename(
  header: string | undefined,
  fallback: string,
): string {
  const match = header ? /filename="?([^";]+)"?/.exec(header) : null
  return match?.[1] ?? fallback
}

/**
 * Downloads everything stored about the user as one JSON file (SEC-02): fetches
 * `GET /users/me/export` and hands it to the browser as a download.
 */
export async function downloadMyData(): Promise<void> {
  const response = await api.get<Blob>('/users/me/export', {
    responseType: 'blob',
  })
  const blob =
    response.data instanceof Blob
      ? response.data
      : new Blob([JSON.stringify(response.data)], { type: 'application/json' })
  const contentDisposition = response.headers['content-disposition'] as
    string | undefined
  const url = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = attachmentFilename(
      contentDisposition,
      'linguaflow-export.json',
    )
    document.body.append(link)
    link.click()
    link.remove()
  } finally {
    URL.revokeObjectURL(url)
  }
}

/** Deletes the account and all its data, then ends the local session. */
export async function deleteAccount(
  request: DeleteAccountRequest,
): Promise<void> {
  await api.delete('/users/me', { data: request })
  tokenStore.set(null)
}
