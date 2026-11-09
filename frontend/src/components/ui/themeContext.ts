import { createContext, useContext } from 'react'

export type ThemePreference = 'system' | 'light' | 'dark'

export interface ThemeApi {
  theme: ThemePreference
  setTheme: (theme: ThemePreference) => void
}

export const ThemeContext = createContext<ThemeApi | null>(null)

export function useTheme(): ThemeApi {
  const api = useContext(ThemeContext)
  if (!api) throw new Error('useTheme must be used inside <ThemeProvider>')
  return api
}
