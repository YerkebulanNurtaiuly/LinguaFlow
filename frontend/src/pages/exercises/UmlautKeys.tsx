import { insertAt } from './exerciseText'

const KEYS = ['ä', 'ö', 'ü', 'ß', 'Ä', 'Ö', 'Ü'] as const

type TextControl = HTMLInputElement | HTMLTextAreaElement

/**
 * Buttons that type ä, ö, ü and ß into the input the learner was last in, at the cursor,
 * for keyboards without them. Pressing one keeps the focus in the input.
 */
export function UmlautKeys({
  target,
  onInsert,
  disabled = false,
}: {
  /** The input to type into, usually the last one focused */
  target: () => TextControl | null
  /** Called with the input and its new value; the caller updates its state */
  onInsert: (input: TextControl, value: string) => void
  disabled?: boolean
}) {
  return (
    <div
      role="group"
      aria-label="Special characters"
      className="flex flex-wrap gap-1"
    >
      {KEYS.map((key) => (
        <button
          key={key}
          type="button"
          disabled={disabled}
          aria-label={`Type ${key}`}
          // Keep the focus (and the cursor) in the input
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => {
            const input = target()
            if (!input) return
            const next = insertAt(
              input.value,
              input.selectionStart,
              input.selectionEnd,
              key,
            )
            onInsert(input, next.value)
            requestAnimationFrame(() => {
              input.focus()
              input.setSelectionRange(next.cursor, next.cursor)
            })
          }}
          className="min-w-9 rounded-md border border-border bg-surface px-2 py-1 text-sm font-medium text-fg hover:bg-surface-muted focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary disabled:opacity-50"
        >
          {key}
        </button>
      ))}
    </div>
  )
}
