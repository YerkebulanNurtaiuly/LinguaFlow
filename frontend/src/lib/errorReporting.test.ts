import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MAX_REPORTS, reportError, resetErrorReporting } from './errorReporting'

/** OPS-04: browser errors go to the backend, without personal data and without flooding. */
describe('error reporting', () => {
  const fetchMock =
    vi.fn<(url: string, init?: RequestInit) => Promise<Response>>()

  beforeEach(() => {
    resetErrorReporting()
    fetchMock.mockImplementation((url: string) =>
      Promise.resolve(
        url === '/version.txt'
          ? new Response('dfba83c\n', { status: 200 })
          : new Response(null, { status: 204 }),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    window.history.replaceState(null, '', '/vocabulary?q=private#row-3')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  const reports = () =>
    fetchMock.mock.calls
      .filter(([url]) => url === '/api/client-errors')
      .map(
        ([, init]) =>
          JSON.parse((init as RequestInit).body as string) as Record<
            string,
            unknown
          >,
      )

  it('posts the error with the path but not the query', async () => {
    await reportError(
      'render',
      new TypeError("Cannot read properties of undefined (reading 'map')"),
    )

    expect(reports()).toEqual([
      {
        kind: 'render',
        message:
          "TypeError: Cannot read properties of undefined (reading 'map')",
        stack: expect.stringContaining('TypeError') as unknown,
        path: '/vocabulary',
        release: 'dfba83c',
      },
    ])
    const [, init] = fetchMock.mock.calls.find(
      ([url]) => url === '/api/client-errors',
    )!
    expect((init as RequestInit).keepalive).toBe(true)
  })

  it('sends each error once and stops after the limit', async () => {
    await reportError('error', 'boom')
    await reportError('error', 'boom')
    for (let i = 0; i < MAX_REPORTS + 5; i++)
      await reportError('error', `boom ${i}`)

    expect(reports()).toHaveLength(MAX_REPORTS)
    expect(reports()[0]?.message).toBe('boom')
  })

  it('never throws when reporting fails', async () => {
    fetchMock.mockRejectedValue(new Error('offline'))

    await expect(
      reportError('unhandledrejection', { reason: 'x' }),
    ).resolves.toBeUndefined()
  })
})
