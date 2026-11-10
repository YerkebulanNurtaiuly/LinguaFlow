import Markdown, { type Components } from 'react-markdown'

/** react-markdown passes its syntax-tree `node` too; it isn't a DOM attribute. */
function dom<T extends { node?: unknown }>(props: T): Omit<T, 'node'> {
  const rest = { ...props }
  delete rest.node
  return rest
}

const components: Components = {
  h1: (props) => <h3 className="mt-4 text-lg font-semibold" {...dom(props)} />,
  h2: (props) => (
    <h4 className="mt-4 text-base font-semibold" {...dom(props)} />
  ),
  h3: (props) => <h5 className="mt-3 text-sm font-semibold" {...dom(props)} />,
  p: (props) => <p className="my-2" {...dom(props)} />,
  ul: (props) => (
    <ul className="my-2 list-disc space-y-1 pl-5" {...dom(props)} />
  ),
  ol: (props) => (
    <ol className="my-2 list-decimal space-y-1 pl-5" {...dom(props)} />
  ),
  a: (props) => (
    <a
      className="text-primary underline"
      target="_blank"
      rel="noopener noreferrer"
      {...dom(props)}
    />
  ),
  code: (props) => (
    <code
      className="rounded bg-surface-muted px-1 py-0.5 font-mono text-[0.9em]"
      {...dom(props)}
    />
  ),
  pre: (props) => (
    <pre
      className="my-2 overflow-x-auto rounded-lg bg-surface-muted p-3 text-sm"
      {...dom(props)}
    />
  ),
  blockquote: (props) => (
    <blockquote
      className="my-2 border-l-4 border-border-strong pl-3 text-fg-muted"
      {...dom(props)}
    />
  ),
  hr: () => <hr className="my-4 border-border" />,
}

/**
 * The learner's Markdown notes, rendered safely: react-markdown never renders raw HTML
 * (no rehype-raw), and its default URL filter drops javascript: and similar links, so
 * notes can't inject scripts (SEC-01). Styled with the design tokens, no typography plugin.
 */
export function MarkdownView({ markdown }: { markdown: string }) {
  return (
    <div className="text-sm leading-relaxed text-fg">
      <Markdown components={components}>{markdown}</Markdown>
    </div>
  )
}
