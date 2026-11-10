import {
  AxiosError,
  type AxiosAdapter,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'

export interface FakeReply {
  status: number
  data?: unknown
}

type Handler = (
  config: InternalAxiosRequestConfig,
) => FakeReply | Promise<FakeReply>

export interface RecordedRequest {
  method: string
  url: string
  authorization: string | undefined
}

const handlers = new Map<string, Handler>()
const recorded: RecordedRequest[] = []

const key = (method: string, url: string) => `${method.toUpperCase()} ${url}`

/** Answers used only when a test registered no handler of its own for the request. */
const fallbacks = new Map<string, Handler>()

function resetFallbacks() {
  fallbacks.clear()
  // No refresh cookie unless a test says otherwise
  fallbacks.set(key('POST', '/auth/refresh'), () => ({
    status: 401,
    data: problem(401, 'INVALID_REFRESH_TOKEN'),
  }))
}
resetFallbacks()

const toHandler = (handler: Handler | FakeReply): Handler =>
  typeof handler === 'function' ? handler : () => handler

export function problem(status: number, code: string) {
  return { type: 'about:blank', title: code, status, code }
}

/**
 * Axios adapter installed on the shared `api` client for every test (see setup.ts).
 * Requests go to handlers registered with `fakeApi.on`; anything unexpected fails
 * loudly instead of reaching the network.
 */
export const fakeAdapter: AxiosAdapter = async (config) => {
  const method = (config.method ?? 'get').toUpperCase()
  const url = config.url ?? ''
  const auth = config.headers.get('Authorization')
  recorded.push({
    method,
    url,
    authorization: typeof auth === 'string' ? auth : undefined,
  })

  const handler =
    handlers.get(key(method, url)) ?? fallbacks.get(key(method, url))
  if (!handler) {
    throw new Error(`Unexpected request in test: ${method} ${url}`)
  }
  const reply = await handler(config)
  const response: AxiosResponse = {
    data: reply.data,
    status: reply.status,
    statusText: '',
    headers: {},
    config,
  }
  if (reply.status >= 400) {
    throw new AxiosError(
      `Request failed with status code ${reply.status}`,
      reply.status >= 500 ? 'ERR_BAD_RESPONSE' : 'ERR_BAD_REQUEST',
      config,
      undefined,
      response,
    )
  }
  return response
}

export const fakeApi = {
  on(method: string, url: string, handler: Handler | FakeReply): void {
    handlers.set(key(method, url), toHandler(handler))
  },

  /** Like `on`, but a handler the test registers itself (before or after) wins. */
  fallback(method: string, url: string, handler: Handler | FakeReply): void {
    fallbacks.set(key(method, url), toHandler(handler))
  },

  /** Requests seen so far, optionally only those to `url`. */
  requests(url?: string): RecordedRequest[] {
    return url ? recorded.filter((r) => r.url === url) : [...recorded]
  },

  reset(): void {
    handlers.clear()
    resetFallbacks()
    recorded.length = 0
  },
}
