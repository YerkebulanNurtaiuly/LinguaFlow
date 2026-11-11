import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import {
  deleteVocabularyItem,
  fetchTags,
  fetchVocabulary,
  fetchVocabularyItem,
  type VocabularyQuery,
} from './vocabularyApi'

/** Everything vocabulary-related; invalidate it after changing a word. */
export const VOCABULARY_QUERY_KEY = ['vocabulary'] as const
/** Tag list with word counts; invalidate it when words or tags change. */
export const TAGS_QUERY_KEY = ['tags'] as const
/** How often a list or word with words waiting for AI is refreshed (VOC-05). */
export const PENDING_AI_POLL_MS = 5_000

/** A page of the list. The previous page stays on screen while the next loads. */
export function useVocabularyList(query: VocabularyQuery) {
  return useQuery({
    queryKey: [...VOCABULARY_QUERY_KEY, 'list', query],
    queryFn: ({ signal }) => fetchVocabulary(query, signal),
    placeholderData: keepPreviousData,
    // Words waiting for AI are filled in within seconds, so keep checking until they are
    refetchInterval: (current) =>
      current.state.data?.content.some((word) => word.aiStatus === 'PENDING_AI')
        ? PENDING_AI_POLL_MS
        : false,
  })
}

export function useTags() {
  return useQuery({ queryKey: TAGS_QUERY_KEY, queryFn: fetchTags })
}

export function useDeleteVocabularyItem() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: deleteVocabularyItem,
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: VOCABULARY_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: TAGS_QUERY_KEY }),
      ]),
  })
}

/** One word with its examples, for the edit form. */
export function useVocabularyItem(id: string) {
  return useQuery({
    queryKey: [...VOCABULARY_QUERY_KEY, 'item', id],
    queryFn: () => fetchVocabularyItem(id),
    refetchInterval: (current) =>
      current.state.data?.aiStatus === 'PENDING_AI'
        ? PENDING_AI_POLL_MS
        : false,
  })
}

/** Refreshes lists, single words and tag counts after a word or tag changed. */
export function useInvalidateVocabulary() {
  const queryClient = useQueryClient()
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: VOCABULARY_QUERY_KEY }),
      queryClient.invalidateQueries({ queryKey: TAGS_QUERY_KEY }),
    ])
}
