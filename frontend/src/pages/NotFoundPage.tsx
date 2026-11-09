import { Link } from 'react-router'

export function NotFoundPage() {
  return (
    <main className="mx-auto flex min-h-screen max-w-2xl flex-col justify-center gap-4 px-4">
      <h1 className="text-2xl font-semibold">Page not found</h1>
      <Link to="/" className="text-blue-600 underline">
        Back to home
      </Link>
    </main>
  )
}
