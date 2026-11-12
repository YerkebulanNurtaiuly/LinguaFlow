import { ArrowRight, PencilLine } from 'lucide-react'
import { Link } from 'react-router'

/**
 * /practice: the ways to practise. For now that's grammar exercises (FE-EX-02); the
 * practice modes (current lesson, mixed, full revision) come with FE-PRC-01.
 */
export function PracticePage() {
  return (
    <div className="max-w-3xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Practice</h1>
        <p className="mt-2 text-fg-muted">
          Use what you have learned in exercises written for you.
        </p>
      </div>
      <Link
        to="/practice/exercises"
        className="group flex items-center gap-4 rounded-xl border border-border bg-surface p-5 hover:bg-surface-muted focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
      >
        <PencilLine
          className="size-6 shrink-0 text-primary"
          aria-hidden="true"
        />
        <span className="flex-1">
          <span className="block font-semibold text-fg">Grammar exercises</span>
          <span className="block text-sm text-fg-muted">
            Gaps, multiple choice, word order and translations for the grammar
            topics you choose, with your own words.
          </span>
        </span>
        <ArrowRight className="size-5 text-fg-muted" aria-hidden="true" />
      </Link>
      <p className="text-sm text-fg-subtle">
        Practising your current lesson, mixed practice and full revision are on
        their way.
      </p>
    </div>
  )
}
