import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, GraduationCap, Search, Sparkles } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { z } from 'zod'
import { describeError } from '@/api/errors'
import {
  Alert,
  Badge,
  Button,
  ButtonLink,
  CefrBadge,
  EmptyState,
  Field,
  Input,
  LoadError,
  PageSkeleton,
  RadioCards,
  Select,
} from '@/components/ui'
import {
  createExerciseSet,
  EXERCISE_TYPES,
  type ExerciseSetSummary,
} from '@/exercises/exercisesApi'
import { EXERCISES_QUERY_KEY, useExerciseSets } from '@/exercises/queries'
import type { GrammarTopic } from '@/grammar/grammarApi'
import { useGrammarTopics } from '@/grammar/queries'
import { useMyLanguages } from '@/languages/queries'
import { applyServerFieldErrors } from '@/lib/formErrors'
import { TYPE_LABELS } from './exerciseText'

/** The API's limits (ExerciseSetService). */
const MAX_TOPICS = 5
const COUNTS = [5, 10, 15, 20] as const

/** Types ticked when the learner has no preference saved. */
const DEFAULT_TYPES = [
  'FILL_BLANK',
  'MULTIPLE_CHOICE',
  'WORD_ORDER',
  'TRANSLATION',
] as const

/** Checkbox groups come back from RHF as false, a string or an array. */
const asArray = (value: unknown): string[] =>
  Array.isArray(value) ? value : typeof value === 'string' ? [value] : []

const formSchema = z.object({
  grammarTopicIds: z.preprocess(
    asArray,
    z
      .array(z.string())
      .min(1, 'Choose at least one topic.')
      .max(MAX_TOPICS, `Choose at most ${MAX_TOPICS} topics.`),
  ),
  types: z.preprocess(
    asArray,
    z.array(z.enum(EXERCISE_TYPES)).min(1, 'Choose at least one format.'),
  ),
  count: z.string(),
  difficulty: z.enum(['ANY', 'EASY', 'MEDIUM', 'HARD']),
})
type FormInput = z.input<typeof formSchema>
type FormValues = z.output<typeof formSchema>

/**
 * /practice/exercises: pick grammar topics, formats, a count and a difficulty, and the
 * AI writes a set from the learner's own words (FE-EX-02). Creating one opens the
 * player, which waits while the set is written. `?topic=` preselects a topic.
 */
export function ExerciseBuilderPage() {
  const topics = useGrammarTopics({})
  const languages = useMyLanguages()

  return (
    <div className="max-w-3xl space-y-8">
      <div>
        <ButtonLink to="/practice" variant="ghost" size="sm" className="-ml-3">
          <ArrowLeft className="size-4" aria-hidden="true" />
          Practice
        </ButtonLink>
        <h1 className="mt-2 text-2xl font-semibold tracking-tight">
          Grammar exercises
        </h1>
        <p className="mt-2 text-fg-muted">
          Choose what to practise. The exercises are written for you, with the
          words you are learning.
        </p>
      </div>

      {topics.isError && !topics.data ? (
        <LoadError
          title="Couldn't load your grammar topics"
          description={describeError(topics.error)}
          onRetry={() => void topics.refetch()}
          retrying={topics.isFetching}
        />
      ) : !topics.data || !languages.data ? (
        <PageSkeleton
          label="Loading your topics"
          cards={2}
          withHeader={false}
        />
      ) : topics.data.length === 0 ? (
        <EmptyState
          icon={GraduationCap}
          title="Add a grammar topic first"
          description="Exercises practise the grammar topics you study."
          action={<ButtonLink to="/grammar">Go to grammar</ButtonLink>}
        />
      ) : (
        <BuilderForm
          topics={topics.data}
          preferredTypes={
            languages.data.find((language) => language.active)?.settings
              .preferredExerciseTypes ?? []
          }
        />
      )}

      <RecentSets topics={topics.data ?? []} />
    </div>
  )
}

function BuilderForm({
  topics,
  preferredTypes,
}: {
  topics: GrammarTopic[]
  preferredTypes: readonly (typeof EXERCISE_TYPES)[number][]
}) {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState('')
  const preselected = params.get('topic')

  const form = useForm<FormInput, unknown, FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      grammarTopicIds:
        preselected && topics.some((topic) => topic.id === preselected)
          ? [preselected]
          : [],
      types:
        preferredTypes.length > 0 ? [...preferredTypes] : [...DEFAULT_TYPES],
      count: '10',
      difficulty: 'ANY',
    },
  })
  const { register, handleSubmit, setError, control, formState } = form
  const chosen = asArray(useWatch({ control, name: 'grammarTopicIds' }))
  const difficulty = useWatch({ control, name: 'difficulty' })

  const visible = useMemo(() => {
    const needle = search.trim().toLowerCase()
    return needle
      ? topics.filter(
          (topic) =>
            topic.title.toLowerCase().includes(needle) ||
            chosen.includes(topic.id),
        )
      : topics
  }, [topics, search, chosen])

  const create = useMutation({
    mutationFn: (values: FormValues) =>
      createExerciseSet({
        grammarTopicIds: values.grammarTopicIds,
        types: values.types,
        count: Number(values.count),
        difficulty: values.difficulty === 'ANY' ? undefined : values.difficulty,
      }),
    onSuccess: ({ exerciseSet }) => {
      void queryClient.invalidateQueries({ queryKey: EXERCISES_QUERY_KEY })
      void navigate(`/practice/exercises/${exerciseSet.id}`)
    },
    onError: (error) => {
      if (
        !applyServerFieldErrors(error, setError, [
          'grammarTopicIds',
          'types',
          'count',
        ])
      ) {
        setError('root', { message: describeError(error) })
      }
    },
  })

  return (
    <form
      noValidate
      onSubmit={handleSubmit((values) => create.mutate(values))}
      className="space-y-6 rounded-xl border border-border bg-surface p-5"
    >
      {formState.errors.root?.message && (
        <Alert variant="danger">{formState.errors.root.message}</Alert>
      )}

      <fieldset className="space-y-3">
        <legend className="text-sm font-medium text-fg">
          Grammar topics{' '}
          <span className="font-normal text-fg-muted">
            ({chosen.length} of up to {MAX_TOPICS})
          </span>
        </legend>
        {topics.length > 5 && (
          <div className="relative">
            <Field label="Find a topic">
              <Input
                type="search"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                className="pl-9"
              />
            </Field>
            <Search
              className="pointer-events-none absolute bottom-3 left-3 size-4 text-fg-subtle"
              aria-hidden="true"
            />
          </div>
        )}
        <ul className="max-h-72 space-y-1 overflow-y-auto rounded-lg border border-border p-2">
          {visible.map((topic) => {
            const checked = chosen.includes(topic.id)
            return (
              <li key={topic.id}>
                <label className="flex cursor-pointer items-center gap-3 rounded-md px-2 py-1.5 text-fg hover:bg-surface-muted has-disabled:cursor-default has-disabled:opacity-50">
                  <input
                    type="checkbox"
                    value={topic.id}
                    disabled={!checked && chosen.length >= MAX_TOPICS}
                    className="size-4 accent-primary"
                    {...register('grammarTopicIds')}
                  />
                  <span className="flex-1">{topic.title}</span>
                  {topic.cefrLevel && <CefrBadge level={topic.cefrLevel} />}
                </label>
              </li>
            )
          })}
        </ul>
        {formState.errors.grammarTopicIds?.message && (
          <p role="alert" className="text-sm text-danger">
            {formState.errors.grammarTopicIds.message}
          </p>
        )}
      </fieldset>

      <fieldset className="space-y-3">
        <legend className="text-sm font-medium text-fg">Formats</legend>
        <div className="grid gap-2 sm:grid-cols-2">
          {EXERCISE_TYPES.map((type) => (
            <label
              key={type}
              className="flex items-center gap-2 text-sm text-fg"
            >
              <input
                type="checkbox"
                value={type}
                className="size-4 accent-primary"
                {...register('types')}
              />
              {TYPE_LABELS[type]}
            </label>
          ))}
        </div>
        {formState.errors.types?.message && (
          <p role="alert" className="text-sm text-danger">
            {formState.errors.types.message}
          </p>
        )}
      </fieldset>

      <div className="grid gap-6 sm:grid-cols-2">
        <Field label="How many" error={formState.errors.count?.message}>
          <Select
            {...register('count')}
            options={COUNTS.map((count) => ({
              value: String(count),
              label: `${count} exercises`,
            }))}
          />
        </Field>
      </div>

      <RadioCards
        legend="Difficulty"
        columns={2}
        options={[
          { value: 'ANY', label: 'Match my level' },
          {
            value: 'EASY',
            label: 'Easy',
            description: 'Short, simple sentences',
          },
          { value: 'MEDIUM', label: 'Medium' },
          {
            value: 'HARD',
            label: 'Hard',
            description: 'Longer sentences, closer choices',
          },
        ]}
        value={difficulty}
        onChange={(value) => form.setValue('difficulty', value)}
      />

      <div className="flex justify-end">
        <Button type="submit" disabled={create.isPending}>
          <Sparkles className="size-4" aria-hidden="true" />
          {create.isPending ? 'Asking the AI…' : 'Create exercises'}
        </Button>
      </div>
    </form>
  )
}

const STATUS_BADGES: Record<
  ExerciseSetSummary['status'],
  { label: string; variant: 'info' | 'success' | 'danger' }
> = {
  GENERATING: { label: 'Being written', variant: 'info' },
  READY: { label: 'Ready', variant: 'success' },
  FAILED: { label: 'Failed', variant: 'danger' },
}

function RecentSets({ topics }: { topics: GrammarTopic[] }) {
  const sets = useExerciseSets()
  const titles = useMemo(
    () => new Map(topics.map((topic) => [topic.id, topic.title])),
    [topics],
  )
  const list = sets.data?.content ?? []
  if (list.length === 0) return null

  return (
    <section aria-labelledby="recent-sets" className="space-y-3">
      <h2 id="recent-sets" className="text-lg font-semibold text-fg">
        Your latest sets
      </h2>
      <ul className="space-y-2">
        {list.map((set) => {
          const badge = STATUS_BADGES[set.status]
          const names = set.request.grammarTopicIds
            .map((id) => titles.get(id))
            .filter((title): title is string => title !== undefined)
          return (
            <li key={set.id}>
              <Link
                to={`/practice/exercises/${set.id}`}
                className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border bg-surface px-4 py-3 hover:bg-surface-muted focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
              >
                <span className="min-w-0">
                  <span className="block font-medium text-fg">
                    {names.length > 0 ? names.join(', ') : 'Deleted topics'}
                  </span>
                  <span className="block text-sm text-fg-muted">
                    {set.status === 'READY'
                      ? `${set.exerciseCount} exercises`
                      : `${set.request.count} exercises requested`}{' '}
                    ·{' '}
                    {new Date(set.createdAt).toLocaleDateString(undefined, {
                      day: 'numeric',
                      month: 'short',
                    })}
                  </span>
                </span>
                <Badge variant={badge.variant}>{badge.label}</Badge>
              </Link>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
