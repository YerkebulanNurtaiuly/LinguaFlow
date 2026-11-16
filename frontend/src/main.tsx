import { QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { AppErrorBoundary } from '@/app/AppErrorBoundary'
import { routes } from '@/app/routes'
import { AuthProvider } from '@/auth/AuthProvider'
import { ErrorToasts } from '@/components/layout/ErrorToasts'
import { ThemeProvider, ToastProvider } from '@/components/ui'
import { installErrorReporting } from '@/lib/errorReporting'
import { queryClient } from '@/lib/queryClient'
import './index.css'

const router = createBrowserRouter(routes)

// OPS-04: uncaught errors go to the backend (and Sentry) in production builds
if (import.meta.env.PROD) installErrorReporting()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AppErrorBoundary>
      <ThemeProvider>
        <QueryClientProvider client={queryClient}>
          <ToastProvider>
            <ErrorToasts />
            <AuthProvider>
              <RouterProvider router={router} />
            </AuthProvider>
          </ToastProvider>
        </QueryClientProvider>
      </ThemeProvider>
    </AppErrorBoundary>
  </StrictMode>,
)
