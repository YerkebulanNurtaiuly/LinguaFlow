import { BookOpen, ChevronLeft, ChevronRight, SearchX } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import { describeError } from '@/api/errors'
import {
  Button,
  ConfirmDialog,
  EmptyState,
  LoadError,
  PageSkeleton,
  useToast,
} from '@/components/ui'
import { cn } from '@/lib/cn'
import {
  useDeleteVocabularyItem,
  useTags,
  useVocabularyList,
} from '@/vocabulary/queries'
import {
  PAGE_SIZE,
  type VocabularyQuery,
  type VocabularyWord,
} from '@/vocabulary/vocabularyApi'
import { VocabularyFilterBar } from './VocabularyFilterBar'
import {
  hasFilters,
  paramsFromQuery,
  queryFromParams,
} from './vocabularyFilters'
import { VocabularyTable } from './VocabularyTable'

const wordCount = (count: number) =>
  `${count.toLocaleString()} ${count === 1 ? 'word' : 'words'}`

/** /vocabulary: the learner's words with search, filters, sorting and paging (FE-VOC-01). */
export function VocabularyPage() {
  const [params, setParams] = useSearchParams()
  const query = useMemo(() => queryFromParams(params), [params])
  const list = useVocabularyList(query)
  const tags = useTags()
  const remove = useDeleteVocabularyItem()
  const { toast } = useToast()
  const [toDelete, setToDelete] = useState<VocabularyWord | null>(null)

  const update = useCallback(
    (changes: Partial<VocabularyQuery>) => {
      setParams(
        (current) => {
          const next = { ...queryFromParams(current), ...changes }
          // Any change but paging starts again at the first page
          if (!('page' in changes)) next.page = 0
          return paramsFromQuery(next)
        },
        { replace: !('page' in changes) },
      )
    },
    [setParams],
  )

  const clearFilters = () =>
    setParams(paramsFromQuery({ sort: query.sort, page: 0 }))

  const data = list.data
  // Deleting the last word of the last page leaves it empty: step back one page
  const pageGone =
    data !== undefined &&
    !list.isPlaceholderData &&
    data.content.length === 0 &&
    query.page > 0
  useEffect(() => {
    if (pageGone) update({ page: Math.max(0, data.totalPages - 1) })
  }, [pageGone, data?.totalPages, update])

  const confirmDelete = async () => {
    if (!toDelete) return
    try {
      await remove.mutateAsync(toDelete.id)
    } catch {
      return // The failure is shown as a toast; the dialog stays open to retry
    }
    toast({ title: `Deleted “${toDelete.lemma}”`, variant: 'success' })
    setToDelete(null)
  }

  const filtered = hasFilters(query)
  const first = data ? data.page * PAGE_SIZE + 1 : 0
  const last = data ? first + data.content.length - 1 : 0

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Vocabulary</h1>
        <p className="mt-2 text-fg-muted">
          {data && !filtered
            ? `${wordCount(data.totalElements)} in your collection.`
            : 'Your words with translations, levels and tags.'}
        </p>
      </div>

      <VocabularyFilterBar
        query={query}
        onChange={update}
        onClear={clearFilters}
        tags={tags.data}
      />

      {list.isError && !data ? (
        <LoadError
          title="Couldn't load your vocabulary"
          description={describeError(list.error)}
          onRetry={() => void list.refetch()}
          retrying={list.isFetching}
        />
      ) : !data ? (
        <PageSkeleton label="Loading vocabulary" cards={1} withHeader={false} />
      ) : data.totalElements === 0 ? (
        filtered ? (
          <EmptyState
            icon={SearchX}
            title="No words match"
            description="Try another search or fewer filters."
            action={
              <Button variant="secondary" onClick={clearFilters}>
                Clear filters
              </Button>
            }
          />
        ) : (
          <EmptyState
            icon={BookOpen}
            title="No words yet"
            description="Words you add will appear here, with their translations, levels and tags."
          />
        )
      ) : (
        <div
          className={cn(
            'space-y-4 transition-opacity',
            list.isPlaceholderData && 'opacity-60',
          )}
          aria-busy={list.isPlaceholderData}
        >
          <VocabularyTable
            words={data.content}
            caption={`Your vocabulary, ${first} to ${last} of ${data.totalElements}`}
            onDelete={setToDelete}
          />
          <nav
            aria-label="Pagination"
            className="flex flex-wrap items-center justify-between gap-3 text-sm text-fg-muted"
          >
            <p aria-live="polite">
              {first}–{last} of {wordCount(data.totalElements)}
            </p>
            {data.totalPages > 1 && (
              <div className="flex items-center gap-2">
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={query.page === 0}
                  onClick={() => update({ page: query.page - 1 })}
                >
                  <ChevronLeft className="size-4" aria-hidden="true" />
                  Previous
                </Button>
                <span>
                  Page {data.page + 1} of {data.totalPages}
                </span>
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={data.page + 1 >= data.totalPages}
                  onClick={() => update({ page: query.page + 1 })}
                >
                  Next
                  <ChevronRight className="size-4" aria-hidden="true" />
                </Button>
              </div>
            )}
          </nav>
        </div>
      )}

      <ConfirmDialog
        open={toDelete !== null}
        onCancel={() => setToDelete(null)}
        onConfirm={confirmDelete}
        title={toDelete ? `Delete “${toDelete.lemma}”?` : ''}
        description="Its examples, flashcards and progress are deleted too. This can't be undone."
        confirmLabel="Delete word"
        destructive
      />
    </div>
  )
}
