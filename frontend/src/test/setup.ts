import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach } from 'vitest'
import { tokenStore } from '@/auth/tokenStore'
import { api } from '@/lib/api'
import { fakeAdapter, fakeApi } from './fakeApi'

beforeEach(() => {
  api.defaults.adapter = fakeAdapter
})

afterEach(() => {
  cleanup()
  tokenStore.set(null)
  fakeApi.reset()
})
