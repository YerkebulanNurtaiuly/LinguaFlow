import type { RouteObject } from 'react-router'
import { HomePage } from '@/pages/HomePage'
import { NotFoundPage } from '@/pages/NotFoundPage'

// The full route map (login, dashboard, vocabulary, ...) arrives in FE-01.
export const routes: RouteObject[] = [
  { path: '/', element: <HomePage /> },
  { path: '*', element: <NotFoundPage /> },
]
