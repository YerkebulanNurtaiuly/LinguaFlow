import { ArrowLeft, TriangleAlert } from 'lucide-react'
import { useState } from 'react'
import { useParams } from 'react-router'
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
import type {
  AttemptResult,
  Exercise,
  ExerciseSet,
} from '@/exercises/exercisesApi'
import { useExerciseSet } from '@/exercises/queries'
import { ExerciseCard } from './ExerciseCard'
import { formatScore, TYPE_LABELS } from './exerciseText'

const BACK = '/practice/exercises'

/**
 * /practice/exercises/:id: plays an exercise set (FE-EX-01). One exercise at a time:
 * answer, check, read the feedback, next; a summary at the end. Waits while the AI is
 * still writing the set. Reported exercises are left out.
 */
export function ExercisePlayerPage() {
  const { id = '' } = useParams()
  const set = useExerciseSet(id)

  if (set.isError && !set.data) {
    return getStatus(set.error) === 404 ? (
      <EmptyState
        icon={TriangleAlert}
        title="Exercise set not found"
        description="It may have been deleted."
        action={<ButtonLink to={BACK}>Back to exercises</ButtonLink>}
      />
    ) : (
      <LoadError
        title="Couldn't load the exercises"
        description={describeError(set.error)}
        onRetry={() => void set.refetch()}
        retrying={set.isFetching}
      />
    )
  }
  if (!set.data) {
    return (
      <PageSkeleton label="Loading exercises" cards={1} withHeader={false} />
    )
  }
  return <SetView set={set.data} />
}

function SetView({ set }: { set: ExerciseSet }) {
  if (set.status === 'GENERATING') {
    return (
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
            {set.request.count} exercises with your own words. This takes a
            minute or so; the page updates by itself.
          </p>
        </div>
      </div>
    )
  }
  const playable = set.exercises.filter((exercise) => !exercise.reported)
  if (set.status === 'FAILED' || playable.length === 0) {
    return (
      <EmptyState
        icon={TriangleAlert}
        title={
          set.status === 'FAILED'
            ? "The AI couldn't write this set"
            : 'No exercises to play'
        }
        description={
          set.status === 'FAILED'
            ? 'Try again with other topics or types.'
            : 'Every exercise in this set was reported.'
        }
        action={<ButtonLink to={BACK}>Back to exercises</ButtonLink>}
      />
    )
  }
  return <Session exercises={playable} />
}

/** One run through the set; "Try again" starts a fresh one. */
function Session({ exercises }: { exercises: Exercise[] }) {
  const [run, setRun] = useState(0)
  const [index, setIndex] = useState(0)
  const [results, setResults] = useState<AttemptResult[]>([])
  const [startedAt] = useState(() => Date.now())
  const [finishedAt, setFinishedAt] = useState<number | null>(null)

  const done = index >= exercises.length
  if (done && finishedAt !== null) {
    return (
      <Summary
        exercises={exercises}
        results={results}
        elapsedMs={finishedAt - startedAt}
        onRestart={() => {
          setRun((n) => n + 1)
          setIndex(0)
          setResults([])
          setFinishedAt(null)
        }}
      />
    )
  }
  const exercise = exercises[index]!
  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div>
        <ButtonLink to={BACK} variant="ghost" size="sm" className="-ml-3">
          <ArrowLeft className="size-4" aria-hidden="true" />
          Exercises
        </ButtonLink>
        <div className="mt-2 flex items-center justify-between gap-4 text-sm text-fg-muted">
          <span>
            Exercise {index + 1} of {exercises.length}
          </span>
          <span>{results.filter((result) => result.correct).length} right</span>
        </div>
        <div
          role="progressbar"
          aria-label="Progress"
          aria-valuemin={0}
          aria-valuemax={exercises.length}
          aria-valuenow={results.length}
          className="mt-2 h-2 overflow-hidden rounded-full bg-surface-muted"
        >
          <div
            className="h-full rounded-full bg-primary transition-[width]"
            style={{ width: `${(results.length / exercises.length) * 100}%` }}
          />
        </div>
      </div>
      <ExerciseCard
        key={`${run}-${exercise.id}`}
        exercise={exercise}
        last={index === exercises.length - 1}
        onAnswered={(result) => setResults((current) => [...current, result])}
        onNext={(when) => {
          if (index === exercises.length - 1) setFinishedAt(when)
          setIndex((current) => current + 1)
        }}
      />
    </div>
  )
}

function Summary({
  exercises,
  results,
  elapsedMs,
  onRestart,
}: {
  exercises: Exercise[]
  results: AttemptResult[]
  elapsedMs: number
  onRestart: () => void
}) {
  const right = results.filter((result) => result.correct).length
  const average =
    results.reduce((sum, result) => sum + result.score, 0) /
    Math.max(results.length, 1)
  const minutes = Math.max(1, Math.round(elapsedMs / 60_000))
  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-fg">
          Set done
        </h1>
        <p className="mt-2 text-fg-muted">
          {right} of {exercises.length} right · {formatScore(average)} overall ·{' '}
          {minutes} min
        </p>
      </div>
      <ol aria-label="Your answers" className="space-y-2">
        {exercises.map((exercise, i) => {
          const result = results[i]
          return (
            <li
              key={exercise.id}
              className="flex items-center justify-between gap-3 rounded-lg border border-border bg-surface px-4 py-3"
            >
              <span className="text-fg">
                {i + 1}. {TYPE_LABELS[exercise.type]}
              </span>
              {result && (
                <Badge
                  variant={
                    result.correct
                      ? 'success'
                      : result.score > 0
                        ? 'warning'
                        : 'danger'
                  }
                >
                  {result.correct ? 'Right' : formatScore(result.score)}
                </Badge>
              )}
            </li>
          )
        })}
      </ol>
      <div className="flex flex-wrap gap-2">
        <Button onClick={onRestart}>Try again</Button>
        <ButtonLink to={BACK} variant="secondary">
          Back to exercises
        </ButtonLink>
      </div>
    </div>
  )
}
