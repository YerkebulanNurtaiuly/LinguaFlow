import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, PencilLine, Play } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { describeError } from '@/api/errors'
import {
  Alert,
  Button,
  ButtonLink,
  Field,
  LoadError,
  PageSkeleton,
  RadioCards,
  Select,
} from '@/components/ui'
import {
  startPracticeSession,
  type PracticeMode,
  type PracticeOverview,
} from '@/practice/practiceApi'
import { PRACTICE_QUERY_KEY, usePracticeOverview } from '@/practice/queries'
import { MODE_LABELS, plural } from './practiceText'

type StartableMode = Exclude<PracticeMode, 'WEAK_SPOTS'>

const LENGTHS = [5, 10, 15, 20, 30, 40, 50]

/**
 * /practice: the practice hub (FE-PRC-01). Pick a mode (current lesson, mixed practice,
 * full revision), see what it would use, set the length and what to include, and start
 * a session mixing flashcards and exercises. A session in progress can be continued.
 */
export function PracticePage() {
  const overview = usePracticeOverview()
  return (
    <div className="max-w-3xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Practice</h1>
        <p className="mt-2 text-fg-muted">
          Flashcards and exercises together, chosen from what you have learned.
        </p>
      </div>
      {overview.isError && !overview.data ? (
        <LoadError
          title="Couldn't load your practice options"
          description={describeError(overview.error)}
          onRetry={() => void overview.refetch()}
          retrying={overview.isFetching}
        />
      ) : !overview.data ? (
        <PageSkeleton label="Loading practice" cards={2} withHeader={false} />
      ) : (
        <>
          {overview.data.inProgress && (
            <InProgress session={overview.data.inProgress} />
          )}
          <StartForm overview={overview.data} />
        </>
      )}
      <Link
        to="/practice/exercises"
        className="group flex items-center gap-4 rounded-xl border border-border bg-surface p-5 hover:bg-surface-muted focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
      >
        <PencilLine
          className="size-6 shrink-0 text-primary"
          aria-hidden="true"
        />
        <span className="flex-1">
          <span className="block font-semibold text-fg">Grammar exercises</span>
          <span className="block text-sm text-fg-muted">
            Only exercises, for the grammar topics and formats you choose.
          </span>
        </span>
        <ArrowRight className="size-5 text-fg-muted" aria-hidden="true" />
      </Link>
    </div>
  )
}

function InProgress({
  session,
}: {
  session: NonNullable<PracticeOverview['inProgress']>
}) {
  return (
    <section
      aria-label="Session in progress"
      className="flex flex-wrap items-center gap-4 rounded-xl border border-primary bg-primary-soft p-4"
    >
      <div className="flex-1">
        <p className="font-semibold text-fg">
          {MODE_LABELS[session.mode]} in progress
        </p>
        <p className="text-sm text-fg-muted">
          {session.answered} of {plural(session.items, 'item')} done
        </p>
      </div>
      <ButtonLink to={`/practice/sessions/${session.id}`}>
        Continue
        <ArrowRight className="size-4" aria-hidden="true" />
      </ButtonLink>
    </section>
  )
}

/** What each mode would use, from the overview. */
function modeOptions(overview: PracticeOverview) {
  const lesson = overview.currentLesson
  const topics = plural(overview.topics, 'grammar topic')
  return [
    {
      value: 'CURRENT_LESSON' as const,
      label: MODE_LABELS.CURRENT_LESSON,
      description: (
        <>
          The words and grammar of your latest lesson.
          <span className="mt-1 block text-fg-subtle">
            {lesson
              ? `${lesson.title}: ${plural(lesson.words, 'word')}, ${plural(lesson.topics, 'grammar topic')}`
              : 'No lesson yet'}
          </span>
        </>
      ),
    },
    {
      value: 'MIXED' as const,
      label: MODE_LABELS.MIXED,
      description: (
        <>
          Mostly new material, with older words you know less well or haven’t
          practised for a while.
          <span className="mt-1 block text-fg-subtle">
            {overview.freshWords} new and {overview.words - overview.freshWords}{' '}
            older words, {topics}
          </span>
        </>
      ),
    },
    {
      value: 'FULL_REVISION' as const,
      label: MODE_LABELS.FULL_REVISION,
      description: (
        <>
          Everything you have learned, weakest and most overdue first.
          <span className="mt-1 block text-fg-subtle">
            {plural(overview.words, 'word')}, {topics}
          </span>
        </>
      ),
    },
  ]
}

function StartForm({ overview }: { overview: PracticeOverview }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [mode, setMode] = useState<StartableMode>(
    overview.currentLesson ? 'CURRENT_LESSON' : 'MIXED',
  )
  const [length, setLength] = useState(overview.practiceSessionLength)
  const [flashcards, setFlashcards] = useState(true)
  const [exercises, setExercises] = useState(true)

  const start = useMutation({
    mutationFn: startPracticeSession,
    meta: { errorToast: false },
    onSuccess: async (session) => {
      await queryClient.invalidateQueries({ queryKey: PRACTICE_QUERY_KEY })
      void navigate(`/practice/sessions/${session.id}`)
    },
  })

  const lengths = LENGTHS.includes(length)
    ? LENGTHS
    : [...LENGTHS, length].sort((a, b) => a - b)
  const noLesson = mode === 'CURRENT_LESSON' && !overview.currentLesson
  const nothingChosen = !flashcards && !exercises
  const problem = noLesson
    ? 'There’s no lesson yet. Add one under Lessons, or choose another mode.'
    : nothingChosen
      ? 'Choose flashcards, exercises or both.'
      : undefined

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (problem) return
    start.mutate({
      mode,
      length,
      includeFlashcards: flashcards,
      includeExercises: exercises,
    })
  }

  return (
    <form
      noValidate
      onSubmit={submit}
      aria-label="Start practising"
      className="space-y-5 rounded-xl border border-border bg-surface p-5 shadow-sm"
    >
      <RadioCards
        legend="What to practise"
        options={modeOptions(overview)}
        value={mode}
        onChange={(value) => {
          setMode(value)
          start.reset()
        }}
      />
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Length" hint="Items in the session; there may be fewer">
          <Select
            value={String(length)}
            onChange={(event) => setLength(Number(event.target.value))}
            options={lengths.map((n) => ({
              value: String(n),
              label: `${n} items`,
            }))}
          />
        </Field>
        <fieldset>
          <legend className="mb-2 text-sm font-medium text-fg">Include</legend>
          <div className="space-y-2">
            <label className="flex items-center gap-2 text-sm text-fg">
              <input
                type="checkbox"
                checked={flashcards}
                onChange={(event) => setFlashcards(event.target.checked)}
                className="size-4 accent-primary"
              />
              Flashcards
            </label>
            <label className="flex items-center gap-2 text-sm text-fg">
              <input
                type="checkbox"
                checked={exercises}
                onChange={(event) => setExercises(event.target.checked)}
                className="size-4 accent-primary"
              />
              Exercises written by AI
            </label>
          </div>
        </fieldset>
      </div>
      {problem && <Alert variant="info">{problem}</Alert>}
      {start.isError && (
        <Alert variant="danger">{describeError(start.error)}</Alert>
      )}
      <div className="flex justify-end">
        <Button type="submit" disabled={!!problem || start.isPending}>
          <Play className="size-4" aria-hidden="true" />
          {start.isPending ? 'Starting…' : 'Start'}
        </Button>
      </div>
    </form>
  )
}
