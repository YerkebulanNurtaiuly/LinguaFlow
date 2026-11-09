import { NavLink } from 'react-router'
import { NAV_ITEMS } from '@/app/navigation'

interface NavLinksProps {
  onNavigate?: () => void
}

/** The main navigation list; NavLink sets aria-current="page" on the active item. */
export function NavLinks({ onNavigate }: NavLinksProps) {
  return (
    <ul className="space-y-1">
      {NAV_ITEMS.map(({ path, label, icon: Icon }) => (
        <li key={path}>
          <NavLink
            to={path}
            onClick={onNavigate}
            className={({ isActive }) =>
              `flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors ${
                isActive
                  ? 'bg-indigo-50 text-indigo-700'
                  : 'text-slate-700 hover:bg-slate-100 hover:text-slate-900'
              }`
            }
          >
            <Icon className="size-4 shrink-0" aria-hidden="true" />
            {label}
          </NavLink>
        </li>
      ))}
    </ul>
  )
}
