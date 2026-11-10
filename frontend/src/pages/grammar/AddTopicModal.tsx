import { zodResolver } from '@hookform/resolvers/zod'
import { Check } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { describeError, getApiProblem, hasErrorCode } from '@/api/errors'
import {
  Alert,
  Badge,
  Button,
  CardSkeleton,
  Field,
  Input,
  LoadError,
  Modal,
  Select,
  Tabs,
  Textarea,
  useToast,
} from '@/components/ui'
import {
  addFromCatalog,
  createGrammarTopic,
  type CatalogEntry,
  type CefrLevel,
} from '@/grammar/grammarApi'
import { useGrammarCatalog, useInvalidateGrammar } from '@/grammar/queries'
import { CEFR_LEVELS } from '@/languages/languagesApi'
import { applyServerFieldErrors } from '@/lib/formErrors'
import { LEVEL_NAMES, STARTING_STATUSES, STATUS_LABELS } from './grammarLabels'

export type AddTopicTab = 'catalog' | 'own'

interface AddTopicModalProps {
  initialTab: AddTopicTab
  onClose: () => void
}

/** Add grammar topics: pick suggestions from the catalog or type your own (FE-GRM-01). */
export function AddTopicModal({ initialTab, onClose }: AddTopicModalProps) {
  const [tab, setTab] = useState<string>(initialTab)
  return (
    <Modal
      open
      onClose={onClose}
      size="lg"
      title="Add grammar topics"
      description="Pick from the suggestions for each level, or add a topic of your own."
    >
      <Tabs
        label="How to add"
        value={tab}
        onChange={setTab}
        items={[
          {
            id: 'catalog',
            label: 'Suggestions',
            content: <CatalogPicker />,
          },
          {
            id: 'own',
            label: 'Your own',
            content: <OwnTopicForm onDone={onClose} />,
          },
        ]}
      />
    </Modal>
  )
}

function CatalogPicker() {
  const catalog = useGrammarCatalog()
  const invalidate = useInvalidateGrammar()
  const { toast } = useToast()
  const [level, setLevel] = useState<CefrLevel | ''>('')
  const [search, setSearch] = useState('')
  const [busyId, setBusyId] = useState<string>()

  const visible = useMemo(() => {
    const term = search.trim().toLocaleLowerCase()
    return (catalog.data ?? []).filter(
      (entry) =>
        (level === '' || entry.cefrLevel === level) &&
        (term === '' ||
          entry.title.toLocaleLowerCase().includes(term) ||
          entry.description.toLocaleLowerCase().includes(term)),
    )
  }, [catalog.data, level, search])

  const add = async (entry: CatalogEntry) => {
    setBusyId(entry.id)
    try {
      await addFromCatalog(entry.id)
      toast({ title: `Added “${entry.title}”`, variant: 'success' })
    } catch (error) {
      // Already yours (another tab, or a typed topic): the refresh marks it added
      if (!hasErrorCode(error, 'GRAMMAR_TOPIC_DUPLICATE')) {
        toast({ title: describeError(error), variant: 'error' })
      }
    } finally {
      await invalidate()
      setBusyId(undefined)
    }
  }

  if (catalog.isError) {
    return (
      <LoadError
        title="Couldn't load the suggestions"
        description={describeError(catalog.error)}
        onRetry={() => void catalog.refetch()}
        retrying={catalog.isFetching}
      />
    )
  }
  if (!catalog.data) {
    return (
      <div role="status" aria-label="Loading suggestions">
        <CardSkeleton lines={6} />
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Level">
          <Select
            value={level}
            placeholder="All levels"
            options={CEFR_LEVELS.filter((l) =>
              catalog.data.some((entry) => entry.cefrLevel === l),
            ).map((l) => ({ value: l, label: `${l} · ${LEVEL_NAMES[l]}` }))}
            onChange={(event) => setLevel(event.target.value as CefrLevel | '')}
          />
        </Field>
        <Field label="Find a suggestion">
          <Input
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="e.g. Dativ"
          />
        </Field>
      </div>

      {visible.length === 0 ? (
        <p className="text-sm text-fg-muted">No suggestions match.</p>
      ) : (
        <ul className="max-h-[50vh] divide-y divide-border overflow-y-auto rounded-lg border border-border">
          {visible.map((entry) => (
            <li key={entry.id} className="flex items-start gap-3 p-3">
              <div className="min-w-0 flex-1">
                <p className="flex items-baseline gap-2">
                  <span className="font-medium text-fg">{entry.title}</span>
                  <span className="text-xs text-fg-subtle">
                    <span className="sr-only">Level </span>
                    {entry.cefrLevel}
                  </span>
                </p>
                <p className="text-sm text-fg-muted">{entry.description}</p>
              </div>
              {entry.topicId ? (
                <Badge variant="success" className="shrink-0">
                  <Check className="mr-1 size-3" aria-hidden="true" />
                  Added
                </Badge>
              ) : (
                <Button
                  variant="secondary"
                  size="sm"
                  className="shrink-0"
                  loading={busyId === entry.id}
                  disabled={busyId !== undefined && busyId !== entry.id}
                  onClick={() => void add(entry)}
                  // A hidden " Dativ" span would be joined without its space ("AddDativ")
                  aria-label={`Add ${entry.title}`}
                >
                  Add
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

const ownTopicSchema = z.object({
  title: z
    .string()
    .trim()
    .min(1, 'Enter a title.')
    .max(200, 'The title can be at most 200 characters.'),
  cefrLevel: z.union([z.enum(CEFR_LEVELS), z.literal('')]),
  status: z.enum(STARTING_STATUSES),
  description: z
    .string()
    .max(2000, 'The description can be at most 2000 characters.')
    .transform((value) => value.trim()),
})

type OwnTopicInput = z.input<typeof ownTopicSchema>
type OwnTopicValues = z.output<typeof ownTopicSchema>

function OwnTopicForm({ onDone }: { onDone: () => void }) {
  const invalidate = useInvalidateGrammar()
  const { toast } = useToast()
  const [formError, setFormError] = useState<string>()
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<OwnTopicInput, unknown, OwnTopicValues>({
    resolver: zodResolver(ownTopicSchema),
    defaultValues: { title: '', cefrLevel: '', status: 'NEW', description: '' },
  })

  const onSubmit = handleSubmit(async (values) => {
    setFormError(undefined)
    try {
      const topic = await createGrammarTopic({
        title: values.title,
        cefrLevel: values.cefrLevel || undefined,
        status: values.status,
        description: values.description || undefined,
      })
      void invalidate()
      toast({ title: `Added “${topic.title}”`, variant: 'success' })
      onDone()
    } catch (error) {
      if (
        hasErrorCode(error, 'GRAMMAR_TOPIC_DUPLICATE') &&
        getApiProblem(error)?.existingId
      ) {
        setError(
          'title',
          { type: 'server', message: `You already have “${values.title}”.` },
          { shouldFocus: true },
        )
        return
      }
      if (
        !applyServerFieldErrors(error, setError, [
          'title',
          'cefrLevel',
          'status',
          'description',
        ])
      ) {
        setFormError(describeError(error))
      }
    }
  })

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4">
      {formError && <Alert variant="danger">{formError}</Alert>}
      <Field
        label="Title"
        hint="e.g. Dativ or Nebensätze mit obwohl"
        error={errors.title?.message}
        required
      >
        <Input autoComplete="off" {...register('title')} />
      </Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Level" error={errors.cefrLevel?.message}>
          <Select
            placeholder="Not set"
            options={CEFR_LEVELS.map((l) => ({
              value: l,
              label: `${l} · ${LEVEL_NAMES[l]}`,
            }))}
            {...register('cefrLevel')}
          />
        </Field>
        <Field
          label="Status"
          hint="Mastered is earned through practice."
          error={errors.status?.message}
        >
          <Select
            options={STARTING_STATUSES.map((s) => ({
              value: s,
              label: STATUS_LABELS[s],
            }))}
            {...register('status')}
          />
        </Field>
      </div>
      <Field label="Description" error={errors.description?.message}>
        <Textarea rows={3} {...register('description')} />
      </Field>
      <div className="flex justify-end border-t border-border pt-4">
        <Button type="submit" loading={isSubmitting}>
          Add topic
        </Button>
      </div>
    </form>
  )
}
