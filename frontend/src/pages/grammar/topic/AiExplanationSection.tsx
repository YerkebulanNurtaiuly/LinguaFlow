import { Sparkles } from 'lucide-react'
import type { GrammarTopic } from '@/grammar/grammarApi'
import { SettingsSection } from '@/pages/settings/SettingsSection'

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })

/**
 * The AI-written explanation (GRM-04), kept apart from the learner's notes. Until AI
 * generation exists, topics have none and the panel says so.
 */
export function AiExplanationSection({ topic }: { topic: GrammarTopic }) {
  const explanation = topic.aiExplanation
  return (
    <SettingsSection
      title="AI explanation"
      aside={<Sparkles className="size-4 text-primary" aria-hidden="true" />}
      description={
        explanation && topic.aiExplanationGeneratedAt
          ? `Written by AI on ${dateFormat.format(new Date(topic.aiExplanationGeneratedAt))}.`
          : undefined
      }
    >
      {explanation ? (
        <div className="space-y-4 text-sm">
          {explanation.summary && (
            <p className="text-fg">{explanation.summary}</p>
          )}
          {explanation.rules.length > 0 && (
            <div>
              <h3 className="font-medium text-fg">Rules</h3>
              <ul className="mt-1 list-disc space-y-1 pl-5 text-fg">
                {explanation.rules.map((rule, index) => (
                  <li key={index}>{rule}</li>
                ))}
              </ul>
            </div>
          )}
          {explanation.examples.length > 0 && (
            <div>
              <h3 className="font-medium text-fg">Examples</h3>
              <ul className="mt-1 space-y-1 text-fg-muted">
                {explanation.examples.map((example, index) => (
                  <li key={index} lang="de">
                    {example}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      ) : (
        <p className="text-sm text-fg-muted">
          No AI explanation yet. Once AI explanations are available, a summary
          with rules and examples will appear here, separate from your notes.
        </p>
      )}
    </SettingsSection>
  )
}
