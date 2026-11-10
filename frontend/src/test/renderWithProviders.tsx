import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { routes } from '@/app/routes'
import { AuthProvider } from '@/auth/AuthProvider'
import type { CurrentUser } from '@/auth/authApi'
import { tokenStore } from '@/auth/tokenStore'
import { ThemeProvider, ToastProvider } from '@/components/ui'
import { fakeApi } from './fakeApi'

export const TEST_ACCESS_TOKEN = 'test-access-token'

export const testUser: CurrentUser = {
  id: '0190a6f0-0000-7000-8000-000000000001',
  email: 'anna@example.com',
  displayName: 'Anna',
  role: 'USER',
  emailVerified: true,
  interfaceLanguage: 'en',
  timezone: 'Europe/Berlin',
  createdAt: '2026-11-01T10:00:00Z',
}

export interface RenderRouteOptions {
  /**
   * `authenticated` (default) starts with an access token and `user` as the profile;
   * `anonymous` starts with none, so the app tries the refresh cookie first, which the
   * fake API rejects unless the test registers its own POST /auth/refresh handler.
   */
  session?: 'authenticated' | 'anonymous'
  user?: CurrentUser
}

/** Renders the app's routes at `path` with all app providers and a fresh, retry-free query client. */
export function renderRoute(
  path: string,
  { session = 'authenticated', user = testUser }: RenderRouteOptions = {},
) {
  if (session === 'authenticated') {
    tokenStore.set(TEST_ACCESS_TOKEN)
    fakeApi.fallback('GET', '/users/me', { status: 200, data: user })
  }
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const result = render(
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <AuthProvider>
            <RouterProvider router={router} />
          </AuthProvider>
        </ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  )
  return { ...result, router, queryClient }
}
