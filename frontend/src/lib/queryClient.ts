import {
  MutationCache,
  QueryCache,
  QueryClient,
  type QueryClientConfig,
} from '@tanstack/react-query'
import { describeError, getStatus } from '@/api/errors'
import { errorNotifier } from './errorNotifier'

/** Per-query or per-mutation options read by the global error handling. */
export interface AppMeta extends Record<string, unknown> {
  /** false: the component shows this error itself, so no toast */
  errorToast?: boolean
  /** The toast's title when this mutation fails; defaults to "Couldn't save your changes" */
  errorTitle?: string
}

declare module '@tanstack/react-query' {
  interface Register {
    queryMeta: AppMeta
    mutationMeta: AppMeta
  }
}

/** A 401 that survived the token refresh ends the session; the login page explains it. */
const endsSession = (error: unknown) => getStatus(error) === 401

/**
 * The app's QueryClient with the global error policy:
 * - a query whose FIRST load fails shows its error inline (LoadError), no toast;
 * - a query that fails to REFRESH data already on screen gets a toast, since the
 *   page keeps showing the older data and would otherwise fail silently;
 * - a failed mutation gets a toast, unless it has its own onError or
 *   `meta: { errorToast: false }` (forms that show the error next to the fields).
 */
export function createQueryClient(config: QueryClientConfig = {}): QueryClient {
  return new QueryClient({
    ...config,
    queryCache: new QueryCache({
      onError: (error, query) => {
        if (query.meta?.errorToast === false || endsSession(error)) return
        if (query.state.data === undefined) return
        errorNotifier.notify({
          title: "Couldn't refresh the latest data",
          description: describeError(error),
        })
      },
    }),
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => {
        if (mutation.options.meta?.errorToast === false) return
        if (mutation.options.onError || endsSession(error)) return
        errorNotifier.notify({
          title:
            mutation.options.meta?.errorTitle ?? "Couldn't save your changes",
          description: describeError(error),
        })
      },
    }),
    defaultOptions: {
      ...config.defaultOptions,
      queries: {
        staleTime: 30_000,
        retry: 1,
        refetchOnWindowFocus: false,
        ...config.defaultOptions?.queries,
      },
    },
  })
}

export const queryClient = createQueryClient()
