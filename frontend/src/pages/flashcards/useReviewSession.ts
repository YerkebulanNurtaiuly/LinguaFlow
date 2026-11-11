import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useMemo, useRef, useState } from 'react'
import { describeError } from '@/api/errors'
import { useToast } from '@/components/ui'
import { REVIEWS_QUERY_KEY } from '@/reviews/queries'
import {
  fetchReviewQueue,
  submitReview,
  type QueueItem,
  type ReviewGrade,
} from '@/reviews/reviewsApi'

export interface ReviewAnswer {
  cardId: string
  grade: ReviewGrade
  durationMs: number
}

export type SessionPhase = 'idle' | 'loading' | 'reviewing' | 'summary'

/** The server's limit on a review's duration (SRS-02). */
const MAX_DURATION_MS = 3_600_000

/**
 * One review session (FE-FC-01). It loads the queue, grades its cards in order, and
 * when the batch is done asks the server again: learning steps that came due meanwhile
 * and cards beyond the first batch continue the session. Each time a card is shown it
 * gets a fresh review token, kept for retries, so a resend after a network error is
 * recorded once (SRS-02).
 */
export function useReviewSession() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const [phase, setPhase] = useState<SessionPhase>('idle')
  const [batch, setBatch] = useState<QueueItem[]>([])
  const [index, setIndex] = useState(0)
  const [answers, setAnswers] = useState<ReviewAnswer[]>([])
  const [nextLearningDueAt, setNextLearningDueAt] = useState<string>()
  const [submitting, setSubmitting] = useState(false)
  const [startedAt, setStartedAt] = useState(0)
  const [endedAt, setEndedAt] = useState(0)
  const shownAt = useRef(0)

  const current = phase === 'reviewing' ? batch[index] : undefined
  // A new token for every showing of a card, also when the same card comes back
  const token = useMemo(
    () => (current ? crypto.randomUUID() : undefined),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- one token per (batch, index)
    [batch, index],
  )

  const refreshStats = useCallback(
    () => queryClient.invalidateQueries({ queryKey: REVIEWS_QUERY_KEY }),
    [queryClient],
  )

  const finish = useCallback(() => {
    setEndedAt(Date.now())
    setPhase('summary')
    void refreshStats()
  }, [refreshStats])

  /** Loads the next batch; with nothing due, the session ends. */
  const load = useCallback(async () => {
    try {
      const queue = await fetchReviewQueue()
      setNextLearningDueAt(queue.nextLearningDueAt)
      if (queue.cards.length === 0) {
        finish()
        return
      }
      setBatch(queue.cards)
      setIndex(0)
      shownAt.current = Date.now()
      setPhase('reviewing')
    } catch (error) {
      toast({ title: describeError(error), variant: 'error' })
      setPhase((previous) => (previous === 'loading' ? 'idle' : previous))
    }
  }, [finish, toast])

  const start = useCallback(async () => {
    setAnswers([])
    setStartedAt(Date.now())
    setEndedAt(0)
    setPhase('loading')
    await load()
  }, [load])

  /** Continues after the summary, e.g. once learning steps are due again. */
  const resume = useCallback(async () => {
    setEndedAt(0)
    setPhase('loading')
    await load()
  }, [load])

  const grade = useCallback(
    async (value: ReviewGrade) => {
      if (!current || !token || submitting) return
      setSubmitting(true)
      const durationMs = Math.min(
        MAX_DURATION_MS,
        Math.max(0, Date.now() - shownAt.current),
      )
      try {
        await submitReview({
          flashcardId: current.card.id,
          grade: value,
          reviewToken: token,
          durationMs,
        })
      } catch (error) {
        // The card stays, with its token, so trying again is safe
        toast({ title: describeError(error), variant: 'error' })
        setSubmitting(false)
        return
      }
      setAnswers((previous) => [
        ...previous,
        { cardId: current.card.id, grade: value, durationMs },
      ])
      if (index + 1 < batch.length) {
        setIndex(index + 1)
        shownAt.current = Date.now()
      } else {
        // Still submitting while the next batch loads, so the card can't be graded twice
        await load()
      }
      setSubmitting(false)
    },
    [batch.length, current, index, load, submitting, toast, token],
  )

  const leave = useCallback(() => {
    setPhase('idle')
    setBatch([])
    void refreshStats()
  }, [refreshStats])

  return {
    phase,
    current,
    token,
    /** Cards graded plus cards left in this batch. */
    total: answers.length + (phase === 'reviewing' ? batch.length - index : 0),
    answers,
    submitting,
    nextLearningDueAt,
    /** How long the session took; set once it ends. */
    elapsedMs: endedAt ? endedAt - startedAt : 0,
    start,
    resume,
    grade,
    finish,
    leave,
  }
}
