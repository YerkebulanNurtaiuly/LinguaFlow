import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { NAV_ITEMS } from '@/app/navigation'
import { fetchHealth } from '@/lib/health'
import { renderRoute } from '@/test/renderWithProviders'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

const mainNav = () => screen.getAllByRole('navigation', { name: 'Main' })[0]!

describe('app shell and routing', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockReset()
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
  })

  it('redirects the root to the dashboard', async () => {
    renderRoute('/')
    expect(
      await screen.findByRole('heading', { name: 'Dashboard' }),
    ).toBeInTheDocument()
  })

  it.each(NAV_ITEMS)('renders $label at $path inside the shell', (item) => {
    renderRoute(item.path)

    expect(
      screen.getByRole('heading', { level: 1, name: item.label }),
    ).toBeInTheDocument()
    expect(
      within(mainNav()).getByRole('link', { name: item.label }),
    ).toHaveAttribute('aria-current', 'page')
  })

  it('lists every section in the main navigation', () => {
    renderRoute('/dashboard')
    const links = within(mainNav()).getAllByRole('link')
    expect(links.map((link) => link.getAttribute('href'))).toEqual(
      NAV_ITEMS.map((item) => item.path),
    )
  })

  it('navigates when a link is clicked', async () => {
    renderRoute('/dashboard')
    await userEvent.click(
      within(mainNav()).getByRole('link', { name: 'Grammar' }),
    )
    expect(
      screen.getByRole('heading', { level: 1, name: 'Grammar' }),
    ).toBeInTheDocument()
  })

  it('sets the document title per page', async () => {
    renderRoute('/vocabulary')
    await waitFor(() => expect(document.title).toBe('Vocabulary · LinguaFlow'))
  })

  it('shows the backend status in the sidebar', async () => {
    renderRoute('/dashboard')
    expect(await screen.findByText('UP')).toBeInTheDocument()
  })

  it('reports an unreachable backend', async () => {
    vi.mocked(fetchHealth).mockRejectedValue(new Error('network down'))
    renderRoute('/dashboard')
    expect(await screen.findByText('unreachable')).toBeInTheDocument()
  })

  describe('mobile navigation', () => {
    it('opens, and closes again on navigation', async () => {
      renderRoute('/dashboard')
      const toggle = screen.getByRole('button', { name: 'Open navigation' })
      expect(toggle).toHaveAttribute('aria-expanded', 'false')

      await userEvent.click(toggle)
      const drawer = screen.getByRole('dialog', { name: 'Navigation' })
      expect(toggle).toHaveAttribute('aria-expanded', 'true')
      expect(
        within(drawer).getByRole('button', { name: 'Close navigation' }),
      ).toHaveFocus()

      await userEvent.click(
        within(drawer).getByRole('link', { name: 'Practice' }),
      )
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      expect(
        screen.getByRole('heading', { level: 1, name: 'Practice' }),
      ).toBeInTheDocument()
    })

    it('closes on Escape and on backdrop click', async () => {
      renderRoute('/dashboard')
      const toggle = screen.getByRole('button', { name: 'Open navigation' })

      await userEvent.click(toggle)
      await userEvent.keyboard('{Escape}')
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

      await userEvent.click(toggle)
      await userEvent.click(screen.getByTestId('mobile-nav-backdrop'))
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })

  it.each([
    ['/login', 'Log in'],
    ['/register', 'Create account'],
  ])('renders %s without the app navigation', (path, heading) => {
    renderRoute(path)
    expect(
      screen.getByRole('heading', { level: 1, name: heading }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('navigation', { name: 'Main' }),
    ).not.toBeInTheDocument()
  })

  it('renders the not-found page for unknown paths', async () => {
    renderRoute('/does-not-exist')
    expect(
      screen.getByRole('heading', { name: 'Page not found' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: 'Back to the dashboard' }),
    ).toHaveAttribute('href', '/dashboard')
    await waitFor(() =>
      expect(document.title).toBe('Page not found · LinguaFlow'),
    )
  })
})
