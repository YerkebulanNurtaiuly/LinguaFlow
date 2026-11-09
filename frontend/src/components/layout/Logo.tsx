import { Link } from 'react-router'

export function Logo() {
  return (
    <Link
      to="/dashboard"
      className="text-lg font-bold tracking-tight text-slate-900"
    >
      Lingua<span className="text-indigo-600">Flow</span>
    </Link>
  )
}
