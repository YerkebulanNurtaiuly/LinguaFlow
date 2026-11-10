import { useRef } from 'react'
import { describeError } from '@/api/errors'
import { CardSkeleton, LoadError, Modal, useToast } from '@/components/ui'
import {
  useInvalidateVocabulary,
  useTags,
  useVocabularyItem,
} from '@/vocabulary/queries'
import type { VocabularyWord } from '@/vocabulary/vocabularyApi'
import { WordForm } from './WordForm'

/** Which word the modal shows: a new one, or an existing one by ID. */
export type WordModalTarget = { mode: 'add' } | { mode: 'edit'; id: string }

interface WordModalProps {
  target: WordModalTarget
  onClose: () => void
  /** Switches the modal to another word (the duplicate warning's link) */
  onOpen: (target: WordModalTarget) => void
}

/** The add/edit dialog of the vocabulary page (FE-VOC-02). */
export function WordModal({ target, onClose, onOpen }: WordModalProps) {
  const tags = useTags()
  const invalidate = useInvalidateVocabulary()
  const { toast } = useToast()
  // A new word starts in the word field; an edit starts on the dialog's first control
  const lemmaInput = useRef<HTMLInputElement>(null)

  const saved = (word: VocabularyWord, created: boolean) => {
    void invalidate()
    toast({
      title: created ? `Added “${word.lemma}”` : `Saved “${word.lemma}”`,
      variant: 'success',
    })
    onClose()
  }

  const formProps = {
    tags: tags.data ?? [],
    onSaved: saved,
    onCancel: onClose,
    onOpenExisting: (id: string) => onOpen({ mode: 'edit', id }),
    onTagCreated: () => void invalidate(),
  }

  return (
    <Modal
      open
      onClose={onClose}
      size="lg"
      initialFocus={lemmaInput}
      title={target.mode === 'add' ? 'Add a word' : 'Edit word'}
      description={
        target.mode === 'add'
          ? 'Only the word is required. AI fills in the rest later.'
          : undefined
      }
    >
      {target.mode === 'add' ? (
        <WordForm {...formProps} lemmaRef={lemmaInput} />
      ) : (
        <EditWord id={target.id} {...formProps} />
      )}
    </Modal>
  )
}

function EditWord({
  id,
  ...formProps
}: { id: string } & Omit<Parameters<typeof WordForm>[0], 'word'>) {
  const word = useVocabularyItem(id)
  if (word.isError) {
    return (
      <LoadError
        title="Couldn't load this word"
        description={describeError(word.error)}
        onRetry={() => void word.refetch()}
        retrying={word.isFetching}
      />
    )
  }
  if (!word.data) {
    return (
      <div role="status" aria-label="Loading word">
        <CardSkeleton lines={6} />
      </div>
    )
  }
  // Keyed by ID so switching words starts with a fresh form
  return <WordForm key={word.data.id} word={word.data} {...formProps} />
}
