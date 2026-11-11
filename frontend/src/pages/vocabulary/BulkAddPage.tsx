import { ArrowLeft, CircleAlert, CircleCheck, Sparkles } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import type { BulkVocabularyFormat } from '@/api/types'
import { describeError, getFieldErrors } from '@/api/errors'
import {
  Alert,
  Badge,
  Button,
  ButtonLink,
  Card,
  CardContent,
  Field,
  RadioCards,
  Spinner,
  Table,
  TableCaption,
  TBody,
  Td,
  Th,
  THead,
  Tr,
  Textarea,
} from '@/components/ui'
import { cn } from '@/lib/cn'
import {
  confirmBulk,
  MAX_BULK_LINES,
  previewBulk,
  type BulkPreview,
  type BulkPreviewLine,
  type BulkResult,
} from '@/vocabulary/bulkApi'
import { useInvalidateVocabulary, useTags } from '@/vocabulary/queries'
import { useAiJobProgress } from '@/ai/aiJobsApi'

const FORMAT_OPTIONS = [
  {
    value: 'TEXT',
    label: 'One per line',
    description: 'A word or phrase per line, optionally “der Hund - dog”.',
  },
  {
    value: 'CSV',
    label: 'CSV',
    description:
      'Comma or semicolon separated: word, translation. A header row (lemma, translation, partOfSpeech, cefrLevel, notes) allows more columns.',
  },
] as const

const PLACEHOLDERS: Record<BulkVocabularyFormat, string> = {
  TEXT: 'der Hund - dog\ndie Katze - cat\nlaufen\nGuten Morgen!',
  CSV: 'lemma,translation,partOfSpeech\nder Hund,dog,noun\nlaufen,to run,verb',
}

const countLines = (text: string) =>
  text.split(/\r?\n/).filter((line) => line.trim()).length

const plural = (count: number, one: string, many: string) =>
  `${count.toLocaleString()} ${count === 1 ? one : many}`

type Step =
  | { name: 'input' }
  | { name: 'review'; preview: BulkPreview }
  | { name: 'done'; result: BulkResult }

/**
 * /vocabulary/add: paste many words, review each line, add the ones to keep
 * (FE-VOC-03). The preview saves nothing; confirming adds the selected lines.
 */
export function BulkAddPage() {
  const [step, setStep] = useState<Step>({ name: 'input' })
  const [text, setText] = useState('')
  const [format, setFormat] = useState<BulkVocabularyFormat>('TEXT')

  return (
    <div className="max-w-5xl space-y-6">
      <div>
        <ButtonLink
          to="/vocabulary"
          variant="ghost"
          size="sm"
          className="-ml-3 mb-2"
        >
          <ArrowLeft className="size-4" aria-hidden="true" />
          Vocabulary
        </ButtonLink>
        <h1 className="text-2xl font-semibold tracking-tight">
          Add many words
        </h1>
        <p className="mt-2 text-fg-muted">
          Paste a list, check what will be added, then add it in one go.
        </p>
      </div>

      {step.name === 'input' && (
        <InputStep
          text={text}
          format={format}
          onTextChange={setText}
          onFormatChange={setFormat}
          onPreview={(preview) => setStep({ name: 'review', preview })}
        />
      )}
      {step.name === 'review' && (
        <ReviewStep
          preview={step.preview}
          onBack={() => setStep({ name: 'input' })}
          onDone={(result) => setStep({ name: 'done', result })}
        />
      )}
      {step.name === 'done' && (
        <DoneStep
          result={step.result}
          onAddMore={() => {
            setText('')
            setStep({ name: 'input' })
          }}
        />
      )}
    </div>
  )
}

interface InputStepProps {
  text: string
  format: BulkVocabularyFormat
  onTextChange: (text: string) => void
  onFormatChange: (format: BulkVocabularyFormat) => void
  onPreview: (preview: BulkPreview) => void
}

function InputStep({
  text,
  format,
  onTextChange,
  onFormatChange,
  onPreview,
}: InputStepProps) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const lines = countLines(text)
  const tooMany = lines > MAX_BULK_LINES

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (lines === 0) {
      setError('Paste at least one word.')
      return
    }
    if (tooMany) return
    setError(undefined)
    setBusy(true)
    try {
      onPreview(await previewBulk(text, format))
    } catch (failure) {
      setError(getFieldErrors(failure).text ?? describeError(failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} noValidate className="space-y-5">
      <RadioCards
        legend="Format"
        options={FORMAT_OPTIONS}
        value={format}
        onChange={onFormatChange}
        columns={2}
      />
      <Field
        label="Your words"
        hint={
          tooMany
            ? undefined
            : `${plural(lines, 'line', 'lines')} (up to ${MAX_BULK_LINES})`
        }
        error={
          tooMany
            ? `That's ${lines} lines; split it into parts of up to ${MAX_BULK_LINES}.`
            : error
        }
      >
        <Textarea
          rows={12}
          value={text}
          onChange={(event) => onTextChange(event.target.value)}
          placeholder={PLACEHOLDERS[format]}
          className="font-mono text-sm"
          spellCheck={false}
        />
      </Field>
      <Button type="submit" loading={busy} disabled={tooMany}>
        Check the list
      </Button>
    </form>
  )
}

const STATUS_BADGES = {
  NEW: <Badge variant="success">New</Badge>,
  DUPLICATE: <Badge variant="warning">Already added</Badge>,
  INVALID: <Badge variant="danger">Can't add</Badge>,
} as const

const ROW_TINTS = {
  NEW: undefined,
  DUPLICATE: 'bg-warning-soft/40',
  INVALID: 'bg-danger-soft/40',
} as const

interface ReviewStepProps {
  preview: BulkPreview
  onBack: () => void
  onDone: (result: BulkResult) => void
}

function ReviewStep({ preview, onBack, onDone }: ReviewStepProps) {
  const tags = useTags()
  const invalidate = useInvalidateVocabulary()
  const newLines = preview.lines.filter((line) => line.status === 'NEW')
  const [selected, setSelected] = useState(
    () => new Set(newLines.map((line) => line.line)),
  )
  const [tagIds, setTagIds] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()

  const toggle = (line: number) =>
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(line)) next.delete(line)
      else next.add(line)
      return next
    })

  const add = async () => {
    const items = newLines
      .filter((line) => selected.has(line.line))
      .map((line) => ({
        lemma: line.lemma ?? '',
        translation: line.translation,
        partOfSpeech: line.partOfSpeech,
        cefrLevel: line.cefrLevel,
        notes: line.notes,
      }))
    setError(undefined)
    setBusy(true)
    try {
      const result = await confirmBulk({
        items,
        tagIds: tagIds.length > 0 ? tagIds : undefined,
      })
      void invalidate()
      onDone(result)
    } catch (failure) {
      setError(describeError(failure))
      setBusy(false)
    }
  }

  return (
    <div className="space-y-5">
      <p aria-live="polite" className="text-sm text-fg-muted">
        {plural(preview.newCount, 'new word', 'new words')}
        {preview.duplicateCount > 0 &&
          ` · ${plural(preview.duplicateCount, 'already in your vocabulary', 'already in your vocabulary')}`}
        {preview.invalidCount > 0 &&
          ` · ${plural(preview.invalidCount, "line can't be added", "lines can't be added")}`}
      </p>

      {preview.lines.length === 0 ? (
        <Alert variant="info">There were no words in the list.</Alert>
      ) : (
        <Table>
          <TableCaption>What will be added, line by line</TableCaption>
          <THead>
            <Tr>
              <Th className="w-12">
                <span className="sr-only">Add</span>
              </Th>
              <Th className="w-16">Line</Th>
              <Th>Word</Th>
              <Th>Translation</Th>
              <Th>Result</Th>
            </Tr>
          </THead>
          <TBody>
            {preview.lines.map((line) => (
              <PreviewRow
                key={line.line}
                line={line}
                checked={selected.has(line.line)}
                onToggle={() => toggle(line.line)}
              />
            ))}
          </TBody>
        </Table>
      )}

      {newLines.length > 1 && (
        <div className="flex gap-2">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setSelected(new Set(newLines.map((l) => l.line)))}
          >
            Select all new
          </Button>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setSelected(new Set())}
          >
            Select none
          </Button>
        </div>
      )}

      {tags.data && tags.data.length > 0 && newLines.length > 0 && (
        <fieldset className="space-y-2">
          <legend className="text-sm font-medium text-fg">
            Tag the added words
          </legend>
          <div className="flex flex-wrap gap-x-4 gap-y-2">
            {tags.data.map((tag) => (
              <label
                key={tag.id}
                className="inline-flex items-center gap-2 text-sm text-fg"
              >
                <input
                  type="checkbox"
                  className="size-4 accent-primary"
                  checked={tagIds.includes(tag.id)}
                  onChange={(event) =>
                    setTagIds((current) =>
                      event.target.checked
                        ? [...current, tag.id]
                        : current.filter((id) => id !== tag.id),
                    )
                  }
                />
                {tag.name}
              </label>
            ))}
          </div>
        </fieldset>
      )}

      {error && <Alert variant="danger">{error}</Alert>}

      <div className="flex flex-wrap gap-3 border-t border-border pt-4">
        <Button variant="secondary" onClick={onBack} disabled={busy}>
          Edit the list
        </Button>
        <Button onClick={add} loading={busy} disabled={selected.size === 0}>
          {selected.size === 0
            ? 'Add words'
            : `Add ${plural(selected.size, 'word', 'words')}`}
        </Button>
      </div>
    </div>
  )
}

function PreviewRow({
  line,
  checked,
  onToggle,
}: {
  line: BulkPreviewLine
  checked: boolean
  onToggle: () => void
}) {
  const label = line.lemma ?? line.text
  return (
    <tr className={cn(ROW_TINTS[line.status])}>
      <Td>
        <input
          type="checkbox"
          className="size-4 accent-primary"
          checked={line.status === 'NEW' && checked}
          disabled={line.status !== 'NEW'}
          onChange={onToggle}
          aria-label={`Add ${label}`}
        />
      </Td>
      <Td className="text-fg-muted tabular-nums">{line.line}</Td>
      <Td className="font-medium">{line.lemma ?? <span>{line.text}</span>}</Td>
      <Td className="text-fg-muted">{line.translation}</Td>
      <Td>
        <div className="flex flex-wrap items-center gap-2">
          {line.status === 'DUPLICATE' && line.duplicateOfLine !== undefined ? (
            <Badge variant="warning">Repeated</Badge>
          ) : (
            STATUS_BADGES[line.status]
          )}
          {line.message && (
            <span className="text-xs text-fg-muted">{line.message}</span>
          )}
        </div>
      </Td>
    </tr>
  )
}

function DoneStep({
  result,
  onAddMore,
}: {
  result: BulkResult
  onAddMore: () => void
}) {
  const progress = useAiJobProgress(result.aiJobIds)
  const skipped = result.results.filter((r) => r.status !== 'CREATED')
  const jobs = progress.data
  const done = jobs?.finished === true
  const someFailed = done && jobs.failed + jobs.dead > 0

  return (
    <div className="space-y-5">
      <Alert
        variant="success"
        title={`Added ${plural(result.createdCount, 'word', 'words')}`}
      >
        {skipped.length > 0
          ? `${plural(skipped.length, 'word was', 'words were')} skipped.`
          : 'Everything on the list was added.'}
      </Alert>

      {skipped.length > 0 && (
        <ul className="space-y-1 text-sm">
          {skipped.map((r) => (
            <li key={r.index}>
              <span className="font-medium">{r.lemma}</span>
              <span className="text-fg-muted">: {r.message}</span>
            </li>
          ))}
        </ul>
      )}

      {result.aiJobIds.length > 0 && (
        <Card>
          <CardContent>
            <div role="status" className="flex items-start gap-3">
              {someFailed ? (
                <CircleAlert
                  className="mt-0.5 size-5 text-warning-soft-fg"
                  aria-hidden="true"
                />
              ) : done ? (
                <CircleCheck
                  className="mt-0.5 size-5 text-success-soft-fg"
                  aria-hidden="true"
                />
              ) : (
                <Sparkles
                  className="mt-0.5 size-5 text-primary"
                  aria-hidden="true"
                />
              )}
              <div className="space-y-1">
                <p className="font-medium">
                  {someFailed
                    ? "AI couldn't fill in every word"
                    : done
                      ? 'AI has filled in the details'
                      : 'AI is filling in the details'}
                </p>
                <p className="text-sm text-fg-muted">
                  {jobs === undefined ? (
                    <span className="inline-flex items-center gap-2">
                      <Spinner /> Checking…
                    </span>
                  ) : someFailed ? (
                    'Some words are still missing their details. You can fill them in yourself from your vocabulary.'
                  ) : done ? (
                    'Translations, articles, plurals and examples are ready.'
                  ) : (
                    `Adding translations, articles, plurals and examples to ${plural(result.createdCount, 'word', 'words')}${jobs.total > 1 ? ` (${jobs.succeeded + jobs.failed + jobs.dead} of ${jobs.total} batches done)` : ''}. You can keep learning meanwhile.`
                  )}
                </p>
              </div>
            </div>
          </CardContent>
        </Card>
      )}

      <div className="flex flex-wrap gap-3">
        <ButtonLink to="/vocabulary">Go to vocabulary</ButtonLink>
        <Button variant="secondary" onClick={onAddMore}>
          Add more words
        </Button>
      </div>
    </div>
  )
}
