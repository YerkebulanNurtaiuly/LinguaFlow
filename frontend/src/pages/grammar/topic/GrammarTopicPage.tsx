import { ArrowLeft, PencilLine } from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { describeError, getStatus } from '@/api/errors'
import {
  Button,
  ButtonLink,
  CefrBadge,
  ConfirmDialog,
  EmptyState,
  LoadError,
  PageSkeleton,
  StatusBadge,
  useToast,
} from '@/components/ui'
import { deleteGrammarTopic } from '@/grammar/grammarApi'
import {
  grammarTopicKey,
  statusHistoryKey,
  useGrammarTopic,
  useInvalidateGrammar,
} from '@/grammar/queries'
import { SettingsSection } from '@/pages/settings/SettingsSection'
import { AiExplanationSection } from './AiExplanationSection'
import { TopicDetailsSection } from './TopicDetailsSection'
import { TopicNotesSection } from './TopicNotesSection'
import { TopicStatusSection } from './TopicStatusSection'

/** /grammar/:id: one grammar topic with its status, details, notes and AI explanation (FE-GRM-02). */
export function GrammarTopicPage() {
  const { id = '' } = useParams()
  const topic = useGrammarTopic(id)
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const invalidate = useInvalidateGrammar()
  const { toast } = useToast()
  const [confirmDelete, setConfirmDelete] = useState(false)

  const back = (
    <ButtonLink to="/grammar" variant="ghost" size="sm" className="-ml-3">
      <ArrowLeft className="size-4" aria-hidden="true" />
      Grammar
    </ButtonLink>
  )

  if (topic.isError && !topic.data) {
    return (
      <div className="max-w-3xl space-y-4">
        {back}
        {getStatus(topic.error) === 404 ? (
          <EmptyState
            title="Topic not found"
            description="It may have been deleted. Your other topics are on the grammar page."
          />
        ) : (
          <LoadError
            title="Couldn't load this topic"
            description={describeError(topic.error)}
            onRetry={() => void topic.refetch()}
            retrying={topic.isFetching}
          />
        )}
      </div>
    )
  }
  if (!topic.data) {
    return <PageSkeleton label="Loading topic" cards={3} />
  }

  const current = topic.data
  const remove = async () => {
    try {
      await deleteGrammarTopic(current.id)
    } catch (error) {
      toast({ title: describeError(error), variant: 'error' })
      setConfirmDelete(false)
      return
    }
    toast({ title: `Deleted “${current.title}”`, variant: 'success' })
    navigate('/grammar', { replace: true })
    // Refetching the deleted topic would 404 into an error toast: drop it instead
    queryClient.removeQueries({ queryKey: grammarTopicKey(current.id) })
    queryClient.removeQueries({ queryKey: statusHistoryKey(current.id) })
    void invalidate()
  }

  return (
    <div className="max-w-3xl space-y-6">
      <div>
        {back}
        <div className="mt-2 flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-semibold tracking-tight">
            {current.title}
          </h1>
          {current.cefrLevel && <CefrBadge level={current.cefrLevel} />}
          <StatusBadge status={current.status} />
        </div>
        {current.description && (
          <p className="mt-2 text-fg-muted">{current.description}</p>
        )}
        <ButtonLink
          to={`/practice/exercises?topic=${current.id}`}
          variant="secondary"
          size="sm"
          className="mt-4"
        >
          <PencilLine className="size-4" aria-hidden="true" />
          Practise with exercises
        </ButtonLink>
      </div>

      <TopicStatusSection topic={current} />
      <TopicNotesSection key={`notes-${current.id}`} topic={current} />
      <AiExplanationSection topic={current} />
      <TopicDetailsSection key={`details-${current.id}`} topic={current} />

      <SettingsSection
        title="Delete topic"
        tone="danger"
        description="Deletes the topic with its notes, status history and progress. Your vocabulary isn't affected."
      >
        <Button variant="danger" onClick={() => setConfirmDelete(true)}>
          Delete topic
        </Button>
      </SettingsSection>

      <ConfirmDialog
        open={confirmDelete}
        onCancel={() => setConfirmDelete(false)}
        onConfirm={remove}
        title={`Delete “${current.title}”?`}
        description="Its notes, status history and progress are deleted too. This can't be undone."
        confirmLabel="Delete topic"
        destructive
      />
    </div>
  )
}
