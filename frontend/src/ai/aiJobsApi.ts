import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { z } from 'zod'
import type { AiJobProgress } from '@/api/types'
import { api } from '@/lib/api'
import { VOCABULARY_QUERY_KEY } from '@/vocabulary/queries'

/** Background AI work such as enriching a batch of words (AI-08). */
export const AI_JOBS_QUERY_KEY = ['ai-jobs'] as const

/** How often unfinished jobs are checked; enrichment takes seconds per batch. */
export const AI_JOB_POLL_MS = 3_000

const progressSchema = z.object({
  total: z.number(),
  queued: z.number(),
  running: z.number(),
  succeeded: z.number(),
  failed: z.number(),
  dead: z.number(),
  finished: z.boolean(),
  notFound: z.array(z.string()),
}) satisfies z.ZodType<AiJobProgress>

export type JobProgress = z.infer<typeof progressSchema>

/** Counts by status for the given jobs (at most 100). */
export async function fetchAiJobProgress(ids: string[]): Promise<JobProgress> {
  const params = new URLSearchParams()
  ids.forEach((id) => params.append('id', id))
  const { data } = await api.get<unknown>('/ai/jobs/progress', { params })
  return progressSchema.parse(data)
}

/**
 * Follows a set of AI jobs until all have finished, e.g. the batches of a bulk add.
 * When they finish, the vocabulary is refreshed so lists show the AI's results.
 */
export function useAiJobProgress(ids: string[]) {
  const queryClient = useQueryClient()
  const query = useQuery({
    queryKey: [...AI_JOBS_QUERY_KEY, 'progress', ...ids],
    queryFn: () => fetchAiJobProgress(ids),
    enabled: ids.length > 0,
    refetchInterval: (current) =>
      current.state.data?.finished ? false : AI_JOB_POLL_MS,
  })
  const finished = query.data?.finished === true
  useEffect(() => {
    if (finished) {
      void queryClient.invalidateQueries({ queryKey: VOCABULARY_QUERY_KEY })
    }
  }, [finished, queryClient])
  return query
}
