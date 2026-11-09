import { useQuery } from '@tanstack/react-query'
import { fetchHealth } from '@/lib/health'

export function HomePage() {
  const health = useQuery({ queryKey: ['health'], queryFn: fetchHealth })

  let status: string
  if (health.isPending) status = 'checking…'
  else if (health.isError) status = 'unreachable'
  else status = health.data.status

  return (
    <main className="mx-auto flex min-h-screen max-w-2xl flex-col justify-center gap-4 px-4">
      <h1 className="text-4xl font-bold tracking-tight">LinguaFlow</h1>
      <p className="text-lg text-slate-600">
        AI-powered personalized language learning.
      </p>
      <p className="text-sm text-slate-500">
        Backend status: <span className="font-medium">{status}</span>
      </p>
    </main>
  )
}
