import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchHealth } from '@/lib/health'
import { renderRoute } from '@/test/renderWithProviders'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

describe('routes', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockReset()
  })

  it('shows the backend status on the home page', async () => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })

    renderRoute('/')

    expect(
      screen.getByRole('heading', { name: 'LinguaFlow' }),
    ).toBeInTheDocument()
    expect(await screen.findByText('UP')).toBeInTheDocument()
  })

  it('reports an unreachable backend', async () => {
    vi.mocked(fetchHealth).mockRejectedValue(new Error('network down'))

    renderRoute('/')

    expect(await screen.findByText('unreachable')).toBeInTheDocument()
  })

  it('renders the not-found page for unknown paths', () => {
    renderRoute('/does-not-exist')

    expect(
      screen.getByRole('heading', { name: 'Page not found' }),
    ).toBeInTheDocument()
  })
})
