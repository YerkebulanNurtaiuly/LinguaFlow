import {
  keepPreviousData,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import {
  fetchCatalog,
  fetchGrammarTopic,
  fetchGrammarTopics,
  fetchStatusHistory,
  type GrammarTopicFilter,
} from './grammarApi'

/** Everything grammar-related, the catalog's "added" marks included. */
export const GRAMMAR_QUERY_KEY = ['grammar'] as const

export function useGrammarTopics(filter: GrammarTopicFilter) {
  return useQuery({
    queryKey: [...GRAMMAR_QUERY_KEY, 'topics', filter],
    queryFn: ({ signal }) => fetchGrammarTopics(filter, signal),
    placeholderData: keepPreviousData,
  })
}

export function useGrammarCatalog() {
  return useQuery({
    queryKey: [...GRAMMAR_QUERY_KEY, 'catalog'],
    queryFn: fetchCatalog,
  })
}

/** Refreshes topic lists and the catalog marks after a topic changed. */
export function useInvalidateGrammar() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: GRAMMAR_QUERY_KEY })
}

export const grammarTopicKey = (id: string) =>
  [...GRAMMAR_QUERY_KEY, 'topic', id] as const

export function useGrammarTopic(id: string) {
  return useQuery({
    queryKey: grammarTopicKey(id),
    queryFn: () => fetchGrammarTopic(id),
  })
}

export const statusHistoryKey = (id: string) =>
  [...GRAMMAR_QUERY_KEY, 'history', id] as const

export function useStatusHistory(id: string) {
  return useQuery({
    queryKey: statusHistoryKey(id),
    queryFn: () => fetchStatusHistory(id),
  })
}
