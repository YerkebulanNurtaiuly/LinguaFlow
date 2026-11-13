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

const emptyPage = {
  content: [],
  page: 0,
  size: 20,
  totalElements: 0,
  totalPages: 0,
}

/** A learner with nothing reviewed yet. */
export const emptyReviewStats = {
  today: '2026-11-13',
  dueToday: 0,
  newAvailable: 0,
  reviewedToday: 0,
  dailyReviewGoal: 50,
  currentStreak: 0,
  longestStreak: 0,
  retention: { days: 30, reviews: 0, remembered: 0, rate: null },
  forecast: [],
}

const emptyMaterial = {
  total: 0,
  byStatus: [],
  byLevel: [],
  unleveled: 0,
}

/** A learner with no practice yet. */
export const emptyProgressStats = {
  today: '2026-11-13',
  words: emptyMaterial,
  grammarTopics: emptyMaterial,
  activity: [],
  accuracy: [],
  currentStreak: 0,
  longestStreak: 0,
  timeStudied: { todayMs: 0, periodMs: 0, totalMs: 0 },
}

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
    cardDirections: ['TARGET_TO_NATIVE', 'NATIVE_TO_TARGET'],
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
  fakeApi.fallback('GET', '/study-sessions/active', { status: 204 })
  // The dashboard (FE-PRG-01), where logged-in tests usually land
  fakeApi.fallback('GET', '/reviews/stats', {
    status: 200,
    data: emptyReviewStats,
  })
  fakeApi.fallback('GET', '/progress/stats', {
    status: 200,
    data: emptyProgressStats,
  })
  fakeApi.fallback('GET', '/progress/weak-points', {
    status: 200,
    data: { grammarTopics: [], words: [] },
  })
  fakeApi.fallback('GET', '/practice-sessions', {
    status: 200,
    data: emptyPage,
  })
  fakeApi.fallback('GET', '/practice-sessions/overview', {
    status: 200,
    data: {
      currentLesson: null,
      words: 0,
      freshWords: 0,
      topics: 0,
      practiceSessionLength: 20,
      inProgress: null,
    },
  })
  fakeApi.fallback('GET', '/study-sessions/timeline', {
    status: 200,
    data: {
      months: [],
      sessionCount: 0,
      vocabularyCount: 0,
      grammarTopicCount: 0,
    },
  })
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
