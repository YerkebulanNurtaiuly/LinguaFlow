import {
  QueryClientProvider,
  useMutation,
  useQuery,
  type QueryClient,
} from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppErrorBoundary } from '@/app/AppErrorBoundary'
import { RouteErrorPage } from '@/app/RouteErrorPage'
import { ErrorToasts } from '@/components/layout/ErrorToasts'
import { ToastProvider } from '@/components/ui'
import { fetchHealth } from '@/lib/health'
import { createQueryClient } from '@/lib/queryClient'
import { renderApp } from '@/test/renderWithProviders'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

// Lets a test make the Vocabulary placeholder crash while rendering
const crash = vi.hoisted(() => ({ vocabulary: false }))
vi.mock('@/pages/PlaceholderPage', async (importOriginal) => {
  const original =
    await importOriginal<typeof import('@/pages/PlaceholderPage')>()
  return {
    PlaceholderPage: (props: { title: string; description: string }) => {
      if (crash.vocabulary && props.title === 'Vocabulary') {
        throw new Error('Vocabulary exploded')
      }
      return <original.PlaceholderPage {...props} />
    },
  }
})

function httpError(status: number): AxiosError {
  return new AxiosError('Request failed', 'ERR_BAD_RESPONSE', undefined, null, {
    status,
    statusText: '',
    data: { code: 'INTERNAL_ERROR', status, detail: 'boom' },
    headers: {},
    config: { headers: new AxiosHeaders() },
  })
}

const networkError = () =>
  new AxiosError('Network Error', AxiosError.ERR_NETWORK)

describe('error boundaries', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
    // React reports caught render errors on the console; keep the output clean
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    crash.vocabulary = false
    vi.mocked(console.error).mockRestore()
  })

  it('shows a crashing section inside the shell, so navigation keeps working', async () => {
    crash.vocabulary = true
    await renderApp('/vocabulary')

    expect(
      screen.getByRole('heading', { name: 'Something went wrong' }),
    ).toBeInTheDocument()
    const nav = screen.getAllByRole('navigation', { name: 'Main' })[0]!
    await userEvent.click(within(nav).getByRole('link', { name: 'Grammar' }))

    expect(
      screen.getByRole('heading', { level: 1, name: 'Grammar' }),
    ).toBeInTheDocument()
  })

  it('renders the section again on Try again', async () => {
    crash.vocabulary = true
    await renderApp('/vocabulary')
    await screen.findByRole('heading', { name: 'Something went wrong' })

    crash.vocabulary = false
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Vocabulary' }),
    ).toBeInTheDocument()
  })

  it('shows technical details only in development', async () => {
    crash.vocabulary = true
    await renderApp('/vocabulary')

    expect(screen.getByText(/Vocabulary exploded/)).toBeInTheDocument()
  })

  it('catches errors outside a section with a full-page fallback', async () => {
    function Broken(): never {
      throw new Error('broken')
    }
    const router = createMemoryRouter([
      { path: '/', element: <Broken />, errorElement: <RouteErrorPage /> },
    ])
    render(<RouterProvider router={router} />)

    expect(
      await screen.findByRole('heading', { name: 'Something went wrong' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: 'Go to the dashboard' }),
    ).toHaveAttribute('href', '/dashboard')
  })

  it('catches a crash outside the router', () => {
    function Broken(): never {
      throw new Error('provider crash')
    }
    render(
      <AppErrorBoundary>
        <Broken />
      </AppErrorBoundary>,
    )

    expect(screen.getByRole('alert')).toHaveTextContent(
      'LinguaFlow stopped working',
    )
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument()
  })
})

describe('global query and mutation error toasts', () => {
  let client: QueryClient

  function renderWithClient(ui: React.ReactNode) {
    client = createQueryClient({
      defaultOptions: { queries: { retry: false, staleTime: 0 } },
    })
    return render(
      <QueryClientProvider client={client}>
        <ToastProvider>
          <ErrorToasts />
          {ui}
        </ToastProvider>
      </QueryClientProvider>,
    )
  }

  const toastRegion = () =>
    screen
      .getAllByRole('alert')
      .map((region) => region.textContent ?? '')
      .join(' ')

  function Words({
    fetcher,
    errorToast,
  }: {
    fetcher: () => Promise<string[]>
    errorToast?: boolean
  }) {
    const words = useQuery({
      queryKey: ['words'],
      queryFn: fetcher,
      meta: errorToast === undefined ? undefined : { errorToast },
    })
    return <p>{words.data ? words.data.join(', ') : 'no words'}</p>
  }

  it('does not toast when the first load fails (the page shows that inline)', async () => {
    renderWithClient(<Words fetcher={() => Promise.reject(networkError())} />)

    await waitFor(() =>
      expect(client.getQueryState(['words'])?.status).toBe('error'),
    )
    expect(toastRegion()).toBe('')
  })

  it('toasts when refreshing data that is already shown fails', async () => {
    let fail = false
    renderWithClient(
      <Words
        fetcher={() =>
          fail ? Promise.reject(httpError(503)) : Promise.resolve(['Hund'])
        }
      />,
    )
    await screen.findByText('Hund')

    fail = true
    await act(() => client.refetchQueries({ queryKey: ['words'] }))

    await waitFor(() =>
      expect(toastRegion()).toContain("Couldn't refresh the latest data"),
    )
    expect(toastRegion()).toContain('Something went wrong on our side')
    expect(screen.getByText('Hund')).toBeInTheDocument()
  })

  it.each([
    ['opted out with meta.errorToast = false', networkError(), false],
    ['ended the session (401)', httpError(401), undefined],
  ])(
    'stays quiet when a refresh fails for a query that %s',
    async (_case, error, errorToast) => {
      let fail = false
      renderWithClient(
        <Words
          errorToast={errorToast}
          fetcher={() =>
            fail ? Promise.reject(error) : Promise.resolve(['Hund'])
          }
        />,
      )
      await screen.findByText('Hund')

      fail = true
      await act(() => client.refetchQueries({ queryKey: ['words'] }))

      expect(client.getQueryState(['words'])?.status).toBe('error')
      expect(toastRegion()).toBe('')
    },
  )

  function SaveButton({ errorToast }: { errorToast?: boolean }) {
    const save = useMutation({
      mutationFn: () => Promise.reject(networkError()),
      meta: errorToast === undefined ? undefined : { errorToast },
    })
    return (
      <button type="button" onClick={() => save.mutate()}>
        Save
      </button>
    )
  }

  it('toasts a failed mutation', async () => {
    renderWithClient(<SaveButton />)

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(toastRegion()).toContain("Couldn't save your changes"),
    )
    expect(toastRegion()).toContain("Can't reach LinguaFlow right now.")
  })

  it('leaves mutations that show their own error alone', async () => {
    renderWithClient(<SaveButton errorToast={false} />)

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() =>
      expect(client.getMutationCache().getAll()[0]?.state.status).toBe('error'),
    )

    expect(toastRegion()).toBe('')
  })

  it('shows the same error only once when several requests fail together', async () => {
    renderWithClient(
      <>
        <SaveButton />
        <SaveButton />
      </>,
    )

    for (const button of screen.getAllByRole('button', { name: 'Save' })) {
      await userEvent.click(button)
    }

    await waitFor(() =>
      expect(toastRegion()).toContain("Couldn't save your changes"),
    )
    expect(screen.getAllByText("Couldn't save your changes")).toHaveLength(1)
  })
})
