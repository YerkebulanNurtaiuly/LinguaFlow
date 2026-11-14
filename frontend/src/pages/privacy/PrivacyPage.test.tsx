import userEvent from '@testing-library/user-event'
import { screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { attachmentFilename } from '@/account/accountApi'
import { fakeApi } from '@/test/fakeApi'
import { renderApp, renderRoute } from '@/test/renderWithProviders'

describe('privacy (SEC-02)', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('is open to visitors, and the registration page links to it', async () => {
    const user = userEvent.setup()
    renderRoute('/register', { session: 'anonymous' })
    await user.click(
      await screen.findByRole('link', { name: 'How we handle your data' }),
    )

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Privacy' }),
    ).toBeInTheDocument()
    expect(document.title).toBe('Privacy · LinguaFlow')
    expect(
      screen.getByRole('heading', { name: 'What the AI sees' }),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/never send your name, email address/),
    ).toBeVisible()
    expect(
      screen.getByRole('link', { name: 'Back to creating an account' }),
    ).toHaveAttribute('href', '/register')
  })

  it('sends a logged-in learner back to settings', async () => {
    renderRoute('/privacy')
    expect(
      await screen.findByRole('link', { name: 'Back to settings' }),
    ).toHaveAttribute('href', '/settings')
  })

  it('downloads all data from settings as the file the server names', async () => {
    const user = userEvent.setup()
    const createObjectURL = vi.fn(() => 'blob:export')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal(
      'URL',
      Object.assign(URL, { createObjectURL, revokeObjectURL }),
    )
    const downloads: string[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      downloads.push(this.download)
    })
    fakeApi.on('GET', '/users/me/export', {
      status: 200,
      data: { format: 'linguaflow-export', version: 1, tables: {} },
      headers: {
        'content-disposition':
          'attachment; filename="linguaflow-export-2026-11-14.json"',
      },
    })
    await renderApp('/settings')

    const section = await screen.findByRole('region', { name: 'Your data' })
    await user.click(
      within(section).getByRole('button', { name: 'Download my data' }),
    )

    expect(
      await within(section).findByText('Your data was downloaded.'),
    ).toBeInTheDocument()
    expect(fakeApi.requests('/users/me/export')).toHaveLength(1)
    expect(downloads).toEqual(['linguaflow-export-2026-11-14.json'])
    expect(createObjectURL).toHaveBeenCalledOnce()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:export')
    expect(
      within(section).getByRole('link', { name: 'How we handle your data' }),
    ).toHaveAttribute('href', '/privacy')
  })

  it('reads the file name from the header, with a fallback', () => {
    expect(
      attachmentFilename('attachment; filename="a-b.json"', 'x.json'),
    ).toBe('a-b.json')
    expect(
      attachmentFilename('attachment; filename=plain.json', 'x.json'),
    ).toBe('plain.json')
    expect(attachmentFilename(undefined, 'x.json')).toBe('x.json')
  })
})
