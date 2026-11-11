import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { REVIEWS_QUERY_KEY } from '@/reviews/queries'
import {
  fetchCardReviews,
  fetchFlashcards,
  setCardSuspended,
  type FlashcardQuery,
} from './flashcardsApi'

/** The flashcard browser's lists and card histories; invalidate after reviews too. */
export const FLASHCARDS_QUERY_KEY = ['flashcards'] as const

/** A page of cards. The previous page stays on screen while the next loads. */
export function useFlashcardList(query: FlashcardQuery) {
  return useQuery({
    queryKey: [...FLASHCARDS_QUERY_KEY, 'list', query],
    queryFn: ({ signal }) => fetchFlashcards(query, signal),
    placeholderData: keepPreviousData,
  })
}

export function useCardReviews(id: string) {
  return useQuery({
    queryKey: [...FLASHCARDS_QUERY_KEY, 'reviews', id],
    queryFn: ({ signal }) => fetchCardReviews(id, signal),
  })
}

/** Suspends or unsuspends a card; lists and review stats change with it. */
export function useSetSuspended() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, suspended }: { id: string; suspended: boolean }) =>
      setCardSuspended(id, suspended),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: FLASHCARDS_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: REVIEWS_QUERY_KEY }),
      ]),
  })
}
