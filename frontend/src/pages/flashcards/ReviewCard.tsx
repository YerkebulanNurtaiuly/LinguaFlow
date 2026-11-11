import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Button, Input } from '@/components/ui'
import { cn } from '@/lib/cn'
import {
  checkAnswer,
  formatInterval,
  splitArticle,
  type AnswerCheck,
} from '@/reviews/reviewFormat'
import type { QueueItem, ReviewGrade } from '@/reviews/reviewsApi'

const ARTICLE_COLORS = {
  der: 'text-article-der',
  die: 'text-article-die',
  das: 'text-article-das',
} as const

/** German text with a leading article in its gender's colour (der, die, das). */
export function GermanText({ text }: { text: string }) {
  const { article, rest } = splitArticle(text)
  if (!article) return <>{text}</>
  return (
    <>
      <span className={cn('font-semibold', ARTICLE_COLORS[article])}>
        {article}
      </span>{' '}
      {rest}
    </>
  )
}

const GRADES: {
  grade: ReviewGrade
  label: string
  key: string
  preview: keyof QueueItem['previews']
  className: string
}[] = [
  {
    grade: 'AGAIN',
    label: 'Again',
    key: '1',
    preview: 'again',
    className: 'text-danger-soft-fg',
  },
  {
    grade: 'HARD',
    label: 'Hard',
    key: '2',
    preview: 'hard',
    className: 'text-warning-soft-fg',
  },
  {
    grade: 'GOOD',
    label: 'Good',
    key: '3',
    preview: 'good',
    className: 'text-success-soft-fg',
  },
  {
    grade: 'EASY',
    label: 'Easy',
    key: '4',
    preview: 'easy',
    className: 'text-info-soft-fg',
  },
]

const SUGGESTED: Record<AnswerCheck, ReviewGrade> = {
  correct: 'GOOD',
  article: 'HARD',
  wrong: 'AGAIN',
}

const CHECK_MESSAGES: Record<AnswerCheck, { text: string; className: string }> =
  {
    correct: {
      text: 'Correct',
      className: 'bg-success-soft text-success-soft-fg',
    },
    article: {
      text: 'Almost: check the article',
      className: 'bg-warning-soft text-warning-soft-fg',
    },
    wrong: {
      text: 'Not quite',
      className: 'bg-danger-soft text-danger-soft-fg',
    },
  }

function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  return (
    target.isContentEditable ||
    ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)
  )
}

export interface ReviewCardProps {
  item: QueueItem
  /** Ask for the answer to be typed before it's shown. */
  typing: boolean
  submitting: boolean
  onGrade: (grade: ReviewGrade) => void
}

/**
 * One flashcard under review (FE-FC-01): the question, then (Space, or after typing the
 * answer) the answer with plural, verb forms and examples, and four grade buttons
 * showing when each grade brings the card back (keys 1–4). Remount it per card.
 */
export function ReviewCard({
  item,
  typing,
  submitting,
  onGrade,
}: ReviewCardProps) {
  const { card, previews } = item
  const { content } = card
  const [flipped, setFlipped] = useState(false)
  const [typed, setTyped] = useState('')
  const [check, setCheck] = useState<AnswerCheck>()
  const [now] = useState(() => Date.now())
  const backRef = useRef<HTMLDivElement>(null)
  const germanFront = card.direction === 'TARGET_TO_NATIVE'

  const reveal = useCallback(() => {
    setFlipped(true)
    // Read the answer out, and keep keys 1–4 away from the input
    requestAnimationFrame(() => backRef.current?.focus())
  }, [])

  const submitTyped = (event: FormEvent) => {
    event.preventDefault()
    setCheck(checkAnswer(typed, content.back))
    reveal()
  }

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.repeat || event.ctrlKey || event.metaKey || event.altKey) return
      if (isTypingTarget(event.target)) return
      if (!flipped) {
        if (event.key === ' ' || event.key === 'Enter') {
          // A focused button handles its own Space and Enter
          if (event.target instanceof HTMLButtonElement) return
          event.preventDefault()
          reveal()
        }
        return
      }
      const choice = GRADES.find((g) => g.key === event.key)
      if (choice && !submitting) {
        event.preventDefault()
        onGrade(choice.grade)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [flipped, submitting, onGrade, reveal])

  const directionLabel = germanFront ? 'German → English' : 'English → German'
  const suggested = check ? SUGGESTED[check] : undefined

  return (
    <div className="space-y-4">
      <div className="[perspective:1200px]">
        <div
          className={cn(
            'grid transition-transform duration-500 [transform-style:preserve-3d] motion-reduce:transition-none',
            flipped && '[transform:rotateY(180deg)]',
          )}
        >
          {/* Front */}
          <section
            aria-label="Question"
            aria-hidden={flipped}
            inert={flipped}
            className="flex min-h-72 flex-col items-center justify-center gap-4 rounded-xl border border-border bg-surface p-6 text-center shadow-sm [backface-visibility:hidden] [grid-area:1/1]"
          >
            <p className="text-xs font-medium tracking-wide text-fg-subtle uppercase">
              {directionLabel}
            </p>
            <p className="text-3xl font-semibold break-words text-fg sm:text-4xl">
              {germanFront ? (
                <GermanText text={content.front} />
              ) : (
                content.front
              )}
            </p>
            {content.hint && <p className="text-fg-muted">({content.hint})</p>}
            {typing ? (
              <form
                onSubmit={submitTyped}
                className="flex w-full max-w-sm flex-col gap-2 sm:flex-row"
              >
                <Input
                  aria-label="Your answer"
                  autoFocus
                  autoComplete="off"
                  autoCapitalize="off"
                  spellCheck={false}
                  value={typed}
                  onChange={(event) => setTyped(event.target.value)}
                  placeholder={
                    germanFront ? 'Type the meaning' : 'Type the German word'
                  }
                />
                <Button type="submit">Check</Button>
              </form>
            ) : (
              <Button variant="secondary" onClick={reveal}>
                Show answer
                <kbd className="ml-1 rounded border border-border px-1.5 text-xs text-fg-subtle">
                  Space
                </kbd>
              </Button>
            )}
          </section>

          {/* Back */}
          <section
            aria-label="Answer"
            aria-hidden={!flipped}
            inert={!flipped}
            className="flex min-h-72 flex-col items-center justify-center gap-3 rounded-xl border border-border bg-surface p-6 text-center shadow-sm [backface-visibility:hidden] [grid-area:1/1] [transform:rotateY(180deg)]"
          >
            <div
              ref={backRef}
              tabIndex={-1}
              // Focused only so screen readers read the answer; the global focus ring
              // (unlayered CSS) would win over a utility class
              style={{ outline: 'none' }}
              className="flex w-full flex-col items-center gap-3"
            >
              <p className="text-fg-muted">
                {germanFront ? (
                  <GermanText text={content.front} />
                ) : (
                  content.front
                )}
              </p>
              <p className="text-3xl font-semibold break-words text-fg sm:text-4xl">
                {germanFront ? (
                  content.back
                ) : (
                  <GermanText text={content.back} />
                )}
              </p>
              {check && (
                <p
                  className={cn(
                    'rounded-md px-3 py-1 text-sm font-medium',
                    CHECK_MESSAGES[check].className,
                  )}
                >
                  {CHECK_MESSAGES[check].text}
                  {check !== 'correct' && typed.trim() && (
                    <span className="font-normal">
                      {' '}
                      · you typed “{typed.trim()}”
                    </span>
                  )}
                </p>
              )}
              <WordDetails item={item} />
            </div>
          </section>
        </div>
      </div>

      {flipped && (
        <div
          role="group"
          aria-label="How well did you know it?"
          className="grid grid-cols-2 gap-2 sm:grid-cols-4"
        >
          {GRADES.map((g) => (
            <GradeButton
              key={g.grade}
              label={g.label}
              shortcut={g.key}
              interval={formatInterval(previews[g.preview], now)}
              className={g.className}
              suggested={suggested === g.grade}
              disabled={submitting}
              onClick={() => onGrade(g.grade)}
            />
          ))}
        </div>
      )}
    </div>
  )
}

function WordDetails({ item }: { item: QueueItem }) {
  const { content } = item.card
  const hasForms = content.plural || content.praeteritum || content.perfect
  return (
    <>
      {hasForms && (
        <dl className="flex flex-wrap justify-center gap-x-5 gap-y-1 text-sm">
          {content.plural && (
            <div className="flex gap-1.5">
              <dt className="text-fg-subtle">Plural</dt>
              <dd className="text-fg">{content.plural}</dd>
            </div>
          )}
          {content.praeteritum && (
            <div className="flex gap-1.5">
              <dt className="text-fg-subtle">Präteritum</dt>
              <dd className="text-fg">{content.praeteritum}</dd>
            </div>
          )}
          {content.perfect && (
            <div className="flex gap-1.5">
              <dt className="text-fg-subtle">Perfekt</dt>
              <dd className="text-fg">{content.perfect}</dd>
            </div>
          )}
        </dl>
      )}
      {content.examples.length > 0 && (
        <ul className="w-full max-w-md space-y-2 border-t border-border pt-3 text-left text-sm">
          {content.examples.map((example) => (
            <li key={example.sentence}>
              <p className="text-fg">{example.sentence}</p>
              {example.translation && (
                <p className="text-fg-muted">{example.translation}</p>
              )}
            </li>
          ))}
        </ul>
      )}
    </>
  )
}

interface GradeButtonProps {
  label: string
  shortcut: string
  interval: string
  className: string
  suggested: boolean
  disabled: boolean
  onClick: () => void
}

function GradeButton({
  label,
  shortcut,
  interval,
  className,
  suggested,
  disabled,
  onClick,
}: GradeButtonProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-keyshortcuts={shortcut}
      aria-label={`${label}, again in ${interval}`}
      className={cn(
        'flex flex-col items-center gap-0.5 rounded-lg border bg-surface px-3 py-2 transition-colors hover:bg-surface-muted disabled:cursor-not-allowed disabled:opacity-60',
        suggested ? 'border-primary ring-2 ring-ring' : 'border-border-strong',
      )}
    >
      <span className={cn('font-semibold', className)}>{label}</span>
      <span className="text-xs text-fg-muted">
        {interval}
        <kbd className="ml-1.5 rounded border border-border px-1 text-fg-subtle">
          {shortcut}
        </kbd>
      </span>
    </button>
  )
}
