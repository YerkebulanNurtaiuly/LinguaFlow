import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Plus, X } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate, useParams } from 'react-router'
import type { z } from 'zod'
import { describeError, getStatus } from '@/api/errors'
import type { UpdateStudySessionRequest } from '@/api/types'
import {
  Alert,
  Badge,
  Button,
  ButtonLink,
  CefrBadge,
  ConfirmDialog,
  EmptyState,
  Field,
  Input,
  LoadError,
  PageSkeleton,
  StatusBadge,
  Textarea,
  useToast,
} from '@/components/ui'
import { SettingsSection } from '@/pages/settings/SettingsSection'
import {
  sessionKey,
  useInvalidateSessions,
  useSession,
  useSessionTopics,
  useSessionWords,
} from '@/sessions/queries'
import {
  deleteSession,
  detachTopic,
  detachWord,
  updateSession,
  type StudySession,
} from '@/sessions/sessionsApi'
import { AddTopicsModal, AddWordsModal } from './AddMaterialModals'
import { formatDay, topicCount, wordCount } from './sessionFormat'
import { sessionDetailsSchema } from './sessionForms'

/** /sessions/:id: one lesson with its words and grammar topics (FE-SES-01). */
export function SessionPage() {
  const { id = '' } = useParams()
  const session = useSession(id)

  const back = (
    <ButtonLink to="/sessions" variant="ghost" size="sm" className="-ml-3">
      <ArrowLeft className="size-4" aria-hidden="true" />
      Study sessions
    </ButtonLink>
  )

  if (session.isError && !session.data) {
    return (
      <div className="max-w-3xl space-y-4">
        {back}
        {getStatus(session.error) === 404 ? (
          <EmptyState
            title="Lesson not found"
            description="It may have been deleted. Your other lessons are on the study sessions page."
          />
        ) : (
          <LoadError
            title="Couldn't load this lesson"
            description={describeError(session.error)}
            onRetry={() => void session.refetch()}
            retrying={session.isFetching}
          />
        )}
      </div>
    )
  }
  if (!session.data) return <PageSkeleton label="Loading lesson" cards={3} />

  return (
    <div className="max-w-3xl space-y-6">
      <div>
        {back}
        <LessonHeader session={session.data} />
      </div>
      <WordsSection session={session.data} />
      <TopicsSection session={session.data} />
      <DetailsSection key={session.data.id} session={session.data} />
      <DeleteSection session={session.data} />
    </div>
  )
}

/** Saves a change, shows the result at once and refreshes the rest. */
function useSaveSession(id: string) {
  const queryClient = useQueryClient()
  const invalidate = useInvalidateSessions()
  return async (request: UpdateStudySessionRequest) => {
    const updated = await updateSession(id, request)
    queryClient.setQueryData(sessionKey(id), updated)
    void invalidate()
    return updated
  }
}

function LessonHeader({ session }: { session: StudySession }) {
  const save = useSaveSession(session.id)
  const { toast } = useToast()
  const [busy, setBusy] = useState(false)

  const setActive = async (active: boolean) => {
    setBusy(true)
    try {
      await save({ active })
      toast({
        title: active
          ? `“${session.title}” is now your active lesson`
          : `Ended “${session.title}”`,
        variant: 'success',
      })
    } catch (error) {
      toast({ title: describeError(error), variant: 'error' })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="mt-2 space-y-3">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">
          {session.title}
        </h1>
        {session.active && <Badge variant="primary">Active</Badge>}
      </div>
      <p className="text-fg-muted">
        <time dateTime={session.studiedOn}>{formatDay(session.studiedOn)}</time>
        {' · '}
        {wordCount(session.vocabularyCount)} ·{' '}
        {topicCount(session.grammarTopicCount)}
      </p>
      {session.active ? (
        <Alert variant="info">
          New words and grammar topics join this lesson automatically.{' '}
          <Button
            variant="ghost"
            size="sm"
            loading={busy}
            onClick={() => void setActive(false)}
          >
            End lesson
          </Button>
        </Alert>
      ) : (
        <Button
          variant="secondary"
          size="sm"
          loading={busy}
          onClick={() => void setActive(true)}
        >
          Make it the active lesson
        </Button>
      )}
    </div>
  )
}

function MaterialList({
  empty,
  children,
}: {
  empty: string
  children: ReactNode[]
}) {
  return children.length === 0 ? (
    <p className="text-sm text-fg-muted">{empty}</p>
  ) : (
    <ul className="divide-y divide-border rounded-lg border border-border">
      {children}
    </ul>
  )
}

function RemoveButton({
  label,
  onRemove,
}: {
  label: string
  onRemove: () => Promise<void>
}) {
  const [busy, setBusy] = useState(false)
  return (
    <Button
      variant="ghost"
      size="sm"
      className="px-2"
      aria-label={label}
      loading={busy}
      onClick={async () => {
        setBusy(true)
        try {
          await onRemove()
        } finally {
          setBusy(false)
        }
      }}
    >
      <X className="size-4" aria-hidden="true" />
    </Button>
  )
}

function WordsSection({ session }: { session: StudySession }) {
  const words = useSessionWords(session.id)
  const invalidate = useInvalidateSessions()
  const { toast } = useToast()
  const [adding, setAdding] = useState(false)

  const remove = async (wordId: string, lemma: string) => {
    try {
      await detachWord(session.id, wordId)
      await invalidate()
      toast({
        title: `Removed “${lemma}” from this lesson`,
        variant: 'success',
      })
    } catch (error) {
      toast({ title: describeError(error), variant: 'error' })
    }
  }

  return (
    <SettingsSection
      title="Words"
      description="Removing a word here keeps it in your vocabulary."
      aside={<Badge>{words.data?.length ?? session.vocabularyCount}</Badge>}
    >
      {words.isError && !words.data ? (
        <LoadError
          title="Couldn't load the words"
          description={describeError(words.error)}
          onRetry={() => void words.refetch()}
          retrying={words.isFetching}
        />
      ) : !words.data ? (
        <PageSkeleton label="Loading words" cards={1} />
      ) : (
        <MaterialList empty="No words in this lesson yet.">
          {words.data.map((word) => (
            <li key={word.id} className="flex items-center gap-3 px-3 py-2">
              <span className="min-w-0 flex-1">
                <span className="font-medium text-fg">{word.lemma}</span>
                {word.translation && (
                  <span className="ml-2 text-sm text-fg-muted">
                    {word.translation}
                  </span>
                )}
              </span>
              <RemoveButton
                label={`Remove ${word.lemma} from this lesson`}
                onRemove={() => remove(word.id, word.lemma)}
              />
            </li>
          ))}
        </MaterialList>
      )}
      <Button
        variant="secondary"
        size="sm"
        className="mt-4"
        onClick={() => setAdding(true)}
      >
        <Plus className="size-4" aria-hidden="true" />
        Add words
      </Button>
      {adding && (
        <AddWordsModal
          sessionId={session.id}
          inLesson={new Set(words.data?.map((word) => word.id))}
          onClose={() => setAdding(false)}
        />
      )}
    </SettingsSection>
  )
}

function TopicsSection({ session }: { session: StudySession }) {
  const topics = useSessionTopics(session.id)
  const invalidate = useInvalidateSessions()
  const { toast } = useToast()
  const [adding, setAdding] = useState(false)

  const remove = async (topicId: string, title: string) => {
    try {
      await detachTopic(session.id, topicId)
      await invalidate()
      toast({
        title: `Removed “${title}” from this lesson`,
        variant: 'success',
      })
    } catch (error) {
      toast({ title: describeError(error), variant: 'error' })
    }
  }

  return (
    <SettingsSection
      title="Grammar topics"
      description="Removing a topic here keeps it in your grammar topics."
      aside={<Badge>{topics.data?.length ?? session.grammarTopicCount}</Badge>}
    >
      {topics.isError && !topics.data ? (
        <LoadError
          title="Couldn't load the topics"
          description={describeError(topics.error)}
          onRetry={() => void topics.refetch()}
          retrying={topics.isFetching}
        />
      ) : !topics.data ? (
        <PageSkeleton label="Loading topics" cards={1} />
      ) : (
        <MaterialList empty="No grammar topics in this lesson yet.">
          {topics.data.map((topic) => (
            <li key={topic.id} className="flex items-center gap-3 px-3 py-2">
              <span className="flex min-w-0 flex-1 flex-wrap items-center gap-2">
                <Link
                  to={`/grammar/${topic.id}`}
                  className="font-medium text-fg hover:text-primary hover:underline"
                >
                  {topic.title}
                </Link>
                {topic.cefrLevel && <CefrBadge level={topic.cefrLevel} />}
                <StatusBadge status={topic.status} />
              </span>
              <RemoveButton
                label={`Remove ${topic.title} from this lesson`}
                onRemove={() => remove(topic.id, topic.title)}
              />
            </li>
          ))}
        </MaterialList>
      )}
      <Button
        variant="secondary"
        size="sm"
        className="mt-4"
        onClick={() => setAdding(true)}
      >
        <Plus className="size-4" aria-hidden="true" />
        Add topics
      </Button>
      {adding && (
        <AddTopicsModal
          sessionId={session.id}
          inLesson={new Set(topics.data?.map((topic) => topic.id))}
          onClose={() => setAdding(false)}
        />
      )}
    </SettingsSection>
  )
}

type DetailsInput = z.input<typeof sessionDetailsSchema>
type DetailsValues = z.output<typeof sessionDetailsSchema>

const valuesOf = (session: StudySession): DetailsInput => ({
  title: session.title,
  studiedOn: session.studiedOn,
  notes: session.notes ?? '',
})

function DetailsSection({ session }: { session: StudySession }) {
  const save = useSaveSession(session.id)
  const { toast } = useToast()
  const [formError, setFormError] = useState<string>()
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<DetailsInput, unknown, DetailsValues>({
    resolver: zodResolver(sessionDetailsSchema),
    defaultValues: valuesOf(session),
  })

  const onSubmit = handleSubmit(async (values) => {
    setFormError(undefined)
    const original = valuesOf(session)
    const request: UpdateStudySessionRequest = {}
    if (values.title !== original.title) request.title = values.title
    if (values.studiedOn !== original.studiedOn)
      request.studiedOn = values.studiedOn
    if (values.notes !== original.notes) request.notes = values.notes
    try {
      reset(valuesOf(await save(request)))
      toast({ title: 'Lesson saved', variant: 'success' })
    } catch (error) {
      setFormError(describeError(error))
    }
  })

  return (
    <SettingsSection title="Details">
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        {formError && <Alert variant="danger">{formError}</Alert>}
        <div className="grid gap-4 sm:grid-cols-[2fr_1fr]">
          <Field label="Title" error={errors.title?.message} required>
            <Input autoComplete="off" {...register('title')} />
          </Field>
          <Field label="Date" error={errors.studiedOn?.message}>
            <Input type="date" {...register('studiedOn')} />
          </Field>
        </div>
        <Field label="Notes" error={errors.notes?.message}>
          <Textarea rows={3} {...register('notes')} />
        </Field>
        <div className="flex justify-end">
          <Button type="submit" loading={isSubmitting} disabled={!isDirty}>
            Save lesson
          </Button>
        </div>
      </form>
    </SettingsSection>
  )
}

function DeleteSection({ session }: { session: StudySession }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const invalidate = useInvalidateSessions()
  const { toast } = useToast()
  const [confirming, setConfirming] = useState(false)

  const remove = async () => {
    try {
      await deleteSession(session.id)
    } catch (error) {
      toast({ title: describeError(error), variant: 'error' })
      setConfirming(false)
      return
    }
    toast({ title: `Deleted “${session.title}”`, variant: 'success' })
    void navigate('/sessions', { replace: true })
    // Refetching the deleted lesson would 404 into an error toast: drop it instead
    queryClient.removeQueries({ queryKey: sessionKey(session.id) })
    void invalidate()
  }

  return (
    <SettingsSection
      title="Delete lesson"
      tone="danger"
      description="Deletes the lesson. Its words and grammar topics stay in your collection."
    >
      <Button variant="danger" onClick={() => setConfirming(true)}>
        Delete lesson
      </Button>
      <ConfirmDialog
        open={confirming}
        onCancel={() => setConfirming(false)}
        onConfirm={remove}
        title={`Delete “${session.title}”?`}
        description="The lesson goes; its words and grammar topics stay. This can't be undone."
        confirmLabel="Delete lesson"
        destructive
      />
    </SettingsSection>
  )
}
