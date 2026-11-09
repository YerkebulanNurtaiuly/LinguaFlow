import { Link } from 'react-router'
import { RouteTitle } from '@/app/RouteTitle'

export function NotFoundPage() {
  return (
    <main className="mx-auto flex min-h-screen max-w-2xl flex-col justify-center gap-4 px-4">
      <RouteTitle />
      <p className="text-sm font-semibold text-indigo-600">404</p>
      <h1 className="text-2xl font-semibold">Page not found</h1>
      <p className="text-slate-600">
        The page you are looking for does not exist or has moved.
      </p>
      <Link to="/dashboard" className="text-indigo-600 underline">
        Back to the dashboard
      </Link>
    </main>
  )
}
