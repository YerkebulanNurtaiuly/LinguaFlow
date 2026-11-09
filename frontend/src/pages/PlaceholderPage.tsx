interface PlaceholderPageProps {
  title: string
  description: string
}

/** Stand-in for sections whose feature task has not been built yet. */
export function PlaceholderPage({ title, description }: PlaceholderPageProps) {
  return (
    <section className="max-w-3xl">
      <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
      <p className="mt-2 text-fg-muted">{description}</p>
      <p className="mt-6 rounded-lg border border-dashed border-border-strong p-6 text-sm text-fg-subtle">
        This section is not built yet.
      </p>
    </section>
  )
}
