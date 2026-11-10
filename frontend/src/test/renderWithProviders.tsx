import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { routes } from '@/app/routes'
import { AuthProvider } from '@/auth/AuthProvider'
import type { CurrentUser } from '@/auth/authApi'
import { tokenStore } from '@/auth/tokenStore'
import { ErrorToasts } from '@/components/layout/ErrorToasts'
import { ThemeProvider, ToastProvider } from '@/components/ui'
import type {
  AvailableLanguage,
  LearnedLanguage,
} from '@/languages/languagesApi'
import { createQueryClient } from '@/lib/queryClient'
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

/** The language catalog as the backend seeds it: German to learn, English for explanations. */
export const testCatalog: AvailableLanguage[] = [
  {
    code: 'de',
    name: 'German',
    nativeName: 'Deutsch',
    supportedAsTarget: true,
    supportedAsExplanation: true,
  },
  {
    code: 'en',
    name: 'English',
    nativeName: 'English',
    supportedAsTarget: false,
    supportedAsExplanation: true,
  },
]

export const testLearnedLanguage: LearnedLanguage = {
  languageCode: 'de',
  explanationLanguageCode: 'en',
  selfAssessedLevel: 'A2',
  active: true,
  createdAt: '2026-11-01T10:05:00Z',
  settings: {
    dailyReviewGoal: 50,
    newCardsPerDay: 20,
    practiceSessionLength: 20,
    preferredExerciseTypes: [],
  },
}

export interface RenderRouteOptions {
  /**
   * `authenticated` (default) starts with an access token and `user` as the profile;
   * `anonymous` starts with none, so the app tries the refresh cookie first, which the
   * fake API rejects unless the test registers its own POST /auth/refresh handler.
   */
  session?: 'authenticated' | 'anonymous'
  user?: CurrentUser
  /** What GET /users/me/languages returns; `[]` means onboarding isn't done yet */
  languages?: LearnedLanguage[]
}

/**
 * Renders the app's routes at `path` with all app providers and a fresh, retry-free
 * query client. The profile, the user's languages and the catalog are answered by
 * fallbacks, which a test's own `fakeApi.on` handlers override.
 */
export function renderRoute(
  path: string,
  {
    session = 'authenticated',
    user = testUser,
    languages = [testLearnedLanguage],
  }: RenderRouteOptions = {},
) {
  if (session === 'authenticated') tokenStore.set(TEST_ACCESS_TOKEN)
  fakeApi.fallback('GET', '/users/me', { status: 200, data: user })
  fakeApi.fallback('GET', '/users/me/languages', {
    status: 200,
    data: languages,
  })
  fakeApi.fallback('GET', '/languages', { status: 200, data: testCatalog })
  // An empty vocabulary, so sections that list words render without setup
  fakeApi.fallback('GET', '/vocabulary', {
    status: 200,
    data: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
  })
  fakeApi.fallback('GET', '/tags', { status: 200, data: [] })
  fakeApi.fallback('GET', '/grammar-topics', {
    status: 200,
    data: { content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 },
  })
  fakeApi.fallback('GET', '/grammar-topics/catalog', { status: 200, data: [] })
  const queryClient = createQueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const result = render(
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <ErrorToasts />
          <AuthProvider>
            <RouterProvider router={router} />
          </AuthProvider>
        </ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  )
  return { ...result, router, queryClient }
}

/** Like `renderRoute`, then waits until the app shell (after the session and onboarding checks) is shown. */
export async function renderApp(path: string, options?: RenderRouteOptions) {
  const view = renderRoute(path, options)
  await screen.findAllByRole('navigation', { name: 'Main' })
  return view
}
