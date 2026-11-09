import { useEffect } from 'react'
import { useMatches } from 'react-router'

export interface RouteHandle {
  title: string
}

const APP_NAME = 'LinguaFlow'

function hasTitle(handle: unknown): handle is RouteHandle {
  return (
    typeof handle === 'object' &&
    handle !== null &&
    typeof (handle as RouteHandle).title === 'string'
  )
}

/** Sets document.title from the deepest matched route's `handle.title`. */
export function RouteTitle() {
  const matches = useMatches()
  const title = matches
    .map((match) => match.handle)
    .filter(hasTitle)
    .at(-1)?.title

  useEffect(() => {
    document.title = title ? `${title} · ${APP_NAME}` : APP_NAME
  }, [title])

  return null
}
