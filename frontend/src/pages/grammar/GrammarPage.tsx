import { GraduationCap, Plus, Search, SearchX, X } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { describeError } from '@/api/errors'
import {
  Button,
  EmptyState,
  Field,
  Input,
  LoadError,
  PageSkeleton,
  Select,
  StatusBadge,
} from '@/components/ui'
import type {
  CefrLevel,
  GrammarTopic,
  GrammarTopicFilter,
  KnowledgeStatus,
} from '@/grammar/grammarApi'
import { useGrammarTopics } from '@/grammar/queries'
import { CEFR_LEVELS } from '@/languages/languagesApi'
import { cn } from '@/lib/cn'
import { KNOWLEDGE_STATUSES } from '@/vocabulary/vocabularyApi'
import { AddTopicModal, type AddTopicTab } from './AddTopicModal'
import { LEVEL_NAMES, STATUS_LABELS, topicCount } from './grammarLabels'

const SEARCH_DELAY_MS = 300

const oneOf = <T extends string>(values: readonly T[], raw: string | null) =>
  raw !== null && (values as readonly string[]).includes(raw)
    ? (raw as T)
    : undefined

/** Filters live in the URL (?q=&status=&cefrLevel=) like the vocabulary list's. */
function filterFromParams(params: URLSearchParams): GrammarTopicFilter {
  return {
    q: params.get('q')?.trim() || undefined,
    status: oneOf(KNOWLEDGE_STATUSES, params.get('status')),
    cefrLevel: oneOf(CEFR_LEVELS, params.get('cefrLevel')),
  }
}

function paramsFromFilter(filter: GrammarTopicFilter): URLSearchParams {
  const params = new URLSearchParams()
  if (filter.q) params.set('q', filter.q)
  if (filter.status) params.set('status', filter.status)
  if (filter.cefrLevel) params.set('cefrLevel', filter.cefrLevel)
  return params
}

interface Group {
  key: string
  heading: string
  topics: GrammarTopic[]
}

/** Topics by CEFR level, easiest first; topics without a level come last. */
function groupByLevel(topics: GrammarTopic[]): Group[] {
  const groups: Group[] = CEFR_LEVELS.map((level) => ({
    key: level,
    heading: `${level} · ${LEVEL_NAMES[level]}`,
    topics: topics.filter((topic) => topic.cefrLevel === level),
  }))
  groups.push({
    key: 'none',
    heading: 'No level set',
    topics: topics.filter((topic) => topic.cefrLevel === undefined),
  })
  return groups.filter((group) => group.topics.length > 0)
}

/** /grammar: the learner's grammar topics grouped by level (FE-GRM-01). */
export function GrammarPage() {
  const [params, setParams] = useSearchParams()
  const filter = useMemo(() => filterFromParams(params), [params])
  const topics = useGrammarTopics(filter)
  const [adding, setAdding] = useState<AddTopicTab | null>(null)

  const update = useCallback(
    (changes: Partial<GrammarTopicFilter>) =>
      setParams(
        (current) =>
          paramsFromFilter({ ...filterFromParams(current), ...changes }),
        { replace: true },
      ),
    [setParams],
  )
  const filtered = Boolean(filter.q || filter.status || filter.cefrLevel)
  const clear = () => setParams(new URLSearchParams())

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Grammar</h1>
          <p className="mt-2 text-fg-muted">
            {topics.data && !filtered
              ? `${topicCount(topics.data.length)} you're working on.`
              : 'The grammar topics you study and how well you know them.'}
          </p>
        </div>
        <Button onClick={() => setAdding('catalog')}>
          <Plus className="size-4" aria-hidden="true" />
          Add topic
        </Button>
      </div>

      <FilterBar
        filter={filter}
        onChange={update}
        onClear={clear}
        filtered={filtered}
      />

      {topics.isError && !topics.data ? (
        <LoadError
          title="Couldn't load your grammar topics"
          description={describeError(topics.error)}
          onRetry={() => void topics.refetch()}
          retrying={topics.isFetching}
        />
      ) : !topics.data ? (
        <PageSkeleton label="Loading grammar topics" cards={2} />
      ) : topics.data.length === 0 ? (
        filtered ? (
          <EmptyState
            icon={SearchX}
            title="No topics match"
            description="Try another search or fewer filters."
            action={
              <Button variant="secondary" onClick={clear}>
                Clear filters
              </Button>
            }
          />
        ) : (
          <EmptyState
            icon={GraduationCap}
            title="No grammar topics yet"
            description="Pick topics from the suggestions for your level, or add your own."
            action={
              <Button onClick={() => setAdding('catalog')}>
                Browse suggestions
              </Button>
            }
          />
        )
      ) : (
        <div
          className={cn(
            'space-y-8 transition-opacity',
            topics.isPlaceholderData && 'opacity-60',
          )}
          aria-busy={topics.isPlaceholderData}
        >
          {groupByLevel(topics.data).map((group) => (
            <TopicGroup key={group.key} group={group} />
          ))}
        </div>
      )}

      {adding && (
        <AddTopicModal initialTab={adding} onClose={() => setAdding(null)} />
      )}
    </div>
  )
}

function TopicGroup({ group }: { group: Group }) {
  const headingId = `grammar-level-${group.key}`
  return (
    <section aria-labelledby={headingId} className="space-y-3">
      <h2
        id={headingId}
        className="flex items-baseline gap-2 text-lg font-semibold"
      >
        {group.heading}
        <span className="text-sm font-normal text-fg-muted">
          {topicCount(group.topics.length)}
        </span>
      </h2>
      <ul className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {group.topics.map((topic) => (
          <li
            key={topic.id}
            className="flex flex-col gap-2 rounded-xl border border-border bg-surface p-4"
          >
            <div className="flex items-start justify-between gap-3">
              <h3 className="font-medium text-fg">
                <Link
                  to={`/grammar/${topic.id}`}
                  className="rounded hover:text-primary hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
                >
                  {topic.title}
                </Link>
              </h3>
              <StatusBadge status={topic.status} />
            </div>
            {topic.description && (
              <p className="line-clamp-2 text-sm text-fg-muted">
                {topic.description}
              </p>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}

interface FilterBarProps {
  filter: GrammarTopicFilter
  onChange: (changes: Partial<GrammarTopicFilter>) => void
  onClear: () => void
  filtered: boolean
}

function FilterBar({ filter, onChange, onClear, filtered }: FilterBarProps) {
  const [search, setSearch] = useState(filter.q ?? '')
  const [syncedQ, setSyncedQ] = useState(filter.q)
  const [sentQ, setSentQ] = useState(filter.q)

  // The URL changed from outside (Clear filters, back button): show its search text
  if (filter.q !== syncedQ) {
    setSyncedQ(filter.q)
    if (filter.q !== sentQ) setSearch(filter.q ?? '')
  }

  useEffect(() => {
    const value = search.trim() || undefined
    if (value === filter.q) return
    const timer = setTimeout(() => {
      setSentQ(value)
      onChange({ q: value })
    }, SEARCH_DELAY_MS)
    return () => clearTimeout(timer)
  }, [search, filter.q, onChange])

  return (
    <div className="space-y-4 rounded-xl border border-border bg-surface p-4">
      <div className="grid gap-4 sm:grid-cols-3">
        <div className="relative">
          <Field label="Search">
            <Input
              type="search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Title or description"
              className="pl-9"
              maxLength={200}
            />
          </Field>
          <Search
            className="pointer-events-none absolute bottom-3 left-3 size-4 text-fg-subtle"
            aria-hidden="true"
          />
        </div>
        <Field label="Level">
          <Select
            value={filter.cefrLevel ?? ''}
            placeholder="Any level"
            options={CEFR_LEVELS.map((level) => ({
              value: level,
              label: `${level} · ${LEVEL_NAMES[level]}`,
            }))}
            onChange={(event) =>
              onChange({
                cefrLevel: (event.target.value || undefined) as
                  CefrLevel | undefined,
              })
            }
          />
        </Field>
        <Field label="Status">
          <Select
            value={filter.status ?? ''}
            placeholder="Any status"
            options={KNOWLEDGE_STATUSES.map((status) => ({
              value: status,
              label: STATUS_LABELS[status],
            }))}
            onChange={(event) =>
              onChange({
                status: (event.target.value || undefined) as
                  KnowledgeStatus | undefined,
              })
            }
          />
        </Field>
      </div>
      {filtered && (
        <Button variant="ghost" size="sm" onClick={onClear}>
          <X className="size-4" aria-hidden="true" />
          Clear filters
        </Button>
      )}
    </div>
  )
}
