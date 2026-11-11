import { zodResolver } from '@hookform/resolvers/zod'
import { Plus, Sparkles, X } from 'lucide-react'
import { useEffect, useState, type RefObject } from 'react'
import { useFieldArray, useForm, useWatch, type Path } from 'react-hook-form'
import {
  describeError,
  getApiProblem,
  getFieldErrors,
  hasErrorCode,
} from '@/api/errors'
import {
  Alert,
  Badge,
  Button,
  Field,
  Input,
  Select,
  Textarea,
} from '@/components/ui'
import { CEFR_LEVELS } from '@/languages/languagesApi'
import {
  createTag,
  createVocabularyItem,
  GENDERS,
  PARTS_OF_SPEECH,
  updateVocabularyItem,
  type TagWithCount,
  type VocabularyWord,
} from '@/vocabulary/vocabularyApi'
import { PART_OF_SPEECH_LABELS } from './vocabularyFilters'
import {
  EMPTY_WORD,
  GENDER_LABELS,
  hasNounFields,
  MAX_EXAMPLES,
  SELECTABLE_STATUSES,
  STATUS_LABELS,
  toCreateRequest,
  toFormField,
  toUpdateRequest,
  valuesOf,
  wordFormSchema,
  type WordFormInput,
  type WordFormValues,
} from './wordFormValues'

const AI_HINT = 'Filled in by AI. Change it to use your own.'

interface WordFormProps {
  /** The word to edit; without it the form adds a new one */
  word?: VocabularyWord
  tags: TagWithCount[]
  onSaved: (word: VocabularyWord, created: boolean) => void
  onCancel: () => void
  /** Opens a word the learner already has (from the duplicate warning) */
  onOpenExisting: (id: string) => void
  /** A tag was created inline; the caller refreshes the tag list */
  onTagCreated: () => void
  /** Receives the word input, so the dialog can focus it when it opens */
  lemmaRef?: RefObject<HTMLInputElement | null>
  /** Asks the AI to fill in the word again (VOC-06); edit mode only */
  onRegenerate?: (word: VocabularyWord) => Promise<void>
}

interface Duplicate {
  lemma: string
  existingId: string
}

/** Add or edit one word (FE-VOC-02). Only the word itself is required. */
export function WordForm({
  word,
  tags,
  onSaved,
  onCancel,
  onOpenExisting,
  onTagCreated,
  lemmaRef,
  onRegenerate,
}: WordFormProps) {
  const editing = word !== undefined
  const [regenerating, setRegenerating] = useState(false)
  const [formError, setFormError] = useState<string>()
  const [duplicate, setDuplicate] = useState<Duplicate>()
  const [newTagName, setNewTagName] = useState('')
  const [tagError, setTagError] = useState<string>()
  const [addingTag, setAddingTag] = useState(false)
  // Tags created here, shown until the refreshed tag list includes them
  const [createdTags, setCreatedTags] = useState<TagWithCount[]>([])

  const {
    register,
    handleSubmit,
    setError,
    setValue,
    getValues,
    reset,
    control,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<WordFormInput, unknown, WordFormValues>({
    resolver: zodResolver(wordFormSchema),
    defaultValues: word ? valuesOf(word) : EMPTY_WORD,
  })
  const lemmaField = register('lemma')
  const examples = useFieldArray({ control, name: 'examples' })
  const partOfSpeech = useWatch({ control, name: 'partOfSpeech' })

  // A word the AI is still filling in refreshes while open; adopt its new values
  // unless the learner has started typing
  useEffect(() => {
    if (word && !isDirty) reset(valuesOf(word))
  }, [word, isDirty, reset])

  const aiFields = new Set(word?.aiGeneratedFields ?? [])
  const aiHint = (field: string) => (aiFields.has(field) ? AI_HINT : undefined)
  const pending = word?.aiStatus === 'PENDING_AI'

  const regenerate = async () => {
    if (!word || !onRegenerate) return
    setRegenerating(true)
    try {
      await onRegenerate(word)
    } finally {
      setRegenerating(false)
    }
  }

  const allTags = [
    ...tags,
    ...createdTags.filter((tag) => !tags.some((t) => t.id === tag.id)),
  ]

  const onSubmit = handleSubmit(async (values) => {
    setFormError(undefined)
    setDuplicate(undefined)
    try {
      if (word) {
        const request = toUpdateRequest(word, values)
        onSaved(
          request ? await updateVocabularyItem(word.id, request) : word,
          false,
        )
      } else {
        onSaved(await createVocabularyItem(toCreateRequest(values)), true)
      }
    } catch (error) {
      const existingId = getApiProblem(error)?.existingId
      if (hasErrorCode(error, 'VOCABULARY_DUPLICATE') && existingId) {
        setDuplicate({ lemma: values.lemma, existingId })
        return
      }
      let applied = false
      for (const [serverField, message] of Object.entries(
        getFieldErrors(error),
      )) {
        const field = toFormField(serverField) as Path<WordFormInput>
        if (field in getValues() || field.startsWith('examples.')) {
          setError(
            field,
            { type: 'server', message },
            { shouldFocus: !applied },
          )
          applied = true
        }
      }
      if (!applied) setFormError(describeError(error))
    }
  })

  const addTag = async () => {
    const name = newTagName.trim()
    if (!name) return
    setTagError(undefined)
    setAddingTag(true)
    try {
      let tag: TagWithCount
      try {
        tag = await createTag(name)
        setCreatedTags((current) => [...current, tag])
        onTagCreated()
      } catch (error) {
        const existingId = getApiProblem(error)?.existingId
        const existing = allTags.find((t) => t.id === existingId)
        if (!hasErrorCode(error, 'TAG_NAME_TAKEN') || !existing) throw error
        tag = existing
      }
      const selected = getValues('tagIds')
      if (!selected.includes(tag.id)) {
        setValue('tagIds', [...selected, tag.id], { shouldDirty: true })
      }
      setNewTagName('')
    } catch (error) {
      setTagError(getFieldErrors(error).name ?? describeError(error))
    } finally {
      setAddingTag(false)
    }
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-5">
      {formError && <Alert variant="danger">{formError}</Alert>}
      {pending && (
        <Alert variant="info" title="AI is filling in this word">
          Its details appear here in a few seconds. What you save now is kept.
        </Alert>
      )}
      {duplicate && (
        <Alert variant="info" title="You already have this word">
          <p>“{duplicate.lemma}” is already in your vocabulary.</p>
          <Button
            variant="secondary"
            size="sm"
            className="mt-2"
            onClick={() => onOpenExisting(duplicate.existingId)}
          >
            Open “{duplicate.lemma}”
          </Button>
        </Alert>
      )}

      <div className="grid gap-4 sm:grid-cols-2">
        <Field
          label="Word or phrase"
          hint="Include the article for nouns, e.g. der Hund."
          error={errors.lemma?.message}
          required
        >
          <Input
            autoComplete="off"
            {...lemmaField}
            ref={(element) => {
              lemmaField.ref(element)
              if (lemmaRef) lemmaRef.current = element
            }}
          />
        </Field>
        <Field
          label="Translation"
          hint={aiHint('translation')}
          error={errors.translation?.message}
        >
          <Input autoComplete="off" {...register('translation')} />
        </Field>
        <Field
          label="Word type"
          hint={aiHint('partOfSpeech')}
          error={errors.partOfSpeech?.message}
        >
          <Select
            placeholder="Not set"
            options={PARTS_OF_SPEECH.map((value) => ({
              value,
              label: PART_OF_SPEECH_LABELS[value],
            }))}
            {...register('partOfSpeech')}
          />
        </Field>
        <Field
          label="Level"
          hint={aiHint('cefrLevel')}
          error={errors.cefrLevel?.message}
        >
          <Select
            placeholder="Not set"
            options={CEFR_LEVELS.map((value) => ({ value, label: value }))}
            {...register('cefrLevel')}
          />
        </Field>
        {hasNounFields(partOfSpeech) && (
          <>
            <Field
              label="Gender"
              hint={aiHint('gender')}
              error={errors.gender?.message}
            >
              <Select
                placeholder="Not set"
                options={GENDERS.map((value) => ({
                  value,
                  label: GENDER_LABELS[value],
                }))}
                {...register('gender')}
              />
            </Field>
            <Field
              label="Plural"
              hint={aiHint('plural') ?? 'e.g. die Hunde'}
              error={errors.plural?.message}
            >
              <Input autoComplete="off" {...register('plural')} />
            </Field>
          </>
        )}
        <Field
          label="Status"
          hint={
            word?.status === 'MASTERED'
              ? 'Mastered through practice.'
              : 'Mastered is earned through practice.'
          }
          error={errors.status?.message}
        >
          <Select
            options={[
              ...SELECTABLE_STATUSES.map((value) => ({
                value,
                label: STATUS_LABELS[value],
              })),
              ...(word?.status === 'MASTERED'
                ? [{ value: 'MASTERED', label: 'Mastered', disabled: true }]
                : []),
            ]}
            {...register('status')}
          />
        </Field>
      </div>

      <Field label="Notes" hint={aiHint('notes')} error={errors.notes?.message}>
        <Textarea rows={2} {...register('notes')} />
      </Field>

      <fieldset className="space-y-2">
        <legend className="text-sm font-medium text-fg">Tags</legend>
        {allTags.length > 0 && (
          <div className="flex flex-wrap gap-x-4 gap-y-2">
            {allTags.map((tag) => (
              <label
                key={tag.id}
                className="inline-flex items-center gap-2 text-sm text-fg"
              >
                <input
                  type="checkbox"
                  value={tag.id}
                  className="size-4 accent-primary"
                  {...register('tagIds')}
                />
                {tag.name}
              </label>
            ))}
          </div>
        )}
        {errors.tagIds?.message && (
          <p className="text-xs font-medium text-danger">
            {errors.tagIds.message}
          </p>
        )}
        <div className="flex items-end gap-2">
          <div className="flex-1">
            <Field label="New tag" error={tagError}>
              <Input
                value={newTagName}
                maxLength={50}
                placeholder="e.g. Kapitel 3"
                onChange={(event) => setNewTagName(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    // Enter adds the tag instead of submitting the word
                    event.preventDefault()
                    void addTag()
                  }
                }}
              />
            </Field>
          </div>
          <Button
            variant="secondary"
            onClick={() => void addTag()}
            loading={addingTag}
            disabled={!newTagName.trim()}
            className={tagError ? 'mb-6' : undefined}
          >
            Add tag
          </Button>
        </div>
      </fieldset>

      {editing && partOfSpeech === 'VERB' && (
        <fieldset className="space-y-3">
          <legend className="text-sm font-medium text-fg">Verb forms</legend>
          {aiHint('verbForms') && (
            <p className="text-xs text-fg-muted">{aiHint('verbForms')}</p>
          )}
          <div className="grid gap-4 sm:grid-cols-3">
            <Field
              label="Präteritum"
              hint="e.g. lief"
              error={errors.praeteritum?.message}
            >
              <Input autoComplete="off" {...register('praeteritum')} />
            </Field>
            <Field
              label="Partizip II"
              hint="e.g. gelaufen"
              error={errors.partizip2?.message}
            >
              <Input autoComplete="off" {...register('partizip2')} />
            </Field>
            <Field label="Perfect with" error={errors.auxiliary?.message}>
              <Select
                placeholder="Not set"
                options={[
                  { value: 'HABEN', label: 'haben' },
                  { value: 'SEIN', label: 'sein' },
                ]}
                {...register('auxiliary')}
              />
            </Field>
          </div>
        </fieldset>
      )}

      <fieldset className="space-y-3">
        <legend className="text-sm font-medium text-fg">Examples</legend>
        {examples.fields.some((field) => field.aiGenerated) && (
          <p className="text-xs text-fg-muted">
            Editing an AI example makes it your own.
          </p>
        )}
        {examples.fields.map((field, index) => (
          <div
            key={field.id}
            className="grid gap-3 rounded-lg border border-border p-3 sm:grid-cols-[1fr_1fr_auto]"
          >
            <Field
              label={`Example ${index + 1}`}
              error={errors.examples?.[index]?.sentence?.message}
            >
              <Input {...register(`examples.${index}.sentence`)} />
            </Field>
            <Field
              label={`Example ${index + 1} translation`}
              error={errors.examples?.[index]?.translation?.message}
            >
              <Input {...register(`examples.${index}.translation`)} />
            </Field>
            <Button
              variant="ghost"
              size="sm"
              className="self-end px-2 sm:mb-1"
              onClick={() => examples.remove(index)}
            >
              <X className="size-4" aria-hidden="true" />
              <span className="sr-only">Remove example {index + 1}</span>
            </Button>
            {field.aiGenerated && (
              <Badge variant="info" className="w-fit sm:col-span-3">
                <Sparkles className="mr-1 size-3" aria-hidden="true" />
                AI example
              </Badge>
            )}
          </div>
        ))}
        {examples.fields.length < MAX_EXAMPLES && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => examples.append({ sentence: '', translation: '' })}
          >
            <Plus className="size-4" aria-hidden="true" />
            Add example
          </Button>
        )}
      </fieldset>

      <div className="flex flex-wrap items-center justify-end gap-3 border-t border-border pt-4">
        {editing && onRegenerate && (
          <Button
            variant="ghost"
            className="mr-auto"
            onClick={() => void regenerate()}
            loading={regenerating}
            disabled={pending || isDirty}
            title={
              isDirty
                ? 'Save or cancel your changes first'
                : pending
                  ? 'AI is still working on this word'
                  : undefined
            }
          >
            <Sparkles className="size-4" aria-hidden="true" />
            Ask AI again
          </Button>
        )}
        <Button variant="secondary" onClick={onCancel}>
          Cancel
        </Button>
        <Button
          type="submit"
          loading={isSubmitting}
          disabled={editing && !isDirty}
        >
          {editing ? 'Save changes' : 'Add word'}
        </Button>
      </div>
    </form>
  )
}
