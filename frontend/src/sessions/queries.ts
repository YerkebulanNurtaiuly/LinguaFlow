import { useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchGrammarTopics } from '@/grammar/grammarApi'
import { GRAMMAR_QUERY_KEY } from '@/grammar/queries'
import { VOCABULARY_QUERY_KEY } from '@/vocabulary/queries'
import {
  fetchActiveSession,
  fetchSession,
  fetchSessionWords,
  fetchTimeline,
} from './sessionsApi'

/** Everything about study sessions: timeline, active lesson, lessons and their material. */
export const SESSIONS_QUERY_KEY = ['sessions'] as const

export const sessionKey = (id: string) =>
  [...SESSIONS_QUERY_KEY, 'session', id] as const

export function useTimeline() {
  return useQuery({
    queryKey: [...SESSIONS_QUERY_KEY, 'timeline'],
    queryFn: fetchTimeline,
  })
}

export function useActiveSession() {
  return useQuery({
    queryKey: [...SESSIONS_QUERY_KEY, 'active'],
    queryFn: fetchActiveSession,
  })
}

export function useSession(id: string) {
  return useQuery({ queryKey: sessionKey(id), queryFn: () => fetchSession(id) })
}

export function useSessionWords(id: string) {
  return useQuery({
    queryKey: [...sessionKey(id), 'words'],
    queryFn: () => fetchSessionWords(id),
  })
}

export function useSessionTopics(id: string) {
  return useQuery({
    queryKey: [...sessionKey(id), 'topics'],
    queryFn: ({ signal }) => fetchGrammarTopics({ sessionId: id }, signal),
  })
}

/**
 * Refreshes sessions after a change. Word and topic lists filtered by lesson live under
 * their own keys, so those are refreshed too.
 */
export function useInvalidateSessions() {
  const queryClient = useQueryClient()
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: SESSIONS_QUERY_KEY }),
      queryClient.invalidateQueries({ queryKey: VOCABULARY_QUERY_KEY }),
      queryClient.invalidateQueries({ queryKey: GRAMMAR_QUERY_KEY }),
    ])
}
