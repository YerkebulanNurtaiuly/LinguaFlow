import { useLayoutEffect, useRef, useState } from 'react'

/**
 * The width of the element the ref is on, kept current with a ResizeObserver, so a
 * chart can draw in real pixels and its text stays readable at any width. Starts at
 * {@code fallback} (also what jsdom reports, where layout is zero).
 */
export function useElementWidth<T extends HTMLElement>(fallback = 640) {
  const ref = useRef<T>(null)
  const [width, setWidth] = useState(fallback)
  useLayoutEffect(() => {
    const element = ref.current
    if (!element) return
    const update = () => {
      const measured = element.getBoundingClientRect().width
      if (measured > 0) setWidth(Math.floor(measured))
    }
    update()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(update)
    observer.observe(element)
    return () => observer.disconnect()
  }, [])
  return [ref, width] as const
}
