import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  ArrowRight,
  BookOpen,
  Flame,
  Layers,
  Shuffle,
  Target,
  TriangleAlert,
} from 'lucide-react'
import type { ReactNode } from 'react'
import { Link, useNavigate } from 'react-router'
import { useAuth } from '@/auth/authContext'
import { describeError } from '@/api/errors'
import {
  Badge,
  ButtonLink,
  Card,
  CardContent,
  CardSkeleton,
  LoadError,
} from '@/components/ui'
import { EXERCISES_QUERY_KEY } from '@/exercises/queries'
import { MODE_LABELS, plural } from '@/pages/practice/practiceText'
import {
  startPracticeSession,
  type ListedPracticeSession,
  type PracticeMode,
} from '@/practice/practiceApi'
import {
  PRACTICE_QUERY_KEY,
  usePracticeOverview,
  useRecentPracticeSessions,
} from '@/practice/queries'
import { startWeakSpots, type WeakPoint } from '@/progress/progressApi'
import { useProgressStats, useWeakPoints } from '@/progress/queries'
import { useReviewStats } from '@/reviews/queries'
import { reasonText } from './dashboardText'
import { GoalRing } from './GoalRing'

/**
 * /dashboard (FE-PRG-01): what's due today with the daily goal ring and the streak,
 * one-click starts for each practice mode and for the weak spots, recent practice
 * sessions, and the weakest grammar topics.
 */
export function DashboardPage() {
  const { user } = useAuth()
  return (
    <div className="max-w-5xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Dashboard</h1>
        {user && (
          <p className="mt-2 text-fg-muted">
            Welcome back, {user.displayName}.
          </p>
        )}
      </div>
      <Today />
      <QuickStart />
      <div className="grid gap-6 lg:grid-cols-2">
        <RecentSessions />
        <WeakTopics />
      </div>
    </div>
  )
}

function Today() {
  const reviews = useReviewStats()
  const progress = useProgressStats()
  if (
    (reviews.isError && !reviews.data) ||
    (progress.isError && !progress.data)
  ) {
    return (
      <LoadError
        title="Couldn't load today's figures"
        description={describeError(reviews.error ?? progress.error)}
        onRetry={() => {
          void reviews.refetch()
          void progress.refetch()
        }}
        retrying={reviews.isFetching || progress.isFetching}
      />
    )
  }
  if (!reviews.data || !progress.data) {
    return (
      <div className="grid gap-4 sm:grid-cols-3">
        <CardSkeleton lines={2} />
        <CardSkeleton lines={2} />
        <CardSkeleton lines={2} />
      </div>
    )
  }
  const stats = reviews.data
  const due = stats.dueToday + stats.newAvailable
  return (
    <section aria-label="Today" className="grid gap-4 sm:grid-cols-3">
      <Card>
        <CardContent className="flex h-full flex-col">
          <p className="flex items-center gap-2 text-sm text-fg-muted">
            <Layers className="size-4" aria-hidden="true" />
            Due today
          </p>
          <p className="mt-2 text-2xl font-semibold text-fg">
            {plural(due, 'card')}
          </p>
          <p className="mt-1 text-sm text-fg-subtle">
            {stats.dueToday} to review, {stats.newAvailable} new
          </p>
          <div className="mt-auto pt-4">
            {due > 0 ? (
              <ButtonLink to="/flashcards" size="sm">
                Start review
              </ButtonLink>
            ) : (
              <p className="text-sm text-fg-muted">All caught up.</p>
            )}
          </div>
        </CardContent>
      </Card>
      <Card>
        <CardContent className="flex items-center gap-4">
          <GoalRing done={stats.reviewedToday} goal={stats.dailyReviewGoal} />
          <div>
            <p className="flex items-center gap-2 text-sm text-fg-muted">
              <Target className="size-4" aria-hidden="true" />
              Daily goal
            </p>
            <p className="mt-1 font-semibold text-fg">
              {stats.reviewedToday} of {stats.dailyReviewGoal} reviews
            </p>
            <p className="mt-1 text-sm text-fg-subtle">
              {stats.reviewedToday >= stats.dailyReviewGoal
                ? 'Reached for today'
                : `${stats.dailyReviewGoal - stats.reviewedToday} to go`}
            </p>
          </div>
        </CardContent>
      </Card>
      <Card>
        <CardContent>
          <p className="flex items-center gap-2 text-sm text-fg-muted">
            <Flame className="size-4" aria-hidden="true" />
            Streak
          </p>
          <p className="mt-2 text-2xl font-semibold text-fg">
            {plural(progress.data.currentStreak, 'day')}
          </p>
          <p className="mt-1 text-sm text-fg-subtle">
            Longest: {plural(progress.data.longestStreak, 'day')}. Any review or
            exercise counts.
          </p>
        </CardContent>
      </Card>
    </section>
  )
}

const QUICK_MODES: {
  mode: PracticeMode
  icon: typeof Shuffle
  hint: string
}[] = [
  { mode: 'CURRENT_LESSON', icon: BookOpen, hint: 'Your latest lesson' },
  { mode: 'MIXED', icon: Shuffle, hint: 'New and older material' },
  { mode: 'FULL_REVISION', icon: Layers, hint: 'Everything, weakest first' },
]

function QuickStart() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const overview = usePracticeOverview()
  const start = useMutation({
    meta: { errorTitle: "Couldn't start practising" },
    mutationFn: (mode: PracticeMode) =>
      startPracticeSession({
        mode,
        length: overview.data?.practiceSessionLength ?? 20,
        includeFlashcards: true,
        includeExercises: true,
      }),
    onSuccess: async (session) => {
      await queryClient.invalidateQueries({ queryKey: PRACTICE_QUERY_KEY })
      void navigate(`/practice/sessions/${session.id}`)
    },
  })
  const weakSpots = useMutation({
    meta: { errorTitle: "Couldn't start practising" },
    mutationFn: startWeakSpots,
    onSuccess: async (started) => {
      await queryClient.invalidateQueries({ queryKey: EXERCISES_QUERY_KEY })
      void navigate(`/practice/exercises/${started.exerciseSetId}`)
    },
  })
  const busy = start.isPending || weakSpots.isPending
  const noLesson = overview.data !== undefined && !overview.data.currentLesson
  return (
    <section aria-labelledby="quick-start-heading" className="space-y-3">
      <div className="flex items-center justify-between gap-2">
        <h2 id="quick-start-heading" className="text-lg font-semibold">
          Practise now
        </h2>
        <Link to="/practice" className="text-sm text-primary hover:underline">
          More options
        </Link>
      </div>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {QUICK_MODES.map(({ mode, icon: Icon, hint }) => (
          <QuickButton
            key={mode}
            icon={<Icon className="size-5 text-primary" aria-hidden="true" />}
            label={MODE_LABELS[mode]}
            hint={
              mode === 'CURRENT_LESSON' && noLesson ? 'No lesson yet' : hint
            }
            disabled={busy || (mode === 'CURRENT_LESSON' && noLesson)}
            pending={start.isPending && start.variables === mode}
            onClick={() => start.mutate(mode)}
          />
        ))}
        <QuickButton
          icon={<Target className="size-5 text-primary" aria-hidden="true" />}
          label="Weak spots"
          hint="Exercises on your mistakes"
          disabled={busy}
          pending={weakSpots.isPending}
          onClick={() => weakSpots.mutate()}
        />
      </div>
    </section>
  )
}

function QuickButton({
  icon,
  label,
  hint,
  disabled,
  pending,
  onClick,
}: {
  icon: ReactNode
  label: string
  hint: string
  disabled: boolean
  pending: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className="flex items-center gap-3 rounded-xl border border-border bg-surface p-4 text-left shadow-sm transition-colors hover:bg-surface-muted disabled:cursor-not-allowed disabled:opacity-60"
    >
      {icon}
      <span className="min-w-0 flex-1">
        <span className="block font-semibold text-fg">
          {pending ? 'Starting…' : label}
        </span>
        <span className="block text-sm text-fg-muted">{hint}</span>
      </span>
    </button>
  )
}

const STATUS_LABELS: Record<ListedPracticeSession['status'], string> = {
  IN_PROGRESS: 'In progress',
  COMPLETED: 'Done',
  ABANDONED: 'Ended',
}

function RecentSessions() {
  const sessions = useRecentPracticeSessions()
  return (
    <section aria-labelledby="recent-heading" className="space-y-3">
      <h2 id="recent-heading" className="text-lg font-semibold">
        Recent practice
      </h2>
      {sessions.isError && !sessions.data ? (
        <LoadError
          title="Couldn't load your sessions"
          description={describeError(sessions.error)}
          onRetry={() => void sessions.refetch()}
          retrying={sessions.isFetching}
        />
      ) : !sessions.data ? (
        <CardSkeleton lines={3} />
      ) : sessions.data.length === 0 ? (
        <p className="rounded-xl border border-border bg-surface p-4 text-fg-muted">
          No practice sessions yet. Start one above.
        </p>
      ) : (
        <ul className="space-y-2">
          {sessions.data.map((session) => (
            <li key={session.id}>
              <Link
                to={`/practice/sessions/${session.id}`}
                className="flex items-center gap-3 rounded-lg border border-border bg-surface px-4 py-3 hover:bg-surface-muted"
              >
                <span className="min-w-0 flex-1">
                  <span className="block font-medium text-fg">
                    {MODE_LABELS[session.mode]}
                  </span>
                  <span className="block text-sm text-fg-muted">
                    {formatDate(session.startedAt)} · {session.answered} of{' '}
                    {plural(session.items, 'item')}
                  </span>
                </span>
                <Badge
                  variant={session.status === 'COMPLETED' ? 'success' : 'info'}
                >
                  {STATUS_LABELS[session.status]}
                </Badge>
                <ArrowRight
                  className="size-4 text-fg-muted"
                  aria-hidden="true"
                />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function WeakTopics() {
  const weak = useWeakPoints()
  return (
    <section aria-labelledby="weak-heading" className="space-y-3">
      <h2 id="weak-heading" className="text-lg font-semibold">
        Weak topics
      </h2>
      {weak.isError && !weak.data ? (
        <LoadError
          title="Couldn't load your weak topics"
          description={describeError(weak.error)}
          onRetry={() => void weak.refetch()}
          retrying={weak.isFetching}
        />
      ) : !weak.data ? (
        <CardSkeleton lines={3} />
      ) : weak.data.grammarTopics.length === 0 ? (
        <p className="rounded-xl border border-border bg-surface p-4 text-fg-muted">
          Nothing weak yet. Weak topics show up here once you have practised
          some exercises.
        </p>
      ) : (
        <ul className="space-y-2">
          {weak.data.grammarTopics.map((topic) => (
            <WeakTopic key={topic.id} topic={topic} />
          ))}
        </ul>
      )}
    </section>
  )
}

function WeakTopic({ topic }: { topic: WeakPoint }) {
  return (
    <li className="rounded-lg border border-border bg-surface px-4 py-3">
      <div className="flex items-center justify-between gap-3">
        <Link
          to={`/grammar/${topic.id}`}
          className="font-medium text-fg hover:underline"
        >
          {topic.name}
        </Link>
        <span className="flex items-center gap-1 text-sm text-fg-muted">
          <TriangleAlert className="size-4" aria-hidden="true" />
          {Math.round(topic.score * 100)}% weak
        </span>
      </div>
      {topic.reasons.length > 0 && (
        <p className="mt-1 text-sm text-fg-muted">
          {topic.reasons.map(reasonText).join(' · ')}
        </p>
      )}
    </li>
  )
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, {
    day: 'numeric',
    month: 'short',
  })
}
