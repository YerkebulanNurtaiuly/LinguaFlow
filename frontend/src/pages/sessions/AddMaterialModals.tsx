import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useEffect, useState, type ReactNode } from 'react'
import { describeError } from '@/api/errors'
import {
  Badge,
  Button,
  CardSkeleton,
  Field,
  Input,
  LoadError,
  Modal,
  useToast,
} from '@/components/ui'
import { fetchGrammarTopics } from '@/grammar/grammarApi'
import { useInvalidateSessions } from '@/sessions/queries'
import { attachTopics, attachWords } from '@/sessions/sessionsApi'
import { fetchVocabulary } from '@/vocabulary/vocabularyApi'
import { topicCount, wordCount } from './sessionFormat'

/** How many words a search shows; learners narrow it down by typing. */
const WORD_RESULTS = 50

const SEARCH_DELAY_MS = 300

interface Choice {
  id: string
  label: string
  detail?: string
}

interface PickerProps {
  title: string
  description: string
  searchLabel: string
  /** IDs already in the lesson: shown ticked and locked */
  inLesson: Set<string>
  choices: Choice[] | undefined
  loadError: unknown
  onRetry: () => void
  retrying: boolean
  search: string
  onSearch: (text: string) => void
  footnote?: ReactNode
  addLabel: (count: number) => string
  onAdd: (ids: string[]) => Promise<void>
  onClose: () => void
}

/** A searchable checklist for picking material to add to a lesson. */
function MaterialPicker(props: PickerProps) {
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [saving, setSaving] = useState(false)

  const toggle = (id: string) =>
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const add = async () => {
    setSaving(true)
    try {
      await props.onAdd([...selected])
      props.onClose()
    } catch {
      // onAdd reported the failure; the dialog stays open to try again
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open
      onClose={props.onClose}
      size="lg"
      title={props.title}
      description={props.description}
      footer={
        <>
          <Button variant="secondary" onClick={props.onClose}>
            Cancel
          </Button>
          <Button
            onClick={() => void add()}
            loading={saving}
            disabled={selected.size === 0}
          >
            {props.addLabel(selected.size)}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <Field label={props.searchLabel}>
          <Input
            type="search"
            value={props.search}
            onChange={(event) => props.onSearch(event.target.value)}
          />
        </Field>
        {props.loadError ? (
          <LoadError
            title="Couldn't load the list"
            description={describeError(props.loadError)}
            onRetry={props.onRetry}
            retrying={props.retrying}
          />
        ) : !props.choices ? (
          <div role="status" aria-label="Loading">
            <CardSkeleton lines={5} />
          </div>
        ) : props.choices.length === 0 ? (
          <p className="text-sm text-fg-muted">Nothing found.</p>
        ) : (
          <ul className="max-h-[50vh] divide-y divide-border overflow-y-auto rounded-lg border border-border">
            {props.choices.map((choice) => {
              const already = props.inLesson.has(choice.id)
              return (
                <li key={choice.id}>
                  <label className="flex cursor-pointer items-center gap-3 p-3 hover:bg-surface-muted">
                    <input
                      type="checkbox"
                      className="size-4 accent-primary"
                      checked={already || selected.has(choice.id)}
                      disabled={already}
                      onChange={() => toggle(choice.id)}
                    />
                    <span className="min-w-0 flex-1">
                      <span className="font-medium text-fg">
                        {choice.label}
                      </span>
                      {choice.detail && (
                        <span className="ml-2 text-sm text-fg-muted">
                          {choice.detail}
                        </span>
                      )}
                    </span>
                    {already && <Badge>In this lesson</Badge>}
                  </label>
                </li>
              )
            })}
          </ul>
        )}
        {props.footnote}
      </div>
    </Modal>
  )
}

interface AddModalProps {
  sessionId: string
  inLesson: Set<string>
  onClose: () => void
}

/** Pick words from the vocabulary to add to the lesson. */
export function AddWordsModal({ sessionId, inLesson, onClose }: AddModalProps) {
  const [search, setSearch] = useState('')
  const [q, setQ] = useState('')
  const invalidate = useInvalidateSessions()
  const { toast } = useToast()
  // Search once typing pauses, not on every key
  useEffect(() => {
    const timer = setTimeout(() => setQ(search.trim()), SEARCH_DELAY_MS)
    return () => clearTimeout(timer)
  }, [search])
  const words = useQuery({
    queryKey: ['sessions', 'word-picker', q],
    queryFn: ({ signal }) =>
      fetchVocabulary(
        { q: q || undefined, sort: 'lemma,asc', page: 0 },
        signal,
        WORD_RESULTS,
      ),
    placeholderData: keepPreviousData,
  })

  return (
    <MaterialPicker
      title="Add words"
      description="Tick the words you studied in this lesson."
      searchLabel="Find words"
      inLesson={inLesson}
      search={search}
      onSearch={setSearch}
      choices={words.data?.content.map((word) => ({
        id: word.id,
        label: word.lemma,
        detail: word.translation,
      }))}
      loadError={words.isError && !words.data ? words.error : undefined}
      onRetry={() => void words.refetch()}
      retrying={words.isFetching}
      footnote={
        words.data && words.data.totalElements > WORD_RESULTS ? (
          <p className="text-xs text-fg-subtle">
            Showing the first {WORD_RESULTS} of {words.data.totalElements}.
            Search to find others.
          </p>
        ) : undefined
      }
      addLabel={(count) => (count ? `Add ${wordCount(count)}` : 'Add words')}
      onAdd={async (ids) => {
        try {
          await attachWords(sessionId, ids)
        } catch (error) {
          toast({ title: describeError(error), variant: 'error' })
          throw error
        }
        void invalidate()
        toast({ title: `Added ${wordCount(ids.length)}`, variant: 'success' })
      }}
      onClose={onClose}
    />
  )
}

/** Pick grammar topics to add to the lesson. */
export function AddTopicsModal({
  sessionId,
  inLesson,
  onClose,
}: AddModalProps) {
  const [search, setSearch] = useState('')
  const invalidate = useInvalidateSessions()
  const { toast } = useToast()
  const topics = useQuery({
    queryKey: ['sessions', 'topic-picker'],
    queryFn: ({ signal }) => fetchGrammarTopics({}, signal),
  })
  const term = search.trim().toLocaleLowerCase()

  return (
    <MaterialPicker
      title="Add grammar topics"
      description="Tick the topics you studied in this lesson."
      searchLabel="Find topics"
      inLesson={inLesson}
      search={search}
      onSearch={setSearch}
      choices={topics.data
        ?.filter((topic) => topic.title.toLocaleLowerCase().includes(term))
        .map((topic) => ({
          id: topic.id,
          label: topic.title,
          detail: topic.cefrLevel,
        }))}
      loadError={topics.isError ? topics.error : undefined}
      onRetry={() => void topics.refetch()}
      retrying={topics.isFetching}
      addLabel={(count) => (count ? `Add ${topicCount(count)}` : 'Add topics')}
      onAdd={async (ids) => {
        try {
          await attachTopics(sessionId, ids)
        } catch (error) {
          toast({ title: describeError(error), variant: 'error' })
          throw error
        }
        void invalidate()
        toast({ title: `Added ${topicCount(ids.length)}`, variant: 'success' })
      }}
      onClose={onClose}
    />
  )
}
