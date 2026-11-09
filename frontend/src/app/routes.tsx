import { Navigate, type RouteObject } from 'react-router'
import { AppLayout } from '@/app/layouts/AppLayout'
import { AuthLayout } from '@/app/layouts/AuthLayout'
import { NAV_ITEMS } from '@/app/navigation'
import type { RouteHandle } from '@/app/RouteTitle'
import { DesignSystemPage } from '@/pages/DesignSystemPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { PlaceholderPage } from '@/pages/PlaceholderPage'

const title = (value: string): RouteHandle => ({ title: value })

/**
 * The full route map. Sections render placeholders until their feature task replaces
 * the element (e.g. FE-VOC-01 for /vocabulary); login/register forms arrive in FE-04
 * and the redirect-to-login guard in FE-03.
 */
export const routes: RouteObject[] = [
  { index: true, element: <Navigate to="/dashboard" replace /> },
  {
    element: <AuthLayout />,
    children: [
      {
        path: '/login',
        handle: title('Log in'),
        element: (
          <PlaceholderPage
            title="Log in"
            description="Log in with your email and password."
          />
        ),
      },
      {
        path: '/register',
        handle: title('Create account'),
        element: (
          <PlaceholderPage
            title="Create account"
            description="Start learning with your own vocabulary and grammar."
          />
        ),
      },
    ],
  },
  {
    element: <AppLayout />,
    children: [
      ...NAV_ITEMS.map(({ path, label, description }) => ({
        path,
        handle: title(label),
        element: <PlaceholderPage title={label} description={description} />,
      })),
      // Dev-only component showcase; not part of production builds' routes
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
  { path: '*', handle: title('Page not found'), element: <NotFoundPage /> },
]
