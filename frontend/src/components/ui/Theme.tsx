import { Monitor, Moon, Sun } from 'lucide-react'
import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { cn } from '@/lib/cn'
import { ThemeContext, useTheme, type ThemePreference } from './themeContext'

const STORAGE_KEY = 'lf-theme'

function readStored(): ThemePreference {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    return value === 'light' || value === 'dark' ? value : 'system'
  } catch {
    return 'system'
  }
}

/**
 * Light/dark/system theme. "system" removes data-theme so the CSS follows the OS;
 * index.html applies the stored choice before first paint to avoid a flash.
 */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<ThemePreference>(readStored)

  useEffect(() => {
    const root = document.documentElement
    if (theme === 'system') delete root.dataset.theme
    else root.dataset.theme = theme
  }, [theme])

  const setTheme = useCallback((next: ThemePreference) => {
    setThemeState(next)
    try {
      if (next === 'system') localStorage.removeItem(STORAGE_KEY)
      else localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // Storage unavailable (private mode): the choice lasts for this page view only
    }
  }, [])

  const api = useMemo(() => ({ theme, setTheme }), [theme, setTheme])
  return <ThemeContext.Provider value={api}>{children}</ThemeContext.Provider>
}

const order: ThemePreference[] = ['system', 'light', 'dark']
const labels = { system: 'System', light: 'Light', dark: 'Dark' }
const icons = { system: Monitor, light: Sun, dark: Moon }

/** Cycles System → Light → Dark. */
export function ThemeToggle({ className }: { className?: string }) {
  const { theme, setTheme } = useTheme()
  const next = order[(order.indexOf(theme) + 1) % order.length]!
  const Icon = icons[theme]
  return (
    <button
      type="button"
      onClick={() => setTheme(next)}
      className={cn(
        'inline-flex items-center gap-2 rounded-md px-2 py-1 text-xs text-fg-muted hover:bg-surface-muted hover:text-fg',
        className,
      )}
      aria-label={`Theme: ${labels[theme]}. Switch to ${labels[next]}`}
    >
      <Icon className="size-4" aria-hidden="true" />
      {labels[theme]}
    </button>
  )
}
