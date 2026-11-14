/// <reference types="vite/client" />

/** The app's own Vite variables, so reading them is typed (QA-06's strict lint). */
interface ImportMetaEnv {
  /** Base URL of the API; defaults to `/api` on the same origin. */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
