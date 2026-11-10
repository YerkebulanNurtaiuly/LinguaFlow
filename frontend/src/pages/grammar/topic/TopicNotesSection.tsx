import { lazy, Suspense, useState } from 'react'
import { describeError, getFieldErrors } from '@/api/errors'
import { Button, Field, Tabs, Textarea, useToast } from '@/components/ui'
import type { GrammarTopic } from '@/grammar/grammarApi'
import { SettingsSection } from '@/pages/settings/SettingsSection'

// react-markdown is only needed for the preview, so it stays out of the main bundle
const MarkdownView = lazy(() =>
  import('./MarkdownView').then((module) => ({ default: module.MarkdownView })),
)
import { useSaveTopic } from './useSaveTopic'

export const MAX_NOTES_LENGTH = 20_000

/** The learner's own notes in Markdown, with a preview (FE-GRM-02). */
export function TopicNotesSection({ topic }: { topic: GrammarTopic }) {
  const save = useSaveTopic(topic.id)
  const { toast } = useToast()
  const saved = topic.personalNotes ?? ''
  const [notes, setNotes] = useState(saved)
  const [tab, setTab] = useState('write')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string>()

  const tooLong = notes.length > MAX_NOTES_LENGTH
  const changed = notes.trimEnd() !== saved

  const submit = async () => {
    setError(undefined)
    setSaving(true)
    try {
      const updated = await save({ personalNotes: notes })
      setNotes(updated.personalNotes ?? '')
      toast({ title: 'Notes saved', variant: 'success' })
    } catch (failure) {
      setError(getFieldErrors(failure).personalNotes ?? describeError(failure))
    } finally {
      setSaving(false)
    }
  }

  return (
    <SettingsSection
      title="Your notes"
      description="Rules, examples and reminders in your own words. Markdown works: **bold**, lists, `code`, links."
    >
      <Tabs
        label="Notes editor"
        value={tab}
        onChange={setTab}
        items={[
          {
            id: 'write',
            label: 'Write',
            content: (
              <Field
                label="Notes"
                hint={`${notes.length.toLocaleString()} / ${MAX_NOTES_LENGTH.toLocaleString()} characters`}
                error={
                  tooLong
                    ? `Notes can be at most ${MAX_NOTES_LENGTH.toLocaleString()} characters.`
                    : error
                }
              >
                <Textarea
                  rows={10}
                  value={notes}
                  onChange={(event) => setNotes(event.target.value)}
                  className="font-mono text-sm"
                  placeholder={
                    '## Rule\n- mit, nach, bei … take the dative\n\nIch helfe **dem** Mann.'
                  }
                />
              </Field>
            ),
          },
          {
            id: 'preview',
            label: 'Preview',
            content: notes.trim() ? (
              <Suspense
                fallback={
                  <p className="text-sm text-fg-muted">Loading preview…</p>
                }
              >
                <MarkdownView markdown={notes} />
              </Suspense>
            ) : (
              <p className="text-sm text-fg-muted">Nothing to preview yet.</p>
            ),
          },
        ]}
      />
      <div className="mt-4 flex justify-end">
        <Button
          onClick={() => void submit()}
          loading={saving}
          disabled={!changed || tooLong}
        >
          Save notes
        </Button>
      </div>
    </SettingsSection>
  )
}
