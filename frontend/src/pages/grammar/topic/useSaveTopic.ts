import { useQueryClient } from '@tanstack/react-query'
import type { UpdateGrammarTopicRequest } from '@/api/types'
import { updateGrammarTopic, type GrammarTopic } from '@/grammar/grammarApi'
import { GRAMMAR_QUERY_KEY, grammarTopicKey } from '@/grammar/queries'

/**
 * Saves a partial update, puts the result in the topic's cache entry straight away and
 * refreshes everything else grammar-related (lists, history, catalog marks).
 */
export function useSaveTopic(id: string) {
  const queryClient = useQueryClient()
  return async (request: UpdateGrammarTopicRequest): Promise<GrammarTopic> => {
    const updated = await updateGrammarTopic(id, request)
    queryClient.setQueryData(grammarTopicKey(id), updated)
    void queryClient.invalidateQueries({
      queryKey: GRAMMAR_QUERY_KEY,
      predicate: (query) =>
        query.queryKey[2] !== id || query.queryKey[1] !== 'topic',
    })
    return updated
  }
}
