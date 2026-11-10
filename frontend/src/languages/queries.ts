import { useQuery } from '@tanstack/react-query'
import { fetchLanguages, fetchMyLanguages } from './languagesApi'

export const LANGUAGES_QUERY_KEY = ['languages'] as const
export const MY_LANGUAGES_QUERY_KEY = ['user-languages'] as const

/** The language catalog; it only changes with a deployment. */
export function useLanguages() {
  return useQuery({
    queryKey: LANGUAGES_QUERY_KEY,
    queryFn: fetchLanguages,
    staleTime: Infinity,
  })
}

/** The current user's learning languages. Mutations update or invalidate this key. */
export function useMyLanguages() {
  return useQuery({
    queryKey: MY_LANGUAGES_QUERY_KEY,
    queryFn: fetchMyLanguages,
    staleTime: 5 * 60_000,
  })
}
