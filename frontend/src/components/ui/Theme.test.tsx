import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { ThemeProvider, ThemeToggle } from './Theme'

describe('ThemeToggle', () => {
  beforeEach(() => {
    localStorage.clear()
    delete document.documentElement.dataset.theme
  })

  it('cycles System -> Light -> Dark -> System and remembers the choice', async () => {
    render(
      <ThemeProvider>
        <ThemeToggle />
      </ThemeProvider>,
    )
    const toggle = () => screen.getByRole('button', { name: /Theme:/ })
    expect(toggle()).toHaveAccessibleName('Theme: System. Switch to Light')
    expect(document.documentElement.dataset.theme).toBeUndefined()

    await userEvent.click(toggle())
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(localStorage.getItem('lf-theme')).toBe('light')

    await userEvent.click(toggle())
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(localStorage.getItem('lf-theme')).toBe('dark')

    await userEvent.click(toggle())
    expect(document.documentElement.dataset.theme).toBeUndefined()
    expect(localStorage.getItem('lf-theme')).toBeNull()
  })

  it('starts from the stored preference', () => {
    localStorage.setItem('lf-theme', 'dark')
    render(
      <ThemeProvider>
        <ThemeToggle />
      </ThemeProvider>,
    )
    expect(
      screen.getByRole('button', { name: /Theme: Dark/ }),
    ).toBeInTheDocument()
    expect(document.documentElement.dataset.theme).toBe('dark')
  })
})
