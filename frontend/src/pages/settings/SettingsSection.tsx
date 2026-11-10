import { type ReactNode, useId } from 'react'
import { Card, CardContent, CardHeader } from '@/components/ui'
import { cn } from '@/lib/cn'

interface SettingsSectionProps {
  title: string
  description?: ReactNode
  /** Optional element next to the title, e.g. a badge */
  aside?: ReactNode
  tone?: 'default' | 'danger'
  children: ReactNode
}

/** One card on the settings page; labelled by its heading so it shows up as a region. */
export function SettingsSection({
  title,
  description,
  aside,
  tone = 'default',
  children,
}: SettingsSectionProps) {
  const headingId = useId()
  return (
    <section aria-labelledby={headingId}>
      <Card className={cn(tone === 'danger' && 'border-danger/40')}>
        <CardHeader>
          <div className="flex items-center gap-2">
            <h2
              id={headingId}
              className={cn(
                'text-base font-semibold text-fg',
                tone === 'danger' && 'text-danger',
              )}
            >
              {title}
            </h2>
            {aside}
          </div>
          {description && (
            <p className="mt-1 text-sm text-fg-muted">{description}</p>
          )}
        </CardHeader>
        <CardContent>{children}</CardContent>
      </Card>
    </section>
  )
}
