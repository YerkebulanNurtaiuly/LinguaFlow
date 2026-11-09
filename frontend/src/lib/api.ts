import axios from 'axios'

/**
 * Shared HTTP client for the backend. Auth token injection and refresh handling
 * are added in FE-03.
 */
export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  headers: { 'Content-Type': 'application/json' },
})
