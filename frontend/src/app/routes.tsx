import type { ReactNode } from 'react'
import { Navigate, type RouteObject } from 'react-router'
import { AppLayout } from '@/app/layouts/AppLayout'
import { AuthLayout } from '@/app/layouts/AuthLayout'
import { NAV_ITEMS } from '@/app/navigation'
import { RouteErrorPage } from '@/app/RouteErrorPage'
import type { RouteHandle } from '@/app/RouteTitle'
import { GuestRoute } from '@/auth/GuestRoute'
import { ProtectedRoute } from '@/auth/ProtectedRoute'
import { OnboardingGate } from '@/onboarding/OnboardingGate'
import { LoginPage } from '@/pages/auth/LoginPage'
import { RegisterPage } from '@/pages/auth/RegisterPage'
import { DesignSystemPage } from '@/pages/DesignSystemPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { OnboardingPage } from '@/pages/onboarding/OnboardingPage'
import { PlaceholderPage } from '@/pages/PlaceholderPage'
import { SettingsPage } from '@/pages/settings/SettingsPage'
import { VocabularyPage } from '@/pages/vocabulary/VocabularyPage'

const title = (value: string): RouteHandle => ({ title: value })

/** Sections that are built; the others still show a placeholder. */
const SECTION_PAGES: Partial<Record<string, ReactNode>> = {
  '/settings': <SettingsPage />,
  '/vocabulary': <VocabularyPage />,
}

/**
 * The full route map. Sections render placeholders until their feature task replaces
 * the element (e.g. FE-VOC-01 for /vocabulary). Everything inside AppLayout requires a
 * session (ProtectedRoute redirects to /login) and a learning language (OnboardingGate
 * redirects to /onboarding); /login and /register are for logged-out users only
 * (GuestRoute sends a logged-in user on).
 *
 * Errors thrown while rendering: a section's error shows inside the shell (inline
 * RouteErrorPage), anything else gets the full-page RouteErrorPage.
 */
export const routes: RouteObject[] = [
  {
    errorElement: <RouteErrorPage />,
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      {
        element: <GuestRoute />,
        children: [
          {
            element: <AuthLayout />,
            children: [
              {
                path: '/login',
                handle: title('Log in'),
                element: <LoginPage />,
              },
              {
                path: '/register',
                handle: title('Create account'),
                element: <RegisterPage />,
              },
            ],
          },
        ],
      },
      {
        element: <ProtectedRoute />,
        children: [
          {
            element: <AuthLayout width="wide" />,
            children: [
              {
                path: '/onboarding',
                handle: title('Get started'),
                element: <OnboardingPage />,
              },
            ],
          },
          {
            element: <OnboardingGate />,
            children: [
              {
                element: <AppLayout />,
                children: [
                  {
                    errorElement: <RouteErrorPage inline />,
                    children: [
                      ...NAV_ITEMS.map(({ path, label, description }) => ({
                        path,
                        handle: title(label),
                        element: SECTION_PAGES[path] ?? (
                          <PlaceholderPage
                            title={label}
                            description={description}
                          />
                        ),
                      })),
                      // Dev-only component showcase; not in production builds
                      ...(import.meta.env.DEV
                        ? [
                            {
                              path: '/design-system',
                              handle: title('Design system'),
                              element: <DesignSystemPage />,
                            },
                          ]
                        : []),
                    ],
                  },
                ],
              },
            ],
          },
        ],
      },
      {
        path: '*',
        handle: title('Page not found'),
        element: <NotFoundPage />,
      },
    ],
  },
]
