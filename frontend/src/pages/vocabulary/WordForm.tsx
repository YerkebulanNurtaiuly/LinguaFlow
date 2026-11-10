import { zodResolver } from '@hookform/resolvers/zod'
import { Plus, Sparkles, X } from 'lucide-react'
import { useState, type RefObject } from 'react'
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
}: WordFormProps) {
  const editing = word !== undefined
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
    control,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<WordFormInput, unknown, WordFormValues>({
    resolver: zodResolver(wordFormSchema),
    defaultValues: word ? valuesOf(word) : EMPTY_WORD,
  })
  const lemmaField = register('lemma')
  const examples = useFieldArray({ control, name: 'examples' })
  const partOfSpeech = useWatch({ control, name: 'partOfSpeech' })

  const aiFields = new Set(word?.aiGeneratedFields ?? [])
  const aiHint = (field: string) => (aiFields.has(field) ? AI_HINT : undefined)
  // The API can't clear a choice, so an existing value has no empty option
  const placeholder = (current: string | undefined, label: string) =>
    editing && current ? undefined : label

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
            placeholder={placeholder(word?.partOfSpeech, 'Not set')}
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
            placeholder={placeholder(word?.cefrLevel, 'Not set')}
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
                placeholder={placeholder(word?.gender, 'Not set')}
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

      <Field label="Notes" error={errors.notes?.message}>
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

      {editing ? (
        word.examples.length > 0 && (
          <section aria-label="Examples" className="space-y-2">
            <h3 className="text-sm font-medium text-fg">Examples</h3>
            <ul className="space-y-2">
              {word.examples.map((example) => (
                <li
                  key={example.id}
                  className="rounded-lg bg-surface-muted px-3 py-2 text-sm"
                >
                  <p className="text-fg">{example.sentence}</p>
                  {example.translation && (
                    <p className="text-fg-muted">{example.translation}</p>
                  )}
                  {example.aiGenerated && (
                    <Badge variant="info" className="mt-1">
                      <Sparkles className="mr-1 size-3" aria-hidden="true" />
                      AI example
                    </Badge>
                  )}
                </li>
              ))}
            </ul>
          </section>
        )
      ) : (
        <fieldset className="space-y-3">
          <legend className="text-sm font-medium text-fg">Examples</legend>
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
      )}

      <div className="flex justify-end gap-3 border-t border-border pt-4">
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
