import axios from 'axios'
import { z } from 'zod'

const healthSchema = z.object({ status: z.string() })

export type Health = z.infer<typeof healthSchema>

/** Calls the backend actuator health endpoint (outside /api, so no base URL). */
export async function fetchHealth(): Promise<Health> {
  const { data } = await axios.get<unknown>('/actuator/health')
  return healthSchema.parse(data)
}
