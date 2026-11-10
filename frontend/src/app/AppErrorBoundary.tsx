import { Component, type ErrorInfo, type ReactNode } from 'react'

interface State {
  error: Error | null
}

/**
 * Last line of defence around the providers (theme, query client, auth, router). Route
 * errors are handled by RouteErrorPage; this only catches a crash outside the router,
 * so it renders plain markup that needs no context.
 */
export class AppErrorBoundary extends Component<
  { children: ReactNode },
  State
> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('LinguaFlow crashed', error, info.componentStack)
  }

  render() {
    if (!this.state.error) return this.props.children
    return (
      <main
        role="alert"
        className="mx-auto flex min-h-screen max-w-xl flex-col justify-center gap-4 bg-canvas px-4 text-fg"
      >
        <h1 className="text-2xl font-semibold">LinguaFlow stopped working</h1>
        <p className="text-fg-muted">
          Something unexpected happened. Reloading usually fixes it; your saved
          data is safe.
        </p>
        <div>
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="h-10 rounded-md bg-primary px-4 text-sm font-medium text-primary-fg hover:bg-primary-hover"
          >
            Reload
          </button>
        </div>
      </main>
    )
  }
}
