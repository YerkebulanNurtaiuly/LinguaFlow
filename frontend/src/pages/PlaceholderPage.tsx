interface PlaceholderPageProps {
  title: string
  description: string
}

/** Stand-in for sections whose feature task has not been built yet. */
export function PlaceholderPage({ title, description }: PlaceholderPageProps) {
  return (
    <section className="max-w-3xl">
      <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
      <p className="mt-2 text-slate-600">{description}</p>
      <p className="mt-6 rounded-lg border border-dashed border-slate-300 p-6 text-sm text-slate-500">
        This section is not built yet.
      </p>
    </section>
  )
}
