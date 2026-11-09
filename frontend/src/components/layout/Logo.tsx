import { Link } from 'react-router'

export function Logo() {
  return (
    <Link to="/dashboard" className="text-lg font-bold tracking-tight text-fg">
      Lingua<span className="text-primary">Flow</span>
    </Link>
  )
}
