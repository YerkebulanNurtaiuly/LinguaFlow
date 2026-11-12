import { useMutation } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, Sparkles, TriangleAlert } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router'
import { getStatus } from '@/api/errors'
import { describeError } from '@/api/errors'
import {
  Badge,
  Button,
  ButtonLink,
  EmptyState,
  LoadError,
  PageSkeleton,
  Spinner,
} from '@/components/ui'
import {
  submitAttempt,
  type AttemptResult,
  type Exercise,
  type ExerciseAnswer,
  type ExerciseSet,
} from '@/exercises/exercisesApi'
import { useExerciseSet } from '@/exercises/queries'
import { AnswerFeedback } from './AnswerFeedback'
import {
  ChoiceInput,
  FillBlankInput,
  TextInput,
  WordOrderInput,
} from './AnswerInputs'
import {
  DEFAULT_INSTRUCTIONS,
  formatScore,
  splitGaps,
  TYPE_LABELS,
} from './exerciseText'

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

/** What the learner has entered so far, by input type. */
type Draft =
  | { kind: 'blanks'; blanks: string[] }
  | { kind: 'choice'; index: number | undefined }
  | { kind: 'order'; order: number[] }
  | { kind: 'text'; text: string }

function emptyDraft(exercise: Exercise): Draft {
  switch (exercise.type) {
    case 'FILL_BLANK':
      return {
        kind: 'blanks',
        blanks: splitGaps(exercise.prompt.text)
          .slice(1)
          .map(() => ''),
      }
    case 'MULTIPLE_CHOICE':
      return { kind: 'choice', index: undefined }
    case 'WORD_ORDER':
      return { kind: 'order', order: [] }
    case 'ERROR_CORRECTION':
      // Start from the sentence: fixing it is less typing than rewriting it
      return { kind: 'text', text: exercise.prompt.sentence }
    default:
      return { kind: 'text', text: '' }
  }
}

function answerOf(exercise: Exercise, draft: Draft): ExerciseAnswer | null {
  switch (draft.kind) {
    case 'blanks':
      return draft.blanks.some((blank) => blank.trim())
        ? { blanks: draft.blanks }
        : null
    case 'choice':
      return draft.index === undefined ? null : { index: draft.index }
    case 'order':
      return draft.order.length === 0 || exercise.type !== 'WORD_ORDER'
        ? null
        : { tokens: draft.order.map((i) => exercise.prompt.tokens[i]!) }
    case 'text':
      return draft.text.trim() ? { text: draft.text } : null
  }
}

function ExerciseCard({
  exercise,
  last,
  onAnswered,
  onNext,
}: {
  exercise: Exercise
  last: boolean
  onAnswered: (result: AttemptResult) => void
  onNext: (when: number) => void
}) {
  const [draft, setDraft] = useState<Draft>(() => emptyDraft(exercise))
  const shownAt = useRef(0)
  const nextRef = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    shownAt.current = Date.now()
  }, [])

  const submit = useMutation({
    mutationFn: (answer: ExerciseAnswer) =>
      submitAttempt(exercise.id, answer, Date.now() - shownAt.current),
    onSuccess: onAnswered,
    // The global toast reports a failure (e.g. the AI being busy); the answer stays
  })
  const result = submit.data
  const answer = answerOf(exercise, draft)
  const locked = result !== undefined || submit.isPending

  useEffect(() => {
    if (result) nextRef.current?.focus()
  }, [result])

  return (
    <article aria-label={TYPE_LABELS[exercise.type]} className="space-y-6">
      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault()
          if (answer && !locked) submit.mutate(answer)
        }}
        className="space-y-5 rounded-xl border border-border bg-surface p-5 shadow-sm"
      >
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant="primary">{TYPE_LABELS[exercise.type]}</Badge>
          {exercise.difficulty && (
            <Badge>{exercise.difficulty.toLowerCase()}</Badge>
          )}
        </div>
        <h1 className="text-base font-medium text-fg-muted">
          {exercise.prompt.instruction ?? DEFAULT_INSTRUCTIONS[exercise.type]}
        </h1>
        <AnswerInput
          exercise={exercise}
          draft={draft}
          setDraft={setDraft}
          result={result}
          locked={locked}
        />
        {!result && (
          <div className="flex justify-end">
            <Button type="submit" disabled={!answer || submit.isPending}>
              {submit.isPending ? (
                <>
                  <Sparkles className="size-4" aria-hidden="true" />
                  Checking…
                </>
              ) : (
                'Check'
              )}
            </Button>
          </div>
        )}
      </form>

      {result && (
        <>
          <AnswerFeedback exercise={exercise} result={result} />
          <div className="flex justify-end">
            <Button ref={nextRef} onClick={() => onNext(Date.now())}>
              {last ? 'See results' : 'Next'}
              <ArrowRight className="size-4" aria-hidden="true" />
            </Button>
          </div>
        </>
      )}
    </article>
  )
}

function AnswerInput({
  exercise,
  draft,
  setDraft,
  result,
  locked,
}: {
  exercise: Exercise
  draft: Draft
  setDraft: (draft: Draft) => void
  result: AttemptResult | undefined
  locked: boolean
}) {
  switch (exercise.type) {
    case 'FILL_BLANK':
      return (
        <FillBlankInput
          prompt={exercise.prompt}
          value={draft.kind === 'blanks' ? draft.blanks : []}
          onChange={(blanks) => setDraft({ kind: 'blanks', blanks })}
          disabled={locked}
          parts={result?.parts}
        />
      )
    case 'MULTIPLE_CHOICE':
      return (
        <ChoiceInput
          prompt={exercise.prompt}
          value={draft.kind === 'choice' ? draft.index : undefined}
          onChange={(index) => setDraft({ kind: 'choice', index })}
          disabled={locked}
          correctAnswer={result?.correctAnswer}
        />
      )
    case 'WORD_ORDER':
      return (
        <WordOrderInput
          prompt={exercise.prompt}
          value={draft.kind === 'order' ? draft.order : []}
          onChange={(order) => setDraft({ kind: 'order', order })}
          disabled={locked}
          parts={result?.parts}
        />
      )
    default:
      return (
        <TextInput
          exercise={exercise}
          value={draft.kind === 'text' ? draft.text : ''}
          onChange={(text) => setDraft({ kind: 'text', text })}
          disabled={locked}
        />
      )
  }
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
