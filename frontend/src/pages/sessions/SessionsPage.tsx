import { CalendarDays, Plus } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { describeError } from '@/api/errors'
import {
  Alert,
  Badge,
  Button,
  ButtonLink,
  EmptyState,
  LoadError,
  PageSkeleton,
  useToast,
} from '@/components/ui'
import {
  useActiveSession,
  useInvalidateSessions,
  useTimeline,
} from '@/sessions/queries'
import {
  updateSession,
  type StudySession,
  type TimelineMonth,
} from '@/sessions/sessionsApi'
import { NewSessionModal } from './NewSessionModal'
import {
  formatDay,
  formatMonth,
  lessonCount,
  topicCount,
  wordCount,
} from './sessionFormat'

/** /sessions: the learner's lessons as a timeline, and the active lesson (FE-SES-01). */
export function SessionsPage() {
  const timeline = useTimeline()
  const [creating, setCreating] = useState(false)

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">
            Study sessions
          </h1>
          <p className="mt-2 text-fg-muted">
            {timeline.data && timeline.data.sessionCount > 0
              ? `${lessonCount(timeline.data.sessionCount)} · ${wordCount(timeline.data.vocabularyCount)} · ${topicCount(timeline.data.grammarTopicCount)}`
              : 'Your lessons and what you studied in each.'}
          </p>
        </div>
        <Button onClick={() => setCreating(true)}>
          <Plus className="size-4" aria-hidden="true" />
          New lesson
        </Button>
      </div>

      <ActiveLessonBanner />

      {timeline.isError && !timeline.data ? (
        <LoadError
          title="Couldn't load your lessons"
          description={describeError(timeline.error)}
          onRetry={() => void timeline.refetch()}
          retrying={timeline.isFetching}
        />
      ) : !timeline.data ? (
        <PageSkeleton label="Loading lessons" cards={2} />
      ) : timeline.data.months.length === 0 ? (
        <EmptyState
          icon={CalendarDays}
          title="No lessons yet"
          description="Create a lesson for each class or study day. Words and grammar topics you add while it's active are collected in it."
          action={
            <Button onClick={() => setCreating(true)}>
              Create your first lesson
            </Button>
          }
        />
      ) : (
        <div className="space-y-8">
          {timeline.data.months.map((month) => (
            <MonthSection key={month.month} month={month} />
          ))}
        </div>
      )}

      {creating && <NewSessionModal onClose={() => setCreating(false)} />}
    </div>
  )
}

function ActiveLessonBanner() {
  const active = useActiveSession()
  const invalidate = useInvalidateSessions()
  const { toast } = useToast()
  const [ending, setEnding] = useState(false)
  const session = active.data
  if (!session) return null

  const end = async () => {
    setEnding(true)
    try {
      await updateSession(session.id, { active: false })
      await invalidate()
      toast({ title: `Ended “${session.title}”`, variant: 'success' })
    } catch (error) {
      toast({ title: describeError(error), variant: 'error' })
    } finally {
      setEnding(false)
    }
  }

  return (
    <Alert variant="info" title={`“${session.title}” is your active lesson`}>
      <p>New words and grammar topics join it automatically.</p>
      <div className="mt-3 flex flex-wrap gap-2">
        <ButtonLink
          to={`/sessions/${session.id}`}
          variant="secondary"
          size="sm"
        >
          Open lesson
        </ButtonLink>
        <Button variant="ghost" size="sm" loading={ending} onClick={end}>
          End lesson
        </Button>
      </div>
    </Alert>
  )
}

function MonthSection({ month }: { month: TimelineMonth }) {
  const headingId = `sessions-${month.month}`
  return (
    <section aria-labelledby={headingId} className="space-y-3">
      <h2
        id={headingId}
        className="flex flex-wrap items-baseline gap-x-2 text-lg font-semibold"
      >
        {formatMonth(month.month)}
        <span className="text-sm font-normal text-fg-muted">
          {lessonCount(month.sessionCount)} · {wordCount(month.vocabularyCount)}{' '}
          · {topicCount(month.grammarTopicCount)}
        </span>
      </h2>
      <ol className="space-y-3 border-l-2 border-border pl-4">
        {month.sessions.map((session) => (
          <LessonCard key={session.id} session={session} />
        ))}
      </ol>
    </section>
  )
}

function LessonCard({ session }: { session: StudySession }) {
  return (
    <li className="rounded-xl border border-border bg-surface p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h3 className="font-medium text-fg">
            <Link
              to={`/sessions/${session.id}`}
              className="rounded hover:text-primary hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
            >
              {session.title}
            </Link>
          </h3>
          <p className="text-sm text-fg-muted">
            <time dateTime={session.studiedOn}>
              {formatDay(session.studiedOn)}
            </time>
            {' · '}
            {wordCount(session.vocabularyCount)} ·{' '}
            {topicCount(session.grammarTopicCount)}
          </p>
        </div>
        {session.active && <Badge variant="primary">Active</Badge>}
      </div>
      {session.notes && (
        <p className="mt-2 line-clamp-2 text-sm text-fg-muted">
          {session.notes}
        </p>
      )}
    </li>
  )
}
