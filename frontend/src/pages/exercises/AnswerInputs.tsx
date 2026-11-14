import { useRef, type DragEvent, type KeyboardEvent } from 'react'
import { Badge, Field, Input, RadioCards } from '@/components/ui'
import type { Exercise } from '@/exercises/exercisesApi'
import { cn } from '@/lib/cn'
import { GermanText } from '@/pages/flashcards/ReviewCard'
import { splitGaps } from './exerciseText'
import { UmlautKeys } from './UmlautKeys'

type TextControl = HTMLInputElement | HTMLTextAreaElement

/** How a gap or token was graded: true right, false wrong, undefined not yet. */
type Part = boolean | undefined

/** The colours for a graded part, or {@code base} while ungraded ({@code cn} doesn't resolve conflicts). */
const partClass = (part: Part, base: string) =>
  part === true
    ? 'border-success-soft-fg bg-success-soft text-success-soft-fg'
    : part === false
      ? 'border-danger bg-danger-soft text-danger-soft-fg'
      : base

/** A sentence with an input in each gap, plus umlaut keys. */
export function FillBlankInput({
  prompt,
  value,
  onChange,
  disabled,
  parts,
}: {
  prompt: Extract<Exercise, { type: 'FILL_BLANK' }>['prompt']
  value: string[]
  onChange: (value: string[]) => void
  disabled: boolean
  parts?: boolean[]
}) {
  const last = useRef<HTMLInputElement | null>(null)
  const pieces = splitGaps(prompt.text)
  const set = (index: number, text: string) =>
    onChange(
      pieces.slice(1).map((_, i) => (i === index ? text : (value[i] ?? ''))),
    )

  return (
    <div className="space-y-4">
      <p className="text-lg leading-loose text-fg">
        {pieces.map((piece, i) => (
          <span key={i}>
            {piece}
            {i < pieces.length - 1 && (
              <input
                aria-label={`Gap ${i + 1}`}
                data-gap={i}
                value={value[i] ?? ''}
                disabled={disabled}
                autoFocus={i === 0}
                autoComplete="off"
                spellCheck={false}
                onFocus={(event) => {
                  last.current = event.currentTarget
                }}
                onChange={(event) => set(i, event.target.value)}
                className={cn(
                  'mx-1 inline-block w-32 rounded-md border px-2 py-0.5 text-base focus-visible:outline-2 focus-visible:outline-primary disabled:opacity-100',
                  partClass(
                    parts?.[i],
                    'border-border-strong bg-surface text-fg',
                  ),
                )}
              />
            )}
          </span>
        ))}
      </p>
      {prompt.hint && (
        <p className="text-sm text-fg-muted">Hint: {prompt.hint}</p>
      )}
      <UmlautKeys
        disabled={disabled}
        target={() =>
          last.current ??
          document.querySelector<HTMLInputElement>('[data-gap="0"]')
        }
        onInsert={(input: TextControl, text) =>
          set(Number(input.dataset.gap ?? 0), text)
        }
      />
    </div>
  )
}

/** The options as radio cards; after grading, the right and the chosen one are marked. */
export function ChoiceInput({
  prompt,
  value,
  onChange,
  disabled,
  correctAnswer,
}: {
  prompt: Extract<Exercise, { type: 'MULTIPLE_CHOICE' }>['prompt']
  value: number | undefined
  onChange: (value: number) => void
  disabled: boolean
  /** The right option, once graded */
  correctAnswer?: string
}) {
  return (
    <div className="space-y-4">
      <p className="text-lg text-fg">
        <GermanText text={prompt.question} />
      </p>
      <fieldset disabled={disabled} className="disabled:opacity-100">
        <RadioCards
          legend="Options"
          hideLegend
          columns={2}
          options={prompt.options.map((option, i) => ({
            value: String(i),
            label: option,
            aside:
              correctAnswer === undefined ? undefined : option ===
                correctAnswer ? (
                <Badge variant="success">Right answer</Badge>
              ) : i === value ? (
                <Badge variant="danger">Your answer</Badge>
              ) : undefined,
          }))}
          value={value === undefined ? undefined : String(value)}
          onChange={(chosen) => onChange(Number(chosen))}
        />
      </fieldset>
    </div>
  )
}

/**
 * Word order: tap words to build the sentence, tap one in the sentence to put it back.
 * Words in the sentence can be dragged, or moved with the arrow keys, to reorder them.
 * The value is the order as indexes into the prompt's tokens (tokens may repeat).
 */
export function WordOrderInput({
  prompt,
  value,
  onChange,
  disabled,
  parts,
}: {
  prompt: Extract<Exercise, { type: 'WORD_ORDER' }>['prompt']
  value: number[]
  onChange: (value: number[]) => void
  disabled: boolean
  parts?: boolean[]
}) {
  const dragged = useRef<{ from: 'answer' | 'bank'; index: number } | null>(
    null,
  )
  const bank = prompt.tokens.map((_, i) => i).filter((i) => !value.includes(i))

  const move = (from: number, to: number) => {
    if (to < 0 || to >= value.length) return
    const next = [...value]
    const [token] = next.splice(from, 1)
    next.splice(to, 0, token!)
    onChange(next)
  }
  const dropAt = (event: DragEvent, position: number) => {
    event.preventDefault()
    const source = dragged.current
    dragged.current = null
    if (!source || disabled) return
    if (source.from === 'answer') {
      move(source.index, Math.min(position, value.length - 1))
    } else {
      const next = [...value]
      next.splice(position, 0, source.index)
      onChange(next)
    }
  }
  const onKey = (position: number) => (event: KeyboardEvent) => {
    if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      event.preventDefault()
      move(position, position + (event.key === 'ArrowLeft' ? -1 : 1))
    }
  }

  const chip =
    'rounded-lg border px-3 py-1.5 text-base font-medium focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary disabled:cursor-default'

  return (
    <div className="space-y-4">
      <div>
        <p id="word-order-answer" className="mb-2 text-sm font-medium text-fg">
          Your sentence
        </p>
        <ol
          aria-labelledby="word-order-answer"
          onDragOver={(event) => event.preventDefault()}
          onDrop={(event) => dropAt(event, value.length)}
          className="flex min-h-14 flex-wrap items-center gap-2 rounded-xl border border-dashed border-border-strong bg-surface-muted p-3"
        >
          {value.length === 0 && (
            <li className="text-sm text-fg-subtle">
              Tap the words below in order.
            </li>
          )}
          {value.map((tokenIndex, position) => (
            <li
              key={`${tokenIndex}`}
              draggable={!disabled}
              onDragStart={() => {
                dragged.current = { from: 'answer', index: position }
              }}
              onDragOver={(event) => event.preventDefault()}
              onDrop={(event) => {
                event.stopPropagation()
                dropAt(event, position)
              }}
            >
              <button
                type="button"
                disabled={disabled}
                aria-label={`Remove “${prompt.tokens[tokenIndex]}”, word ${position + 1}`}
                onClick={() => onChange(value.filter((_, i) => i !== position))}
                onKeyDown={onKey(position)}
                className={cn(
                  chip,
                  partClass(
                    parts?.[position],
                    'border-primary bg-primary-soft text-primary-soft-fg',
                  ),
                )}
              >
                {prompt.tokens[tokenIndex]}
              </button>
            </li>
          ))}
        </ol>
        {!disabled && value.length > 1 && (
          <p className="mt-1 text-xs text-fg-subtle">
            Drag words, or use the arrow keys on a word, to move it.
          </p>
        )}
      </div>
      <div>
        <p id="word-order-bank" className="mb-2 text-sm font-medium text-fg">
          Words
        </p>
        <ul
          aria-labelledby="word-order-bank"
          className="flex min-h-12 flex-wrap gap-2"
        >
          {bank.map((tokenIndex) => (
            <li
              key={tokenIndex}
              draggable={!disabled}
              onDragStart={() => {
                dragged.current = { from: 'bank', index: tokenIndex }
              }}
            >
              <button
                type="button"
                disabled={disabled}
                aria-label={`Add “${prompt.tokens[tokenIndex]}”`}
                onClick={() => onChange([...value, tokenIndex])}
                className={cn(
                  chip,
                  'border-border bg-surface text-fg hover:bg-surface-muted',
                )}
              >
                {prompt.tokens[tokenIndex]}
              </button>
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}

/** A written answer for translation, sentence building, fixing and rewriting. */
export function TextInput({
  exercise,
  value,
  onChange,
  disabled,
}: {
  exercise: Exclude<
    Exercise,
    { type: 'FILL_BLANK' | 'MULTIPLE_CHOICE' | 'WORD_ORDER' }
  >
  value: string
  onChange: (value: string) => void
  disabled: boolean
}) {
  const input = useRef<HTMLInputElement | null>(null)
  return (
    <div className="space-y-4">
      <TextExercise exercise={exercise} />
      <Field label="Your answer">
        <Input
          ref={input}
          value={value}
          disabled={disabled}
          autoFocus
          autoComplete="off"
          spellCheck={false}
          maxLength={1000}
          onChange={(event) => onChange(event.target.value)}
        />
      </Field>
      <UmlautKeys
        disabled={disabled}
        target={() => input.current}
        onInsert={(_, text) => onChange(text)}
      />
    </div>
  )
}

function TextExercise({
  exercise,
}: {
  exercise: Exclude<
    Exercise,
    { type: 'FILL_BLANK' | 'MULTIPLE_CHOICE' | 'WORD_ORDER' }
  >
}) {
  switch (exercise.type) {
    case 'TRANSLATION':
      return (
        <p className="text-lg text-fg">
          {exercise.prompt.direction === 'TARGET_TO_NATIVE' ? (
            <GermanText text={exercise.prompt.text} />
          ) : (
            exercise.prompt.text
          )}
        </p>
      )
    case 'SENTENCE_CONSTRUCTION':
      return (
        <div className="space-y-2">
          <p className="text-lg text-fg">{exercise.prompt.task}</p>
          <ul aria-label="Words to use" className="flex flex-wrap gap-2">
            {exercise.prompt.words.map((word) => (
              <li key={word}>
                <Badge variant="primary">{word}</Badge>
              </li>
            ))}
          </ul>
        </div>
      )
    case 'ERROR_CORRECTION':
      return (
        <p className="text-lg text-fg">
          <GermanText text={exercise.prompt.sentence} />
        </p>
      )
    case 'TRANSFORMATION':
      return (
        <div className="space-y-2">
          <p className="text-lg text-fg">
            <GermanText text={exercise.prompt.sentence} />
          </p>
          <p className="text-fg-muted">{exercise.prompt.task}</p>
        </div>
      )
  }
}
