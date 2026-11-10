import { QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { AppErrorBoundary } from '@/app/AppErrorBoundary'
import { routes } from '@/app/routes'
import { AuthProvider } from '@/auth/AuthProvider'
import { ErrorToasts } from '@/components/layout/ErrorToasts'
import { ThemeProvider, ToastProvider } from '@/components/ui'
import { queryClient } from '@/lib/queryClient'
import './index.css'

const router = createBrowserRouter(routes)

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
