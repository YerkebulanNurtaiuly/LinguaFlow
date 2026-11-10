import { useState } from 'react'
import { describeError } from '@/api/errors'
import {
  ConfirmDialog,
  RadioCards,
  type RadioCardOption,
  useToast,
} from '@/components/ui'
import type {
  GrammarTopic,
  KnowledgeStatus,
  StatusChange,
} from '@/grammar/grammarApi'
import { useStatusHistory } from '@/grammar/queries'
import { SettingsSection } from '@/pages/settings/SettingsSection'
import { STATUS_LABELS } from '../grammarLabels'
import { useSaveTopic } from './useSaveTopic'

const STATUS_HINTS: Record<KnowledgeStatus, string> = {
  NEW: "You haven't started yet.",
  LEARNING: "You're working on it.",
  LEARNED: 'You know the rules and can use them.',
  MASTERED:
    'Normally earned through practice; you can also confirm it yourself.',
}

const REASON_LABELS: Record<StatusChange['reason'], string> = {
  CREATED: 'added',
  MANUAL: 'by you',
  MASTERY_RULE: 'from your practice results',
  DEMOTION_RULE: 'from your practice results',
}

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })

/**
 * How well the learner knows the topic (GRM-02 rules): LEARNING and LEARNED any time,
 * MASTERED after a confirmation, and NEW only while the topic hasn't been started.
 */
export function TopicStatusSection({ topic }: { topic: GrammarTopic }) {
  const save = useSaveTopic(topic.id)
  const history = useStatusHistory(topic.id)
  const { toast } = useToast()
  const [confirmMastered, setConfirmMastered] = useState(false)
  const [saving, setSaving] = useState(false)

  const statuses: KnowledgeStatus[] =
    topic.status === 'NEW'
      ? ['NEW', 'LEARNING', 'LEARNED', 'MASTERED']
      : ['LEARNING', 'LEARNED', 'MASTERED']
  const options: RadioCardOption<KnowledgeStatus>[] = statuses.map(
    (status) => ({
      value: status,
      label: STATUS_LABELS[status],
      description: STATUS_HINTS[status],
    }),
  )

  const change = async (status: KnowledgeStatus, confirmed = false) => {
    setSaving(true)
    try {
      await save({ status, confirmMastered: confirmed || undefined })
      toast({
        title: `“${topic.title}” is now ${STATUS_LABELS[status].toLowerCase()}`,
        variant: 'success',
      })
    } catch (error) {
      toast({ title: describeError(error), variant: 'error' })
    } finally {
      setSaving(false)
    }
  }

  return (
    <SettingsSection
      title="How well you know it"
      description="Your progress on this topic. Changes are kept in the history below."
    >
      <div aria-busy={saving} className={saving ? 'opacity-60' : undefined}>
        <RadioCards
          legend="Status"
          hideLegend
          options={options}
          value={topic.status}
          columns={2}
          onChange={(status) => {
            if (saving || status === topic.status) return
            if (status === 'MASTERED') setConfirmMastered(true)
            else void change(status)
          }}
        />
      </div>

      {history.data && history.data.length > 0 && (
        <div className="mt-5">
          <h3 className="text-sm font-medium text-fg">History</h3>
          <ol className="mt-2 space-y-1 text-sm text-fg-muted">
            {[...history.data].reverse().map((entry, index) => (
              <li key={`${entry.changedAt}-${index}`}>
                <time dateTime={entry.changedAt}>
                  {dateFormat.format(new Date(entry.changedAt))}
                </time>
                {': '}
                {entry.fromStatus
                  ? `${STATUS_LABELS[entry.fromStatus]} → ${STATUS_LABELS[entry.toStatus]}`
                  : STATUS_LABELS[entry.toStatus]}
                <span className="text-fg-subtle">
                  {' '}
                  ({REASON_LABELS[entry.reason]})
                </span>
              </li>
            ))}
          </ol>
        </div>
      )}

      <ConfirmDialog
        open={confirmMastered}
        onCancel={() => setConfirmMastered(false)}
        onConfirm={async () => {
          await change('MASTERED', true)
          setConfirmMastered(false)
        }}
        title={`Mark “${topic.title}” as mastered?`}
        description="Mastery is normally earned through practice. Mark it yourself only if you're sure you know it well."
        confirmLabel="Mark as mastered"
      />
    </SettingsSection>
  )
}
