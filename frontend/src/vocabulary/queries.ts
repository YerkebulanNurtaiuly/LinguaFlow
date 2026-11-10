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
  type VocabularyQuery,
} from './vocabularyApi'

/** Everything vocabulary-related; invalidate it after changing a word. */
export const VOCABULARY_QUERY_KEY = ['vocabulary'] as const
/** Tag list with word counts; invalidate it when words or tags change. */
export const TAGS_QUERY_KEY = ['tags'] as const

/** A page of the list. The previous page stays on screen while the next loads. */
export function useVocabularyList(query: VocabularyQuery) {
  return useQuery({
    queryKey: [...VOCABULARY_QUERY_KEY, 'list', query],
    queryFn: ({ signal }) => fetchVocabulary(query, signal),
    placeholderData: keepPreviousData,
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
