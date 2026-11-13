import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, RotateCcw, TriangleAlert } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { describeError, getStatus } from '@/api/errors'
import {
  Badge,
  Button,
  ButtonLink,
  EmptyState,
  LoadError,
  PageSkeleton,
  Spinner,
} from '@/components/ui'
import type { Exercise } from '@/exercises/exercisesApi'
import { FLASHCARDS_QUERY_KEY } from '@/flashcards/queries'
import { PROGRESS_QUERY_KEY } from '@/progress/queries'
import { ExerciseCard } from '@/pages/exercises/ExerciseCard'
import { formatScore, TYPE_LABELS } from '@/pages/exercises/exerciseText'
import { GermanText, ReviewCard } from '@/pages/flashcards/ReviewCard'
import {
  completePracticeSession,
  retryPracticeSession,
  type PracticeItem,
  type PracticeSession,
} from '@/practice/practiceApi'
import { PRACTICE_QUERY_KEY, usePracticeSession } from '@/practice/queries'
import { REVIEWS_QUERY_KEY } from '@/reviews/queries'
import { submitReview, type ReviewGrade } from '@/reviews/reviewsApi'
import { formatDuration, MODE_LABELS, plural } from './practiceText'

const BACK = '/practice'

/** The server's limit on an answer's duration. */
const MAX_DURATION_MS = 3_600_000

/**
 * /practice/sessions/:id: plays a practice session (FE-PRC-01). Flashcards and
 * exercises come one at a time, in the session's order; exercises still being written
 * join as they arrive. Answers name the session, so it tracks them, and when the last
 * item is answered the session is completed and its summary shown, with "Practise
 * mistakes again".
 */
export function PracticeSessionPage() {
  const { id = '' } = useParams()
  const session = usePracticeSession(id)

  if (session.isError && !session.data) {
    return getStatus(session.error) === 404 ? (
      <EmptyState
        icon={TriangleAlert}
        title="Practice session not found"
        description="It may belong to another language or have been removed."
        action={<ButtonLink to={BACK}>Back to practice</ButtonLink>}
      />
    ) : (
      <LoadError
        title="Couldn't load the session"
        description={describeError(session.error)}
        onRetry={() => void session.refetch()}
        retrying={session.isFetching}
      />
    )
  }
  if (!session.data) {
    return (
      <PageSkeleton label="Loading practice" cards={1} withHeader={false} />
    )
  }
  return session.data.status === 'IN_PROGRESS' ? (
    <Player key={session.data.id} session={session.data} />
  ) : (
    <Summary session={session.data} />
  )
}

/** The card or exercise an item shows. */
function refOf(item: PracticeItem): string {
  return item.flashcard?.card.id ?? item.exercise?.id ?? `${item.position}`
}

/** Whether an item can be shown: deleted words or exercises leave empty items behind. */
function playable(item: PracticeItem): boolean {
  return item.flashcard !== undefined || item.exercise !== undefined
}

function Player({ session }: { session: PracticeSession }) {
  const queryClient = useQueryClient()
  // Answered here but maybe not yet in the loaded session
  const [answered, setAnswered] = useState<ReadonlySet<string>>(new Set())
  // The item on screen stays put when exercises join and the order changes
  const [pinned, setPinned] = useState<string>()

  const done = (item: PracticeItem) =>
    item.answeredAt !== undefined ||
    answered.has(refOf(item)) ||
    !playable(item)
  const open = session.items.filter((item) => !done(item))
  const current =
    open.find((item) => refOf(item) === pinned) ?? open[0] ?? undefined
  const currentRef = current ? refOf(current) : undefined
  if (currentRef !== undefined && currentRef !== pinned) setPinned(currentRef)

  const generating = session.exerciseSet?.status === 'GENERATING'
  const waitingFor = generating
    ? Math.max(
        0,
        session.exerciseSet!.requested -
          session.items.filter((item) => item.type === 'EXERCISE').length,
      )
    : 0
  const total = session.items.length + waitingFor
  const doneCount = session.items.length - open.length

  const complete = useMutation({
    mutationFn: () => completePracticeSession(session.id),
    onSuccess: async (completed) => {
      queryClient.setQueryData(
        [...PRACTICE_QUERY_KEY, 'session', session.id],
        completed,
      )
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: [...PRACTICE_QUERY_KEY, 'overview'],
        }),
        queryClient.invalidateQueries({ queryKey: REVIEWS_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: FLASHCARDS_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: PROGRESS_QUERY_KEY }),
      ])
    },
  })

  // Everything answered and nothing more coming: finish
  const finished = !current && !generating
  const completeMutate = complete.mutate
  const completeIdle = complete.isIdle
  useEffect(() => {
    if (finished && completeIdle) completeMutate()
  }, [finished, completeIdle, completeMutate])

  const markDone = (ref: string) =>
    setAnswered((previous) => new Set(previous).add(ref))

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div>
        <div className="flex items-center justify-between gap-2">
          <ButtonLink to={BACK} variant="ghost" size="sm" className="-ml-3">
            <ArrowLeft className="size-4" aria-hidden="true" />
            Practice
          </ButtonLink>
          {current && (
            <Button
              variant="ghost"
              size="sm"
              disabled={complete.isPending}
              onClick={() => complete.mutate()}
            >
              End session
            </Button>
          )}
        </div>
        <div className="mt-2 flex items-center justify-between gap-4 text-sm text-fg-muted">
          <span>{MODE_LABELS[session.mode]}</span>
          <span>
            {doneCount} of {total} done
          </span>
        </div>
        <div
          role="progressbar"
          aria-label="Progress"
          aria-valuemin={0}
          aria-valuemax={total}
          aria-valuenow={doneCount}
          className="mt-2 h-2 overflow-hidden rounded-full bg-surface-muted"
        >
          <div
            className="h-full rounded-full bg-primary transition-[width]"
            style={{ width: `${(doneCount / Math.max(total, 1)) * 100}%` }}
          />
        </div>
        {generating && current && (
          <p role="status" className="mt-2 text-sm text-fg-muted">
            <Spinner className="mr-1.5 inline-block align-[-2px]" />
            Writing {plural(waitingFor, 'exercise')} for you; they join as you
            go.
          </p>
        )}
      </div>

      {current?.flashcard ? (
        <FlashcardStep
          key={`${current.position}-${refOf(current)}`}
          sessionId={session.id}
          item={current}
          onDone={() => markDone(refOf(current))}
        />
      ) : current?.exercise ? (
        <ExerciseCard
          key={`${current.position}-${refOf(current)}`}
          exercise={current.exercise}
          last={open.length === 1 && !generating}
          practiceSessionId={session.id}
          onAnswered={() => {}}
          onNext={() => markDone(refOf(current))}
        />
      ) : generating ? (
        <div
          role="status"
          className="flex flex-col items-center gap-4 rounded-xl border border-border bg-surface p-10 text-center"
        >
          <Spinner />
          <div>
            <h1 className="text-xl font-semibold text-fg">
              Writing your exercises…
            </h1>
            <p className="mt-1 text-fg-muted">
              The cards are done. Your exercises are almost ready; the page
              updates by itself.
            </p>
          </div>
        </div>
      ) : complete.isError ? (
        <LoadError
          title="Couldn't finish the session"
          description={describeError(complete.error)}
          onRetry={() => complete.mutate()}
          retrying={complete.isPending}
        />
      ) : (
        <PageSkeleton label="Finishing" cards={1} withHeader={false} />
      )}
    </div>
  )
}

/** One flashcard: show the answer, then grade it with the session's ID. */
function FlashcardStep({
  sessionId,
  item,
  onDone,
}: {
  sessionId: string
  item: PracticeItem
  onDone: () => void
}) {
  const card = item.flashcard!
  // One token per showing, kept on a failed submit so a retry is recorded once
  const token = useMemo(() => crypto.randomUUID(), [])
  const shownAt = useRef(0)
  useEffect(() => {
    shownAt.current = Date.now()
  }, [])
  const grade = useMutation({
    mutationFn: (value: ReviewGrade) =>
      submitReview({
        flashcardId: card.card.id,
        grade: value,
        reviewToken: token,
        durationMs: Math.min(
          MAX_DURATION_MS,
          Math.max(0, Date.now() - shownAt.current),
        ),
        practiceSessionId: sessionId,
      }),
    onSuccess: onDone,
  })
  return (
    <ReviewCard
      item={card}
      typing={false}
      submitting={grade.isPending}
      onGrade={(value) => grade.mutate(value)}
    />
  )
}

/** A short text for an exercise in the summary. */
function preview(exercise: Exercise): string {
  switch (exercise.type) {
    case 'FILL_BLANK':
      return exercise.prompt.text
    case 'MULTIPLE_CHOICE':
      return exercise.prompt.question
    case 'WORD_ORDER':
      return exercise.prompt.tokens.join(' ')
    case 'TRANSLATION':
      return exercise.prompt.text
    case 'SENTENCE_CONSTRUCTION':
      return exercise.prompt.task
    case 'ERROR_CORRECTION':
      return exercise.prompt.sentence
    case 'TRANSFORMATION':
      return exercise.prompt.sentence
  }
}

function Summary({ session }: { session: PracticeSession }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const summary = session.summary
  const retry = useMutation({
    mutationFn: () => retryPracticeSession(session.id),
    onSuccess: async (next) => {
      await queryClient.invalidateQueries({ queryKey: PRACTICE_QUERY_KEY })
      void navigate(`/practice/sessions/${next.id}`)
    },
  })
  if (!summary) {
    return (
      <EmptyState
        icon={TriangleAlert}
        title="This session was ended"
        description="It has no results to show."
        action={<ButtonLink to={BACK}>Back to practice</ButtonLink>}
      />
    )
  }
  const { flashcards, exercises } = summary
  const missedCards = session.items.filter(
    (item) =>
      item.flashcard &&
      summary.missedFlashcardIds.includes(item.flashcard.card.id),
  )
  const missedExercises = session.items.filter(
    (item) =>
      item.exercise && summary.missedExerciseIds.includes(item.exercise.id),
  )
  const missed = missedCards.length + missedExercises.length

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-fg">
          Session done
        </h1>
        <p className="mt-2 text-fg-muted">
          {MODE_LABELS[session.mode]} · {summary.answeredItems} of{' '}
          {plural(summary.items, 'item')} answered ·{' '}
          {formatDuration(summary.durationMs)}
        </p>
      </div>

      <dl className="grid gap-3 sm:grid-cols-2">
        {flashcards.items > 0 && (
          <div className="rounded-xl border border-border bg-surface p-4">
            <dt className="text-sm text-fg-muted">Flashcards remembered</dt>
            <dd className="mt-1 text-2xl font-semibold text-fg">
              {flashcards.remembered} of {flashcards.answered}
            </dd>
            <dd className="mt-1 text-sm text-fg-muted">
              Again {flashcards.again} · Hard {flashcards.hard} · Good{' '}
              {flashcards.good} · Easy {flashcards.easy}
            </dd>
          </div>
        )}
        {exercises.items > 0 && (
          <div className="rounded-xl border border-border bg-surface p-4">
            <dt className="text-sm text-fg-muted">Exercises right</dt>
            <dd className="mt-1 text-2xl font-semibold text-fg">
              {exercises.correct} of {exercises.answered}
            </dd>
            <dd className="mt-1 text-sm text-fg-muted">
              {exercises.averageScore === undefined
                ? 'None answered'
                : `${formatScore(exercises.averageScore)} on average`}
            </dd>
          </div>
        )}
      </dl>

      {missed > 0 ? (
        <section aria-labelledby="missed-heading" className="space-y-3">
          <h2 id="missed-heading" className="text-lg font-semibold text-fg">
            To practise again
          </h2>
          <ul className="space-y-2">
            {missedCards.map((item) => {
              const card = item.flashcard!.card
              const germanFront = card.direction === 'TARGET_TO_NATIVE'
              return (
                <li
                  key={card.id}
                  className="flex items-center justify-between gap-3 rounded-lg border border-border bg-surface px-4 py-3"
                >
                  <span className="text-fg">
                    {germanFront ? (
                      <GermanText text={card.content.front} />
                    ) : (
                      card.content.front
                    )}{' '}
                    <span className="text-fg-subtle">→</span>{' '}
                    {germanFront ? (
                      card.content.back
                    ) : (
                      <GermanText text={card.content.back} />
                    )}
                  </span>
                  <Badge>Flashcard</Badge>
                </li>
              )
            })}
            {missedExercises.map((item) => (
              <li
                key={item.exercise!.id}
                className="flex items-center justify-between gap-3 rounded-lg border border-border bg-surface px-4 py-3"
              >
                <span className="min-w-0 truncate text-fg">
                  {preview(item.exercise!)}
                </span>
                <Badge>{TYPE_LABELS[item.exercise!.type]}</Badge>
              </li>
            ))}
          </ul>
        </section>
      ) : (
        summary.answeredItems > 0 && (
          <p className="text-fg-muted">No mistakes this time. Well done!</p>
        )
      )}

      <div className="flex flex-wrap gap-2">
        {missed > 0 && (
          <Button disabled={retry.isPending} onClick={() => retry.mutate()}>
            <RotateCcw className="size-4" aria-hidden="true" />
            {retry.isPending
              ? 'Starting…'
              : `Practise mistakes again (${missed})`}
          </Button>
        )}
        <ButtonLink to={BACK} variant={missed > 0 ? 'secondary' : 'primary'}>
          Back to practice
        </ButtonLink>
      </div>
    </div>
  )
}
