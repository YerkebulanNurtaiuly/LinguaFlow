import { useQuery } from '@tanstack/react-query'
import { fetchHealth } from '@/lib/health'

/** Small dev-friendly indicator of the backend health endpoint. */
export function BackendStatus() {
  const health = useQuery({
    queryKey: ['health'],
    queryFn: fetchHealth,
    refetchInterval: 60_000,
  })

  let label: string
  let dot: string
  if (health.isPending) {
    label = 'checking…'
    dot = 'bg-slate-300'
  } else if (health.isError) {
    label = 'unreachable'
    dot = 'bg-red-500'
  } else {
    label = health.data.status
    dot = health.data.status === 'UP' ? 'bg-emerald-500' : 'bg-amber-500'
  }

  return (
    <p className="flex items-center gap-2 text-xs text-slate-500">
      <span className={`size-2 rounded-full ${dot}`} aria-hidden="true" />
      Backend: <span className="font-medium">{label}</span>
    </p>
  )
}
