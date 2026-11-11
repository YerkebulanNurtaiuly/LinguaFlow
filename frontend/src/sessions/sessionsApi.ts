import { z } from 'zod'
import type {
  CreateStudySessionRequest,
  StudySession as ApiStudySession,
  StudySessionTimeline as ApiTimeline,
  UpdateStudySessionRequest,
} from '@/api/types'
import { api } from '@/lib/api'
import {
  fetchVocabulary,
  type VocabularyWord,
} from '@/vocabulary/vocabularyApi'

const optional = <T extends z.ZodType>(schema: T) =>
  schema.nullish().transform((value) => value ?? undefined)

const sessionSchema = z.object({
  id: z.string(),
  title: z.string(),
  studiedOn: z.string(),
  notes: optional(z.string()),
  active: z.boolean(),
  vocabularyCount: z.number(),
  grammarTopicCount: z.number(),
  createdAt: z.string(),
  updatedAt: z.string(),
  version: z.number(),
}) satisfies z.ZodType<ApiStudySession>

const timelineSchema = z.object({
  months: z.array(
    z.object({
      month: z.string(),
      sessionCount: z.number(),
      vocabularyCount: z.number(),
      grammarTopicCount: z.number(),
      sessions: z.array(sessionSchema),
    }),
  ),
  sessionCount: z.number(),
  vocabularyCount: z.number(),
  grammarTopicCount: z.number(),
}) satisfies z.ZodType<ApiTimeline>

export type StudySession = z.infer<typeof sessionSchema>
export type Timeline = z.infer<typeof timelineSchema>
export type TimelineMonth = Timeline['months'][number]

/** All lessons of the active language by month, most recent first (SES-02). */
export async function fetchTimeline(): Promise<Timeline> {
  const { data } = await api.get<unknown>('/study-sessions/timeline')
  return timelineSchema.parse(data)
}

/** The lesson new words and topics join, or null when none is active. */
export async function fetchActiveSession(): Promise<StudySession | null> {
  const response = await api.get<unknown>('/study-sessions/active')
  return response.status === 204 ? null : sessionSchema.parse(response.data)
}

export async function fetchSession(id: string): Promise<StudySession> {
  const { data } = await api.get<unknown>(
    `/study-sessions/${encodeURIComponent(id)}`,
  )
  return sessionSchema.parse(data)
}

export async function createSession(
  request: CreateStudySessionRequest,
): Promise<StudySession> {
  const { data } = await api.post<unknown>('/study-sessions', request)
  return sessionSchema.parse(data)
}

/** Partial update; `active: true` makes it the active lesson and ends the others. */
export async function updateSession(
  id: string,
  request: UpdateStudySessionRequest,
): Promise<StudySession> {
  const { data } = await api.patch<unknown>(
    `/study-sessions/${encodeURIComponent(id)}`,
    request,
  )
  return sessionSchema.parse(data)
}

/** Deletes the lesson; its words and topics stay. */
export async function deleteSession(id: string): Promise<void> {
  await api.delete(`/study-sessions/${encodeURIComponent(id)}`)
}

export async function attachWords(
  id: string,
  vocabularyItemIds: string[],
): Promise<StudySession> {
  const { data } = await api.post<unknown>(
    `/study-sessions/${encodeURIComponent(id)}/vocabulary`,
    { vocabularyItemIds },
  )
  return sessionSchema.parse(data)
}

export async function detachWord(id: string, wordId: string): Promise<void> {
  await api.delete(
    `/study-sessions/${encodeURIComponent(id)}/vocabulary/${encodeURIComponent(wordId)}`,
  )
}

export async function attachTopics(
  id: string,
  grammarTopicIds: string[],
): Promise<StudySession> {
  const { data } = await api.post<unknown>(
    `/study-sessions/${encodeURIComponent(id)}/grammar-topics`,
    { grammarTopicIds },
  )
  return sessionSchema.parse(data)
}

export async function detachTopic(id: string, topicId: string): Promise<void> {
  await api.delete(
    `/study-sessions/${encodeURIComponent(id)}/grammar-topics/${encodeURIComponent(topicId)}`,
  )
}

/** Every word of the lesson, A–Z; lessons hold tens of words, so all pages are loaded. */
export async function fetchSessionWords(
  sessionId: string,
): Promise<VocabularyWord[]> {
  const words: VocabularyWord[] = []
  for (let page = 0; ; page++) {
    const result = await fetchVocabulary(
      { sort: 'lemma,asc', page, sessionId },
      undefined,
      100,
    )
    words.push(...result.content)
    if (page + 1 >= result.totalPages) return words
  }
}
