import { CircleAlert, Sparkles, Trash2 } from 'lucide-react'
import {
  Badge,
  Button,
  CefrBadge,
  StatusBadge,
  Table,
  TableCaption,
  TBody,
  Td,
  Th,
  THead,
  Tr,
} from '@/components/ui'
import type { AiStatus, VocabularyWord } from '@/vocabulary/vocabularyApi'
import { PART_OF_SPEECH_LABELS } from './vocabularyFilters'

/** Whether AI enrichment (VOC-05) has filled in the word's details. */
export function AiStatusBadge({ status }: { status: AiStatus }) {
  if (status === 'PENDING_AI') {
    return (
      <Badge variant="info" title="AI is filling in the missing details">
        <Sparkles className="mr-1 size-3" aria-hidden="true" />
        AI pending
      </Badge>
    )
  }
  if (status === 'AI_FAILED') {
    return (
      <Badge variant="danger" title="AI couldn't fill in the details">
        <CircleAlert className="mr-1 size-3" aria-hidden="true" />
        AI failed
      </Badge>
    )
  }
  return null
}

interface VocabularyTableProps {
  words: VocabularyWord[]
  caption: string
  onDelete: (word: VocabularyWord) => void
}

/**
 * The words of one page. On narrow screens the less important columns are hidden
 * and their content moves under the word, so nothing needs sideways scrolling.
 */
export function VocabularyTable({
  words,
  caption,
  onDelete,
}: VocabularyTableProps) {
  return (
    <Table>
      <TableCaption>{caption}</TableCaption>
      <THead>
        <Tr>
          <Th>Word</Th>
          <Th>Translation</Th>
          <Th className="hidden md:table-cell">Type</Th>
          <Th className="hidden sm:table-cell">Level</Th>
          <Th className="hidden sm:table-cell">Status</Th>
          <Th className="hidden lg:table-cell">Tags</Th>
          <Th>
            <span className="sr-only">Actions</span>
          </Th>
        </Tr>
      </THead>
      <TBody>
        {words.map((word) => (
          <Tr key={word.id}>
            <Td>
              <div className="font-medium">{word.lemma}</div>
              {word.plural && (
                <div className="text-xs text-fg-muted">
                  <span className="sr-only">Plural: </span>
                  {word.plural}
                </div>
              )}
              <div className="mt-1 flex flex-wrap gap-1">
                <AiStatusBadge status={word.aiStatus} />
                <span className="sm:hidden">
                  <StatusBadge status={word.status} />
                </span>
              </div>
            </Td>
            <Td className="text-fg-muted">
              {word.translation ?? (
                <span className="text-fg-subtle">
                  <span aria-hidden="true">—</span>
                  <span className="sr-only">No translation yet</span>
                </span>
              )}
            </Td>
            <Td className="hidden text-fg-muted md:table-cell">
              {word.partOfSpeech
                ? PART_OF_SPEECH_LABELS[word.partOfSpeech]
                : ''}
            </Td>
            <Td className="hidden sm:table-cell">
              {word.cefrLevel && <CefrBadge level={word.cefrLevel} />}
            </Td>
            <Td className="hidden sm:table-cell">
              <StatusBadge status={word.status} />
            </Td>
            <Td className="hidden lg:table-cell">
              <div className="flex flex-wrap gap-1">
                {word.tags.map((tag) => (
                  <Badge key={tag.id}>{tag.name}</Badge>
                ))}
              </div>
            </Td>
            <Td className="w-12 text-right">
              <Button
                variant="ghost"
                size="sm"
                onClick={() => onDelete(word)}
                className="px-2"
              >
                <Trash2 className="size-4" aria-hidden="true" />
                <span className="sr-only">Delete {word.lemma}</span>
              </Button>
            </Td>
          </Tr>
        ))}
      </TBody>
    </Table>
  )
}
